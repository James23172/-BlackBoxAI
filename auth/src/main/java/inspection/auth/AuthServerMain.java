package inspection.auth;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import inspection.auth.model.Role;
import inspection.auth.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.Executors;

/**
 * 认证服务 — 独立HTTP端口 8890
 *
 * POST /api/auth/register  { username, password, role }
 *   Header: Authorization: Bearer <admin_token>   — 仅管理员可注册
 * POST /api/auth/login     { username, password }
 *   → 返回 { success, token, username, role, exp }
 * GET  /api/auth/verify    ?token=xxx
 *   → 返回 { success, username, role } 或 401
 */
public class AuthServerMain {
    private static final Logger log = LoggerFactory.getLogger(AuthServerMain.class);
    private static final String SECRET = generateSecret();
    private static final long TOKEN_TTL_MS = 24 * 60 * 60 * 1000; // 24h
    private static JedisPool pool;
    private static UserManager users;
    private static PermissionManager perms;

    public static void main(String[] args) throws Exception {
        pool = new JedisPool("localhost", 6379);
        users = new UserManager(pool);
        perms = new PermissionManager(pool);

        // 清除旧格式哈希（salt+SHA-256(明文) → 改为 salt+SHA-256(SHA-256(明文))）
        try (Jedis jedis = pool.getResource()) {
            jedis.del("auth:user:admin");
        } catch (Exception ignored) {}

        // 确保默认管理员存在（带重试，防止AuthServer先于Redis启动）
        for (int i = 0; i < 5; i++) {
            try {
                if (users.getUser("admin") == null) {
                    users.register("admin", sha256Hex("admin123"), Role.CONFIGURATOR);
                    log.info("已创建默认管理员: admin/admin123");
                }
                break;
            } catch (Exception e) {
                log.warn("初始化 admin 失败 (第{}次): {}", i + 1, e.getMessage());
                if (i < 4) Thread.sleep(1000);
            }
        }
        perms.savePermissions(PermissionManager.DEFAULT_PERMISSIONS);

        HttpServer server = HttpServer.create(new InetSocketAddress(8890), 0);
        server.createContext("/api/auth/register", new RegisterHandler());
        server.createContext("/api/auth/login", new LoginHandler());
        server.createContext("/api/auth/verify", new VerifyHandler());
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        log.info("AuthServer 已启动，端口: 8890");
    }

