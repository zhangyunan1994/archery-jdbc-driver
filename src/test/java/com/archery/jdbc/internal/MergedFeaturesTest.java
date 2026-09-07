package com.archery.jdbc.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 合并 oc/pi 优点后的新特性测试：可滚动导航、BigInteger、SQLState 映射 */
public class MergedFeaturesTest {

    // ------------------------------------------------------------ 可滚动导航（来自 oc）

    private ArcheryResultSet newRs(String json) {
        QueryResult r = QueryResult.fromJson(JsonParser.parseString(json).getAsJsonObject());
        return new ArcheryResultSet(null, r);
    }

    private static final String TWO_ROWS_JSON = "{"
            + "\"column_list\": [\"id\"],"
            + "\"column_type_list\": [3],"
            + "\"rows\": [[1], [2]]"
            + "}";

    @Test
    public void scrollInsensitiveNavigation() throws Exception {
        ArcheryResultSet rs = newRs(TWO_ROWS_JSON);
        assertEquals(java.sql.ResultSet.TYPE_SCROLL_INSENSITIVE, rs.getType());

        assertTrue(rs.last());
        assertEquals(2, rs.getInt(1));
        assertTrue(rs.previous());
        assertEquals(1, rs.getInt(1));
        assertTrue(rs.first());
        assertEquals(1, rs.getInt(1));
        assertTrue(rs.next());
        assertEquals(2, rs.getInt(1));
        rs.afterLast();
        assertFalse(rs.next());
        rs.beforeFirst();
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
    }

    @Test
    public void absoluteAndRelative() throws Exception {
        ArcheryResultSet rs = newRs(TWO_ROWS_JSON);
        assertTrue(rs.absolute(2));
        assertEquals(2, rs.getInt(1));
        assertFalse(rs.absolute(5));
        assertTrue(rs.absolute(-1)); // 最后一行
        assertEquals(2, rs.getInt(1));
        assertFalse(rs.relative(-5));
        assertEquals(0, rs.getRow());
        rs.beforeFirst();
        assertTrue(rs.relative(2));
        assertEquals(2, rs.getInt(1));
    }

    @Test
    public void emptyResultSetNavigation() throws Exception {
        ArcheryResultSet rs = newRs("{\"column_list\": [\"a\"], \"rows\": []}");
        assertFalse(rs.next());
        assertFalse(rs.first());
        assertFalse(rs.last());
        assertFalse(rs.absolute(1));
        rs.afterLast();
        assertFalse(rs.previous());
    }

    // ------------------------------------------------------------ BigInteger（来自 pi）

    @Test
    public void bigIntegerForOverRangeValues() throws Exception {
        // 超出 long 的无符号 BIGINT（雪花 ID 场景）
        Object v = SqlValues.getObject(new JsonPrimitive("9223372036854775808"), Types.BIGINT);
        assertEquals(BigInteger.class, v.getClass());
        assertEquals(new BigInteger("9223372036854775808"), v);
        // 范围内仍是 Long
        Object v2 = SqlValues.getObject(new JsonPrimitive("12345"), Types.BIGINT);
        assertEquals(Long.valueOf(12345L), v2);
    }

    @Test
    public void overRangeDigitColumnInfersDecimal() {
        QueryResult r = QueryResult.fromJson(JsonParser.parseString("{"
                + "\"column_list\": [\"snow_id\"],"
                + "\"rows\": [[\"17000000000000000001\"], [\"9223372036854775808\"]]"
                + "}").getAsJsonObject());
        assertEquals(Types.DECIMAL, r.columnTypes.get(0).intValue());
    }

    // ------------------------------------------------------------ SQLState 映射（来自 pi）

    @Test
    public void sqlStateKeywordMapping() {
        assertEquals("28000", ArcheryHttpClient.sqlStateFor("你所在组未关联该实例"));
        assertEquals("28000", ArcheryHttpClient.sqlStateFor("无该表权限"));
        assertEquals("42000", ArcheryHttpClient.sqlStateFor("只支持select查询语句"));
        assertEquals("42000", ArcheryHttpClient.sqlStateFor("table t1 doesn't exist"));
        assertEquals("08000", ArcheryHttpClient.sqlStateFor("连接超时"));
        assertEquals("HY000", ArcheryHttpClient.sqlStateFor("其他错误"));
        assertEquals("HY000", ArcheryHttpClient.sqlStateFor(null));
    }
}
