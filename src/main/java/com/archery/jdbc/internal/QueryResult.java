package com.archery.jdbc.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * /query/ 响应 data 部分的解析结果。
 * <p>
 * 服务端原始字段（sql/engines/models.py ResultSet.__dict__）： column_list、rows、affected_rows、full_sql、query_time、error、is_masked
 * 等。
 * <p>
 * 类型解析优先级：
 * <ol>
 *   <li>data.column_type_list —— 服务端类型扩展约定（元素为 MySQL 类型码或类型名），
 *       存在时类型精确（serverTyped=true）</li>
 *   <li>缺失时按行值做启发式推断（best-effort，受 bigint_as_string/Decimal 序列化为字符串影响，
 *       需配合服务端扩展才能完全精确）</li>
 * </ol>
 */
final class QueryResult {

  final List<String> columnNames = new ArrayList<>();
  final List<Integer> columnTypes = new ArrayList<>();
  final List<String> columnTypeNames = new ArrayList<>();
  final List<JsonArray> rows = new ArrayList<>();
  long affectedRows;
  double queryTime;
  boolean serverTyped;
  boolean masked;

  private QueryResult() {
  }

  static QueryResult fromJson(JsonObject data) {
    QueryResult r = new QueryResult();
    if (data.has("column_list") && data.get("column_list").isJsonArray()) {
      for (JsonElement e : data.getAsJsonArray("column_list")) {
        r.columnNames.add(e.isJsonNull() ? "" : e.getAsString());
      }
    }
    if (data.has("affected_rows") && !data.get("affected_rows").isJsonNull()) {
      try {
        r.affectedRows = Long.parseLong(data.get("affected_rows").getAsString());
      }
      catch (NumberFormatException ignore) {
        r.affectedRows = 0;
      }
    }
    if (data.has("query_time") && !data.get("query_time").isJsonNull()) {
      try {
        r.queryTime = data.get("query_time").getAsDouble();
      }
      catch (NumberFormatException ignore) {
        r.queryTime = 0;
      }
    }
    if (data.has("is_masked")) {
      r.masked = data.get("is_masked").isJsonPrimitive()
          && data.get("is_masked").getAsJsonPrimitive().isBoolean()
          && data.get("is_masked").getAsBoolean();
    }

    if (data.has("rows") && data.get("rows").isJsonArray()) {
      for (JsonElement row : data.getAsJsonArray("rows")) {
        if (row.isJsonArray()) {
          r.rows.add(row.getAsJsonArray());
        }
        else {
          JsonArray single = new JsonArray();
          single.add(row);
          r.rows.add(single);
        }
      }
    }

    resolveTypes(r, data);
    return r;
  }

  /**
   * 构造本地元数据结果集（DatabaseMetaData 用），绕过服务端
   */
  static QueryResult of(String[] columnNames, int[] columnTypes, List<JsonArray> rows) {
    QueryResult r = new QueryResult();
    for (String c : columnNames) {
      r.columnNames.add(c);
    }
    for (int t : columnTypes) {
      r.columnTypes.add(t);
      r.columnTypeNames.add(MysqlTypes.jdbcNameOf(t));
    }
    r.serverTyped = true;
    if (rows != null) {
      r.rows.addAll(rows);
    }
    r.affectedRows = r.rows.size();
    return r;
  }

  int columnCount() {
    return columnNames.size();
  }

