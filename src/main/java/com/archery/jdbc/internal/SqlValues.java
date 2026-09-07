package com.archery.jdbc.internal;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * JSON 值 ↔ JDBC Java 对象转换，以及 PreparedStatement 字面量渲染。
 * <p>
 * 服务端序列化约定（common/utils/extend_json_encoder.py）： datetime → "YYYY-MM-DD HH:MM:SS"、date → "YYYY-MM-DD"、Decimal → 字符串、
 * bigint_as_string=True（大整数序列化为字符串）。因此字符串解析需尽量宽容。
 */
final class SqlValues {

  private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
  private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
  private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private SqlValues() {
  }

  /**
   * 原始 JSON 元素的字符串形态（列值展示统一入口）
   */
  static String asString(JsonElement e) {
    if (e == null || e.isJsonNull()) {
      return null;
    }
    if (e.isJsonPrimitive()) {
      return e.getAsString();
    }
    return e.toString();
  }

  /**
   * 是否为 SQL NULL
   */
  static boolean isNull(JsonElement e) {
    return e == null || e.isJsonNull();
  }

  /**
   * 按列的 JDBC 类型转换为 Java 对象（ResultSet.getObject 语义）。
   */
  static Object getObject(JsonElement e, int jdbcType) throws SQLException {
    if (isNull(e)) {
      return null;
    }
    JsonPrimitive p = e.getAsJsonPrimitive();
    try {
      switch (jdbcType) {
        case Types.TINYINT:
        case Types.SMALLINT:
        case Types.INTEGER:
          return (int) parseLong(asStringOf(p));
        case Types.BIGINT:
          // 超出 long 范围的整数（如无符号 BIGINT / 雪花 ID）返回 BigInteger 保持精度
          return parseInteger(asStringOf(p));
        case Types.REAL:
          return (float) parseDouble(asStringOf(p));
        case Types.FLOAT:
        case Types.DOUBLE:
          return parseDouble(asStringOf(p));
        case Types.DECIMAL:
        case Types.NUMERIC:
          return new BigDecimal(asStringOf(p).trim());
        case Types.BIT:
        case Types.BOOLEAN:
          return toBoolean(asStringOf(p));
        case Types.DATE:
          return getDate(e);
        case Types.TIME:
          return getTime(e);
        case Types.TIMESTAMP:
          return getTimestamp(e);
        default:
          // CHAR/VARCHAR/LONGVARCHAR/BINARY 等统一按字符串
          return asStringOf(p);
      }
    }
    catch (SQLException ex) {
      throw ex;
    }
    catch (Exception ex) {
      throw new SQLException("值转换失败: " + asStringOf(p) + " → " + MysqlTypes.jdbcNameOf(jdbcType), "HY000", ex);
    }
  }

  private static String asStringOf(JsonPrimitive p) {
    return p.isBoolean() ? (p.getAsBoolean() ? "1" : "0") : p.getAsString();
  }

