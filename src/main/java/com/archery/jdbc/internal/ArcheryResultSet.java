package com.archery.jdbc.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

/**
 * ResultSet 实现：遍历 /query/ 返回的 rows（二维数组）。
 * <p>
 * 值读取按 ResultSetMetaData 声明的列类型转换（见 SqlValues）； 大整数/Decimal 在服务端序列化为字符串，转换时统一宽容解析。 结果集一次性物化在内存中，因此提供
 * TYPE_SCROLL_INSENSITIVE 全导航 （first/last/absolute/previous 等，借鉴定制 oc 实现）；更新能力不支持（只读）。
 */
public class ArcheryResultSet implements ResultSet {

  private final Statement ownerStatement;
  private final QueryResult result;
  private final Map<String, Integer> labelIndex = new HashMap<>();

  private int cursor = -1; // before first
  private boolean closed;
  private boolean lastWasNull;
  private int fetchDirection = FETCH_FORWARD;

  ArcheryResultSet(Statement owner, QueryResult result) {
    this.ownerStatement = owner;
    this.result = result;
    for (int i = 0; i < result.columnNames.size(); i++) {
      labelIndex.put(result.columnNames.get(i).toLowerCase(), i + 1);
    }
  }

  private void checkOpen() throws SQLException {
    if (closed) {
      throw new SQLException("ResultSet 已关闭", "HY010");
    }
  }

  private void checkCursor() throws SQLException {
    checkOpen();
    if (cursor < 0) {
      throw new SQLException("游标位于第一行之前，请先调用 next()", "HY010");
    }
    if (cursor >= result.rows.size()) {
      throw new SQLException("游标已越过最后一行", "HY010");
    }
  }

  private int columnIndex(int columnIndex) throws SQLException {
    if (columnIndex < 1 || columnIndex > result.columnNames.size()) {
      throw new SQLException("列索引越界: " + columnIndex + " (共 " + result.columnNames.size() + " 列)", "07009");
    }
    return columnIndex - 1;
  }

  @Override
  public int findColumn(String columnLabel) throws SQLException {
    Integer idx = columnLabel == null ? null : labelIndex.get(columnLabel.toLowerCase());
    if (idx == null) {
      throw new SQLException("未找到列: " + columnLabel, "07009");
    }
    return idx;
  }

  /**
   * 取当前行指定列的原始 JSON 元素，并维护 wasNull 状态
   */
  private JsonElement raw(int columnIndex) throws SQLException {
    checkCursor();
    int col = columnIndex(columnIndex);
    JsonArray row = result.rows.get(cursor);
    JsonElement e = col < row.size() ? row.get(col) : null;
    lastWasNull = SqlValues.isNull(e);
    return e;
  }

  private JsonElement raw(String columnLabel) throws SQLException {
    return raw(findColumn(columnLabel));
  }

  private int typeOf(int columnIndex) {
    return result.columnTypes.get(columnIndex - 1);
  }

  private String asString(JsonElement e) {
    if (lastWasNull) {
      return null;
    }
    return SqlValues.asString(e);
  }

  private static SQLException unsupported(String api) {
    return new SQLFeatureNotSupportedException("只读驱动不支持: " + api, "0A000");
  }

  // ============================================================ 游标

  @Override
  public boolean next() throws SQLException {
    checkOpen();
    if (cursor < result.rows.size() - 1) {
      cursor++;
      return true;
    }
    cursor = result.rows.size(); // after last
    return false;
  }

  @Override
  public boolean isBeforeFirst() throws SQLException {
    checkOpen();
    return cursor == -1 && !result.rows.isEmpty();
  }

  @Override
  public boolean isAfterLast() throws SQLException {
    checkOpen();
    return !result.rows.isEmpty() && cursor >= result.rows.size();
  }

  @Override
  public boolean isFirst() throws SQLException {
    checkOpen();
    return cursor == 0;
  }