    // ==================== REGISTER ====================
    static class RegisterHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            // CORS preflight
            if ("OPTIONS".equals(ex.getRequestMethod())) { sendCors(ex); return; }
            if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, error("仅支持POST")); return; }

            // ── 权限检查：只有管理员可注册 ──
            String authHeader = ex.getRequestHeaders().getFirst("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                send(ex, 403, error("权限不足，请先以管理员身份登录")); return;
            }
            String token = authHeader.substring(7);
            String[] tokenInfo = parseToken(token);
            if (tokenInfo == null) {
                send(ex, 401, error("会话已过期，请重新登录")); return;
            }
            if (!"configurator".equals(tokenInfo[1])) {
                send(ex, 403, error("权限不足，仅管理员可创建用户")); return;
            }

            try {
                JSONObject body = JSON.parseObject(
                    new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String username = body.getString("username");
                String password = body.getString("transHash");
                String roleStr = body.getString("role");
                Role role = Role.fromString(roleStr);
                if (username == null || password == null || role == null) {
                    send(ex, 400, error("参数不完整（需要 username, transHash, role）")); return;
                }
                if (username.length() < 2 || password.length() < 3) {
                    send(ex, 400, error("用户名至少2位，密码至少3位")); return;
                }
                if (users.register(username, password, role)) {
                    log.info("注册成功: {} ({}) by {}", username, role.getRoleName(), tokenInfo[0]);
                    send(ex, 200, ok("注册成功"));
                } else {
                    send(ex, 409, error("用户名已存在"));
                }
            } catch (Exception e) {
                log.error("注册异常", e);
                send(ex, 500, error("服务器内部错误，请稍后重试"));
            }
        }
    }

    // ==================== LOGIN ====================
    static class LoginHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equals(ex.getRequestMethod())) { sendCors(ex); return; }
            if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, error("仅支持POST")); return; }

            try {
                JSONObject body = JSON.parseObject(
                    new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String username = body.getString("username");
                String password = body.getString("transHash");
                if (username == null || password == null) {
                    send(ex, 400, error("请输入用户名和 transHash")); return;
                }

                // ── 兜底自愈：admin 不存在时现场创建 ──
                User user = users.getUser(username);
                if (user == null && "admin".equals(username)) {
                    users.register("admin", sha256Hex("admin123"), Role.CONFIGURATOR);
                    log.info("admin 账户已自愈重建");
                }

                // ── 验证密码 ──
                user = users.login(username, password);
                if (user == null) {
                    send(ex, 401, error("用户名或密码错误")); return;
                }

                // 配置员仅限本机登录
                if (user.getRole() == Role.CONFIGURATOR) {
                    String ip = ex.getRemoteAddress().getAddress().getHostAddress();
                    boolean isLocal = "127.0.0.1".equals(ip)
                                   || "0:0:0:0:0:0:0:1".equals(ip)
                                   || "::1".equals(ip);
                    if (!isLocal) {
                        send(ex, 403, error("配置员仅限主公机本机登录"));
                        return;
                    }
                }

                String token = createToken(user.getUsername(), user.getRole().getRoleName());
                log.info("登录成功: {} ({})", user.getUsername(), user.getRole().getRoleName());

                JSONObject r = new JSONObject();
                r.put("success", true);
                r.put("token", token);
                r.put("username", user.getUsername());
                r.put("role", user.getRole().getRoleName());
                r.put("exp", System.currentTimeMillis() + TOKEN_TTL_MS);
                send(ex, 200, r.toJSONString());
            } catch (Exception e) {
                log.error("登录异常", e);
                send(ex, 500, error("服务器内部错误，请稍后重试"));
            }
        }
    }

    // ==================== VERIFY ====================
    static class VerifyHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equals(ex.getRequestMethod())) { sendCors(ex); return; }
            String query = ex.getRequestURI().getQuery();
            String token = null;
            if (query != null) {
                for (String p : query.split("&")) {
                    if (p.startsWith("token=")) { token = p.substring(6); break; }
                }
            }
            if (token == null || token.isEmpty()) {
                send(ex, 401, error("缺少认证令牌")); return;
            }
            String[] parts = parseToken(token);
            if (parts == null) {
                send(ex, 401, error("会话已过期，请重新登录")); return;
            }
            JSONObject r = new JSONObject();
            r.put("success", true);
            r.put("username", parts[0]);
            r.put("role", parts[1]);
            send(ex, 200, r.toJSONString());
        }
    }

    // ==================== 工具方法 ====================

    private static String ok(String msg) {
        JSONObject r = new JSONObject(); r.put("success", true); r.put("message", msg); return r.toJSONString();
    }
    private static String error(String msg) {
        JSONObject r = new JSONObject(); r.put("success", false); r.put("error", msg); return r.toJSONString();
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static void sendCors(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        ex.sendResponseHeaders(204, -1);
    }

    // ==================== Token (HMAC-SHA256, 含到期时间) ====================

    static String createToken(String username, String role) {
        long exp = System.currentTimeMillis() + TOKEN_TTL_MS;
        String payload = username + ":" + role + ":" + exp;
        String sig = hmacSha256(payload, SECRET);
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString((payload + ":" + sig).getBytes(StandardCharsets.UTF_8));
    }

    /** @return [username, role] 或 null（无效/过期） */
    static String[] parseToken(String token) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int lastColon = decoded.lastIndexOf(':');
            if (lastColon < 0) return null;
            String payload = decoded.substring(0, lastColon);
            String sig = decoded.substring(lastColon + 1);
            if (!hmacSha256(payload, SECRET).equals(sig)) return null;
            String[] parts = payload.split(":");
            if (parts.length < 3) return null;
            long exp = Long.parseLong(parts[2]);
            if (System.currentTimeMillis() > exp) return null; // 已过期
            return new String[]{parts[0], parts[1]};
        } catch (Exception e) { return null; }
    }

    private static String hmacSha256(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static String generateSecret() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    private static String sha256Hex(String input) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