  static boolean toBoolean(String s) {
    if (s == null) {
      return false;
    }
    s = s.trim();
    return "1".equals(s) || "true".equalsIgnoreCase(s) || "t".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s);
  }

  static long parseLong(String s) throws SQLException {
    try {
      return Long.parseLong(s.trim());
    }
    catch (NumberFormatException e) {
      // 浮点形式的整数值（如 "12.0"）宽容处理
      try {
        return (long) Double.parseDouble(s.trim());
      }
      catch (NumberFormatException e2) {
        throw new SQLException("无法转换为整数: " + s, "HY000", e2);
      }
    }
  }

  /**
   * 整数解析：long 范围内返回 Long，超出返回 BigInteger（精度无损，对齐 pi 实现的优点）。
   */
  static Number parseInteger(String s) throws SQLException {
    String v = s.trim();
    try {
      return Long.parseLong(v);
    }
    catch (NumberFormatException ignored) {
      try {
        return new java.math.BigInteger(v);
      }
      catch (NumberFormatException e) {
        throw new SQLException("无法转换为整数: " + s, "HY000", e);
      }
    }
  }

  static double parseDouble(String s) throws SQLException {
    try {
      return Double.parseDouble(s.trim());
    }
    catch (NumberFormatException e) {
      throw new SQLException("无法转换为数值: " + s, "HY000", e);
    }
  }

  static Date getDate(JsonElement e) throws SQLException {
    String s = asString(e);
    if (s == null) {
      return null;
    }
    try {
      String day = s.trim();
      int space = indexOfAny(day, ' ', 'T');
      if (space > 0) {
        day = day.substring(0, space);
      }
      return Date.valueOf(LocalDate.parse(day, DATE_FMT));
    }
    catch (Exception ex) {
      throw new SQLException("无法转换为 DATE: " + s, "HY000", ex);
    }
  }

  static Time getTime(JsonElement e) throws SQLException {
    String s = asString(e);
    if (s == null) {
      return null;
    }
    try {
      String t = s.trim();
      int space = indexOfAny(t, ' ', 'T');
      if (space > 0) {
        t = t.substring(space + 1);
      }
      if (t.length() > 8) {
        t = t.substring(0, 8); // 丢弃微秒，java.sql.Time 不携带纳秒
      }
      return Time.valueOf(LocalTime.parse(t, TIME_FMT));
    }
    catch (Exception ex) {
      throw new SQLException("无法转换为 TIME: " + s, "HY000", ex);
    }
  }

  static Timestamp getTimestamp(JsonElement e) throws SQLException {
    String s = asString(e);
    if (s == null) {
      return null;
    }
    try {
      String v = s.trim().replace('T', ' ');
      // 去除时区后缀（Archery 输出为本地时间字符串）
      if (v.endsWith("Z") || (v.length() > 6 && (v.charAt(v.length() - 6) == '+'))) {
        v = v.length() > 6 && v.charAt(v.length() - 6) == '+' ? v.substring(0, v.length() - 6)
            : v.substring(0, v.length() - 1);
      }
      int dot = v.indexOf('.');
      Timestamp ts;
      if (dot > 0) {
        ts = Timestamp.valueOf(LocalDateTime.parse(v.substring(0, dot), DATETIME_FMT));
        String frac = v.substring(dot + 1);
        int nanos = 0;
        if (!frac.isEmpty() && frac.length() <= 9) {
          StringBuilder padded = new StringBuilder(frac);
          while (padded.length() < 9) {
            padded.append('0');
          }
          nanos = Integer.parseInt(padded.toString());
        }
        ts.setNanos(nanos);
      }
      else {
        ts = Timestamp.valueOf(LocalDateTime.parse(v, DATETIME_FMT));
      }
      return ts;
    }
    catch (Exception ex) {
      throw new SQLException("无法转换为 TIMESTAMP: " + s, "HY000", ex);
    }
  }

  private static int indexOfAny(String s, char a, char b) {
    int ia = s.indexOf(a);
    int ib = s.indexOf(b);
    if (ia < 0) {
      return ib;
    }
    if (ib < 0) {
      return ia;
    }
    return Math.min(ia, ib);
  }

  // ------------------------------------------------ PreparedStatement 字面量渲染

  /**
   * 将参数渲染为 SQL 字面量。 字符串转义：单引号翻倍（'' 写法在 MySQL 默认模式与 NO_BACKSLASH_ESCAPES 模式下均合法）， 反斜杠翻倍（默认模式需要；NO_BACKSLASH_ESCAPES
   * 模式下会多一个反斜杠，见 README 已知限制）。
   */
  static String renderLiteral(Object param) throws SQLException {
    if (param == null) {
      return "NULL";
    }
    if (param instanceof String) {
      return quote((String) param);
    }
    if (param instanceof Boolean) {
      return (Boolean) param ? "1" : "0";
    }
    if (param instanceof Byte || param instanceof Short || param instanceof Integer
        || param instanceof Long || param instanceof java.math.BigInteger) {
      return param.toString();
    }
    if (param instanceof Float || param instanceof Double) {
      double d = ((Number) param).doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) {
        throw new SQLException("不支持 NaN/Infinity 参数");
      }
      return new BigDecimal(d).toPlainString();
    }
    if (param instanceof BigDecimal) {
      return ((BigDecimal) param).toPlainString();
    }
    if (param instanceof java.sql.Date) {
      return quote(((java.sql.Date) param).toLocalDate().format(DATE_FMT));
    }
    if (param instanceof Time) {
      return quote(((Time) param).toLocalTime().format(TIME_FMT));
    }
    if (param instanceof Timestamp) {
      Timestamp ts = (Timestamp) param;
      String base = ts.toLocalDateTime().format(DATETIME_FMT);
      int nanos = ts.getNanos();
      if (nanos > 0) {
        String frac = String.format("%09d", nanos);
        int lastNonZero = frac.length();
        while (lastNonZero > 0 && frac.charAt(lastNonZero - 1) == '0') {
          lastNonZero--;
        }
        base = base + "." + frac.substring(0, lastNonZero);
      }
      return quote(base);
    }
    if (param instanceof java.util.Date) {
      return quote(new Timestamp(((java.util.Date) param).getTime()).toLocalDateTime().format(DATETIME_FMT));
    }
    throw new SQLException("不支持的参数类型: " + param.getClass().getName());
  }

  static String quote(String s) {
    StringBuilder sb = new StringBuilder(s.length() + 2);
    sb.append('\'');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\'' || c == '\\') {
        sb.append(c);
      }
      sb.append(c);
    }
    sb.append('\'');
    return sb.toString();
  }
}
