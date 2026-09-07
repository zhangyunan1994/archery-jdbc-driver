package com.archery.jdbc.internal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Archery HTTP 会话客户端。
 * <p>
 * 认证模型（对应当前 Archery 代码，无 token 机制，Session + CSRF）：
 * <ol>
 *   <li>GET /login/ —— 预取 csrftoken cookie</li>
 *   <li>POST /authenticate/ —— 表单 username/password，携带 X-CSRFToken 头；
 *       成功后服务端 set-cookie 下发 sessionid 并轮换 csrftoken
 *       （Django login() 会 rotate_token）</li>
 *   <li>POST /query/ —— 携带 sessionid + csrftoken cookie 与 X-CSRFToken 头</li>
 * </ol>
 * 会话过期时 Archery 的 CheckLoginMiddleware 会 302 到 /login/，这里捕获后自动重登录一次；
 * CSRF 校验失败返回 403，这里刷新 token 后重试一次。
 */
final class ArcheryHttpClient {

  private final ArcheryUrl cfg;
  private final Map<String, String> cookies = new LinkedHashMap<>();
  private volatile int readTimeoutMs;
  private volatile boolean loggedIn;

  ArcheryHttpClient(ArcheryUrl cfg) {
    this.cfg = cfg;
    this.readTimeoutMs = cfg.readTimeoutMs;
  }

  /**
   * 是否已持有有效登录会话（lazyLogin 模式下连接建立后为 false）
   */
  synchronized boolean isLoggedIn() {
    return loggedIn;
  }

  // ------------------------------------------------------------------ 登录

  void login(String user, String password) throws SQLException {
    try {
      // 1. 预取 csrf cookie（登录页渲染 {% csrf_token %} 时下发）
      get(cfg.baseUrl() + "/login/");
      String csrf = cookies.get("csrftoken");
      if (csrf == null || csrf.isEmpty()) {
        // 部分部署登录页不渲染表单时可能不下发，尝试首页
        get(cfg.baseUrl() + "/");
        csrf = cookies.get("csrftoken");
      }
      if (csrf == null || csrf.isEmpty()) {
        throw new SQLException("未获取到 csrftoken，无法登录 Archery", "28000");
      }

      // 2. 提交认证
      StringBuilder form = new StringBuilder();
      appendForm(form, "username", user);
      appendForm(form, "password", password);
      Response resp = postForm(cfg.baseUrl() + "/authenticate/", form.toString());

      if (resp.redirected) {
        throw new SQLException("登录请求被重定向（可能 Archery 未部署在预期路径，尝试 basePath 参数）", "28000");
      }
      String body = resp.bodyOrEmpty();
      JsonObject json = parseJson(body, "/authenticate/");
      int status = json.has("status") ? json.get("status").getAsInt() : 1;
      String msg = json.has("msg") && !json.get("msg").isJsonNull() ? json.get("msg").getAsString() : "";
      if (status != 0) {
        throw new SQLException("Archery 登录失败: " + msg, "28000");
      }
      if (!cookies.containsKey("sessionid")) {
        throw new SQLException("Archery 登录后未获得 sessionid", "28000");
      }
      loggedIn = true;
    }
    catch (IOException e) {
      throw new SQLException("登录 Archery 失败: " + e.getMessage(), "08001", e);
    }
  }

  /**
   * 退出登录（close 时 best-effort 调用；失败静默忽略，服务端会话最终会过期）
   */
  synchronized void logout() {
    if (!loggedIn) {
      return;
    }
    loggedIn = false;
    try {
      get(cfg.baseUrl() + "/logout/");
    }
    catch (Exception ignore) {
      // 会话由服务端过期机制兜底
    }
  }

  // ------------------------------------------------------------------ 查询

  /**
   * 执行 POST /query/ 并解析 ResultSet JSON。 lazyLogin 模式下首次调用会先触发登录。
   *
   * @param limitNum 行数上限（0 = 不限）
   */
  QueryResult query(String instanceName, String dbName, String sql, int limitNum) throws SQLException {
    if (!loggedIn) {
      synchronized (this) {
        if (!loggedIn) {
          login(cfg.user, cfg.password);
        }
      }
    }
    StringBuilder form = new StringBuilder();
    appendForm(form, "instance_name", instanceName);
    appendForm(form, "db_name", dbName == null ? "" : dbName);
    appendForm(form, "sql_content", sql);
    appendForm(form, "limit_num", String.valueOf(limitNum));

    SQLException lastError = null;
    for (int attempt = 1; attempt <= 3; attempt++) {
      try {
        Response resp = postForm(cfg.baseUrl() + "/query/", form.toString());

        // 会话过期：CheckLoginMiddleware 302 → /login/
        if (resp.redirected && attempt == 1) {
          login(cfg.user, cfg.password);
          continue;
        }
        // CSRF 校验失败：403，刷新 token 重试一次
        if (resp.code == 403 && attempt <= 2) {
          get(cfg.baseUrl() + "/login/");
          continue;
        }
        if (resp.redirected) {
          throw new SQLException("Archery 会话已过期且重登录失败", "28000");
        }

        return parseQueryResponse(resp.bodyOrEmpty());
      }
      catch (IOException e) {
        lastError = new SQLException("查询请求失败: " + e.getMessage(), "08000", e);
      }
    }
    throw lastError != null ? lastError : new SQLException("查询请求失败", "08000");
  }

