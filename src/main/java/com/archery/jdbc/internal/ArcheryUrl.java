package com.archery.jdbc.internal;

import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 连接参数（JDBC URL + Properties 合并解析）。
 * <p>
 * URL 语法：
 * <pre>
 *   jdbc:archery://host:port/instance_name/db_name?user=u&amp;password=p
 *   jdbc:archery:https://host/instance_name/db_name
 *   jdbc:archery://host:port/instance_name/db_name?basePath=/archery&amp;limit=5000
 * </pre>
 * <p>
 * 参数（URL query 或 Properties，Properties 优先）：
 * <ul>
 *   <li>user / password —— Archery 平台账号（用于 POST /authenticate/ 登录）</li>
 *   <li>ssl —— true 时使用 https（默认 false）</li>
 *   <li>basePath —— 反向代理子路径，如 /archery</li>
 *   <li>limit —— 默认结果行数上限（即 /query/ 的 limit_num；0=不限）</li>
 *   <li>connectTimeout —— 连接超时毫秒，默认 10000</li>
 *   <li>readTimeout —— 读超时毫秒，默认 180000（服务端另有 max_execution_time 保护）</li>
 *   <li>trustAll —— true 时信任自签名证书（默认 false）</li>
 * </ul>
 */
public final class ArcheryUrl {

  private static final Pattern URL_PATTERN = Pattern.compile(
      "^jdbc:archery:(?:(https?):)?//([^/:?#]+)(?::(\\d+))?/([^/?#]+)(?:/([^?#]*))?(?:\\?(.*))?$");

  static final int DEFAULT_PORT = 80;
  static final int DEFAULT_LIMIT = 5000;
  static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
  static final int DEFAULT_READ_TIMEOUT_MS = 180_000;

  final String rawUrl;
  final String host;
  final int port;
  final boolean ssl;
  final String basePath;
  final String instanceName;
  final String dbName;
  final String user;
  final String password;
  final int defaultLimit;
  final int connectTimeoutMs;
  final int readTimeoutMs;
  final boolean trustAll;
  final boolean lazyLogin;

  private ArcheryUrl(String rawUrl, String host, int port, boolean ssl, String basePath,
      String instanceName, String dbName, String user, String password,
      int defaultLimit, int connectTimeoutMs, int readTimeoutMs, boolean trustAll,
      boolean lazyLogin) {
    this.rawUrl = rawUrl;
    this.host = host;
    this.port = port;
    this.ssl = ssl;
    this.basePath = basePath;
    this.instanceName = instanceName;
    this.dbName = dbName;
    this.user = user;
    this.password = password;
    this.defaultLimit = defaultLimit;
    this.connectTimeoutMs = connectTimeoutMs;
    this.readTimeoutMs = readTimeoutMs;
    this.trustAll = trustAll;
    this.lazyLogin = lazyLogin;
  }

  public static ArcheryUrl parse(String url, Properties info) throws SQLException {
    if (url == null) {
      throw new SQLException("URL 为空", "HY000");
    }
    Matcher m = URL_PATTERN.matcher(url);
    if (!m.matches()) {
      throw new SQLException(
          "无法解析 JDBC URL: " + url
              + "，期望格式 jdbc:archery://host:port/instance_name/db_name?user=..&password=..",
          "HY000");
    }
    Properties props = new Properties();
    if (info != null) {
      props.putAll(info);
    }
    // URL query 参数仅作为缺省补充（Properties 优先）
    if (m.group(6) != null) {
      for (String pair : m.group(6).split("&")) {
        if (pair.isEmpty()) {
          continue;
        }
        int eq = pair.indexOf('=');
        String key = eq < 0 ? pair : pair.substring(0, eq);
        String val = eq < 0 ? "" : pair.substring(eq + 1);
        String k = urlDecode(key);
        if (!props.containsKey(k)) {
          props.setProperty(k, urlDecode(val));
        }
      }
    }

    String scheme = m.group(1);
    boolean ssl = scheme != null ? "https".equalsIgnoreCase(scheme) : boolParam(props, "ssl", false);
    int defaultPort = ssl ? 443 : DEFAULT_PORT;
    int port = intParam(props, "port", m.group(3) != null ? Integer.parseInt(m.group(3)) : defaultPort);
    if (port <= 0 || port > 65535) {
      throw new SQLException("非法端口: " + port, "HY000");
    }

    String user = props.getProperty("user");
    String password = props.getProperty("password");
    if (user == null || user.isEmpty()) {
      throw new SQLException("缺少 user 参数（Archery 平台账号）", "28000");
    }

    int limit = intParam(props, "limit", DEFAULT_LIMIT);
    int connectTimeout = intParam(props, "connectTimeout", DEFAULT_CONNECT_TIMEOUT_MS);
    int readTimeout = intParam(props, "readTimeout", DEFAULT_READ_TIMEOUT_MS);
    String basePath = normalizeBasePath(props.getProperty("basePath", ""));
    boolean trustAll = boolParam(props, "trustAll", false);
    boolean lazyLogin = boolParam(props, "lazyLogin", false);

    return new ArcheryUrl(url,
        m.group(2),
        port,
        ssl,
        basePath,
        urlDecode(m.group(4)),
        m.group(5) == null || m.group(5).isEmpty() ? null : urlDecode(m.group(5)),
        user,
        password == null ? "" : password,
        limit,
        connectTimeout,
        readTimeout,
        trustAll,
        lazyLogin);
  }

