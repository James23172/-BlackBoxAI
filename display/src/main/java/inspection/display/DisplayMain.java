package inspection.display;

import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import inspection.common.client.BlackboardClient;
import inspection.common.client.MessageBusClient;
import inspection.common.config.ArgsParser;
import inspection.common.config.ConfigConstants;
import inspection.common.model.MQMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Display 模块入口
 * 启动 WebSocket 服务器（端口 8887）+ HTTP 静态文件服务器（端口 8888）+ StateBroadcaster
 */
public class DisplayMain {
    private static final Logger LOG = LoggerFactory.getLogger(DisplayMain.class);

    public static void main(String[] args) throws Exception {
        ArgsParser argsParser = new ArgsParser(args);
        String redisHost = argsParser.get("--redis-host", ConfigConstants.REDIS_HOST);
        int redisPort = argsParser.getInt("--redis-port", ConfigConstants.REDIS_PORT);
        String mqHost = argsParser.get("--mq-host", ConfigConstants.RABBITMQ_HOST);
        int mqPort = argsParser.getInt("--mq-port", ConfigConstants.RABBITMQ_PORT);
        int wsPort = argsParser.getInt("--ws-port", 8887);
        int httpPort = argsParser.getInt("--http-port", 8888);
        int carCount = argsParser.getInt("--car-count", 4);
        String machineId = argsParser.get("--machine", "主");
        String authHost = argsParser.get("--auth-host", "localhost");
        int authPort = argsParser.getInt("--auth-port", 8890);

        // --port 兼容旧调用方式：同时设置 ws 和 http 端口
        if (argsParser.get("--port", null) != null) {
            wsPort = argsParser.getInt("--port", 8887);
            httpPort = wsPort + 1;
        }

        // 设置 auth 地址供 CommandReceiver 使用
        System.setProperty("auth.host", authHost);
        System.setProperty("auth.port", String.valueOf(authPort));

        LOG.info("Display 模块启动中...");

        // 初始化基础客户端
        BlackboardClient bb = new BlackboardClient(redisHost, redisPort);
        MessageBusClient mq = new MessageBusClient(mqHost, mqPort,
                ConfigConstants.RABBITMQ_USER, ConfigConstants.RABBITMQ_PASS,
                ConfigConstants.RABBITMQ_VHOST);

        // 启动 WebSocket 服务器
        CommandReceiver wsServer = new CommandReceiver(new InetSocketAddress(wsPort), mq);
        wsServer.setMachineId(machineId);
        wsServer.setBlackboard(bb);
        wsServer.start();
        LOG.info("WebSocket 服务器已启动，端口: {}", wsPort);

        // 启动 StateBroadcaster（订阅 UpdateView 广播）
        StateBroadcaster broadcaster = new StateBroadcaster(bb, mq, wsServer);
        wsServer.setStateBroadcaster(broadcaster);
        broadcaster.start();

        // 启动 HTTP 静态文件服务器
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(httpPort), 0);
        httpServer.createContext("/", new StaticFileHandler());
        httpServer.createContext("/api/config", new ConfigHandler(machineId, authHost, authPort));
        httpServer.setExecutor(null);
        httpServer.start();
        LOG.info("HTTP 服务器已启动，端口: {}，访问 http://localhost:{}/index.html", httpPort, httpPort);

        LOG.info("Display 模块启动完成");

        // 自动初始化：向 TaskConfigurator 发送默认配置，预加载地图与小车
        JSONObject initData = new JSONObject();
        initData.put("mapWidth", ConfigConstants.DEFAULT_MAP_WIDTH);
        initData.put("mapHeight", ConfigConstants.DEFAULT_MAP_HEIGHT);
        initData.put("carCount", carCount);
        initData.put("obstacleDensity", ConfigConstants.DEFAULT_OBSTACLE_DENSITY);
        initData.put("active", false);  // 不激活，等用户点 Start
        MQMessage initMsg = new MQMessage("FORWARD_CONFIG", initData);
        mq.sendToQueue(ConfigConstants.QUEUE_TASK_CONFIG_CMD, initMsg);
        LOG.info("已发送自动初始化配置: {}x{}, carCount={} (active=false，等待用户 Start)",
                ConfigConstants.DEFAULT_MAP_WIDTH, ConfigConstants.DEFAULT_MAP_HEIGHT, carCount);

        // 添加关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Display 模块正在关闭...");
            try {
                wsServer.stop(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            httpServer.stop(0);
            mq.close();
            bb.close();
            LOG.info("Display 模块已关闭");
        }));

        // 保持进程存活
        Thread.currentThread().join();
    }

    /**
     * 静态文件处理器，从 classpath 的 web/ 目录读取文件
     */
    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();

            // 映射根路径和默认页面
            if ("/".equals(path)) {
                path = "/index.html";
            }

            // 从 classpath 加载资源
            String resourcePath = "web" + path;
            InputStream is = DisplayMain.class.getClassLoader().getResourceAsStream(resourcePath);

            if (is == null) {
                String response = "404 Not Found: " + path;
                exchange.sendResponseHeaders(404, response.getBytes().length);
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
                return;
            }

            byte[] bytes = is.readAllBytes();
            is.close();

            // 根据文件扩展名设置 Content-Type
            String contentType = "text/html; charset=UTF-8";
            if (path.endsWith(".js")) {
                contentType = "application/javascript; charset=UTF-8";
            } else if (path.endsWith(".css")) {
                contentType = "text/css; charset=UTF-8";
            } else if (path.endsWith(".json")) {
                contentType = "application/json; charset=UTF-8";
            }

            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(bytes);
            os.close();
        }
    }

    /**
     * /api/config 端点，返回当前 Display 实例的配置信息
     */
    static class ConfigHandler implements HttpHandler {
        private final String machineId;
        private final String authHost;
        private final int authPort;

        ConfigHandler(String machineId, String authHost, int authPort) {
            this.machineId = machineId;
            this.authHost = authHost;
            this.authPort = authPort;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            JSONObject cfg = new JSONObject();
            cfg.put("machine", machineId);
            cfg.put("authServer", "http://" + authHost + ":" + authPort);
            byte[] bytes = cfg.toJSONString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}
