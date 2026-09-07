# AGENTS.md — archery-jdbc-driver 开发上下文

> 本文件面向 AI 编码代理与后续开发者，记录项目背景、进度、关键设计决策与已验证的
> 服务端行为约束。修改代码前请先通读「硬约束」与「服务端实测行为」两节——其中多数
> 结论来自真实环境实测，是踩坑后得出的，不要凭直觉推翻。

## 1. 项目定位

**Archery 平台的只读 JDBC 驱动**（SELECT-only，HTTP 传输）。

- 背景：Archery（Django SQL 审核平台）无对外 token API，只有 session+CSRF 的 Web
  接口。为了让 BI 工具（DataGrip/DBeaver/FineBI/Metabase 等）通过标准 JDBC 访问
  Archery 管控的数据源，自研 thin JDBC 驱动，把 JDBC 调用翻译为对 Archery 现有
  HTTP 接口的调用。
- **零服务端改动**：只用 Archery 现有端点 `POST /authenticate/`、`POST /query/`、
  `GET /logout/`、`GET /api/info`。
- **仅 SELECT**：这是用户明确的需求边界——DELETE/UPDATE/INSERT/DDL 一律不支持，
  `executeUpdate`/`executeBatch` 等抛异常。不要"顺手"实现写能力。
- 复用 Archery 的权限校验、QueryLog 审计、数据脱敏、超时保护——驱动只是传输层，
  不能也不应绕过任何平台规则。
- 数据库凭据由 Archery 托管，客户端只持平台账号。

## 2. 当前状态（截至 2026-09-07）

功能已完成并在真实环境 Archery 1.8.3 回归通过：

- ✅ 连接/认证（session+CSRF，含 csrftoken 轮换跟随、302 自动重登录、403 刷新重试）
- ✅ Statement/PreparedStatement（`?` 占位符客户端替换，统一词法扫描：字符串/
  标识符/`''` 与 `\` 转义/`--`/`#`/`/* */` 注释）
- ✅ ResultSet：TYPE_SCROLL_INSENSITIVE 全导航（first/last/absolute/relative/previous）
- ✅ 列类型：`column_type_list` 前向兼容 + 启发式推断 + BigInteger/BigDecimal 精度保持
- ✅ 元数据内省（SHOW 方案）：getCatalogs/getTables/getColumns/getPrimaryKeys，
  DataGrip/DBeaver 对象树可用
- ✅ SQLState 关键词映射（28000/42000/08000/HY000）
- ✅ lazyLogin（懒登录）、close() best-effort 登出
- ✅ 多级域名 URL（a.archery.local 等，host 原样保留不剥离）
- 测试：45 个单元测试全过（JUnit Jupiter 5.10.2）；真实环境主链路+内省链已回归

## 3. 硬约束（改代码前必读）

1. **JDBC 4.2 接口必须全量实现**：实现类不能是抽象的。JDBC 接口方法面很大
   （ResultSet 约 170 个方法），新增类时先确保所有抽象方法都有实现。注意常量归属：
   `CLOSE_CURSORS_AT_COMMIT`/`TYPE_*`/`FETCH_*` 定义在 `java.sql.ResultSet`
   （不在 Connection/Statement），跨接口引用需 `ResultSet.` 限定。
2. **Java 8 字节码兼容**（`maven.compiler.release=8`）：许多 BI 工具自带 JRE 8。
   不要用 JDK 9+ API（`Map.of`、`String.isBlank` 等）。
3. **URL 前缀 `jdbc:archery:`**：`acceptsURL` 只看前缀；host 正则组为 `[^/:?#]+`
   （任意层级域名）。格式：`jdbc:archery://host[:port]/instance/db?params`，
   `jdbc:archery:https://...` 等价 ssl=true。
4. **SPI 类名**：`com.archery.jdbc.driver.ArcheryDriver`（META-INF/services 同步）。
   历史上从 `com.archery.jdbc.ArcheryDriver` 迁移过一次——改包名时务必同步 SPI 文件
   与 README，并考虑 DataGrip 等工具里用户已保存的旧配置会失效。
5. **不要混入其他 archery-jdbc 变体 jar**：oc/pi 是被淘汰的参考实现（见 §7），
   它们的 URL 语义不同且各有缺陷。
6. **测试不得硬编码真实凭据**。真实环境回归需要凭据时用运行时参数/环境变量注入。

## 4. 架构地图