  private static void resolveTypes(QueryResult r, JsonObject data) {
    int n = r.columnNames.size();
    JsonArray serverTypes = null;
    if (data.has("column_type_list") && data.get("column_type_list").isJsonArray()) {
      serverTypes = data.getAsJsonArray("column_type_list");
    }

    if (serverTypes != null && serverTypes.size() >= n) {
      r.serverTyped = true;
      for (int i = 0; i < n; i++) {
        JsonElement e = serverTypes.get(i);
        int jdbcType;
        String typeName;
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
          int mysqlCode = e.getAsInt();
          jdbcType = MysqlTypes.fromMysqlCode(mysqlCode);
          typeName = MysqlTypes.mysqlNameOfCode(mysqlCode);
        }
        else {
          String name = e.isJsonNull() ? "varchar" : e.getAsString();
          jdbcType = MysqlTypes.fromTypeName(name);
          typeName = name.toLowerCase();
        }
        r.columnTypes.add(jdbcType);
        r.columnTypeNames.add(typeName);
      }
    }
    else {
      // 启发式推断：bigint_as_string / Decimal→字符串 后类型信息已丢失
      r.serverTyped = false;
      for (int i = 0; i < n; i++) {
        int t = inferType(r.rows, i);
        r.columnTypes.add(t);
        r.columnTypeNames.add(MysqlTypes.jdbcNameOf(t));
      }
    }
  }

  private static final Pattern INT_PATTERN = Pattern.compile("-?\\d{1,19}");
  private static final Pattern DOUBLE_PATTERN = Pattern.compile("-?\\d+\\.\\d+([eE][+-]?\\d+)?");
  private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
  private static final Pattern DATETIME_PATTERN = Pattern.compile(
      "\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?");
  private static final Pattern TIME_PATTERN = Pattern.compile("\\d{1,2}:\\d{2}:\\d{2}(\\.\\d+)?");
  private static final Pattern DIGITS_PATTERN = Pattern.compile("-?\\d+");

  private static int inferType(List<JsonArray> rows, int col) {
    boolean sawValue = false;
    boolean allInt = true;
    boolean allDigits = true;   // 全为纯数字但可能超出 BIGINT 表示（如无符号大整数）
    boolean allDouble = true;
    boolean allDate = true;
    boolean allDateTime = true;
    boolean allTime = true;
    int scanned = 0;
    for (JsonArray row : rows) {
      if (col >= row.size()) {
        continue;
      }
      JsonElement e = row.get(col);
      if (e == null || e.isJsonNull()) {
        continue;
      }
      if (!e.isJsonPrimitive()) {
        return Types.VARCHAR;
      }
      JsonPrimitive p = e.getAsJsonPrimitive();
      String s;
      if (p.isBoolean()) {
        return Types.BOOLEAN;
      }
      if (p.isNumber()) {
        sawValue = true;
        allDate = false;
        allDateTime = false;
        allTime = false;
        String numStr = p.getAsString();
        if (!isIntegralLiteral(numStr)) {
          allInt = false;
        }
        if (!DIGITS_PATTERN.matcher(numStr).matches()) {
          allDigits = false;
        }
        if (!DOUBLE_PATTERN.matcher(numStr).matches() && !isIntegralLiteral(numStr)) {
          allDouble = false;
        }
        if (++scanned >= 1000) {
          break;
        }
        continue;
      }
      s = p.getAsString();
      sawValue = true;
      if (!INT_PATTERN.matcher(s).matches()) {
        allInt = false;
      }
      if (!DIGITS_PATTERN.matcher(s).matches()) {
        allDigits = false;
      }
      if (!DOUBLE_PATTERN.matcher(s).matches() && !INT_PATTERN.matcher(s).matches()) {
        allDouble = false;
      }
      if (!DATETIME_PATTERN.matcher(s).matches()) {
        allDateTime = false;
      }
      if (!DATE_PATTERN.matcher(s).matches()) {
        allDate = false;
      }
      if (!TIME_PATTERN.matcher(s).matches()) {
        allTime = false;
      }
      if (++scanned >= 1000) {
        break; // 大结果集时限制推断扫描量
      }
    }
    if (!sawValue) {
      return Types.VARCHAR; // 全 NULL 列
    }
    if (allDateTime) {
      return Types.TIMESTAMP;
    }
    if (allDate) {
      return Types.DATE;
    }
    if (allTime) {
      return Types.TIME;
    }
    if (allInt) {
      return Types.BIGINT; // bigint_as_string 序列化的长整数也归入 BIGINT
    }
    if (allDigits) {
      return Types.DECIMAL; // 纯数字但超出 BIGINT 表示（如无符号大整数），保精度
    }
    if (allDouble) {
      return Types.DOUBLE;
    }
    return Types.VARCHAR;
  }

  private static boolean isIntegralLiteral(String s) {
    try {
      Long.parseLong(s);
      return true;
    }
    catch (NumberFormatException e) {
      return false;
    }
  }

  /**
   * 空结果集（本地构造）
   */
  static JsonElement nullValue() {
    return JsonNull.INSTANCE;
  }
}
