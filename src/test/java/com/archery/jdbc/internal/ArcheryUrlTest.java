package com.archery.jdbc.internal;

import com.archery.jdbc.driver.ArcheryDriver;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ArcheryUrlTest {

    @Test
    public void parseFullUrl() throws SQLException {
        ArcheryUrl u = ArcheryUrl.parse(
                "jdbc:archery://archery.local:8000/mysql_prod/mydb?user=admin&password=secret",
                null);
        assertEquals("archery.local", u.host);
        assertEquals(8000, u.port);
        assertFalse(u.ssl);
        assertEquals("mysql_prod", u.instanceName);
        assertEquals("mydb", u.dbName);
        assertEquals("admin", u.user);
        assertEquals("secret", u.password);
        assertEquals(ArcheryUrl.DEFAULT_LIMIT, u.defaultLimit);
        assertEquals("http://archery.local:8000", u.baseUrl());
    }

    @Test
    public void parseFullUrl1() throws SQLException {
        String URL = "jdbc:archery:https://q.archery.local/abc_从/abc";
        String USER = "abc";
        String PASSWORD = "123";

        Properties props = new Properties();
        props.setProperty("user", USER);
        props.setProperty("password", PASSWORD);

        ArcheryUrl u = ArcheryUrl.parse(
            URL,
            props);
        // 真实环境：三级域名 host 原样保留，https scheme → ssl + 443
        assertEquals("q.archery.local", u.host);
        assertEquals(443, u.port);
        assertTrue(u.ssl);
        assertEquals("abc_从", u.instanceName);
        assertEquals("abc", u.dbName);
        assertEquals(USER, u.user);
        assertEquals(PASSWORD, u.password);
        assertEquals(ArcheryUrl.DEFAULT_LIMIT, u.defaultLimit);
        assertEquals("https://q.archery.local:443", u.baseUrl());
    }

    @Test
    public void parseFullUrl2() throws SQLException {
        ArcheryUrl u = ArcheryUrl.parse(
            "jdbc:archery://a.archery.local:8000/mysql_prod/mydb?user=admin&password=secret",
            null);
        // 三级域名完整保留为 host（连接目标与 URL 一致，不做子域剥离）
        assertEquals("a.archery.local", u.host);
        assertEquals(8000, u.port);
        assertFalse(u.ssl);
        assertEquals("mysql_prod", u.instanceName);
        assertEquals("mydb", u.dbName);
        assertEquals("admin", u.user);
        assertEquals("secret", u.password);
        assertEquals(ArcheryUrl.DEFAULT_LIMIT, u.defaultLimit);
        assertEquals("http://a.archery.local:8000", u.baseUrl());
    }

    @Test
    public void multiLevelDomainHosts() throws SQLException {
        // 任意层级域名 + http/https/端口组合都应正确解析，host 原样保留
        String[][] cases = {
                // {URL, 期望 host, 期望 port}
                {"jdbc:archery://archery.local/i/db?user=u", "archery.local", "80"},
                {"jdbc:archery://a.archery.local/i/db?user=u", "a.archery.local", "80"},
                {"jdbc:archery://a.b.archery.local/i/db?user=u", "a.b.archery.local", "80"},
                {"jdbc:archery://a.archery.local:8000/i/db?user=u", "a.archery.local", "8000"},
                {"jdbc:archery:https://archery.local/i/db?user=u", "archery.local", "443"},
                {"jdbc:archery:https://a.archery.local/i/db?user=u", "a.archery.local", "443"},
                {"jdbc:archery:https://a.b.c.archery.local/i/db?user=u", "a.b.c.archery.local", "443"},
        };
        for (String[] c : cases) {
            ArcheryUrl u = ArcheryUrl.parse(c[0], null);
            assertEquals(c[1], u.host, "host of " + c[0]);
            assertEquals(Integer.parseInt(c[2]), u.port, "port of " + c[0]);
            assertEquals("i", u.instanceName, "instance of " + c[0]);
            assertEquals("db", u.dbName, "db of " + c[0]);
        }
    }

    @Test
    public void parseHttpsScheme() throws SQLException {
        ArcheryUrl u = ArcheryUrl.parse(
                "jdbc:archery:https://a.archery.local/mysql_prod/mydb?user=u", null);
        assertTrue(u.ssl);
        assertEquals("a.archery.local", u.host);
        assertEquals(443, u.port);
        assertEquals("https://a.archery.local:443", u.baseUrl());
    }



    @Test
    public void defaultsAndParams() throws SQLException {
        ArcheryUrl u = ArcheryUrl.parse(
                "jdbc:archery://h/mysql_prod/mydb?user=u&password=p&limit=100&connectTimeout=5000"
                        + "&readTimeout=60000&basePath=/archery&trustAll=true&ssl=true",
                null);
        assertEquals(100, u.defaultLimit);
        assertEquals(5000, u.connectTimeoutMs);
        assertEquals(60000, u.readTimeoutMs);
        assertTrue(u.ssl);
        assertTrue(u.trustAll);
        assertEquals("/archery", u.basePath);
        assertEquals("https://h:443/archery", u.baseUrl());
    }

    @Test
    public void propertiesOverrideUrlParams() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", "prop_user");
        props.setProperty("password", "prop_pass");
        ArcheryUrl u = ArcheryUrl.parse(
                "jdbc:archery://h/i/db?user=url_user&password=url_pass", props);
        assertEquals("prop_user", u.user);
        assertEquals("prop_pass", u.password);
    }

    @Test
    public void lazyLoginParam() throws SQLException {
        // 默认立即登录
        assertFalse(ArcheryUrl.parse("jdbc:archery://h/i/db?user=u", null).lazyLogin);
        // lazyLogin=true 推迟到首次查询
        assertTrue(ArcheryUrl.parse("jdbc:archery://h/i/db?user=u&lazyLogin=true", null).lazyLogin);
    }

    @Test
    public void urlEncodedInstanceAndDb() throws SQLException {
        ArcheryUrl u = ArcheryUrl.parse(
                "jdbc:archery://h/my%20instance/my%2Fdb?user=u", null);
        assertEquals("my instance", u.instanceName);
        assertEquals("my/db", u.dbName);
    }

    @Test
    public void missingDbAllowed() throws SQLException {
        ArcheryUrl u = ArcheryUrl.parse("jdbc:archery://h/instance?user=u", null);
        assertNull(u.dbName);
    }

    @Test
    public void missingUserThrows() {
        assertThrows(SQLException.class, () -> ArcheryUrl.parse("jdbc:archery://h/i/db", null));
    }

    @Test
    public void invalidUrlThrows() {
        assertThrows(SQLException.class, () -> ArcheryUrl.parse("jdbc:mysql://h/db", null));
    }

    @Test
    public void acceptsUrlPrefix() throws SQLException {
        assertTrue(new ArcheryDriver().acceptsURL("jdbc:archery://h/i/db"));
        assertFalse(new ArcheryDriver().acceptsURL("jdbc:mysql://h/db"));
    }
}