  @Override
  public boolean isLast() throws SQLException {
    checkOpen();
    return !result.rows.isEmpty() && cursor == result.rows.size() - 1;
  }

  @Override
  public int getRow() throws SQLException {
    checkOpen();
    return (cursor >= 0 && cursor < result.rows.size()) ? cursor + 1 : 0;
  }

  @Override
  public void beforeFirst() throws SQLException {
    checkOpen();
    cursor = -1;
  }

  @Override
  public void afterLast() throws SQLException {
    checkOpen();
    cursor = result.rows.size();
  }

  @Override
  public boolean first() throws SQLException {
    checkOpen();
    if (result.rows.isEmpty()) {
      return false;
    }
    cursor = 0;
    return true;
  }

  @Override
  public boolean last() throws SQLException {
    checkOpen();
    if (result.rows.isEmpty()) {
      return false;
    }
    cursor = result.rows.size() - 1;
    return true;
  }

  @Override
  public boolean absolute(int row) throws SQLException {
    checkOpen();
    if (row == 0) {
      cursor = -1;
      return false;
    }
    if (row > 0) {
      if (row <= result.rows.size()) {
        cursor = row - 1;
        return true;
      }
      cursor = result.rows.size();
      return false;
    }
    int target = result.rows.size() + row; // 负数从尾部计数
    if (target >= 0) {
      cursor = target;
      return true;
    }
    cursor = -1;
    return false;
  }

  @Override
  public boolean relative(int rows) throws SQLException {
    checkOpen();
    int target = cursor + rows;
    if (target < -1) {
      cursor = -1;
      return false;
    }
    if (target >= result.rows.size()) {
      cursor = result.rows.size();
      return false;
    }
    cursor = target;
    return cursor >= 0;
  }

  @Override
  public boolean previous() throws SQLException {
    checkOpen();
    if (cursor > result.rows.size()) {
      cursor = result.rows.size(); // after last 时先收敛再前移
    }
    cursor--;
    return cursor >= 0;
  }

  @Override
  public boolean rowUpdated() {
    return false;
  }

  @Override
  public boolean rowInserted() {
    return false;
  }

  @Override
  public boolean rowDeleted() {
    return false;
  }

  @Override
  public int getType() {
    return TYPE_SCROLL_INSENSITIVE;
  }

  @Override
  public int getConcurrency() {
    return CONCUR_READ_ONLY;
  }

  @Override
  public int getHoldability() {
    return CLOSE_CURSORS_AT_COMMIT;
  }

  @Override
  public void setFetchDirection(int direction) throws SQLException {
    checkOpen();
    if (direction != FETCH_FORWARD && direction != FETCH_REVERSE && direction != FETCH_UNKNOWN) {
      throw new SQLException("非法 fetchDirection: " + direction, "HY000");
    }
    this.fetchDirection = direction;
  }

  @Override
  public int getFetchDirection() throws SQLException {
    checkOpen();
    return fetchDirection;
  }

  @Override
  public void setFetchSize(int rows) {
    // 全量物化，无 fetch size 语义
  }

  @Override
  public int getFetchSize() {
    return result.rows.size();
  }

  // ============================================================ 按索引读取

  @Override
  public String getString(int columnIndex) throws SQLException {
    return asString(raw(columnIndex));
  }

  @Override
  public boolean getBoolean(int columnIndex) throws SQLException {
    JsonElement e = raw(columnIndex);
    return !lastWasNull && SqlValues.toBoolean(SqlValues.asString(e));
  }

  @Override
  public byte getByte(int columnIndex) throws SQLException {
    return (byte) getInt(columnIndex);
  }

  @Override
  public short getShort(int columnIndex) throws SQLException {
    return (short) getInt(columnIndex);
  }

  @Override
  public int getInt(int columnIndex) throws SQLException {
    JsonElement e = raw(columnIndex);
    return lastWasNull ? 0 : (int) SqlValues.parseLong(SqlValues.asString(e));
  }

