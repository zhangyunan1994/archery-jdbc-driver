package com.archery.jdbc.driver;

import com.archery.jdbc.internal.ArcheryConnection;
import com.archery.jdbc.internal.ArcheryUrl;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Archery JDBC 驱动入口（HTTP 传输，只读 SELECT）。
 *
 * <pre>
 * Class.forName("com.archery.jdbc.driver.ArcheryDriver"); // 可省略（SPI 自动注册）
 * Connection c = DriverManager.getConnection(
 *     "jdbc:archery://archery.local:8000/mysql_instance/db1?user=admin&amp;password=***");
 * </pre>
 * <p>
 * URL 语法见 {@link ArcheryUrl}。
 */
public class ArcheryDriver implements Driver {

  public static final String URL_PREFIX = "jdbc:archery:";

  public static final int MAJOR_VERSION = 1;
  public static final int MINOR_VERSION = 0;

  static {
    try {
      DriverManagerHolder.register(new ArcheryDriver());
    }
    catch (SQLException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @Override
  public boolean acceptsURL(String url) throws SQLException {
    return url != null && url.startsWith(URL_PREFIX);
  }

  @Override
  public Connection connect(String url, Properties info) throws SQLException {
    if (!acceptsURL(url)) {
      return null;
    }
    return new ArcheryConnection(ArcheryUrl.parse(url, info));
  }

  @Override
  public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
    if (!acceptsURL(url)) {
      return new DriverPropertyInfo[0];
    }
    return ArcheryUrl.parse(url, info).getPropertyInfo(info);
  }

  @Override
  public int getMajorVersion() {
    return MAJOR_VERSION;
  }

  @Override
  public int getMinorVersion() {
    return MINOR_VERSION;
  }

  /**
   * 支持 JDBC 4.1 以下核心接口（SELECT 范围内）；事务等特性不支持，严格意义非全兼容
   */
  @Override
  public boolean jdbcCompliant() {
    return false;
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    return Logger.getLogger(ArcheryDriver.class.getName());
  }

  /**
   * 分离静态块对 DriverManager 的直接调用，便于类加载测试
   */
  private static final class DriverManagerHolder {

    static void register(Driver driver) throws SQLException {
      java.sql.DriverManager.registerDriver(driver);
    }
  }
}
