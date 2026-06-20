package inspection.replay;

import com.alibaba.fastjson2.JSON;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import inspection.common.client.BlackboardClient;
import inspection.common.config.ArgsParser;
import inspection.common.config.ConfigConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 回放服务 — 独立HTTP端口 8891
 * GET /api/replay/list               → 返回快照数量
 * GET /api/replay/snapshot/{index}   → 返回第N帧完整状态
 * GET /api/replay/metrics            → 返回性能数据
 * GET /api/replay/snapshots?from=N&to=M → 返回批量快照
 */
public class ReplayMain {
    private static final Logger log = LoggerFactory.getLogger(ReplayMain.class);
    private static Jedis jedis;

    public static void main(String[] args) throws Exception {
        ArgsParser argsParser = new ArgsParser(args);
        String redisHost = argsParser.get("--redis-host", ConfigConstants.REDIS_HOST);
        int redisPort = argsParser.getInt("--redis-port", ConfigConstants.REDIS_PORT);
        int httpPort = argsParser.getInt("--port", 8893);

        // 自动清理残留的旧 ReplayServer 进程，避免 BindException
        killPortOwner(httpPort);

        var pool = new redis.clients.jedis.JedisPool(redisHost, redisPort);
        jedis = pool.getResource();

        HttpServer server = HttpServer.create(new InetSocketAddress(httpPort), 0);
        server.createContext("/api/replay/list", new ListHandler());
        server.createContext("/api/replay/snapshot/", new SnapshotHandler());
        server.createContext("/api/replay/metrics", new MetricsHandler());
        server.createContext("/api/replay/snapshots", new BatchHandler());
        server.setExecutor(null);
        server.start();
        log.info("ReplayServer 已启动，端口: {}", httpPort);
    }

    /**
     * 杀掉占用指定端口的 LISTENING 进程，避免重启时 BindException。
     * Windows 专用（依赖 netstat + taskkill）。
     */
    private static void killPortOwner(int port) {
        try {
            Process p = new ProcessBuilder("cmd", "/c",
                    "netstat -ano | findstr :" + port + " | findstr LISTENING").start();
            String out = new String(p.getInputStream().readAllBytes());
            for (String line : out.split("\\r?\\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\s+");
                String pid = parts[parts.length - 1];
                if (pid.matches("\\d+")) {
                    log.info("清理端口 {} 残留进程 PID={}", port, pid);
                    Runtime.getRuntime().exec("taskkill /F /PID " + pid).waitFor();
                    Thread.sleep(500);
                    break;
                }
            }
        } catch (Exception ignored) {}
    }

    static class ListHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            long len = jedis.llen("replay:snapshots");
            List<Long> indices = new ArrayList<>();
            for (long i = 0; i < len; i++) indices.add(i);
            send(ex, 200, JSON.toJSONString(indices));
        }
    }

    static class SnapshotHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            String idxStr = path.substring(path.lastIndexOf('/') + 1);
            try {
                int idx = Integer.parseInt(idxStr);
                String json = jedis.lindex("replay:snapshots", idx);
                if (json == null) { send(ex, 404, "{}"); return; }
                send(ex, 200, json);
            } catch (NumberFormatException e) { send(ex, 400, "{}"); }
        }
    }

    static class MetricsHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            List<String> data = jedis.lrange("analysis:metrics", -100, -1);
            send(ex, 200, JSON.toJSONString(data));
        }
    }

    static class BatchHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            String query = ex.getRequestURI().getQuery();
            int from = 0, to = 99;
            if (query != null) {
                for (String p : query.split("&")) {
                    if (p.startsWith("from=")) from = Integer.parseInt(p.substring(5));
                    else if (p.startsWith("to=")) to = Integer.parseInt(p.substring(3));
                }
            }
            java.util.List<String> result = new java.util.ArrayList<>();
            for (int i = from; i <= to && i < 100000; i++) {
                String json = jedis.lindex("replay:snapshots", i);
                if (json == null) break;
                result.add(json);
            }
            send(ex, 200, JSON.toJSONString(result));
        }
    }

    static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}