  @Override
  public long getLong(int columnIndex) throws SQLException {
    JsonElement e = raw(columnIndex);
    return lastWasNull ? 0L : SqlValues.parseLong(SqlValues.asString(e));
  }

  @Override
  public float getFloat(int columnIndex) throws SQLException {
    return (float) getDouble(columnIndex);
  }

  @Override
  public double getDouble(int columnIndex) throws SQLException {
    JsonElement e = raw(columnIndex);
    return lastWasNull ? 0d : SqlValues.parseDouble(SqlValues.asString(e));
  }

  @Override
  public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
    BigDecimal v = getBigDecimal(columnIndex);
    return v == null ? null : v.setScale(scale, BigDecimal.ROUND_HALF_UP);
  }

  @Override
  public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
    JsonElement e = raw(columnIndex);
    return lastWasNull ? null : new BigDecimal(SqlValues.asString(e).trim());
  }

  @Override
  public byte[] getBytes(int columnIndex) throws SQLException {
    String s = getString(columnIndex);
    return s == null ? null : s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  @Override
  public Date getDate(int columnIndex) throws SQLException {
    return SqlValues.getDate(raw(columnIndex));
  }

  @Override
  public Time getTime(int columnIndex) throws SQLException {
    return SqlValues.getTime(raw(columnIndex));
  }

  @Override
  public Timestamp getTimestamp(int columnIndex) throws SQLException {
    return SqlValues.getTimestamp(raw(columnIndex));
  }

  @Override
  public Date getDate(int columnIndex, Calendar cal) throws SQLException {
    return getDate(columnIndex); // 服务端输出无时区信息
  }

  @Override
  public Time getTime(int columnIndex, Calendar cal) throws SQLException {
    return getTime(columnIndex);
  }

  @Override
  public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
    return getTimestamp(columnIndex);
  }

  @Override
  public Object getObject(int columnIndex) throws SQLException {
    JsonElement e = raw(columnIndex);
    if (lastWasNull) {
      return null;
    }
    return SqlValues.getObject(e, typeOf(columnIndex));
  }

  @Override
  public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
    return getObject(columnIndex);
  }

  @Override
  public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
    Object v = getObject(columnIndex);
    return v == null ? null : type.cast(v);
  }

  @Override
  public boolean wasNull() {
    return lastWasNull;
  }

  // ============================================================ 按列名读取

  @Override
  public String getString(String columnLabel) throws SQLException {
    return getString(findColumn(columnLabel));
  }

  @Override
  public boolean getBoolean(String columnLabel) throws SQLException {
    return getBoolean(findColumn(columnLabel));
  }

  @Override
  public byte getByte(String columnLabel) throws SQLException {
    return getByte(findColumn(columnLabel));
  }

  @Override
  public short getShort(String columnLabel) throws SQLException {
    return getShort(findColumn(columnLabel));
  }

  @Override
  public int getInt(String columnLabel) throws SQLException {
    return getInt(findColumn(columnLabel));
  }

  @Override
  public long getLong(String columnLabel) throws SQLException {
    return getLong(findColumn(columnLabel));
  }

  @Override
  public float getFloat(String columnLabel) throws SQLException {
    return getFloat(findColumn(columnLabel));
  }

  @Override
  public double getDouble(String columnLabel) throws SQLException {
    return getDouble(findColumn(columnLabel));
  }

  @Override
  public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
    return getBigDecimal(findColumn(columnLabel), scale);
  }

  @Override
  public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
    return getBigDecimal(findColumn(columnLabel));
  }

  @Override
  public byte[] getBytes(String columnLabel) throws SQLException {
    return getBytes(findColumn(columnLabel));
  }

  @Override
  public Date getDate(String columnLabel) throws SQLException {
    return getDate(findColumn(columnLabel));
  }

  @Override
  public Time getTime(String columnLabel) throws SQLException {
    return getTime(findColumn(columnLabel));
  }

  @Override
  public Timestamp getTimestamp(String columnLabel) throws SQLException {
    return getTimestamp(findColumn(columnLabel));
  }

  @Override
  public Date getDate(String columnLabel, Calendar cal) throws SQLException {
    return getDate(findColumn(columnLabel));
  }

  @Override
  public Time getTime(String columnLabel, Calendar cal) throws SQLException {
    return getTime(findColumn(columnLabel));
  }

  @Override
  public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {
    return getTimestamp(findColumn(columnLabel));
  }

  @Override
  public Object getObject(String columnLabel) throws SQLException {
    return getObject(findColumn(columnLabel));
  }

  @Override
  public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
    return getObject(findColumn(columnLabel));
  }

  @Override
  public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
    return getObject(findColumn(columnLabel), type);
  }

  // ============================================================ 流 / LOB / 大对象（不支持）

  @Override
  public InputStream getAsciiStream(int columnIndex) throws SQLException {
    throw unsupported("getAsciiStream");
  }

  @Override
  public InputStream getUnicodeStream(int columnIndex) throws SQLException {
    throw unsupported("getUnicodeStream");
  }

  @Override
  public InputStream getBinaryStream(int columnIndex) throws SQLException {
    throw unsupported("getBinaryStream");
  }

  @Override
  public Reader getCharacterStream(int columnIndex) throws SQLException {
    throw unsupported("getCharacterStream");
  }

  @Override
  public InputStream getAsciiStream(String columnLabel) throws SQLException {
    throw unsupported("getAsciiStream");
  }

  @Override
  public InputStream getUnicodeStream(String columnLabel) throws SQLException {
    throw unsupported("getUnicodeStream");
  }

  @Override
  public InputStream getBinaryStream(String columnLabel) throws SQLException {
    throw unsupported("getBinaryStream");
  }

  @Override
  public Reader getCharacterStream(String columnLabel) throws SQLException {
    throw unsupported("getCharacterStream");
  }

  @Override
  public Ref getRef(int columnIndex) throws SQLException {
    throw unsupported("getRef");
  }

  @Override
  public Ref getRef(String columnLabel) throws SQLException {
    throw unsupported("getRef");
  }

  @Override
  public Blob getBlob(int columnIndex) throws SQLException {
    throw unsupported("getBlob");
  }

  @Override
  public Blob getBlob(String columnLabel) throws SQLException {
    throw unsupported("getBlob");
  }

  @Override
  public Clob getClob(int columnIndex) throws SQLException {
    throw unsupported("getClob");
  }

  @Override
  public Clob getClob(String columnLabel) throws SQLException {
    throw unsupported("getClob");
  }

  @Override
  public Array getArray(int columnIndex) throws SQLException {
    throw unsupported("getArray");
  }

  @Override
  public Array getArray(String columnLabel) throws SQLException {
    throw unsupported("getArray");
  }

  @Override
  public URL getURL(int columnIndex) throws SQLException {
    throw unsupported("getURL");
  }

  @Override
  public URL getURL(String columnLabel) throws SQLException {
    throw unsupported("getURL");
  }

  @Override
  public String getNString(int columnIndex) throws SQLException {
    return getString(columnIndex); // UTF-8 通道，NString 等价
  }

  @Override
  public String getNString(String columnLabel) throws SQLException {
    return getString(columnLabel);
  }

  @Override
  public Reader getNCharacterStream(int columnIndex) throws SQLException {
    throw unsupported("getNCharacterStream");
  }

  @Override
  public Reader getNCharacterStream(String columnLabel) throws SQLException {
    throw unsupported("getNCharacterStream");
  }

  @Override
  public NClob getNClob(int columnIndex) throws SQLException {
    throw unsupported("getNClob");
  }

  @Override
  public NClob getNClob(String columnLabel) throws SQLException {
    throw unsupported("getNClob");
  }

  @Override
  public SQLXML getSQLXML(int columnIndex) throws SQLException {
    throw unsupported("getSQLXML");
  }

  @Override
  public SQLXML getSQLXML(String columnLabel) throws SQLException {
    throw unsupported("getSQLXML");
  }

  @Override
  public RowId getRowId(int columnIndex) throws SQLException {
    throw unsupported("getRowId");
  }

  @Override
  public RowId getRowId(String columnLabel) throws SQLException {
    throw unsupported("getRowId");
  }

  @Override
  public String getCursorName() throws SQLException {
    throw unsupported("getCursorName");
  }

  // ============================================================ 更新（只读结果集，全部不支持）

  @Override
  public void updateNull(int columnIndex) throws SQLException {
    throw unsupported("updateNull");
  }

  @Override
  public void updateNull(String columnLabel) throws SQLException {
    throw unsupported("updateNull");
  }

  @Override
  public void updateBoolean(int columnIndex, boolean x) throws SQLException {
    throw unsupported("updateBoolean");
  }

  @Override
  public void updateBoolean(String columnLabel, boolean x) throws SQLException {
    throw unsupported("updateBoolean");
  }

  @Override
  public void updateByte(int columnIndex, byte x) throws SQLException {
    throw unsupported("updateByte");
  }

  @Override
  public void updateByte(String columnLabel, byte x) throws SQLException {
    throw unsupported("updateByte");
  }

  @Override
  public void updateShort(int columnIndex, short x) throws SQLException {
    throw unsupported("updateShort");
  }

  @Override
  public void updateShort(String columnLabel, short x) throws SQLException {
    throw unsupported("updateShort");
  }

  @Override
  public void updateInt(int columnIndex, int x) throws SQLException {
    throw unsupported("updateInt");
  }

  @Override
  public void updateInt(String columnLabel, int x) throws SQLException {
    throw unsupported("updateInt");
  }

  @Override
  public void updateLong(int columnIndex, long x) throws SQLException {
    throw unsupported("updateLong");
  }

  @Override
  public void updateLong(String columnLabel, long x) throws SQLException {
    throw unsupported("updateLong");
  }

  @Override
  public void updateFloat(int columnIndex, float x) throws SQLException {
    throw unsupported("updateFloat");
  }

  @Override
  public void updateFloat(String columnLabel, float x) throws SQLException {
    throw unsupported("updateFloat");
  }

  @Override
  public void updateDouble(int columnIndex, double x) throws SQLException {
    throw unsupported("updateDouble");
  }

  @Override
  public void updateDouble(String columnLabel, double x) throws SQLException {
    throw unsupported("updateDouble");
  }

  @Override
  public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {
    throw unsupported("updateBigDecimal");
  }

  @Override
  public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException {
    throw unsupported("updateBigDecimal");
  }

  @Override
  public void updateString(int columnIndex, String x) throws SQLException {
    throw unsupported("updateString");
  }

  @Override
  public void updateString(String columnLabel, String x) throws SQLException {
    throw unsupported("updateString");
  }

  @Override
  public void updateBytes(int columnIndex, byte[] x) throws SQLException {
    throw unsupported("updateBytes");
  }

  @Override
  public void updateBytes(String columnLabel, byte[] x) throws SQLException {
    throw unsupported("updateBytes");
  }

  @Override
  public void updateDate(int columnIndex, Date x) throws SQLException {
    throw unsupported("updateDate");
  }

  @Override
  public void updateDate(String columnLabel, Date x) throws SQLException {
    throw unsupported("updateDate");
  }

  @Override
  public void updateTime(int columnIndex, Time x) throws SQLException {
    throw unsupported("updateTime");
  }

  @Override
  public void updateTime(String columnLabel, Time x) throws SQLException {
    throw unsupported("updateTime");
  }

  @Override
  public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException {
    throw unsupported("updateTimestamp");
  }

  @Override
  public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException {
    throw unsupported("updateTimestamp");
  }

  @Override
  public void updateObject(int columnIndex, Object x) throws SQLException {
    throw unsupported("updateObject");
  }

  @Override
  public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException {
    throw unsupported("updateObject");
  }

  @Override
  public void updateObject(String columnLabel, Object x) throws SQLException {
    throw unsupported("updateObject");
  }

  @Override
  public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException {
    throw unsupported("updateObject");
  }

  @Override
  public void updateObject(int columnIndex, Object x, java.sql.SQLType targetSqlType) throws SQLException {
    throw unsupported("updateObject");
  }

  @Override
  public void updateObject(int columnIndex, Object x, java.sql.SQLType targetSqlType, int scaleOrLength)
      throws SQLException {
    throw unsupported("updateObject");
  }

  @Override
  public void updateObject(String columnLabel, Object x, java.sql.SQLType targetSqlType) throws SQLException {
    throw unsupported("updateObject");
  }

  @Override
  public void updateObject(String columnLabel, Object x, java.sql.SQLType targetSqlType, int scaleOrLength)
      throws SQLException {
    throw unsupported("updateObject");
  }

  @Override
  public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException {
    throw unsupported("updateAsciiStream");
  }

  @Override
  public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException {
    throw unsupported("updateAsciiStream");
  }

  @Override
  public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {
    throw unsupported("updateAsciiStream");
  }

  @Override
  public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException {
    throw unsupported("updateAsciiStream");
  }

  @Override
  public void updateAsciiStream(String columnLabel, InputStream x, long length) throws SQLException {
    throw unsupported("updateAsciiStream");
  }

  @Override
  public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {
    throw unsupported("updateAsciiStream");
  }

  @Override
  public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException {
    throw unsupported("updateBinaryStream");
  }

  @Override
  public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException {
    throw unsupported("updateBinaryStream");
  }

  @Override
  public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {
    throw unsupported("updateBinaryStream");
  }

  @Override
  public void updateBinaryStream(String columnLabel, InputStream x, int length) throws SQLException {
    throw unsupported("updateBinaryStream");
  }

  @Override
  public void updateBinaryStream(String columnLabel, InputStream x, long length) throws SQLException {
    throw unsupported("updateBinaryStream");
  }

  @Override
  public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {
    throw unsupported("updateBinaryStream");
  }

  @Override
  public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException {
    throw unsupported("updateCharacterStream");
  }

  @Override
  public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
    throw unsupported("updateCharacterStream");
  }

  @Override
  public void updateCharacterStream(int columnIndex, Reader x) throws SQLException {
    throw unsupported("updateCharacterStream");
  }

  @Override
  public void updateCharacterStream(String columnLabel, Reader reader, int length) throws SQLException {
    throw unsupported("updateCharacterStream");
  }

  @Override
  public void updateCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
    throw unsupported("updateCharacterStream");
  }

  @Override
  public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException {
    throw unsupported("updateCharacterStream");
  }

  @Override
  public void updateBlob(int columnIndex, Blob x) throws SQLException {
    throw unsupported("updateBlob");
  }

  @Override
  public void updateBlob(int columnIndex, InputStream inputStream, long length) throws SQLException {
    throw unsupported("updateBlob");
  }

  @Override
  public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException {
    throw unsupported("updateBlob");
  }

  @Override
  public void updateBlob(String columnLabel, Blob x) throws SQLException {
    throw unsupported("updateBlob");
  }

  @Override
  public void updateBlob(String columnLabel, InputStream inputStream, long length) throws SQLException {
    throw unsupported("updateBlob");
  }

  @Override
  public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException {
    throw unsupported("updateBlob");
  }

  @Override
  public void updateClob(int columnIndex, Clob x) throws SQLException {
    throw unsupported("updateClob");
  }

  @Override
  public void updateClob(int columnIndex, Reader reader, long length) throws SQLException {
    throw unsupported("updateClob");
  }

  @Override
  public void updateClob(int columnIndex, Reader reader) throws SQLException {
    throw unsupported("updateClob");
  }

  @Override
  public void updateClob(String columnLabel, Clob x) throws SQLException {
    throw unsupported("updateClob");
  }

  @Override
  public void updateClob(String columnLabel, Reader reader, long length) throws SQLException {
    throw unsupported("updateClob");
  }

  @Override
  public void updateClob(String columnLabel, Reader reader) throws SQLException {
    throw unsupported("updateClob");
  }

  @Override
  public void updateNClob(int columnIndex, NClob nClob) throws SQLException {
    throw unsupported("updateNClob");
  }

  @Override
  public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException {
    throw unsupported("updateNClob");
  }

  @Override
  public void updateNClob(int columnIndex, Reader reader) throws SQLException {
    throw unsupported("updateNClob");
  }

  @Override
  public void updateNClob(String columnLabel, NClob nClob) throws SQLException {
    throw unsupported("updateNClob");
  }

  @Override
  public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException {
    throw unsupported("updateNClob");
  }

  @Override
  public void updateNClob(String columnLabel, Reader reader) throws SQLException {
    throw unsupported("updateNClob");
  }

  @Override
  public void updateArray(int columnIndex, Array x) throws SQLException {
    throw unsupported("updateArray");
  }

  @Override
  public void updateArray(String columnLabel, Array x) throws SQLException {
    throw unsupported("updateArray");
  }

  @Override
  public void updateRef(int columnIndex, Ref x) throws SQLException {
    throw unsupported("updateRef");
  }

  @Override
  public void updateRef(String columnLabel, Ref x) throws SQLException {
    throw unsupported("updateRef");
  }

  @Override
  public void updateRowId(int columnIndex, RowId x) throws SQLException {
    throw unsupported("updateRowId");
  }

  @Override
  public void updateRowId(String columnLabel, RowId x) throws SQLException {
    throw unsupported("updateRowId");
  }

  @Override
  public void updateNString(int columnIndex, String nString) throws SQLException {
    throw unsupported("updateNString");
  }

  @Override
  public void updateNString(String columnLabel, String nString) throws SQLException {
    throw unsupported("updateNString");
  }

  @Override
  public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
    throw unsupported("updateNCharacterStream");
  }

  @Override
  public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {
    throw unsupported("updateNCharacterStream");
  }

  @Override
  public void updateNCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
    throw unsupported("updateNCharacterStream");
  }

  @Override
  public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException {
    throw unsupported("updateNCharacterStream");
  }

  @Override
  public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException {
    throw unsupported("updateSQLXML");
  }

  @Override
  public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException {
    throw unsupported("updateSQLXML");
  }

  @Override
  public void insertRow() throws SQLException {
    throw unsupported("insertRow");
  }

  @Override
  public void updateRow() throws SQLException {
    throw unsupported("updateRow");
  }

  @Override
  public void deleteRow() throws SQLException {
    throw unsupported("deleteRow");
  }

  @Override
  public void refreshRow() throws SQLException {
    throw unsupported("refreshRow");
  }

  @Override
  public void cancelRowUpdates() {
    // 只读结果集，无更新可取消
  }

  @Override
  public void moveToInsertRow() throws SQLException {
    throw unsupported("moveToInsertRow");
  }

  @Override
  public void moveToCurrentRow() {
    // 只读结果集
  }

  // ============================================================ 元数据 / 生命周期

  @Override
  public ResultSetMetaData getMetaData() {
    return new ArcheryResultSetMetaData(result);
  }

  @Override
  public Statement getStatement() {
    return ownerStatement;
  }

  @Override
  public SQLWarning getWarnings() {
    return null;
  }

  @Override
  public void clearWarnings() {
    // 无 warning 通道
  }

  @Override
  public void close() {
    closed = true;
  }

  @Override
  public boolean isClosed() {
    return closed;
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
