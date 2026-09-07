# archery-jdbc-driver

[Archery: SQL 审核查询平台](https://github.com/hhyo/archery)

只读 JDBC 驱动：把 JDBC 的 SELECT 调用翻译成对 Archery 平台 HTTP 接口的调用。
使 BI 工具（DataGrip / DBeaver / FineBI / Metabase / 自研 Java 应用等）通过标准 JDBC
访问 Archery 管控的数据源，同时完整复用 Archery 的**权限校验、查询审计、数据脱敏、超时保护**。

- **仅支持 SELECT**（设计目标）：`executeUpdate` / `executeBatch` / 事务等方法全部抛异常，
  DELETE/UPDATE/INSERT 明确排除。
- **零服务端改动**：只使用 Archery 现有的 `/authenticate/`、`/query/`、`/logout/`、`/api/info` 接口。
- **凭据托管**：客户端只持有 Archery 平台账号，数据库真实凭据由 Archery 托管（加密存储），不出平台。

## 工作原理

```
JDBC 客户端 (BI 工具 / Java 应用)
   │  java.sql API
   ▼
archery-jdbc-driver 驱动（本模块，Java 8 兼容）
   │  GET  /login/          预取 csrftoken cookie
   │  POST /authenticate/   平台登录（session + CSRF）
   │  POST /query/          instance_name / db_name / sql_content / limit_num
   │  GET  /logout/         close() 时 best-effort 登出
   │  GET  /api/info        版本号（DatabaseMetaData 用，免登录白名单接口）
   ▼
Archery 平台（query_check → 权限校验 → limit 改写 → 执行 → 脱敏 → QueryLog 审计）
```

每一次 `executeQuery` 和每一次元数据内省都是一次独立的 `POST /query/`，
审计（QueryLog）精确到平台账号，数据脱敏链路照常生效。

包结构（driver/internal 分层）：

```
com.archery.jdbc.driver   ArcheryDriver（唯一入口，SPI 注册）
com.archery.jdbc.internal 连接/语句/结果集/HTTP 会话/类型映射等实现
                          （调用方只依赖 java.sql 接口，internal 不直接使用）
```

## 构建

```bash
mvn package
# 产物：
#   target/archery-jdbc-driver-1.0.0-jar-with-dependencies.jar  <- 推荐使用（内置 gson，自包含）
#   target/archery-jdbc-driver-1.0.0.jar                        # 纯 jar，需自行提供 gson 依赖

mvn test   # 45 个单元测试
```

## 快速开始

```java
// Java 8 SPI 环境下 Class.forName 可省略（META-INF/services 自动注册）
Class.forName("com.archery.jdbc.driver.ArcheryDriver");

Connection conn = DriverManager.getConnection(
    "jdbc:archery://archery.local:8000/mysql_prod/mydb?user=bi_reader&password=***");
Statement st = conn.createStatement();
ResultSet rs = st.executeQuery("SELECT id, name, created FROM orders WHERE dt >= '2024-01-01'");
while (rs.next()) {
    System.out.println(rs.getLong("id") + " " + rs.getString("name"));
}
```

## 连接 URL

```
jdbc:archery://<host>[:<port>]/<instance_name>/<db_name>?user=..&password=..
jdbc:archery:https://<host>/<instance_name>/<db_name>          # https scheme
```

- **host 支持任意层级域名**：`archery.local`、`a.archery.local`、`a.b.c.archery.local`
  均可，host 原样保留（连接目标与 URL 一致，不做子域剥离）；IP 与 `host:port` 同样支持。
- instance_name / db_name 在路径段（含中文等非 ASCII 字符请做 URL 编码，
  如 `zeus_%E4%BB%8E`）。

| 参数 | 默认 | 说明 |
|---|---|---|
| user / password | 必填 | Archery 平台账号（不是数据库账号） |
| ssl | false | true 时使用 https（`jdbc:archery:https://` 前缀等价） |
| basePath | 空 | 反向代理子路径，如 `/archery` |
| limit | 5000 | 默认行数上限（对应 `/query/` 的 limit_num；0 表示不限行数，交由服务端权限上限约束） |
| connectTimeout | 10000 | 连接超时（毫秒） |
| readTimeout | 180000 | 读超时（毫秒；服务端另有 max_execution_time + kill 连接保护） |
| trustAll | false | 信任自签名证书（企业内网自签环境） |
| lazyLogin | false | **懒登录**：连接时不认证，首次查询时自动登录（连接池友好，连接建立 <10ms） |
| port | 80/443 | 端口（https scheme 默认 443） |

## 认证与会话

当前 Archery 无 token 机制，驱动实现与 Web UI 相同的认证流程：

1. `GET /login/` 预取 `csrftoken` cookie；
2. `POST /authenticate/`（表单 username/password + `X-CSRFToken` 头）；
3. 登录成功后 Django 下发 `sessionid` 并**轮换 csrftoken**（`login()` 内部 rotate_token），
   驱动自动跟随 cookie 轮换；
4. `POST /query/` 携带 session cookie + `X-CSRFToken` 头。

容错行为：

- 会话过期（服务端 302 → /login/）：自动重登录后重试；
- CSRF 校验失败（403）：刷新 token 后重试；
- `lazyLogin=true`：首次查询时才登录（默认 `false`，连接时即认证，fail-fast）；
- `Connection.close()`：best-effort `GET /logout/` 登出，不留遗留服务端会话。

## JDBC 接口支持矩阵

| 接口 | 支持 | 说明 |
|---|---|---|
| Statement.executeQuery | ✅ | 同步全量返回（setFetchSize 仅记录，无流式游标） |
| Statement.setMaxRows | ✅ | 覆盖 URL 的 limit |
| PreparedStatement | ✅ | `?` 占位符在**客户端**替换为字面量；词法扫描覆盖字符串/标识符、`''`/`\` 转义与 `--`/`#`/`/* */` 注释 |
| ResultSet 读取 | ✅ | 按列类型转换；大整数/Decimal 的字符串宽容解析 |
| ResultSet 可滚动导航 | ✅ | **TYPE_SCROLL_INSENSITIVE**：first/last/absolute/relative/previous/beforeFirst/afterLast（结果全量物化，语义准确） |
| ResultSetMetaData | ✅ | 列名 + 类型；物理属性（精度/可空性）不可得，返回 0/未知 |
| DatabaseMetaData | 最小 | getCatalogs/getTables/getColumns/getPrimaryKeys 用 SHOW 语句实现（见下节）；其余返回标准形状空结果集 |
| SQLState 语义 | ✅ | 服务端错误消息关键词映射：权限→28000、语法/脱敏→42000、连接→08000、其余 HY000 |
| 会话生命周期 | ✅ | 过期自动重登录；close 时登出 |
| 事务（commit/rollback/setAutoCommit） | no-op | SELECT 无事务语义，为兼容 BI 工具静默忽略 |
| executeUpdate / executeBatch / getGeneratedKeys | ❌ | 抛 SQLFeatureNotSupportedException / SQLException |

## 元数据内省（DataGrip/DBeaver 的表列表从哪来）

BI 工具**不执行 SQL**，而是调用 JDBC 标准内省 API，由驱动翻译：

```
DatabaseMetaData.getCatalogs()               → SHOW DATABASES
DatabaseMetaData.getTables(cat, sch, "%", t) → SHOW FULL TABLES FROM `<db>` [LIKE '<pattern>']
DatabaseMetaData.getColumns(..., table, ...) → SHOW FULL COLUMNS FROM `<表>` FROM `<db>`
DatabaseMetaData.getPrimaryKeys(...)         → SHOW FULL COLUMNS 中 Key='PRI' 的列合成
```

**为什么用 SHOW 而不是 information_schema**：Archery 的 `query_priv_check` 按 SQL
文本中出现的表名做表级权限校验，`information_schema.tables` 查询会被服务端拦截
（"你无information_schema.tables表的查询权限"）；SHOW 语句在连接库上下文执行，
只需该库的查询授权，实测全部放行。

行为细节：

- `getColumns` 的表名 pattern **不含 `%` 时视为字面表名**（DataGrip 逐表内省传精确名，
  表名中的 `_` 不作通配符）；含 `%` 时先 `SHOW FULL TABLES LIKE` 展开再逐表查询；
- 列类型从 `SHOW FULL COLUMNS` 的 Type 解析：`int(10) unsigned` → INTEGER、
  `varchar(128)` → VARCHAR(size=128)、`decimal(10,2)` → DECIMAL(10,2)、
  `Key='PRI'` → 主键、`Extra` 含 auto_increment → IS_AUTOINCREMENT=YES；
- `SHOW DATABASES` 会列出无权限的库名（MySQL 行为），展开时由服务端权限拦下。

## 列类型映射

优先级：

1. **服务端 `column_type_list`（前向兼容扩展约定）**：若 `/query/` 响应 `data` 含
   `column_type_list`（与 `column_list` 等长），元素为 MySQL 类型码整数
   （`cursor.description[i][1]`，如 3=int、12=datetime、253=varchar）或类型名字符串
   （`"int"`、`"decimal(10,2)"`）。存在时类型精确。
2. **启发式推断（缺省兜底）**：按行值（最多扫描 1000 行）推断
   TIMESTAMP/DATE/TIME/BIGINT/DECIMAL/DOUBLE/BOOLEAN，全 NULL 或无法判定 → VARCHAR。
   - 纯数字但超出 BIGINT 表示的列（无符号大整数/雪花 ID）→ DECIMAL，
     `getObject` 返回 `BigDecimal`；
   - `column_type_list` 声明 BIGINT 而值超出 long 范围 → `getObject` 返回 `BigInteger`（精度无损）。

> Archery 服务端序列化（`common/utils/extend_json_encoder.py`）会把 datetime/Decimal
> 转为字符串、`/query/` 以 `bigint_as_string=True` 输出大整数，因此推断模式无法区分
> 整数与数字字符串。生产环境建议给服务端补 `column_type_list`
> （sql/engines/mysql.py `query()` 中 `cursor.description` 的 type_code 目前被丢弃，
> 补一行即可），驱动无需改动、自动生效。

## 平台账号配置要求（部署侧）

驱动使用的平台账号需同时满足：

1. `sql.query_submit` 权限（在线查询功能权限）；
2. 账号所在资源组关联目标实例；
3. 对目标库持有有效期内的查询授权（QueryPrivileges）。

所有查询记入 QueryLog（审计精确到人）并经过数据脱敏链路。

## 在 DataGrip 中接入

1. **Database → Drivers（驱动管理器）→ "+" → Generic**；
2. **Driver Files**：添加 `archery-jdbc-driver-1.0.0-jar-with-dependencies.jar`
   （⚠️ 不要用 66KB 纯 jar，缺 gson；不要混入其他 archery-jdbc 变体的 jar）；
3. **Driver Class**：点 Find Class，选择 `com.archery.jdbc.driver.ArcheryDriver`；
4. **URL templates**：`jdbc:archery://{host::String}:{port::int}/{instance::String}/{db::String}`；
5. 新建数据源：填 host/port/instance/db 与**平台账号密码**（非数据库账号）；
6. Test Connection 通过后，Database Explorer 会自动内省出库/表/列
   （即上节元数据链路）。

## 在 DBeaver 中接入

1. Database → Driver Manager → New，名称填 Archery；
2. Driver Class：`com.archery.jdbc.driver.ArcheryDriver`（Find Class 自动发现）；
3. URL Template：`jdbc:archery://{host}:{port}/{instance}/{db}`；Default Port: 8000；
4. Libraries 添加 jar-with-dependencies.jar；
5. 新建连接填 host/port/instance/db 与平台账号密码。

## 已知限制

- **无流式游标**：结果集一次性物化，超大结果受 `limit_num` 与 HTTP 响应体积限制；
- **无事务、无写操作**（只读驱动的设计目标）；
- **PreparedStatement 参数在客户端转义**（`'` 翻倍 + `\` 翻倍）：MySQL
  `NO_BACKSLASH_ESCAPES` 模式下反斜杠会被多转义一次；
- **元数据为最小实现**：满足 BI 工具的对象浏览/元数据扫描；跨库表列表
  （catalog 指向非连接库）依赖服务端对该库的授权；`REMARKS`（表注释）为空
  （SHOW FULL TABLES 不含注释）；
- **SHOW DATABASES 列出无权限库名**（MySQL 固有行为），展开这些库时服务端会拒绝；
- **不支持 CTE/WITH 语句**：Archery 服务端 query_check 的语句类型判定不接受
  WITH 开头查询（服务端行为，与驱动无关）；
- **默认 limit_num=5000**：注意不要设为 0 依赖"不限行"——服务端 filter_sql 会把
  0 当作字面 `limit 0` 处理（对常量/无 FROM 查询返回空结果）。
