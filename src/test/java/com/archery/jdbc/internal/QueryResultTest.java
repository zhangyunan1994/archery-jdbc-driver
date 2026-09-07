package com.archery.jdbc.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class QueryResultTest {

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    public void parseWithServerTypeList() {
        JsonObject data = parse("{"
                + "\"column_list\": [\"id\", \"name\", \"created\"],"
                + "\"column_type_list\": [8, 253, 12],"
                + "\"rows\": [[\"9007199254740993\", \"alice\", \"2024-01-02 03:04:05\"],"
                + "           [\"2\", \"bob\", \"2024-01-02 04:04:05\"]],"
                + "\"affected_rows\": 2,"
                + "\"query_time\": \"0.12\""
                + "}");
        QueryResult r = QueryResult.fromJson(data);
        assertTrue(r.serverTyped);
        assertEquals(3, r.columnCount());
        assertEquals(Types.BIGINT, r.columnTypes.get(0).intValue());
        assertEquals(Types.VARCHAR, r.columnTypes.get(1).intValue());
        assertEquals(Types.TIMESTAMP, r.columnTypes.get(2).intValue());
        assertEquals("bigint", r.columnTypeNames.get(0));
        assertEquals(2, r.rows.size());
        assertEquals(2, r.affectedRows);
        assertEquals(0.12, r.queryTime, 1e-9);
    }

    @Test
    public void parseWithTypeNameList() {
        JsonObject data = parse("{"
                + "\"column_list\": [\"a\", \"b\"],"
                + "\"column_type_list\": [\"int\", \"datetime\"],"
                + "\"rows\": []"
                + "}");
        QueryResult r = QueryResult.fromJson(data);
        assertTrue(r.serverTyped);
        assertEquals(Types.INTEGER, r.columnTypes.get(0).intValue());
        assertEquals(Types.TIMESTAMP, r.columnTypes.get(1).intValue());
    }

    @Test
    public void inferenceFromValues() {
        // 模拟 bigint_as_string + Decimal→字符串 的服务端序列化
        JsonObject data = parse("{"
                + "\"column_list\": [\"cnt\", \"ratio\", \"dt\", \"d\", \"label\", \"empty\"],"
                + "\"rows\": ["
                + "  [\"9007199254740993\", \"1.5\", \"2024-01-02 03:04:05\", \"2024-01-02\", \"x\", null],"
                + "  [\"2\", \"2.5\", \"2024-01-03 03:04:05\", \"2024-01-03\", \"y\", null]"
                + "]"
                + "}");
        QueryResult r = QueryResult.fromJson(data);
        assertFalse(r.serverTyped);
        assertEquals(Types.BIGINT, r.columnTypes.get(0).intValue());
        assertEquals(Types.DOUBLE, r.columnTypes.get(1).intValue());
        assertEquals(Types.TIMESTAMP, r.columnTypes.get(2).intValue());
        assertEquals(Types.DATE, r.columnTypes.get(3).intValue());
        assertEquals(Types.VARCHAR, r.columnTypes.get(4).intValue());
        assertEquals(Types.VARCHAR, r.columnTypes.get(5).intValue()); // 全 NULL → VARCHAR
    }

    @Test
    public void jsonNumberInference() {
        JsonObject data = parse("{"
                + "\"column_list\": [\"i\", \"f\"],"
                + "\"rows\": [[1, 1.5], [2, 2.5]]"
                + "}");
        QueryResult r = QueryResult.fromJson(data);
        assertEquals(Types.BIGINT, r.columnTypes.get(0).intValue());
        assertEquals(Types.DOUBLE, r.columnTypes.get(1).intValue());
    }

    @Test
    public void raggedRowsNormalized() {
        // 行短于列数时，缺位按 null 处理（ArcheryResultSet 里同样容忍）
        JsonObject data = parse("{"
                + "\"column_list\": [\"a\", \"b\"],"
                + "\"rows\": [[1], [2, 3]]"
                + "}");
        QueryResult r = QueryResult.fromJson(data);
        assertEquals(2, r.columnCount());
        assertEquals(2, r.rows.size());
        assertEquals(1, r.rows.get(0).size());
        assertEquals(2, r.rows.get(1).size());
    }

    @Test
    public void localResultSets() {
        JsonArray row = new JsonArray();
        row.add("hello");
        QueryResult r = QueryResult.of(new String[]{"col"}, new int[]{Types.VARCHAR},
                java.util.Collections.singletonList(row));
        assertTrue(r.serverTyped);
        assertEquals(1, r.columnCount());
        assertEquals("col", r.columnNames.get(0));
    }
}
