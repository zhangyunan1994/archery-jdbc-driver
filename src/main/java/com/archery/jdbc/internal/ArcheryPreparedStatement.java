package com.archery.jdbc.internal;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Calendar;

/**
 * PreparedStatement 实现：客户端参数替换后走 Statement 通道。
 * <p>
 * 替换在客户端完成（服务端 /query/ 只接受最终 SQL 文本）： 遍历 SQL 字符串，跳过字符串/标识符/注释中的 '?'，按顺序替换为参数字面量。
 */
public class ArcheryPreparedStatement extends ArcheryStatement implements java.sql.PreparedStatement {

  static final Object UNSET = new Object();
  private static final Object NULL_PARAM = new Object();

  private final String sql;
  private final Object[] params;

  ArcheryPreparedStatement(ArcheryConnection conn, String sql) {
    super(conn);
    this.sql = sql;
    this.params = new Object[countPlaceholders(sql)];
    java.util.Arrays.fill(params, UNSET);
  }

  /**
   * 统一词法扫描（计数与替换共用）：识别 '...' / "..." / `...`（含 '' 翻倍与反斜杠转义）、 -- 与 # 行注释、块注释，普通上下文中的 ? 记为占位符。 replace=true 时（out 非
   * null）按序渲染参数字面量；replace=false 仅计数。
   */
  private static int scan(String sql, StringBuilder out, Object[] params, boolean replace) throws SQLException {
    int count = 0;
    int i = 0;
    final int n = sql.length();
    while (i < n) {
      char c = sql.charAt(i);
      if (c == '\'' || c == '"' || c == '`') {
        char quote = c;
        if (out != null) {
          out.append(c);
        }
        i++;
        while (i < n) {
          char ch = sql.charAt(i);
          if (out != null) {
            out.append(ch);
          }
          if (ch == '\\' && quote != '`' && i + 1 < n) {
            // 反斜杠转义（MySQL 默认模式；反引号标识符内无转义语义）
            if (out != null) {
              out.append(sql.charAt(i + 1));
            }
            i += 2;
            continue;
          }
          i++;
          if (ch == quote) {
            if (i < n && sql.charAt(i) == quote) {
              // '' / "" / `` 翻倍转义
              if (out != null) {
                out.append(quote);
              }
              i++;
              continue;
            }
            break;
          }
        }
      }
      else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
        // -- 行注释（到行尾）
        while (i < n && sql.charAt(i) != '\n') {
          if (out != null) {
            out.append(sql.charAt(i));
          }
          i++;
        }
      }
      else if (c == '#') {
        // # 行注释（到行尾）
        while (i < n && sql.charAt(i) != '\n') {
          if (out != null) {
            out.append(sql.charAt(i));
          }
          i++;
        }
      }
      else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
        // /* ... */ 块注释
        if (out != null) {
          out.append(sql.charAt(i)).append(sql.charAt(i + 1));
        }
        i += 2;
        while (i < n) {
          char ch = sql.charAt(i);
          if (out != null) {
            out.append(ch);
          }
          i++;
          if (ch == '*' && i < n && sql.charAt(i) == '/') {
            if (out != null) {
              out.append('/');
            }
            i++;
            break;
          }
        }
      }
      else if (c == '?') {
        count++;
        if (replace) {
          if (count > params.length) {
            throw new SQLException("占位符数量与参数数量不一致", "07000");
          }
          out.append(SqlValues.renderLiteral(params[count - 1]));
        }
        i++;
      }
      else {
        if (out != null) {
          out.append(c);
        }
        i++;
      }
    }
    return count;
  }

  private static int countPlaceholders(String sql) {
    try {
      return scan(sql, null, null, false);
    }
    catch (SQLException e) {
      throw new IllegalStateException(e); // 仅计数不会抛
    }
  }

  private String substitutedSql() throws SQLException {
    return substitute(sql, params);
  }

  /**
   * 占位符替换（static 便于单元测试）：跳过字符串/标识符/注释内的 ?，按序渲染参数字面量
   */
  static String substitute(String sql, Object[] params) throws SQLException {
    for (int i = 0; i < params.length; i++) {
      if (params[i] == UNSET) {
        throw new SQLException("参数未设置: index=" + (i + 1) + " (1-based)", "07000");
      }
    }
    StringBuilder out = new StringBuilder(sql.length() + 64);
    scan(sql, out, params, true);
    return out.toString();
  }

  // ------------------------------------------------------------ 执行

  @Override
  public ResultSet executeQuery() throws SQLException {
    checkOpen();
    return executeAndWrap(substitutedSql(), effectiveLimit());
  }

  @Override
  public boolean execute() throws SQLException {
    checkOpen();
    executeAndWrap(substitutedSql(), effectiveLimit());
    return currentResult != null && currentResult.columnCount() > 0;
  }

  @Override
  public int executeUpdate() throws SQLException {
    throw new SQLException("只读驱动仅支持 SELECT", "HY000");
  }

  @Override
  public long executeLargeUpdate() throws SQLException {
    throw new SQLException("只读驱动仅支持 SELECT", "HY000");
  }

  @Override
  public boolean execute(String sql) throws SQLException {
    throw new SQLFeatureNotSupportedException("PreparedStatement 不支持传入 SQL 文本", "0A000");
  }

  @Override
  public ResultSet executeQuery(String sql) throws SQLException {
    throw new SQLFeatureNotSupportedException("PreparedStatement 不支持传入 SQL 文本", "0A000");
  }

  @Override
  public int executeUpdate(String sql) throws SQLException {
    throw new SQLFeatureNotSupportedException("PreparedStatement 不支持传入 SQL 文本", "0A000");
  }

  // ------------------------------------------------------------ 参数设置

  private void set(int parameterIndex, Object value) throws SQLException {
    checkOpen();
    if (parameterIndex < 1 || parameterIndex > params.length) {
      throw new SQLException("参数索引越界: " + parameterIndex + " (共 " + params.length + " 个)", "07000");
    }
    params[parameterIndex - 1] = value;
  }

  @Override
  public void setNull(int parameterIndex, int sqlType) throws SQLException {
    set(parameterIndex, NULL_PARAM);
  }

  @Override
  public void setNull(int paramIndex, int sqlType, String typeName) throws SQLException {
    setNull(paramIndex, sqlType);
  }

  @Override
  public void setString(int parameterIndex, String x) throws SQLException {
    set(parameterIndex, x);
  }

  @Override
  public void setInt(int parameterIndex, int x) throws SQLException {
    set(parameterIndex, x);
  }

  @Override
  public void setLong(int parameterIndex, long x) throws SQLException {
    set(parameterIndex, x);
  }

  @Override
  public void setShort(int parameterIndex, short x) throws SQLException {
    set(parameterIndex, x);
  }

  @Override
  public void setByte(int parameterIndex, byte x) throws SQLException {
    set(parameterIndex, x);
  }

  @Override
  public void setFloat(int parameterIndex, float x) throws SQLException {
    set(parameterIndex, x);
  }

  @Override
  public void setDouble(int parameterIndex, double x) throws SQLException {
    set(parameterIndex, x);
  }

  @Override
  public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
    set(parameterIndex, x);
  }

  @Override
  public void setBoolean(int parameterIndex, boolean x) throws SQLException {
    set(parameterIndex, x);
  }

  @Override
  public void setDate(int parameterIndex, Date x) throws SQLException {
    set(parameterIndex, x);
  }

  @Override
  public void setTime(int parameterIndex, Time x) throws SQLException {
    set(parameterIndex, x);
  }

  @Override
  public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
    set(parameterIndex, x);
  }

  @Override
  public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
    set(parameterIndex, x);
  }

  @Override
  public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
    set(parameterIndex, x);
  }

  @Override
  public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
    set(parameterIndex, x);
  }

  @Override
  public void setObject(int parameterIndex, Object x) throws SQLException {
    if (x == null) {
      setNull(parameterIndex, Types.NULL);
      return;
    }
    if (x instanceof String || x instanceof Boolean || x instanceof Byte || x instanceof Short
        || x instanceof Integer || x instanceof Long || x instanceof Float || x instanceof Double
        || x instanceof BigDecimal || x instanceof java.math.BigInteger
        || x instanceof Date || x instanceof Time || x instanceof Timestamp
        || x instanceof java.util.Date) {
      set(parameterIndex, x);
    }
    else {
      throw new SQLFeatureNotSupportedException("不支持的参数类型: " + x.getClass().getName(), "0A000");
    }
  }

  @Override
  public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
    setObject(parameterIndex, x);
  }

  @Override
  public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
    setObject(parameterIndex, x);
  }

  @Override
  public void setBytes(int parameterIndex, byte[] x) throws SQLException {
    throw new SQLFeatureNotSupportedException("不支持二进制参数", "0A000");
  }

  @Override
  public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
    throw new SQLFeatureNotSupportedException("不支持流参数", "0A000");
  }

  @Override
  public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {
    throw new SQLFeatureNotSupportedException("不支持流参数", "0A000");
  }

  @Override
  public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
    throw new SQLFeatureNotSupportedException("不支持流参数", "0A000");
  }

  @Override
  public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {
    throw new SQLFeatureNotSupportedException("不支持流参数", "0A000");
  }

  @Override
  public void setRowId(int parameterIndex, RowId x) throws SQLException {
    throw new SQLFeatureNotSupportedException("不支持 RowId 参数", "0A000");
  }

  @Override
  public void setNString(int parameterIndex, String value) throws SQLException {
    set(parameterIndex, value); // UTF-8 通道，NString 等价
  }

  @Override
  public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
    throw new SQLFeatureNotSupportedException("不支持流参数", "0A000");
  }

  @Override
  public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
    throw new SQLFeatureNotSupportedException("不支持流参数", "0A000");
  }

  @Override
  public void setNClob(int parameterIndex, NClob value) throws SQLException {
    throw new SQLFeatureNotSupportedException("不支持 NClob 参数", "0A000");
  }

  @Override
  public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
    throw new SQLFeatureNotSupportedException("不支持 NClob 参数", "0A000");
  }

  @Override
  public void setNClob(int parameterIndex, Reader reader) throws SQLException {
    throw new SQLFeatureNotSupportedException("不支持 NClob 参数", "0A000");
  }

  @Override
  public void setBlob(int parameterIndex, Blob x) throws SQLException {
    throw new SQLFeatureNotSupportedException("不支持 Blob 参数", "0A000");
  }

  @Override
  public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
    throw new SQLFeatureNotSupportedException("不支持 Blob 参数", "0A000");
  }

  @Override
  public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
    throw new SQLFeatureNotSupportedException("不支持 Blob 参数", "0A000");
  }

  @Override
  public void setClob(int parameterIndex, Clob x) throws SQLException {
    throw new SQLFeatureNotSupportedException("不支持 Clob 参数", "0A000");
  }

  @Override
  public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
    throw new SQLFeatureNotSupportedException("不支持 Clob 参数", "0A000");
  }

  @Override
  public void setClob(int parameterIndex, Reader reader) throws SQLException {
    throw new SQLFeatureNotSupportedException("不支持 Clob 参数", "0A000");
  }

  @Override
  public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
    throw new SQLFeatureNotSupportedException("不支持 SQLXML 参数", "0A000");
  }

  @Override
  public void setURL(int parameterIndex, java.net.URL x) throws SQLException {
    throw new SQLFeatureNotSupportedException("不支持 URL 参数", "0A000");
  }

  @Override
  public void setArray(int parameterIndex, Array x) throws SQLException {
    throw new SQLFeatureNotSupportedException("不支持 Array 参数", "0A000");
  }

  @Override
  public void setRef(int parameterIndex, Ref x) throws SQLException {
    throw new SQLFeatureNotSupportedException("不支持 Ref 参数", "0A000");
  }

  @Override
  public void setObject(int parameterIndex, Object x, java.sql.SQLType targetSqlType) throws SQLException {
    setObject(parameterIndex, x);
  }

  @Override
  public void setObject(int parameterIndex, Object x, java.sql.SQLType targetSqlType, int scaleOrLength)
      throws SQLException {
    setObject(parameterIndex, x);
  }

  @Override
  public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
    throw new SQLFeatureNotSupportedException("不支持流参数", "0A000");
  }

  @Override
  public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
    throw new SQLFeatureNotSupportedException("不支持流参数", "0A000");
  }

  @Override
  public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
    throw new SQLFeatureNotSupportedException("不支持流参数", "0A000");
  }

  @Override
  public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
    throw new SQLFeatureNotSupportedException("不支持流参数", "0A000");
  }

  @Override
  public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
    throw new SQLFeatureNotSupportedException("不支持流参数", "0A000");
  }

  @Override
  public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
    throw new SQLFeatureNotSupportedException("不支持流参数", "0A000");
  }

  @Override
  public void clearParameters() {
    for (int i = 0; i < params.length; i++) {
      params[i] = UNSET;
    }
  }

  @Override
  public java.sql.ResultSetMetaData getMetaData() {
    // 未执行前类型未知；执行后由 lastResult 提供列信息
    if (currentResult != null) {
      return new ArcheryResultSetMetaData(currentResult);
    }
    return new ArcheryResultSetMetaData(QueryResult.of(new String[0], new int[0], null));
  }

  @Override
  public java.sql.ParameterMetaData getParameterMetaData() throws SQLException {
    throw new SQLFeatureNotSupportedException("不支持 ParameterMetaData", "0A000");
  }

  @Override
  public void addBatch() throws SQLException {
    throw new SQLFeatureNotSupportedException("只读驱动不支持 addBatch", "0A000");
  }

  @Override
  public void close() throws SQLException {
    super.close();
  }
}
