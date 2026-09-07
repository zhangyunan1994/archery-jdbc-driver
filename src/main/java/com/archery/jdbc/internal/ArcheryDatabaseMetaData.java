package com.archery.jdbc.internal;

import com.archery.jdbc.driver.ArcheryDriver;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * DatabaseMetaData 最小实现。
 * <p>
 * 元数据查询统一走 /query/（与普通 SELECT 同一条链路，同样受权限校验/审计约束）：
 * <ul>
 *   <li>表/列/主键用 SHOW 语句（SHOW FULL TABLES / SHOW FULL COLUMNS）：Archery 的
 *       query_priv_check 按 SQL 文本中的表名校验权限，information_schema.* 查询会被拦
 *       （实测"你无information_schema.tables表的查询权限"），SHOW 语句只需连接库的查询授权；
 *       SHOW DATABASES 实测放行</li>
 *   <li>getColumns 对字面表名（不含 %）直接查单表；含 % 的 pattern 先 SHOW FULL TABLES
 *       LIKE 展开再逐表查询；列名 pattern 在客户端按 LIKE 正则过滤</li>
 *   <li>不支持的对象类型（存储过程/索引/外键等）返回标准列形状的空结果集，
 *       保证 BI 工具元数据扫描不报错</li>
 * </ul>
 */
public class ArcheryDatabaseMetaData implements DatabaseMetaData {

  private final ArcheryConnection conn;
  private volatile String serverVersion;

  ArcheryDatabaseMetaData(ArcheryConnection conn) {
    this.conn = conn;
  }

  // ------------------------------------------------------------ 基础信息

  @Override
  public String getDatabaseProductName() {
    return "Archery";
  }

  @Override
  public String getDatabaseProductVersion() {
    if (serverVersion == null) {
      String v = conn.client().fetchServerVersion();
      serverVersion = v != null ? v : "unknown";
    }
    return serverVersion;
  }

  @Override
  public String getDriverName() {
    return "Archery JDBC Driver (HTTP transport)";
  }

  @Override
  public String getDriverVersion() {
    return ArcheryDriver.MAJOR_VERSION + "." + ArcheryDriver.MINOR_VERSION;
  }

  @Override
  public int getDriverMajorVersion() {
    return ArcheryDriver.MAJOR_VERSION;
  }

  @Override
  public int getDriverMinorVersion() {
    return ArcheryDriver.MINOR_VERSION;
  }

  @Override
  public String getURL() {
    return conn.url.rawUrl;
  }

  @Override
  public String getUserName() {
    return conn.url.user;
  }

  @Override
  public String getIdentifierQuoteString() {
    return "`";
  }

  @Override
  public String getSearchStringEscape() {
    return "\\";
  }

  @Override
  public String getExtraNameCharacters() {
    return "";
  }

  @Override
  public String getSQLKeywords() {
    return "";
  }

  @Override
  public String getNumericFunctions() {
    return "";
  }

  @Override
  public String getStringFunctions() {
    return "";
  }

  @Override
  public String getSystemFunctions() {
    return "";
  }

  @Override
  public String getTimeDateFunctions() {
    return "";
  }

  @Override
  public String getCatalogTerm() {
    return "database";
  }

  @Override
  public String getSchemaTerm() {
    return "";
  }

  @Override
  public String getProcedureTerm() {
    return "procedure";
  }

  @Override
  public int getDefaultTransactionIsolation() {
    return Connection.TRANSACTION_NONE;
  }

  @Override
  public RowIdLifetime getRowIdLifetime() {
    return RowIdLifetime.ROWID_UNSUPPORTED;
  }

  @Override
  public java.sql.ResultSet getCatalogs() throws SQLException {
    return queryInto("TABLE_CAT",
        "SHOW DATABASES", 1, null);
  }

  @Override
  public ResultSet getSchemas() {
    return localResultSet(new String[]{"TABLE_SCHEM", "TABLE_CATALOG"},
        new int[]{java.sql.Types.VARCHAR, java.sql.Types.VARCHAR},
        new ArrayList<JsonArray>());
  }

  @Override
  public ResultSet getTableTypes() {
    List<JsonArray> rows = new ArrayList<>();
    for (String t : Arrays.asList("TABLE", "VIEW", "SYSTEM TABLE")) {
      rows.add(row(t));
    }
    return localResultSet(new String[]{"TABLE_TYPE"}, new int[]{java.sql.Types.VARCHAR}, rows);
  }

  // ------------------------------------------------------------ 表 / 列
  //
  // 元数据查询用 SHOW 语句而非 information_schema：Archery 的 query_priv_check 按
  // SQL 文本中的表名校验权限（实测 information_schema.tables 会被拦
  // "你无information_schema.tables表的查询权限"），而 SHOW 语句在连接库上下文执行、
  // 只需该库的查询授权（SHOW DATABASES 已实测放行）。