```
com.archery.jdbc.driver.ArcheryDriver      入口：acceptsURL/connect/getPropertyInfo，SPI 注册
com.archery.jdbc.internal
  ├─ ArcheryUrl            URL/Properties 解析（正则 + 参数合并，Properties 优先）
  ├─ ArcheryHttpClient     HTTP 会话：登录/cookie/CSRF/query/403-302 恢复/logout/api/info
  ├─ ArcheryConnection     无状态连接持有者；事务 no-op；setCatalog=切库
  ├─ ArcheryStatement      executeQuery → POST /query/；maxRows/limit 协商
  ├─ ArcheryPreparedStatement  占位符替换（scan() 统一词法：计数与替换共用）
  ├─ ArcheryResultSet      SCROLL_INSENSITIVE 全导航 + 类型化读取
  ├─ ArcheryResultSetMetaData  列名/类型（来自 column_type_list 或推断）
  ├─ ArcheryDatabaseMetaData   SHOW 方案元数据（getCatalogs/getTables/getColumns/getPrimaryKeys）
  ├─ QueryResult           /query/ 响应解析 + 类型解析（column_type_list 优先/启发式推断）
  ├─ MysqlTypes            MySQL 类型码/类型名 ↔ java.sql.Types
  ├─ SqlValues             值转换（getObject/日期解析）+ 字面量渲染 + BigInteger
  └─ ArcheryHttpClient     依赖 gson（shade 进 jar-with-dependencies）
```

## 5. 关键设计决策与原因

- **认证**：session+CSRF 模拟浏览器（GET /login/ 取 csrftoken → POST /authenticate/
  携 X-CSRFToken）。Django `login()` 会 rotate_token——登录后 cookie 存储必须刷新，
  否则后续请求 403。会话过期=服务端 302→/login/，捕获后自动重登录；403 刷新 csrf 重试。
- **元数据用 SHOW 而非 information_schema**：Archery 的 `query_priv_check` 按 SQL
  **文本中的表名**做表级权限校验，`information_schema.tables` 查询被拦（实测：
  "你无information_schema.tables表的查询权限"）。SHOW 语句在连接库上下文执行，
  只需该库查询授权，实测放行。getColumns 字面表名（无 `%`）直接单表查；含 `%`
  先 `SHOW FULL TABLES LIKE` 展开再逐表；getPrimaryKeys 由 `Key='PRI'` 合成。
- **类型映射两级**：优先服务端 `column_type_list`（扩展约定：MySQL 类型码或类型名，
  服务端尚未实现——见 §8 待办）；缺失时启发式推断（扫最多 1000 行）。
- **值精度**：BIGINT 声明但超 long → `BigInteger`；纯数字超 BIGINT 范围的推断列 →
  `DECIMAL`（getObject 返回 BigDecimal）。
- **limit 语义**：URL `limit` 默认 5000；Statement.setMaxRows 覆盖；**0 的服务端
  行为不可靠**（见 §6）。
- **事务方法 no-op**：SELECT 无事务语义；为兼容会调 setAutoCommit/commit 的 BI
  工具，静默忽略而不是抛异常。
- **依赖策略**：仅 gson（shade 内嵌）。未采纳"自研 JSON 替换 gson"（收益仅 jar 体积，
  有解析回归风险）。

## 6. Archery 服务端实测行为（重要，勿凭文档/直觉假设）

以下全部来自 https://q.archery.local（Archery 1.8.3）实测：

- `/query/` 响应 `data.column_list` 只有列名；**`bigint_as_string=True`** 使大整数
  序列化为字符串，Decimal/datetime 也统一转字符串——值的 JSON 类型不可信，
  类型必须靠 column_type_list 或推断。
- `query_priv_check` 按 **SQL 文本中的表名**做表级校验（不只是连接的库）：
  information_schema.* 查询被拦；SHOW 语句放行（按连接库校验）。
- `filter_sql` 会给 SQL 追加/改写 LIMIT：`limit_num=0` 时对常量/无 FROM 查询会
  追加字面 `limit 0` → **空结果**。驱动默认发 5000，min(提交值, 用户权限上限)。
- 服务端 `query_check` 白名单只放行 SELECT/SHOW；**CTE（WITH ...）被拒**
  （"不支持的查询语法类型"）——服务端行为，与驱动无关，勿在驱动侧做语句白名单
  （会误杀服务端将来放行的语法）。
- `disable_star` 配置开启时 `select *` 被拦（CTE 里的 * 也会被拦）。
- 超时：服务端 `max_execution_time`（默认 60s）+ django_q 定时 kill 连接；
  驱动的 setQueryTimeout 仅记录。
