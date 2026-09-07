package com.archery.jdbc.internal;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;

/**
 * ResultSetMetaData：列名 + 列类型（来自 column_type_list 或启发式推断）。
 * <p>
 * 精度/小数位等物理属性在 HTTP 协议中不可得，返回 0；可空性未知。
 */
public class ArcheryResultSetMetaData implements ResultSetMetaData {

  private final QueryResult result;

  ArcheryResultSetMetaData(QueryResult result) {
    this.result = result;
  }

  private void check(int column) throws SQLException {
    if (column < 1 || column > result.columnNames.size()) {
      throw new SQLException("列索引越界: " + column + " (共 " + result.columnNames.size() + " 列)", "07009");
    }
  }

  @Override
  public int getColumnCount() {
    return result.columnNames.size();
  }

  @Override
  public String getColumnName(int column) throws SQLException {
    check(column);
    return result.columnNames.get(column - 1);
  }

  @Override
  public String getColumnLabel(int column) throws SQLException {
    return getColumnName(column);
  }

  @Override
  public int getColumnType(int column) throws SQLException {
    check(column);
    return result.columnTypes.get(column - 1);
  }

  @Override
  public String getColumnTypeName(int column) throws SQLException {
    check(column);
    return result.columnTypeNames.get(column - 1);
  }

  @Override
  public String getColumnClassName(int column) throws SQLException {
    check(column);
    int type = result.columnTypes.get(column - 1);
    switch (type) {
      case java.sql.Types.TINYINT:
      case java.sql.Types.SMALLINT:
      case java.sql.Types.INTEGER:
        return Integer.class.getName();
      case java.sql.Types.BIGINT:
        return Long.class.getName();
      case java.sql.Types.REAL:
        return Float.class.getName();
      case java.sql.Types.FLOAT:
      case java.sql.Types.DOUBLE:
        return Double.class.getName();
      case java.sql.Types.DECIMAL:
      case java.sql.Types.NUMERIC:
        return BigDecimal.class.getName();
      case java.sql.Types.BIT:
      case java.sql.Types.BOOLEAN:
        return Boolean.class.getName();
      case java.sql.Types.DATE:
        return Date.class.getName();
      case java.sql.Types.TIME:
        return Time.class.getName();
      case java.sql.Types.TIMESTAMP:
        return Timestamp.class.getName();
      default:
        return String.class.getName();
    }
  }

  @Override
  public int getColumnDisplaySize(int column) throws SQLException {
    check(column);
    String name = result.columnNames.get(column - 1);
    return Math.max(name == null ? 1 : name.length(), 20);
  }

  @Override
  public String getSchemaName(int column) {
    return ""; // MySQL 无 schema
  }

  @Override
  public String getTableName(int column) {
    return ""; // HTTP 协议不携带来源表信息
  }

  @Override
  public String getCatalogName(int column) {
    return "";
  }

  @Override
  public int getPrecision(int column) {
    return 0; // 协议不携带
  }

  @Override
  public int getScale(int column) {
    return 0;
  }

  @Override
  public int isNullable(int column) {
    return columnNullableUnknown;
  }

  @Override
  public boolean isSigned(int column) {
    Integer t = column <= result.columnTypes.size() ? result.columnTypes.get(column - 1) : null;
    return t != null && MysqlTypes.isNumeric(t);
  }

  @Override
  public boolean isAutoIncrement(int column) {
    return false;
  }

  @Override
  public boolean isCaseSensitive(int column) {
    return false;
  }

  @Override
  public boolean isSearchable(int column) {
    return true;
  }

  @Override
  public boolean isCurrency(int column) {
    return false;
  }

  @Override
  public boolean isReadOnly(int column) {
    return true;
  }

  @Override
  public boolean isWritable(int column) {
    return false;
  }

  @Override
  public boolean isDefinitelyWritable(int column) {
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
}
