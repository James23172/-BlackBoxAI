package inspection.replay;

import com.alibaba.fastjson2.JSON;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import inspection.common.client.BlackboardClient;
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
 */
public class ReplayMain {
    private static final Logger log = LoggerFactory.getLogger(ReplayMain.class);
    private static Jedis jedis;

    public static void main(String[] args) throws Exception {
        var pool = new redis.clients.jedis.JedisPool(ConfigConstants.REDIS_HOST, ConfigConstants.REDIS_PORT);
        jedis = pool.getResource();

        HttpServer server = HttpServer.create(new InetSocketAddress(8891), 0);
        server.createContext("/api/replay/list", new ListHandler());
        server.createContext("/api/replay/snapshot/", new SnapshotHandler());
        server.createContext("/api/replay/metrics", new MetricsHandler());
        server.setExecutor(null);
        server.start();
        log.info("ReplayServer 已启动，端口: 8891");
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

    static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}
