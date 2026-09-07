package com.archery.jdbc.internal;

import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SqlValuesTest {

    @Test
    public void asStringVariants() throws SQLException {
        assertEquals("abc", SqlValues.asString(new JsonPrimitive("abc")));
        assertEquals("123", SqlValues.asString(new JsonPrimitive(123)));
        assertNull(SqlValues.asString(JsonNull.INSTANCE));
        assertNull(SqlValues.asString(null));
        assertTrue(SqlValues.isNull(JsonNull.INSTANCE));
        assertFalse(SqlValues.isNull(new JsonPrimitive("x")));
    }

    @Test
    public void getObjectByType() throws SQLException {
        assertEquals(123, SqlValues.getObject(new JsonPrimitive("123"), Types.INTEGER));
        assertEquals(123L, SqlValues.getObject(new JsonPrimitive("123"), Types.BIGINT));
        assertEquals(1.5d, (double) SqlValues.getObject(new JsonPrimitive("1.5"), Types.DOUBLE), 1e-9);
        assertEquals(new BigDecimal("1.50"), SqlValues.getObject(new JsonPrimitive("1.50"), Types.DECIMAL));
        assertEquals(Boolean.TRUE, SqlValues.getObject(new JsonPrimitive("1"), Types.BOOLEAN));
        assertEquals(Boolean.FALSE, SqlValues.getObject(new JsonPrimitive(0), Types.BIT));
        assertEquals("plain", SqlValues.getObject(new JsonPrimitive("plain"), Types.VARCHAR));
        assertNull(SqlValues.getObject(JsonNull.INSTANCE, Types.INTEGER));
    }

    @Test
    public void temporalParsing() throws SQLException {
        Date d = SqlValues.getDate(new JsonPrimitive("2024-03-05"));
        assertEquals("2024-03-05", d.toString());

        Timestamp ts = SqlValues.getTimestamp(new JsonPrimitive("2024-03-05 12:34:56"));
        assertEquals("2024-03-05 12:34:56.0", ts.toString());

        Timestamp tsIso = SqlValues.getTimestamp(new JsonPrimitive("2024-03-05T12:34:56"));
        assertEquals("2024-03-05 12:34:56.0", tsIso.toString());

        Timestamp tsFrac = SqlValues.getTimestamp(new JsonPrimitive("2024-03-05 12:34:56.123456"));
        assertEquals(123456000, tsFrac.getNanos());

        Time t = SqlValues.getTime(new JsonPrimitive("12:34:56"));
        assertEquals("12:34:56", t.toString());

        // 从 datetime 字符串里也能取 date / time
        assertEquals("2024-03-05", SqlValues.getDate(new JsonPrimitive("2024-03-05 12:34:56")).toString());
        assertEquals("12:34:56", SqlValues.getTime(new JsonPrimitive("2024-03-05 12:34:56")).toString());
    }

    @Test
    public void badTimestampThrows() {
        assertThrows(SQLException.class, () -> SqlValues.getTimestamp(new JsonPrimitive("not-a-date")));
    }

    @Test
    public void literalRendering() throws SQLException {
        assertEquals("NULL", SqlValues.renderLiteral(null));
        assertEquals("123", SqlValues.renderLiteral(123));
        assertEquals("1.5", SqlValues.renderLiteral(1.5d));
        assertEquals("1", SqlValues.renderLiteral(Boolean.TRUE));
        assertEquals("0", SqlValues.renderLiteral(Boolean.FALSE));
        assertEquals("'it''s'", SqlValues.renderLiteral("it's"));
        assertEquals("'a\\\\b'", SqlValues.renderLiteral("a\\b"));
        assertEquals("'2024-03-05'", SqlValues.renderLiteral(Date.valueOf("2024-03-05")));
        assertEquals("'12:34:56'", SqlValues.renderLiteral(Time.valueOf("12:34:56")));
        assertEquals("'2024-03-05 12:34:56'",
                SqlValues.renderLiteral(Timestamp.valueOf("2024-03-05 12:34:56")));
        assertEquals("'2024-03-05 12:34:56.5'",
                SqlValues.renderLiteral(Timestamp.valueOf("2024-03-05 12:34:56.5")));
        assertEquals(new BigDecimal("3.14").toPlainString(),
                SqlValues.renderLiteral(new BigDecimal("3.14")));
    }
}