- `GET /api/info` 免登录（中间件白名单），返回 archery 版本号。
- 服务端已实测可用端点行为详见原 Archery 仓库 `sql/query.py`、`sql/engines/mysql.py`、
  `common/auth.py`、`common/middleware/check_login_middleware.py`。

## 7. 历史背景：三实现赛马（为什么是现在这个形态）

早期在原 Archery 仓库并行实现过三版对比：`archery-jdbc-mc`（本驱动前身）、
`archery-jdbc-oc`、`archery-jdbc-pi`。实测对比后 mc 胜出（类型保真/中文正确/错误
恢复/测试完备），并已合并 oc/pi 的优点：

- 来自 oc：SCROLL_INSENSITIVE 可滚动结果集、PS 词法增强（反斜杠+注释）、lazyLogin
- 来自 pi：driver/internal 包分层、BigInteger 精度、SQLState 关键词映射、close 登出
- **有意未采纳**：pi 的驱动侧 SELECT 白名单（会拦 CTE，且与服务端口径不一致）、
  oc/pi 的元数据实现思路（pi 用 SHOW 的方向对了，但其 `limit_num=0` 默认值会触发
  服务端 limit 0 空结果——本驱动默认 5000）
- oc 的已知缺陷（若参考其代码要注意）：手写 urlDecode char→byte 截断中文、
  数字字符串类型退化为 VARCHAR、无测试

## 8. 待办 / 后续方向

- [ ] **服务端 column_type_list 增强**（需改 Archery，本仓库等待即可）：
  `sql/engines/mysql.py` 的 `query()` 把 `cursor.description[i][1]` 类型码随响应
  返回（约定字段名 `column_type_list`），驱动已前向兼容、无需改动即可提升类型精度。
- [ ] **token 鉴权**（需改 Archery）：当前 session+CSRF 需要真实账号密码，长期建议
  服务端加静态 token 绑定平台账号；驱动侧预留扩展（ArcheryHttpClient 登录流程集中）。
- [ ] DataGrip/DBeaver 的驱动配置文档沉淀（用户曾遇 `java.sql.Driver.<init>()`
  报错：根因是 FQCN 变更后旧配置失效 + 需选 jar-with-dependencies）。
- [ ] CI（GitHub Actions：mvn verify）与版本发布流程；jar 目前只有本地构建。
- [ ] 大结果集分页拉取（offset 协商）——当前一次性物化，setFetchSize 不生效。
- [ ] NO_BACKSLASH_ESCAPES 模式适配（当前 `\` 恒翻倍，该模式下会多转义）。

## 9. 构建与测试

```bash
# 单元测试（45 个，无需网络/凭据）
mvn test

# 打包（产物 target/archery-jdbc-driver-1.0.0-jar-with-dependencies.jar）
mvn package
```

- 环境：Java（`--release 8` 编译）+ Maven 3.9+（本机 SDKMAN：
  ~/myapps/sdkman/candidates/maven/current）。注意 Maven 自带的老版
  compiler 插件不识别 `release` 参数，pom 已固定 3.13.0，勿降级。
- shade 配置已设 `createDependencyReducedPom=false`（本项目不发仓库，
  该文件无用）。
- 测试框架：JUnit Jupiter 5.10.2 + Surefire 3.2.5。注意 JUnit 5 的
  `assertEquals(expected, actual, message)`——**消息在最后**（JUnit 4 在最前）；
  异常断言用 `assertThrows`（无 `@Test(expected=...)`）。
- ArcheryUrlTest.parseFullUrl1 是真实环境 URL 的解析用例（不含凭据）。

## 10. 真实环境回归

集成测试不在单测里（无凭据、不进 CI）。回归方式：写临时 main 程序，
`java -cp target/archery-jdbc-driver-1.0.0-jar-with-dependencies.jar Probe.java`，
覆盖以下用例后删除临时文件：

1. 主链路：登录 → `select * from user_api_key where id = 1` → 断言列 `api_key` 值为 `sdf`
2. 类型探测：`select 17000000000000000001 as big_id, 1.5 as ratio, '中文值' as label`
   → big_id 为 DECIMAL/BigDecimal 且精度无损、ratio 为 DOUBLE
3. 内省链：getCatalogs（SHOW DATABASES）/ getTables / getColumns / getPrimaryKeys
4. 滚动游标、lazyLogin（连接 <10ms 首查成功）、close 无异常
5. 中文实例名需 URL 编码（如 `zeus_%E4%BB%8E`）

凭据向账号所有者索取，**用后即弃、严禁提交进仓库或写进任何文件**。
