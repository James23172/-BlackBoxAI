package inspection.display;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import inspection.common.client.BlackboardClient;
import inspection.common.client.MessageBusClient;
import inspection.common.model.Point;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WebSocket 服务器
 * 接收浏览器命令（SET_CONFIG / RESET / START / PAUSE），写入 Redis taskQueue
 * Controller 通过 taskQueue 轮询处理这些命令
 */
public class CommandReceiver extends WebSocketServer {
    private static final Logger LOG = LoggerFactory.getLogger(CommandReceiver.class);

    private final MessageBusClient messageBus;
    private StateBroadcaster stateBroadcaster;
    private BlackboardClient blackboard;

    public CommandReceiver(InetSocketAddress address, MessageBusClient messageBus) {
        super(address);
        this.messageBus = messageBus;
        setConnectionLostTimeout(30);
    }

    public void setStateBroadcaster(StateBroadcaster stateBroadcaster) {
        this.stateBroadcaster = stateBroadcaster;
    }

    public void setBlackboard(BlackboardClient blackboard) {
        this.blackboard = blackboard;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        LOG.info("浏览器已连接: {}, 当前连接数: {}",
                conn.getRemoteSocketAddress(), getConnections().size());
        if (stateBroadcaster != null) {
            stateBroadcaster.sendCurrentState(conn);
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        LOG.info("浏览器已断开: {}, 当前连接数: {}",
                conn.getRemoteSocketAddress(), getConnections().size());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        LOG.debug("收到浏览器消息: {}", message);
        try {
            JSONObject json = JSON.parseObject(message);
            String type = json.getString("type");

            switch (type) {
                case "SET_CONFIG":
                    handleSetConfig(json);
                    break;
                case "RESET":
                    handleReset(json);
                    break;
                case "START":
                    handleStart();
                    break;
                case "PAUSE":
                    handlePause();
                    break;
                case "GET_SNAPSHOT":
                    conn.send("{\"type\":\"ERROR\",\"message\":\"Replay not supported\"}");
                    break;
                case "ROUTE_DISPLAY":
                    handleRouteDisplay(conn, json);
                    break;
                case "ROUTE_HIDE":
                    handleRouteHide(conn);
                    break;
                default:
                    LOG.warn("未知浏览器命令: {}", type);
            }
        } catch (Exception e) {
            LOG.error("处理浏览器消息失败: {}", e.getMessage(), e);
        }
    }

    // ==================== 命令处理 → Redis taskQueue ====================

    private void handleSetConfig(JSONObject json) {
        Map<String, String> task = new LinkedHashMap<>();
        task.put("type", "SET_CONFIG");
        task.put("mapWidth", String.valueOf(json.getIntValue("mapWidth")));
        task.put("mapHeight", String.valueOf(json.getIntValue("mapHeight")));
        task.put("carCount", String.valueOf(json.getIntValue("carCount")));
        task.put("obstacleDensity", String.valueOf(json.getDoubleValue("obstacleDensity")));
        blackboard.pushTask("SET_CONFIG", task);
        LOG.info("推送 SET_CONFIG → taskQueue: {}x{}, carCount={}",
                task.get("mapWidth"), task.get("mapHeight"), task.get("carCount"));
    }

    private void handleReset(JSONObject json) {
        // 1. 唤醒 Controller（否则 taskActive=false 时 Controller 不消费 taskQueue）
        blackboard.setTaskActive(true);
        // 2. 推送 RESET 任务到 taskQueue
        Map<String, String> task = new LinkedHashMap<>();
        task.put("type", "RESET");
        task.put("mapWidth", String.valueOf(json.getIntValue("mapWidth")));
        task.put("mapHeight", String.valueOf(json.getIntValue("mapHeight")));
        task.put("carCount", String.valueOf(json.getIntValue("carCount")));
        task.put("obstacleDensity", String.valueOf(json.getDoubleValue("obstacleDensity")));
        blackboard.pushTask("RESET", task);
        LOG.info("RESET: 已设置 Redis taskActive=true + 推送 taskQueue: {}x{}, carCount={}",
                task.get("mapWidth"), task.get("mapHeight"), task.get("carCount"));
    }

    private void handleStart() {
        // 1. 直接在 Redis 设置 taskActive=true，唤醒 Controller（绕过 taskQueue 死锁）
        blackboard.setTaskActive(true);
        // 2. 同时推送 START 任务（供 Controller 处理空队列初始化等边界情况）
        blackboard.pushTask("START", (Map<String, String>) null);
        LOG.info("START: 已设置 Redis taskActive=true + 推送 taskQueue");
    }

    private void handlePause() {
        blackboard.setTaskActive(false);
        blackboard.pushTask("PAUSE", (Map<String, String>) null);
        LOG.info("PAUSE: 已设置 Redis taskActive=false + 推送 taskQueue");
    }

    // ==================== Route Display ====================

    private void handleRouteDisplay(WebSocket conn, JSONObject json) {
        if (blackboard == null) {
            conn.send("{\"type\":\"ERROR\",\"message\":\"Blackboard not ready\"}");
            return;
        }
        String carId = json.getString("carId");
        if (carId == null || carId.isEmpty()) {
            conn.send("{\"type\":\"ERROR\",\"message\":\"carId required\"}");
            return;
        }
        List<Point> route = blackboard.getFullRoute(carId);
        JSONObject response = new JSONObject();
        response.put("type", "ROUTE_DATA");
        response.put("carId", carId);
        response.put("route", route != null ? route : java.util.Collections.emptyList());
        conn.send(response.toJSONString());
        LOG.info("发送路由数据: carId={}, 路径长度={}", carId, route != null ? route.size() : 0);
    }

    private void handleRouteHide(WebSocket conn) {
        conn.send("{\"type\":\"ROUTE_HIDE\"}");
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        LOG.error("WebSocket 错误: {}", ex.getMessage(), ex);
    }

    @Override
    public void onStart() {
        LOG.info("WebSocket 服务器已启动: {}", getAddress());
    }
}
