package com.archery.jdbc.internal;

import java.sql.Types;
import java.util.HashMap;
import java.util.Map;

/**
 * MySQL 类型 → java.sql.Types 映射。
 * <p>
 * 服务端 /query/ 响应中的 column_type_list（扩展约定）元素可以是：
 * <ul>
 *   <li>整数 —— cursor.description[i][1] 的 MySQL 类型码（如 3=LONG, 253=VAR_STRING）</li>
 *   <li>字符串 —— 列类型名（如 "int"、"varchar"、"datetime"）</li>
 * </ul>
 */
final class MysqlTypes {

  private MysqlTypes() {
  }

  private static final Map<Integer, Integer> CODE_TO_JDBC = new HashMap<>();
  private static final Map<Integer, String> CODE_TO_NAME = new HashMap<>();
  private static final Map<String, Integer> NAME_TO_JDBC = new HashMap<>();
  private static final Map<Integer, String> JDBC_TO_NAME = new HashMap<>();

  private static void code(int mysqlCode, String name, int jdbcType) {
    CODE_TO_JDBC.put(mysqlCode, jdbcType);
    CODE_TO_NAME.put(mysqlCode, name);
    JDBC_TO_NAME.putIfAbsent(jdbcType, name);
  }

  private static void name(String sqlName, int jdbcType) {
    NAME_TO_JDBC.put(sqlName.toLowerCase(), jdbcType);
    JDBC_TO_NAME.putIfAbsent(jdbcType, sqlName.toLowerCase());
  }

  static {
    // ---- MySQL 协议类型码（与 MySQLdb cursor.description[1] 一致）
    code(0, "decimal", Types.DECIMAL);
    code(1, "tinyint", Types.TINYINT);
    code(2, "smallint", Types.SMALLINT);
    code(3, "int", Types.INTEGER);
    code(4, "float", Types.REAL);
    code(5, "double", Types.DOUBLE);
    code(6, "null", Types.NULL);
    code(7, "timestamp", Types.TIMESTAMP);
    code(8, "bigint", Types.BIGINT);
    code(9, "mediumint", Types.INTEGER);
    code(10, "date", Types.DATE);
    code(11, "time", Types.TIME);
    code(12, "datetime", Types.TIMESTAMP);
    code(13, "year", Types.DATE);
    code(14, "newdate", Types.DATE);
    code(15, "varchar", Types.VARCHAR);
    code(16, "bit", Types.BIT);
    code(245, "json", Types.LONGVARCHAR);
    code(246, "decimal", Types.DECIMAL);
    code(247, "enum", Types.CHAR);
    code(248, "set", Types.CHAR);
    code(249, "tinyblob", Types.LONGVARBINARY);
    code(250, "mediumblob", Types.LONGVARBINARY);
    code(251, "longblob", Types.LONGVARBINARY);
    code(252, "blob", Types.LONGVARBINARY);
    code(253, "varchar", Types.VARCHAR);
    code(254, "char", Types.CHAR);
    code(255, "geometry", Types.LONGVARBINARY);

    // ---- 类型名映射（information_schema.columns 的 data_type / 各引擎通用）
    name("tinyint", Types.TINYINT);
    name("smallint", Types.SMALLINT);
    name("mediumint", Types.INTEGER);
    name("int", Types.INTEGER);
    name("integer", Types.INTEGER);
    name("bigint", Types.BIGINT);
    name("float", Types.REAL);
    name("double", Types.DOUBLE);
    name("decimal", Types.DECIMAL);
    name("numeric", Types.DECIMAL);
    name("date", Types.DATE);
    name("datetime", Types.TIMESTAMP);
    name("timestamp", Types.TIMESTAMP);
    name("time", Types.TIME);
    name("year", Types.DATE);
    name("char", Types.CHAR);
    name("varchar", Types.VARCHAR);
    name("nchar", Types.NCHAR);
    name("nvarchar", Types.NVARCHAR);
    name("tinytext", Types.LONGVARCHAR);
    name("text", Types.LONGVARCHAR);
    name("mediumtext", Types.LONGVARCHAR);
    name("longtext", Types.LONGVARCHAR);
    name("json", Types.LONGVARCHAR);
    name("binary", Types.BINARY);
    name("varbinary", Types.VARBINARY);
    name("tinyblob", Types.LONGVARBINARY);
    name("blob", Types.LONGVARBINARY);
    name("mediumblob", Types.LONGVARBINARY);
    name("longblob", Types.LONGVARBINARY);
    name("enum", Types.CHAR);
    name("set", Types.CHAR);
    name("bit", Types.BIT);
    name("boolean", Types.BOOLEAN);
    name("bool", Types.BOOLEAN);
    name("geometry", Types.LONGVARBINARY);
    name("uuid", Types.CHAR);
    name("interval", Types.VARCHAR);
    name("serial", Types.BIGINT);
    name("money", Types.DECIMAL);
    name("xml", Types.LONGVARCHAR);
    name("clob", Types.CLOB);
    name("nclob", Types.NCLOB);
  }

  /**
   * MySQL 类型码 → java.sql.Types；未知码返回 VARCHAR（最安全的兜底）
   */
  static int fromMysqlCode(int mysqlCode) {
    Integer t = CODE_TO_JDBC.get(mysqlCode);
    return t != null ? t : Types.VARCHAR;
  }

  /**
   * MySQL 类型码 → MySQL 类型名（用于 getColumnTypeName）
   */
  static String mysqlNameOfCode(int mysqlCode) {
    String n = CODE_TO_NAME.get(mysqlCode);
    return n != null ? n : "varchar";
  }

  /**
   * 类型名 → java.sql.Types；未知名返回 VARCHAR
   */
  static int fromTypeName(String typeName) {
    if (typeName == null) {
      return Types.VARCHAR;
    }
    String lower = typeName.toLowerCase().trim();
    // 去掉括号与长度，如 "decimal(10,2)" / "unsigned"
    int paren = lower.indexOf('(');
    if (paren > 0) {
      lower = lower.substring(0, paren).trim();
    }
    if (lower.endsWith(" unsigned") || lower.endsWith(" signed")) {
      lower = lower.substring(0, lower.lastIndexOf(' ')).trim();
    }
    Integer t = NAME_TO_JDBC.get(lower);
    return t != null ? t : Types.VARCHAR;
  }

  /**
   * java.sql.Types → 展示用类型名
   */
  static String jdbcNameOf(int jdbcType) {
    String n = JDBC_TO_NAME.get(jdbcType);
    return n != null ? n : "varchar";
  }

  static boolean isNumeric(int jdbcType) {
    switch (jdbcType) {
      case Types.TINYINT:
      case Types.SMALLINT:
      case Types.INTEGER:
      case Types.BIGINT:
      case Types.REAL:
      case Types.FLOAT:
      case Types.DOUBLE:
      case Types.DECIMAL:
      case Types.NUMERIC:
        return true;
      default:
        return false;
    }
  }

  static boolean isIntegral(int jdbcType) {
    switch (jdbcType) {
      case Types.TINYINT:
      case Types.SMALLINT:
      case Types.INTEGER:
      case Types.BIGINT:
        return true;
      default:
        return false;
    }
  }

  static boolean isTemporal(int jdbcType) {
    return jdbcType == Types.DATE || jdbcType == Types.TIME
        || jdbcType == Types.TIMESTAMP || jdbcType == Types.TIME_WITH_TIMEZONE
        || jdbcType == Types.TIMESTAMP_WITH_TIMEZONE;
  }
}
