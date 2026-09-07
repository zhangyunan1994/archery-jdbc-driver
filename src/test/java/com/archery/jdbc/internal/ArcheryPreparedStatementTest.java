package com.archery.jdbc.internal;

import org.junit.jupiter.api.Test;

import java.sql.Date;
import java.sql.SQLException;
import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class ArcheryPreparedStatementTest {

    private static Object[] params(Object... values) {
        return values;
    }

    @Test
    public void simpleSubstitution() throws SQLException {
        String out = ArcheryPreparedStatement.substitute(
                "SELECT * FROM t WHERE id = ? AND name = ?",
                params(1, "alice"));
        assertEquals("SELECT * FROM t WHERE id = 1 AND name = 'alice'", out);
    }

    @Test
    public void placeholderInStringLiteralIgnored() throws SQLException {
        String out = ArcheryPreparedStatement.substitute(
                "SELECT 'a?b' AS q, \"x?y\" AS d, `col?` AS b, id FROM t WHERE id = ?",
                params(7));
        assertEquals("SELECT 'a?b' AS q, \"x?y\" AS d, `col?` AS b, id FROM t WHERE id = 7", out);
    }

    @Test
    public void escapedQuoteInsideLiteral() throws SQLException {
        // '' 转义内的 ? 不替换
        String out = ArcheryPreparedStatement.substitute(
                "SELECT 'it''s a ? test' , id FROM t WHERE id = ?",
                params(3));
        assertEquals("SELECT 'it''s a ? test' , id FROM t WHERE id = 3", out);
    }

    @Test
    public void backslashEscapedQuoteInsideLiteral() throws SQLException {
        // \' 转义内的 ? 不替换（反斜杠转义，来自 oc 的词法增强）
        String out = ArcheryPreparedStatement.substitute(
                "SELECT 'a\\'b?c' AS q, id FROM t WHERE id = ?",
                params(9));
        assertEquals("SELECT 'a\\'b?c' AS q, id FROM t WHERE id = 9", out);
    }

    @Test
    public void placeholdersInCommentsIgnored() throws SQLException {
        // -- / # / /* */ 注释内的 ? 不替换（来自 oc 的词法增强）
        String out = ArcheryPreparedStatement.substitute(
                "SELECT id -- comment ? here\n"
                        + "# another ? comment\n"
                        + "/* block ? comment */\n"
                        + "FROM t WHERE id = ?",
                params(11));
        assertEquals("SELECT id -- comment ? here\n"
                + "# another ? comment\n"
                + "/* block ? comment */\n"
                + "FROM t WHERE id = 11", out);
    }

    @Test
    public void backslashBeforeQuoteStillClosesLiteral() throws SQLException {
        // 'a\\' 中反斜杠转义反斜杠，随后引号结束字面量，其后的 ? 正常替换
        String out = ArcheryPreparedStatement.substitute(
                "SELECT 'a\\\\' , ? ",
                params(5));
        assertEquals("SELECT 'a\\\\' , 5 ", out);
    }

    @Test
    public void nullParamRendersNull() throws SQLException {
        String out = ArcheryPreparedStatement.substitute(
                "SELECT * FROM t WHERE a = ? AND b = ?",
                new Object[]{null, 2});
        // 参数数组直接构造时无 UNSET 哨兵，null → NULL
        assertEquals("SELECT * FROM t WHERE a = NULL AND b = 2", out);
    }

    @Test
    public void unsetParamThrows() {
        try {
            Object[] unset = {ArcheryPreparedStatement.UNSET};
            ArcheryPreparedStatement.substitute("SELECT ? ", unset);
            fail("未设置参数应抛 SQLException");
        } catch (SQLException e) {
            assertEquals("07000", e.getSQLState());
        }
    }

    @Test
    public void timestampLiteral() throws SQLException {
        String out = ArcheryPreparedStatement.substitute(
                "SELECT * FROM t WHERE ts > ?",
                params(Timestamp.valueOf("2024-03-05 12:34:56")));
        assertEquals("SELECT * FROM t WHERE ts > '2024-03-05 12:34:56'", out);
    }

    @Test
    public void placeholderCountMismatch() {
        try {
            ArcheryPreparedStatement.substitute(
                    "SELECT * FROM t WHERE a = ? AND b = ?",
                    params(1));
            fail("参数数量不足应抛异常");
        } catch (SQLException e) {
            assertEquals("07000", e.getSQLState());
        }
    }
}
