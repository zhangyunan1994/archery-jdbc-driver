package com.archery.jdbc.internal;

import org.junit.jupiter.api.Test;

import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MysqlTypesTest {

    @Test
    public void mysqlCodes() {
        assertEquals(Types.INTEGER, MysqlTypes.fromMysqlCode(3));
        assertEquals(Types.BIGINT, MysqlTypes.fromMysqlCode(8));
        assertEquals(Types.TINYINT, MysqlTypes.fromMysqlCode(1));
        assertEquals(Types.TIMESTAMP, MysqlTypes.fromMysqlCode(12));
        assertEquals(Types.DATE, MysqlTypes.fromMysqlCode(10));
        assertEquals(Types.DECIMAL, MysqlTypes.fromMysqlCode(246));
        assertEquals(Types.VARCHAR, MysqlTypes.fromMysqlCode(253));
        assertEquals(Types.CHAR, MysqlTypes.fromMysqlCode(254));
        assertEquals(Types.LONGVARBINARY, MysqlTypes.fromMysqlCode(252));
        assertEquals(Types.LONGVARCHAR, MysqlTypes.fromMysqlCode(245));
    }

    @Test
    public void unknownCodeFallsBackToVarchar() {
        assertEquals(Types.VARCHAR, MysqlTypes.fromMysqlCode(999));
    }

    @Test
    public void typeNames() {
        assertEquals(Types.INTEGER, MysqlTypes.fromTypeName("int"));
        assertEquals(Types.INTEGER, MysqlTypes.fromTypeName("INT"));
        assertEquals(Types.INTEGER, MysqlTypes.fromTypeName("mediumint"));
        assertEquals(Types.TIMESTAMP, MysqlTypes.fromTypeName("datetime"));
        assertEquals(Types.DECIMAL, MysqlTypes.fromTypeName("decimal(10,2)"));
        assertEquals(Types.BIGINT, MysqlTypes.fromTypeName("bigint unsigned"));
        assertEquals(Types.LONGVARCHAR, MysqlTypes.fromTypeName("LONGTEXT"));
        assertEquals(Types.VARCHAR, MysqlTypes.fromTypeName("unknown_type"));
        assertEquals(Types.VARCHAR, MysqlTypes.fromTypeName(null));
    }

    @Test
    public void mysqlNameOfCode() {
        assertEquals("int", MysqlTypes.mysqlNameOfCode(3));
        assertEquals("datetime", MysqlTypes.mysqlNameOfCode(12));
        assertEquals("varchar", MysqlTypes.mysqlNameOfCode(253));
        assertEquals("varchar", MysqlTypes.mysqlNameOfCode(-1));
    }

    @Test
    public void numericHelpers() {
        assertTrue(MysqlTypes.isNumeric(Types.INTEGER));
        assertTrue(MysqlTypes.isNumeric(Types.DECIMAL));
        assertFalse(MysqlTypes.isNumeric(Types.VARCHAR));
        assertTrue(MysqlTypes.isIntegral(Types.BIGINT));
        assertFalse(MysqlTypes.isIntegral(Types.DOUBLE));
        assertTrue(MysqlTypes.isTemporal(Types.TIMESTAMP));
        assertFalse(MysqlTypes.isTemporal(Types.VARCHAR));
    }
}