  private QueryResult parseQueryResponse(String body) throws SQLException {
    JsonObject json = parseJson(body, "/query/");
    int status = json.has("status") ? json.get("status").getAsInt() : 1;
    String msg = json.has("msg") && !json.get("msg").isJsonNull() ? json.get("msg").getAsString() : "";
    if (status != 0) {
      throw new SQLException("查询失败: " + msg, sqlStateFor(msg));
    }
    if (!json.has("data") || json.get("data").isJsonNull()) {
      throw new SQLException("查询失败: 响应缺少 data 字段", "HY000");
    }
    return QueryResult.fromJson(json.getAsJsonObject("data"));
  }

  /**
   * 按服务端错误消息关键词映射 SQLState（对齐 pi 实现的优点）： 28000=授权失败、42000=语法/访问违规、08000=连接异常，其余 HY000。
   */
  static String sqlStateFor(String msg) {
    String m = msg == null ? "" : msg.toLowerCase();
    if (m.contains("权限") || m.contains("无权") || m.contains("未关联") || m.contains("锁定")
        || m.contains("permission") || m.contains("denied")) {
      return "28000";
    }
    if (m.contains("语法") || m.contains("不支持") || m.contains("只支持") || m.contains("bad_query")
        || m.contains("disable_star") || m.contains("不存在") || m.contains("doesn't exist")
        || m.contains("syntax") || m.contains("脱敏")) {
      return "42000";
    }
    if (m.contains("连接") || m.contains("超时") || m.contains("connect") || m.contains("timeout")) {
      return "08000";
    }
    return "HY000";
  }

  /**
   * GET /api/info（免登录白名单接口），取 archery 版本号用于 DatabaseMetaData
   */
  String fetchServerVersion() {
    try {
      Response resp = get(cfg.baseUrl() + "/api/info");
      if (resp.code != 200) {
        return null;
      }
      JsonObject json = parseJson(resp.bodyOrEmpty(), "/api/info");
      if (json.has("archery") && json.getAsJsonObject("archery").has("version")) {
        return json.getAsJsonObject("archery").get("version").getAsString();
      }
    }
    catch (Exception ignore) {
      // 仅用于元数据展示，失败不影响主流程
    }
    return null;
  }

  // ------------------------------------------------------------------ HTTP 基础

  private Response get(String urlStr) throws IOException {
    return send("GET", urlStr, null);
  }

  private Response postForm(String urlStr, String formBody) throws IOException {
    return send("POST", urlStr, formBody);
  }

  private Response send(String method, String urlStr, String formBody) throws IOException {
    HttpURLConnection conn = open(new URL(urlStr));
    conn.setRequestMethod(method);
    conn.setConnectTimeout(cfg.connectTimeoutMs);
    conn.setReadTimeout(readTimeoutMs);
    conn.setInstanceFollowRedirects(false);
    conn.setRequestProperty("Accept", "application/json");
    conn.setRequestProperty("Accept-Encoding", "gzip");
    conn.setRequestProperty("Referer", cfg.baseUrl() + "/");
    if ("POST".equals(method)) {
      conn.setDoOutput(true);
      conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
      String csrf = cookies.get("csrftoken");
      if (csrf != null) {
        conn.setRequestProperty("X-CSRFToken", csrf);
      }
    }
    appendCookieHeader(conn);

    if (formBody != null) {
      byte[] payload = formBody.getBytes(StandardCharsets.UTF_8);
      try (OutputStream os = conn.getOutputStream()) {
        os.write(payload);
        os.flush();
      }
    }

    int code = conn.getResponseCode();
    storeCookies(conn);
    boolean redirected = code == 301 || code == 302 || code == 303 || code == 307;
    String location = conn.getHeaderField("Location");
    boolean loginRedirect = redirected && location != null && location.contains("/login");
    String body = readBody(conn);
    conn.disconnect();
    return new Response(code, body, redirected, loginRedirect);
  }