  private static String normalizeBasePath(String basePath) {
    if (basePath == null) {
      return "";
    }
    basePath = basePath.trim();
    if (basePath.isEmpty() || "/".equals(basePath)) {
      return "";
    }
    if (!basePath.startsWith("/")) {
      basePath = "/" + basePath;
    }
    return basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;
  }

  private static String urlDecode(String s) {
    try {
      return java.net.URLDecoder.decode(s, "UTF-8");
    }
    catch (Exception e) {
      return s;
    }
  }

  private static boolean boolParam(Properties props, String key, boolean dft) {
    String v = props.getProperty(key);
    return v == null || v.isEmpty() ? dft : "true".equalsIgnoreCase(v) || "1".equals(v) || "yes".equalsIgnoreCase(v);
  }

  private static int intParam(Properties props, String key, int dft) {
    String v = props.getProperty(key);
    if (v == null || v.isEmpty()) {
      return dft;
    }
    try {
      return Integer.parseInt(v.trim());
    }
    catch (NumberFormatException e) {
      return dft;
    }
  }

  String baseUrl() {
    return (ssl ? "https" : "http") + "://" + host + ":" + port + basePath;
  }

  public DriverPropertyInfo[] getPropertyInfo(Properties info) {
    Properties props = info == null ? new Properties() : info;
    DriverPropertyInfo user = new DriverPropertyInfo("user", props.getProperty("user", this.user));
    user.required = true;
    user.description = "Archery 平台账号";
    DriverPropertyInfo password = new DriverPropertyInfo("password", props.getProperty("password", this.password));
    password.required = true;
    password.description = "Archery 平台密码";
    DriverPropertyInfo ssl = new DriverPropertyInfo("ssl", props.getProperty("ssl", String.valueOf(this.ssl)));
    ssl.description = "使用 https";
    DriverPropertyInfo limit = new DriverPropertyInfo("limit",
        props.getProperty("limit", String.valueOf(this.defaultLimit)));
    limit.description = "默认结果行数上限，0=不限";
    DriverPropertyInfo basePath = new DriverPropertyInfo("basePath", props.getProperty("basePath", this.basePath));
    basePath.description = "反向代理子路径，如 /archery";
    DriverPropertyInfo trustAll = new DriverPropertyInfo("trustAll",
        props.getProperty("trustAll", String.valueOf(this.trustAll)));
    trustAll.description = "信任自签名证书";
    DriverPropertyInfo lazyLogin = new DriverPropertyInfo("lazyLogin",
        props.getProperty("lazyLogin", String.valueOf(this.lazyLogin)));
    lazyLogin.description = "懒登录：连接时不认证，首次查询时自动登录（连接池友好）";
    return new DriverPropertyInfo[]{user, password, ssl, limit, basePath, trustAll, lazyLogin};
  }

  /**
   * 供测试/诊断展示，隐去密码
   */
  @Override
  public String toString() {
    return "ArcheryUrl{base=" + baseUrl() + ", instance=" + instanceName + ", db=" + dbName + ", user=" + user + "}";
  }

  /**
   * DriverManager.getConnection 入口的代理方法（避免测试直接依赖 DriverManager）
   */
  static java.sql.Connection connectViaDriverManager(String url, Properties props) throws SQLException {
    return DriverManager.getConnection(url, props);
  }
}