  @Override
  public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern,
      String[] types) throws SQLException {
    String db = resolveDb(catalog, schemaPattern);
    StringBuilder sql = new StringBuilder("SHOW FULL TABLES FROM ").append(quoteIdent(db));
    if (tableNamePattern != null && !tableNamePattern.isEmpty()) {
      sql.append(" LIKE ").append(SqlValues.quote(tableNamePattern));
    }
    ResultSet src = executeMetaQuery(sql.toString());

    List<String> typeFilter = types == null ? null : Arrays.asList(types);

    String[] columns = {"TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS",
        "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "SELF_REFERENCING_COL_NAME", "REF_GENERATION"};
    int[] columnTypes = {java.sql.Types.VARCHAR, java.sql.Types.VARCHAR, java.sql.Types.VARCHAR,
        java.sql.Types.VARCHAR, java.sql.Types.VARCHAR, java.sql.Types.VARCHAR,
        java.sql.Types.VARCHAR, java.sql.Types.VARCHAR, java.sql.Types.VARCHAR, java.sql.Types.VARCHAR};
    List<JsonArray> rows = new ArrayList<>();
    try {
      while (src.next()) {
        String tableName = src.getString(1);
        String tableType = mapTableType(src.getString(2));
        if (typeFilter != null && !typeFilter.contains(tableType)) {
          continue;
        }
        // SHOW FULL TABLES 不含表注释（SHOW TABLE STATUS 才有），REMARKS 置空
        rows.add(row(db, null, tableName, tableType,
            null, null, null, null, null, null));
      }
    }
    finally {
      src.close();
    }
    return localResultSet(columns, columnTypes, rows);
  }

  @Override
  public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern,
      String columnNamePattern) throws SQLException {
    String db = resolveDb(catalog, schemaPattern);
    // 表名定位：无 % 视为字面表名（DataGrip 内省逐表调用传的就是精确名，_ 不当通配符）；
    // 含 % 按 LIKE 展开（先 SHOW FULL TABLES，再逐表取列）
    List<String> tables;
    if (tableNamePattern == null || tableNamePattern.isEmpty() || !tableNamePattern.contains("%")) {
      tables = java.util.Collections.singletonList(tableNamePattern == null ? "%" : tableNamePattern);
    }
    else {
      tables = new ArrayList<>();
      ResultSet src = executeMetaQuery("SHOW FULL TABLES FROM " + quoteIdent(db)
          + " LIKE " + SqlValues.quote(tableNamePattern));
      try {
        while (src.next()) {
          tables.add(src.getString(1));
        }
      }
      finally {
        src.close();
      }
    }

    Pattern columnFilter = likePattern(columnNamePattern);

    String[] columns = {"TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE",
        "TYPE_NAME", "COLUMN_SIZE", "BUFFER_LENGTH", "DECIMAL_DIGITS", "NUM_PREC_RADIX",
        "NULLABLE", "REMARKS", "COLUMN_DEF", "SQL_DATA_TYPE", "SQL_DATETIME_SUB",
        "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE", "SCOPE_CATALOG",
        "SCOPE_SCHEMA", "SCOPE_TABLE", "SOURCE_DATA_TYPE", "IS_AUTOINCREMENT"};
    int[] columnTypes = new int[columns.length];
    Arrays.fill(columnTypes, java.sql.Types.VARCHAR);
    columnTypes[4] = java.sql.Types.INTEGER;   // DATA_TYPE
    columnTypes[7] = java.sql.Types.INTEGER;   // BUFFER_LENGTH
    columnTypes[9] = java.sql.Types.INTEGER;   // NUM_PREC_RADIX
    columnTypes[10] = java.sql.Types.INTEGER;  // NULLABLE
    columnTypes[13] = java.sql.Types.INTEGER;  // SQL_DATA_TYPE
    columnTypes[14] = java.sql.Types.INTEGER;  // SQL_DATETIME_SUB
    columnTypes[16] = java.sql.Types.INTEGER;  // ORDINAL_POSITION
    columnTypes[21] = java.sql.Types.INTEGER;  // SOURCE_DATA_TYPE

    List<JsonArray> rows = new ArrayList<>();
    for (String table : tables) {
      ResultSet src = executeMetaQuery("SHOW FULL COLUMNS FROM " + quoteIdent(table)
          + " FROM " + quoteIdent(db));
      try {
        int ordinal = 0;
        while (src.next()) {
          // SHOW FULL COLUMNS: Field, Type, Collation, Null, Key, Default, Extra, Privileges, Comment
          String columnName = src.getString(1);
          if (columnFilter != null && !columnFilter.matcher(columnName).matches()) {
            continue;
          }
          String typeFull = src.getString(2);
          boolean nullable = "YES".equalsIgnoreCase(src.getString(4));
          String key = src.getString(5);
          String columnDefault = src.getString(6);
          String extra = src.getString(7);
          String comment = src.getString(9);
          ordinal++;

          int jdbcType = MysqlTypes.fromTypeName(typeFull);
          long[] meta = parseTypeMeta(typeFull); // [0]=precision/size, [1]=scale
          boolean numeric = MysqlTypes.isNumeric(jdbcType);
          boolean charType = jdbcType == java.sql.Types.CHAR
              || jdbcType == java.sql.Types.VARCHAR
              || jdbcType == java.sql.Types.LONGVARCHAR
              || jdbcType == java.sql.Types.BINARY
              || jdbcType == java.sql.Types.VARBINARY
              || jdbcType == java.sql.Types.LONGVARBINARY;
          rows.add(row(
              db, null, table, columnName,
              jdbcType,
              typeFull,
              meta[0],
              null,
              numeric ? meta[1] : 0L,
              numeric ? 10L : null,
              nullable ? 1 : 0,
              comment,
              columnDefault,
              null, null,
              charType ? meta[0] : null,
              ordinal,
              nullable ? "YES" : "NO",
              null, null, null, null,
              extra != null && extra.toLowerCase().contains("auto_increment") ? "YES" : "NO"));
        }
      }
      finally {
        src.close();
      }
    }
    return localResultSet(columns, columnTypes, rows);
  }

  @Override
  public ResultSet getPrimaryKeys(String catalog, String schema, String table) throws SQLException {
    String db = resolveDb(catalog, schema);
    if (table == null || table.isEmpty()) {
      throw new SQLException("getPrimaryKeys 需要 table 参数", "HY000");
    }
    ResultSet src = executeMetaQuery("SHOW FULL COLUMNS FROM " + quoteIdent(table)
        + " FROM " + quoteIdent(db));

    String[] columns = {"TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME",
        "KEY_SEQ", "PK_NAME"};
    int[] columnTypes = {java.sql.Types.VARCHAR, java.sql.Types.VARCHAR, java.sql.Types.VARCHAR,
        java.sql.Types.VARCHAR, java.sql.Types.SMALLINT, java.sql.Types.VARCHAR};
    List<JsonArray> rows = new ArrayList<>();
    try {
      int seq = 0;
      while (src.next()) {
        // Key='PRI' 即主键列；组合主键按列定义顺序编号
        if ("PRI".equalsIgnoreCase(src.getString(5))) {
          seq++;
          rows.add(row(db, null, table, src.getString(1), seq, "PRIMARY"));
        }
      }
    }
    finally {
      src.close();
    }
    return localResultSet(columns, columnTypes, rows);
  }

  // ------------------------------------------------------------ SHOW 方案辅助

  /**
   * 反引号标识符（内部 ` 翻倍），用于 SHOW 语句中的 db/表名
   */
  private static String quoteIdent(String identifier) {
    if (identifier == null || identifier.isEmpty()) {
      throw new IllegalArgumentException("标识符为空");
    }
    return "`" + identifier.replace("`", "``") + "`";
  }

  private static boolean hasWildcard(String s) {
    return s != null && (s.contains("%") || s.contains("_"));
  }

  /**
   * 从列类型全名解析 (precision/size, scale)： varchar(255) → [255, 0]、decimal(10,2) → [10, 2]、int(11) → [11, 0]、datetime → [0,
   * 0]。
   */
  private static long[] parseTypeMeta(String typeFull) {
    long precision = 0;
    long scale = 0;
    if (typeFull != null) {
      int open = typeFull.indexOf('(');
      int close = typeFull.lastIndexOf(')');
      if (open > 0 && close > open) {
        String inner = typeFull.substring(open + 1, close);
        int comma = inner.indexOf(',');
        try {
          precision = Long.parseLong(inner.substring(0, comma < 0 ? inner.length() : comma).trim());
          if (comma >= 0) {
            scale = Long.parseLong(inner.substring(comma + 1).trim());
          }
        }
        catch (NumberFormatException ignore) {
          precision = 0;
        }
      }
    }
    return new long[]{precision, scale};
  }

  @Override
  public ResultSet getTypeInfo() {
    String[] columns = {"TYPE_NAME", "DATA_TYPE", "PRECISION", "LITERAL_PREFIX", "LITERAL_SUFFIX",
        "CREATE_PARAMS", "NULLABLE", "CASE_SENSITIVE", "SEARCHABLE", "UNSIGNED_ATTRIBUTE",
        "FIXED_PREC_SCALE", "AUTO_INCREMENT", "LOCAL_TYPE_NAME", "MINIMUM_SCALE",
        "MAXIMUM_SCALE", "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "NUM_PREC_RADIX"};
    int[] columnTypes = new int[columns.length];
    Arrays.fill(columnTypes, java.sql.Types.VARCHAR);
    columnTypes[1] = java.sql.Types.SMALLINT;
    columnTypes[2] = java.sql.Types.INTEGER;
    columnTypes[6] = java.sql.Types.SMALLINT;
    columnTypes[7] = java.sql.Types.BOOLEAN;
    columnTypes[8] = java.sql.Types.SMALLINT;
    columnTypes[9] = java.sql.Types.BOOLEAN;
    columnTypes[10] = java.sql.Types.BOOLEAN;
    columnTypes[11] = java.sql.Types.BOOLEAN;
    columnTypes[13] = java.sql.Types.SMALLINT;
    columnTypes[14] = java.sql.Types.SMALLINT;
    columnTypes[15] = java.sql.Types.INTEGER;
    columnTypes[16] = java.sql.Types.INTEGER;
    columnTypes[17] = java.sql.Types.SMALLINT;

    Object[][] defs = {
        {"tinyint", java.sql.Types.TINYINT, "tinyint"},
        {"smallint", java.sql.Types.SMALLINT, "smallint"},
        {"mediumint", java.sql.Types.INTEGER, "mediumint"},
        {"int", java.sql.Types.INTEGER, "int"},
        {"integer", java.sql.Types.INTEGER, "integer"},
        {"bigint", java.sql.Types.BIGINT, "bigint"},
        {"float", java.sql.Types.REAL, "float"},
        {"double", java.sql.Types.DOUBLE, "double"},
        {"decimal", java.sql.Types.DECIMAL, "decimal"},
        {"numeric", java.sql.Types.DECIMAL, "numeric"},
        {"date", java.sql.Types.DATE, "date"},
        {"datetime", java.sql.Types.TIMESTAMP, "datetime"},
        {"timestamp", java.sql.Types.TIMESTAMP, "timestamp"},
        {"time", java.sql.Types.TIME, "time"},
        {"char", java.sql.Types.CHAR, "char"},
        {"varchar", java.sql.Types.VARCHAR, "varchar"},
        {"text", java.sql.Types.LONGVARCHAR, "text"},
        {"json", java.sql.Types.LONGVARCHAR, "json"},
        {"blob", java.sql.Types.LONGVARBINARY, "blob"},
        {"bit", java.sql.Types.BIT, "bit"},
        {"boolean", java.sql.Types.BOOLEAN, "boolean"},
    };
    List<JsonArray> rows = new ArrayList<>();
    for (Object[] d : defs) {
      boolean isString = ((Integer) d[1]) == java.sql.Types.CHAR
          || ((Integer) d[1]) == java.sql.Types.VARCHAR
          || ((Integer) d[1]) == java.sql.Types.LONGVARCHAR;
      rows.add(row(d[0], d[1], 0L, isString ? "'" : null, isString ? "'" : null,
          null, 1, false, 3, false, false, false, d[2], 0L, 0L, 0L, 0L, 10L));
    }
    return localResultSet(columns, columnTypes, rows);
  }

  // ------------------------------------------------------------ 空结果集（标准列形状）

  @Override
  public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern) {
    return empty("PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME", "R1", "R2", "R3",
        "REMARKS", "PROCEDURE_TYPE", "SPECIFIC_NAME");
  }

  @Override
  public ResultSet getProcedureColumns(String catalog, String schemaPattern,
      String procedureNamePattern, String columnNamePattern) {
    return empty("PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME", "COLUMN_NAME",
        "COLUMN_TYPE", "DATA_TYPE", "TYPE_NAME", "PRECISION", "LENGTH", "SCALE", "RADIX",
        "NULLABLE", "REMARKS", "COLUMN_DEF", "SQL_DATA_TYPE", "SQL_DATETIME_SUB",
        "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE", "SPECIFIC_NAME");
  }

  @Override
  public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern) {
    return empty("FUNCTION_CAT", "FUNCTION_SCHEM", "FUNCTION_NAME", "R1", "R2", "R3",
        "REMARKS", "FUNCTION_TYPE", "SPECIFIC_NAME");
  }

  @Override
  public ResultSet getFunctionColumns(String catalog, String schemaPattern,
      String functionNamePattern, String columnNamePattern) {
    return empty("FUNCTION_CAT", "FUNCTION_SCHEM", "FUNCTION_NAME", "COLUMN_NAME", "COLUMN_TYPE",
        "DATA_TYPE", "TYPE_NAME", "PRECISION", "LENGTH", "SCALE", "RADIX", "NULLABLE",
        "REMARKS", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE", "SPECIFIC_NAME");
  }

  @Override
  public ResultSet getBestRowIdentifier(String catalog, String schema, String table, int scope,
      boolean nullable) {
    return empty("SCOPE", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE",
        "BUFFER_LENGTH", "DECIMAL_DIGITS", "PSEUDO_COLUMN");
  }

  @Override
  public ResultSet getVersionColumns(String catalog, String schema, String table) {
    return empty("SCOPE", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE",
        "BUFFER_LENGTH", "DECIMAL_DIGITS", "PSEUDO_COLUMN");
  }

  @Override
  public ResultSet getImportedKeys(String catalog, String schema, String table) {
    return empty("PKTABLE_CAT", "PKTABLE_SCHEM", "PKTABLE_NAME", "PKCOLUMN_NAME",
        "FKTABLE_CAT", "FKTABLE_SCHEM", "FKTABLE_NAME", "FKCOLUMN_NAME", "KEY_SEQ",
        "UPDATE_RULE", "DELETE_RULE", "FK_NAME", "PK_NAME", "DEFERRABILITY");
  }

  @Override
  public ResultSet getExportedKeys(String catalog, String schema, String table) {
    return empty("PKTABLE_CAT", "PKTABLE_SCHEM", "PKTABLE_NAME", "PKCOLUMN_NAME",
        "FKTABLE_CAT", "FKTABLE_SCHEM", "FKTABLE_NAME", "FKCOLUMN_NAME", "KEY_SEQ",
        "UPDATE_RULE", "DELETE_RULE", "FK_NAME", "PK_NAME", "DEFERRABILITY");
  }

  @Override
  public ResultSet getCrossReference(String parentCatalog, String parentSchema, String parentTable,
      String foreignCatalog, String foreignSchema, String foreignTable) {
    return empty("PKTABLE_CAT", "PKTABLE_SCHEM", "PKTABLE_NAME", "PKCOLUMN_NAME",
        "FKTABLE_CAT", "FKTABLE_SCHEM", "FKTABLE_NAME", "FKCOLUMN_NAME", "KEY_SEQ",
        "UPDATE_RULE", "DELETE_RULE", "FK_NAME", "PK_NAME", "DEFERRABILITY");
  }

  @Override
  public ResultSet getIndexInfo(String catalog, String schema, String table, boolean unique,
      boolean approximate) {
    return empty("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "NON_UNIQUE", "INDEX_QUALIFIER",
        "INDEX_NAME", "TYPE", "ORDINAL_POSITION", "COLUMN_NAME", "ASC_OR_DESC",
        "CARDINALITY", "PAGES", "FILTER_CONDITION");
  }

  @Override
  public ResultSet getUDTs(String catalog, String schemaPattern, String typeNamePattern,
      int[] types) {
    return empty("TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "CLASS_NAME", "DATA_TYPE",
        "REMARKS", "BASE_TYPE");
  }

  @Override
  public ResultSet getSuperTypes(String catalog, String schemaPattern, String typeNamePattern) {
    return empty("TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "SUPERTYPE_CAT",
        "SUPERTYPE_SCHEM", "SUPERTYPE_NAME");
  }

  @Override
  public ResultSet getSuperTables(String catalog, String schemaPattern, String tableNamePattern) {
    return empty("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "SUPERTABLE_NAME");
  }

  @Override
  public ResultSet getAttributes(String catalog, String schemaPattern, String typeNamePattern,
      String attributeNamePattern) {
    return empty("TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "ATTR_NAME", "DATA_TYPE",
        "ATTR_TYPE_NAME", "ATTR_SIZE", "DECIMAL_DIGITS", "NUM_PREC_RADIX", "NULLABLE",
        "REMARKS", "ATTR_DEF", "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH",
        "ORDINAL_POSITION", "IS_NULLABLE", "SCOPE_CATALOG", "SCOPE_SCHEMA", "SCOPE_TABLE",
        "SOURCE_DATA_TYPE");
  }

  @Override
  public ResultSet getColumnPrivileges(String catalog, String schema, String table,
      String columnNamePattern) {
    return empty("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "GRANTOR",
        "GRANTEE", "PRIVILEGE", "IS_GRANTABLE");
  }

  @Override
  public ResultSet getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern) {
    return empty("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "GRANTOR", "GRANTEE",
        "PRIVILEGE", "IS_GRANTABLE");
  }

  // ------------------------------------------------------------ 特性支持

  @Override
  public boolean allProceduresAreCallable() {
    return false;
  }

  @Override
  public boolean allTablesAreSelectable() {
    return false; // 取决于平台授权
  }

  @Override
  public boolean usesLocalFiles() {
    return false;
  }

  @Override
  public boolean usesLocalFilePerTable() {
    return false;
  }

  @Override
  public boolean autoCommitFailureClosesAllResultSets() {
    return false;
  }

  @Override
  public java.sql.ResultSet getClientInfoProperties() {
    return empty("NAME", "MAX_LEN", "DEFAULT_VALUE", "DESCRIPTION");
  }

  @Override
  public java.sql.ResultSet getPseudoColumns(String catalog, String schemaPattern,
      String tableNamePattern, String columnNamePattern) {
    return empty("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE",
        "TYPE_NAME", "COLUMN_SIZE", "DECIMAL_DIGITS", "NUM_PREC_RADIX", "COLUMN_USAGE",
        "REMARKS", "CHAR_OCTET_LENGTH", "IS_NULLABLE");
  }

  @Override
  public java.sql.ResultSet getSchemas(String catalog, String schemaPattern) {
    return getSchemas();
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public boolean nullsAreSortedHigh() {
    return false;
  }

  @Override
  public boolean nullsAreSortedLow() {
    return true;
  }

  @Override
  public boolean nullsAreSortedAtStart() {
    return true;
  }

  @Override
  public boolean nullsAreSortedAtEnd() {
    return false;
  }

  @Override
  public boolean supportsTransactions() {
    return false;
  }

  @Override
  public boolean supportsTransactionIsolationLevel(int level) {
    return level == Connection.TRANSACTION_NONE;
  }

  @Override
  public boolean supportsDataDefinitionAndDataManipulationTransactions() {
    return false;
  }

  @Override
  public boolean supportsDataManipulationTransactionsOnly() {
    return false;
  }

  @Override
  public boolean dataDefinitionCausesTransactionCommit() {
    return false;
  }

  @Override
  public boolean dataDefinitionIgnoredInTransactions() {
    return false;
  }

  @Override
  public boolean supportsResultSetType(int type) {
    return type == ResultSet.TYPE_FORWARD_ONLY;
  }

  @Override
  public boolean supportsResultSetConcurrency(int type, int concurrency) {
    return type == ResultSet.TYPE_FORWARD_ONLY && concurrency == ResultSet.CONCUR_READ_ONLY;
  }

  @Override
  public boolean ownUpdatesAreVisible(int type) {
    return false;
  }

  @Override
  public boolean ownDeletesAreVisible(int type) {
    return false;
  }

  @Override
  public boolean ownInsertsAreVisible(int type) {
    return false;
  }

  @Override
  public boolean othersUpdatesAreVisible(int type) {
    return false;
  }

  @Override
  public boolean othersDeletesAreVisible(int type) {
    return false;
  }

  @Override
  public boolean othersInsertsAreVisible(int type) {
    return false;
  }

  @Override
  public boolean updatesAreDetected(int type) {
    return false;
  }

  @Override
  public boolean deletesAreDetected(int type) {
    return false;
  }

  @Override
  public boolean insertsAreDetected(int type) {
    return false;
  }

  @Override
  public boolean supportsBatchUpdates() {
    return false;
  }

  @Override
  public boolean supportsSavepoints() {
    return false;
  }

  @Override
  public boolean supportsNamedParameters() {
    return false;
  }

  @Override
  public boolean supportsMultipleOpenResults() {
    return false;
  }

  @Override
  public boolean supportsGetGeneratedKeys() {
    return false;
  }

  @Override
  public boolean supportsStatementPooling() {
    return false;
  }

  @Override
  public boolean supportsStoredProcedures() {
    return false;
  }

  @Override
  public boolean supportsSelectForUpdate() {
    return false;
  }

  @Override
  public boolean supportsCoreSQLGrammar() {
    return true;
  }

  @Override
  public boolean supportsExtendedSQLGrammar() {
    return false;
  }

  @Override
  public boolean supportsANSI92EntryLevelSQL() {
    return true;
  }

  @Override
  public boolean supportsANSI92IntermediateSQL() {
    return false;
  }

  @Override
  public boolean supportsANSI92FullSQL() {
    return false;
  }

  @Override
  public boolean supportsMinimumSQLGrammar() {
    return true;
  }

  @Override
  public boolean supportsGroupBy() {
    return true;
  }

  @Override
  public boolean supportsGroupByUnrelated() {
    return true;
  }

  @Override
  public boolean supportsGroupByBeyondSelect() {
    return true;
  }

  @Override
  public boolean supportsOrderByUnrelated() {
    return true;
  }

  @Override
  public boolean supportsLikeEscapeClause() {
    return true;
  }

  @Override
  public boolean supportsMultipleTransactions() {
    return false;
  }

  @Override
  public boolean supportsNonNullableColumns() {
    return true;
  }

  @Override
  public boolean supportsConvert() {
    return false;
  }

  @Override
  public boolean supportsConvert(int fromType, int toType) {
    return false;
  }

  @Override
  public boolean supportsTableCorrelationNames() {
    return true;
  }

  @Override
  public boolean supportsDifferentTableCorrelationNames() {
    return false;
  }

  @Override
  public boolean supportsExpressionsInOrderBy() {
    return true;
  }

  @Override
  public boolean supportsColumnAliasing() {
    return true;
  }

  @Override
  public boolean nullPlusNonNullIsNull() {
    return true;
  }

  @Override
  public boolean supportsOpenCursorsAcrossCommit() {
    return false;
  }

  @Override
  public boolean supportsOpenCursorsAcrossRollback() {
    return false;
  }

  @Override
  public boolean supportsOpenStatementsAcrossCommit() {
    return false;
  }

  @Override
  public boolean supportsOpenStatementsAcrossRollback() {
    return false;
  }

  @Override
  public boolean supportsMixedCaseIdentifiers() {
    return false;
  }

  @Override
  public boolean storesUpperCaseIdentifiers() {
    return false;
  }

  @Override
  public boolean storesLowerCaseIdentifiers() {
    return false;
  }

  @Override
  public boolean storesMixedCaseIdentifiers() {
    return true;
  }

  @Override
  public boolean supportsMixedCaseQuotedIdentifiers() {
    return true;
  }

  @Override
  public boolean storesUpperCaseQuotedIdentifiers() {
    return false;
  }

  @Override
  public boolean storesLowerCaseQuotedIdentifiers() {
    return false;
  }

  @Override
  public boolean storesMixedCaseQuotedIdentifiers() {
    return true;
  }

  @Override
  public boolean supportsAlterTableWithAddColumn() {
    return false;
  }

  @Override
  public boolean supportsAlterTableWithDropColumn() {
    return false;
  }

  @Override
  public boolean supportsIntegrityEnhancementFacility() {
    return false;
  }

  @Override
  public boolean supportsOuterJoins() {
    return true;
  }

  @Override
  public boolean supportsFullOuterJoins() {
    return false;
  }

  @Override
  public boolean supportsLimitedOuterJoins() {
    return true;
  }

  @Override
  public boolean supportsSchemasInDataManipulation() {
    return false;
  }

  @Override
  public boolean supportsSchemasInProcedureCalls() {
    return false;
  }

  @Override
  public boolean supportsSchemasInTableDefinitions() {
    return false;
  }

  @Override
  public boolean supportsSchemasInIndexDefinitions() {
    return false;
  }

  @Override
  public boolean supportsSchemasInPrivilegeDefinitions() {
    return false;
  }

  @Override
  public boolean supportsCatalogsInDataManipulation() {
    return true;
  }

  @Override
  public boolean supportsCatalogsInProcedureCalls() {
    return false;
  }

  @Override
  public boolean supportsCatalogsInTableDefinitions() {
    return true;
  }

  @Override
  public boolean supportsCatalogsInIndexDefinitions() {
    return true;
  }

  @Override
  public boolean supportsCatalogsInPrivilegeDefinitions() {
    return false;
  }

  @Override
  public boolean supportsPositionedDelete() {
    return false;
  }

  @Override
  public boolean supportsPositionedUpdate() {
    return false;
  }

  @Override
  public boolean supportsSubqueriesInComparisons() {
    return true;
  }

  @Override
  public boolean supportsSubqueriesInExists() {
    return true;
  }

  @Override
  public boolean supportsSubqueriesInIns() {
    return true;
  }

  @Override
  public boolean supportsSubqueriesInQuantifieds() {
    return true;
  }

  @Override
  public boolean supportsCorrelatedSubqueries() {
    return true;
  }

  @Override
  public boolean supportsUnion() {
    return true;
  }

  @Override
  public boolean supportsUnionAll() {
    return true;
  }

  // ------------------------------------------------------------ 最大值限制（0=未知/不限）

  @Override
  public int getMaxBinaryLiteralLength() {
    return 0;
  }

  @Override
  public int getMaxCharLiteralLength() {
    return 0;
  }

  @Override
  public int getMaxColumnNameLength() {
    return 64;
  }

  @Override
  public int getMaxColumnsInGroupBy() {
    return 0;
  }

  @Override
  public int getMaxColumnsInIndex() {
    return 0;
  }

  @Override
  public int getMaxColumnsInOrderBy() {
    return 0;
  }

  @Override
  public int getMaxColumnsInSelect() {
    return 0;
  }

  @Override
  public int getMaxColumnsInTable() {
    return 0;
  }

  @Override
  public int getMaxConnections() {
    return 0;
  }

  @Override
  public int getMaxCursorNameLength() {
    return 0;
  }

  @Override
  public int getMaxIndexLength() {
    return 0;
  }

  @Override
  public int getMaxSchemaNameLength() {
    return 0;
  }

  @Override
  public int getMaxProcedureNameLength() {
    return 0;
  }

  @Override
  public int getMaxCatalogNameLength() {
    return 0;
  }

  @Override
  public int getMaxRowSize() {
    return 0;
  }

  @Override
  public boolean doesMaxRowSizeIncludeBlobs() {
    return true;
  }

  @Override
  public int getMaxStatementLength() {
    return 0;
  }

  @Override
  public int getMaxStatements() {
    return 0;
  }

  @Override
  public int getMaxTableNameLength() {
    return 64;
  }

  @Override
  public int getMaxTablesInSelect() {
    return 0;
  }

  @Override
  public int getMaxUserNameLength() {
    return 0;
  }

  // ------------------------------------------------------------ 杂项

  @Override
  public boolean isCatalogAtStart() {
    return true;
  }

  @Override
  public String getCatalogSeparator() {
    return ".";
  }

  @Override
  public boolean locatorsUpdateCopy() {
    return true;
  }

  @Override
  public boolean supportsStoredFunctionsUsingCallSyntax() {
    return false;
  }

  @Override
  public int getSQLStateType() {
    return sqlStateSQL;
  }

  @Override
  public int getJDBCMajorVersion() {
    return 4;
  }

  @Override
  public int getJDBCMinorVersion() {
    return 2;
  }

  @Override
  public int getDatabaseMajorVersion() {
    return 1;
  }

  @Override
  public int getDatabaseMinorVersion() {
    return 0;
  }

  @Override
  public int getResultSetHoldability() {
    return java.sql.ResultSet.CLOSE_CURSORS_AT_COMMIT;
  }

  @Override
  public boolean supportsResultSetHoldability(int holdability) {
    return holdability == java.sql.ResultSet.CLOSE_CURSORS_AT_COMMIT;
  }

  @Override
  public boolean supportsMultipleResultSets() {
    return false;
  }

  @Override
  public Connection getConnection() {
    return conn;
  }

  @Override
  public boolean generatedKeyAlwaysReturned() {
    return false;
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (iface.isInstance(this)) {
      return iface.cast(this);
    }
    throw new SQLException("不支持 unwrap: " + iface.getName(), "HY000");
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) {
    return iface.isInstance(this);
  }

  // ------------------------------------------------------------ 内部工具

  /**
   * 元数据查询与普通 SELECT 走同一 /query/ 通道（权限校验/审计一致）
   */
  private ResultSet executeMetaQuery(String sql) throws SQLException {
    java.sql.Statement stmt = conn.createStatement();
    try {
      return stmt.executeQuery(sql);
    }
    catch (SQLException e) {
      stmt.close();
      throw e;
    }
  }

  /**
   * catalog/schema 参数 → 元数据查询的目标库（information_schema 可从任意库上下文访问）
   */
  private String resolveDb(String catalog, String schemaPattern) {
    if (catalog != null && !catalog.isEmpty() && !"%".equals(catalog)) {
      return catalog;
    }
    if (schemaPattern != null && !schemaPattern.isEmpty() && !"%".equals(schemaPattern)) {
      return schemaPattern;
    }
    return conn.currentDb();
  }

  private static String mapTableType(String infoSchemaType) {
    if (infoSchemaType == null) {
      return "TABLE";
    }
    switch (infoSchemaType) {
      case "BASE TABLE":
        return "TABLE";
      case "SYSTEM VIEW":
        return "SYSTEM TABLE";
      default:
        return infoSchemaType; // VIEW 等
    }
  }

  /**
   * SQL LIKE 模式 → 锚定正则（客户端过滤用）；null 或 "%" 返回 null 表示不过滤
   */
  private static Pattern likePattern(String pattern) {
    if (pattern == null || pattern.isEmpty() || "%".equals(pattern)) {
      return null;
    }
    StringBuilder re = new StringBuilder();
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (c == '\\' && i + 1 < pattern.length()) {
        re.append(Pattern.quote(String.valueOf(pattern.charAt(++i))));
      }
      else if (c == '%') {
        re.append(".*");
      }
      else if (c == '_') {
        re.append('.');
      }
      else {
        re.append(Pattern.quote(String.valueOf(c)));
      }
    }
    return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  }

  private static Long parseNullableLong(String s) {
    if (s == null || s.isEmpty() || "null".equalsIgnoreCase(s)) {
      return null;
    }
    try {
      return Long.parseLong(s.trim());
    }
    catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * 通过内部 statement 执行 SQL 并把结果映射为标准形状的元数据结果集
   */
  private ResultSet queryInto(String resultColumn, String sql, int srcColumn,
      String[] ignored) throws SQLException {
    ResultSet src = executeMetaQuery(sql);
    List<JsonArray> rows = new ArrayList<>();
    try {
      while (src.next()) {
        rows.add(row(src.getString(srcColumn)));
      }
    }
    finally {
      src.close();
    }
    return localResultSet(new String[]{resultColumn},
        new int[]{java.sql.Types.VARCHAR}, rows);
  }

  /**
   * 本地构造结果集（不经过服务端）
   */
  private static ResultSet localResultSet(String[] columns, int[] types, List<JsonArray> rows) {
    return new ArcheryResultSet(null, QueryResult.of(columns, types, rows));
  }

  private static ResultSet empty(String... columns) {
    int[] types = new int[columns.length];
    Arrays.fill(types, java.sql.Types.VARCHAR);
    return localResultSet(columns, types, new ArrayList<JsonArray>());
  }

  /**
   * Java 对象行 → JsonArray 行（自动装箱映射）
   */
  private static JsonArray row(Object... values) {
    JsonArray arr = new JsonArray();
    for (Object v : values) {
      if (v == null) {
        arr.add(JsonNull.INSTANCE);
      }
      else if (v instanceof String) {
        arr.add(new JsonPrimitive((String) v));
      }
      else if (v instanceof Boolean) {
        arr.add(new JsonPrimitive((Boolean) v));
      }
      else if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
        arr.add(new JsonPrimitive(((Number) v).longValue()));
      }
      else if (v instanceof Number) {
        arr.add(new JsonPrimitive(((Number) v).doubleValue()));
      }
      else {
        arr.add(new JsonPrimitive(String.valueOf(v)));
      }
    }
    return arr;
  }
}