  private HttpURLConnection open(URL url) throws IOException {
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    if (conn instanceof HttpsURLConnection) {
      HttpsURLConnection https = (HttpsURLConnection) conn;
      if (cfg.trustAll) {
        https.setSSLSocketFactory(trustAllFactory());
        https.setHostnameVerifier(ALL_HOSTS);
      }
    }
    return conn;
  }

  private void appendCookieHeader(HttpURLConnection conn) {
    if (cookies.isEmpty()) {
      return;
    }
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> e : cookies.entrySet()) {
      if (sb.length() > 0) {
        sb.append("; ");
      }
      sb.append(e.getKey()).append('=').append(e.getValue());
    }
    conn.setRequestProperty("Cookie", sb.toString());
  }

  /**
   * 从 Set-Cookie 响应头更新 cookie 存储（登录后 sessionid 与轮换后的 csrftoken）
   */
  private void storeCookies(HttpURLConnection conn) {
    Map<String, List<String>> headers = conn.getHeaderFields();
    if (headers == null) {
      return;
    }
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      String name = entry.getKey();
      if (name == null || !"set-cookie".equalsIgnoreCase(name)) {
        continue;
      }
      for (String raw : entry.getValue()) {
        String first = raw.split(";", 2)[0].trim();
        int eq = first.indexOf('=');
        if (eq <= 0) {
          continue;
        }
        String cName = first.substring(0, eq).trim();
        String cValue = first.substring(eq + 1).trim();
        if ("EXPIRED".equalsIgnoreCase(cValue) || cValue.isEmpty()) {
          cookies.remove(cName); // Django 2.1+ 删除 cookie 的写法
        }
        else {
          cookies.put(cName, cValue);
        }
      }
    }
  }

  private static String readBody(HttpURLConnection conn) throws IOException {
    InputStream in;
    try {
      in = conn.getInputStream();
    }
    catch (IOException e) {
      in = conn.getErrorStream();
    }
    if (in == null) {
      return "";
    }
    if ("gzip".equalsIgnoreCase(conn.getHeaderField("Content-Encoding"))) {
      in = new GZIPInputStream(in);
    }
    try (InputStream is = in) {
      ByteArrayOutputStream buf = new ByteArrayOutputStream();
      byte[] chunk = new byte[8192];
      int n;
      while ((n = is.read(chunk)) > 0) {
        buf.write(chunk, 0, n);
      }
      return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  private static JsonObject parseJson(String body, String where) throws SQLException {
    try {
      return JsonParser.parseString(body).getAsJsonObject();
    }
    catch (Exception e) {
      throw new SQLException("Archery 返回非 JSON 响应(" + where + "): "
          + abbreviate(body), "HY000", e);
    }
  }

  private static String abbreviate(String s) {
    if (s == null) {
      return "";
    }
    s = s.replaceAll("\\s+", " ").trim();
    return s.length() > 200 ? s.substring(0, 200) + "..." : s;
  }

  private static void appendForm(StringBuilder sb, String key, String value) {
    if (sb.length() > 0) {
      sb.append('&');
    }
    try {
      sb.append(java.net.URLEncoder.encode(key, "UTF-8"))
          .append('=')
          .append(java.net.URLEncoder.encode(value == null ? "" : value, "UTF-8"));
    }
    catch (IOException e) {
      throw new IllegalStateException(e); // UTF-8 必然可用
    }
  }

  void setReadTimeoutMs(int ms) {
    this.readTimeoutMs = ms;
  }

  int getReadTimeoutMs() {
    return readTimeoutMs;
  }

  // ------------------------------------------------------------------ SSL 信任

  private static final HostnameVerifier ALL_HOSTS = new HostnameVerifier() {
    @Override
    public boolean verify(String hostname, SSLSession session) {
      return true;
    }
  };

  private static javax.net.ssl.SSLSocketFactory trustAllFactory() {
    TrustManager[] tms = new TrustManager[]{new X509TrustManager() {
      @Override
      public void checkClientTrusted(X509Certificate[] chain, String authType) {
      }

      @Override
      public void checkServerTrusted(X509Certificate[] chain, String authType) {
      }

      @Override
      public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
      }
    }};
    try {
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(null, tms, new SecureRandom());
      return ctx.getSocketFactory();
    }
    catch (Exception e) {
      throw new IllegalStateException("初始化 trustAll SSLContext 失败", e);
    }
  }

  // ------------------------------------------------------------------ 内部结构

  private static final class Response {

    final int code;
    final String body;
    final boolean redirected;
    final boolean redirectedToLogin;

    Response(int code, String body, boolean redirected, boolean redirectedToLogin) {
      this.code = code;
      this.body = body;
      this.redirected = redirected;
      this.redirectedToLogin = redirectedToLogin;
    }

    String bodyOrEmpty() {
      return body == null ? "" : body;
    }
  }
}
