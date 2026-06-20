package inspection.display;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import inspection.common.client.BlackboardClient;
import inspection.common.client.MessageBusClient;
import inspection.common.config.ConfigConstants;
import inspection.common.model.MQMessage;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    private final Map<WebSocket, ConnState> connStates = new ConcurrentHashMap<>();
    private String machineId = "主";

    public void setMachineId(String machineId) {
        this.machineId = machineId;
    }

    // 内联权限常量（与 auth/PermissionManager.DEFAULT_PERMISSIONS 保持同步）
    private static final Set<String> CONFIG_ONLY = Set.of("SET_CONFIG", "RESET", "TOGGLE_OBSTACLE", "RECORD_START", "RECORD_STOP");
    private static final Set<String> CONFIG_OR_OP = Set.of("START", "PAUSE", "ADD_CAR", "REMOVE_CAR");

    private boolean checkPermission(WebSocket conn, String action) {
        ConnState state = connStates.get(conn);
        if (state == null || state.role == null) return false;
        String role = state.role;
        if ("configurator".equals(role)) return true;  // 配置员所有权限
        if ("operator".equals(role)) {
            if (CONFIG_OR_OP.contains(action)) return true;
        }
        // analyst 只能查看
        try {
            conn.send("{\"type\":\"ERROR\",\"message\":\"权限不足\"}");
        } catch (Exception e) { /* ignore */ }
        return false;
    }

    static class ConnState {
        String username;
        String role;
        String machineId;
    }

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
        connStates.put(conn, new ConnState());  // username=null，标记为未认证
        LOG.info("浏览器已连接: {}, 当前连接数: {}",
                conn.getRemoteSocketAddress(), getConnections().size());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        connStates.remove(conn);
        LOG.info("浏览器已断开: {}, 当前连接数: {}",
                conn.getRemoteSocketAddress(), getConnections().size());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        LOG.debug("收到浏览器消息: {}", message);
        try {
            JSONObject json = JSON.parseObject(message);
            String type = json.getString("type");

            // ── AUTH 认证检查 ──
            ConnState state = connStates.get(conn);
            if (state == null) {
                conn.close(4001, "未注册连接");
                return;
            }
            if (state.username == null) {
                // 第一条消息必须是 AUTH
                if (!"AUTH".equals(type)) {
                    conn.close(4001, "请先认证");
                    return;
                }
                handleAuth(conn, json);
                return;
            }

            // ── 权限校验（AUTH 通过后） ──
            // 以下命令需要特定角色
            if ("SET_CONFIG".equals(type) || "RESET".equals(type) || "TOGGLE_OBSTACLE".equals(type)
                    || "RECORD_START".equals(type) || "RECORD_STOP".equals(type)) {
                if (!checkPermission(conn, type)) return;
            }
            if ("START".equals(type) || "PAUSE".equals(type) || "ADD_CAR".equals(type) || "REMOVE_CAR".equals(type)) {
                if (!checkPermission(conn, type)) return;
            }

            switch (type) {
                case "SET_CONFIG":
                    handleSetConfig(json);
                    break;
                case "RESET":
                    handleReset(json);
                    break;
                case "START":
                    handleStart(json, state);
                    break;
                case "PAUSE":
                    handlePause(json, state);
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
                case "ADD_CAR":
                    handleAddCar(json, state);
                    break;
                case "REMOVE_CAR":
                    handleRemoveCar(json);
                    break;
                case "TOGGLE_OBSTACLE":
                    handleToggleObstacle(json);
                    break;
                case "RECORD_START":
                    blackboard.pushTask("RECORD_START", (Map<String, String>) null);
                    LOG.info("RECORD_START: 已推送到 taskQueue");
                    break;
                case "RECORD_STOP":
                    blackboard.pushTask("RECORD_STOP", (Map<String, String>) null);
                    LOG.info("RECORD_STOP: 已推送到 taskQueue");
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

    private void handleStart(JSONObject json, ConnState state) {
        String scope = json.getString("scope");
        if ("personal".equals(scope)) {
            String machineId = state != null ? state.machineId : null;
            if (machineId == null) {
                LOG.warn("START personal 但 machineId 为空");
                return;
            }
            if (blackboard.isGlobalPaused()) {
                LOG.warn("运行员 {} 尝试恢复但全局暂停中", machineId);
                return;
            }
            blackboard.setOperatorPause(machineId, false);
            LOG.info("运行员 {} 恢复了自己的车", machineId);
        } else {
            // 默认全局开始
            blackboard.setGlobalPause(false);
            blackboard.setTaskActive(true);
            LOG.info("全局开始");
        }
    }

    private void handlePause(JSONObject json, ConnState state) {
        String scope = json.getString("scope");
        if ("personal".equals(scope)) {
            String machineId = state != null ? state.machineId : null;
            if (machineId == null) {
                LOG.warn("PAUSE personal 但 machineId 为空");
                return;
            }
            blackboard.setOperatorPause(machineId, true);
            LOG.info("运行员 {} 暂停了自己的车", machineId);
        } else {
            // 默认全局暂停
            blackboard.setGlobalPause(true);
            blackboard.setTaskActive(false);
            LOG.info("全局暂停");
        }
    }

    // ==================== 动态小车增删 ====================

    private void handleAddCar(JSONObject json, ConnState state) {
        String carId = json.getString("carId");
        int x = json.getIntValue("x", 15);
        int y = json.getIntValue("y", 15);

        // 写入 car owner（使用服务端记录的 machineId，不信任客户端）
        String machine = state != null ? state.machineId : null;
        if (machine != null && !machine.isEmpty()) {
            blackboard.setCarOwner(carId, machine);
        }

        // 1. 发送 FORWARD_CONFIG(addCar=true) 到 TaskConfigurator
        try {
            JSONObject data = new JSONObject();
            data.put("addCar", true);
            data.put("addCarId", carId);
            data.put("x", x);
            data.put("y", y);
            data.put("mapWidth", blackboard.getMapWidth());
            data.put("mapHeight", blackboard.getMapHeight());
            data.put("machine", machine);
            MQMessage msg = new MQMessage("FORWARD_CONFIG", data);
            messageBus.sendToQueue(ConfigConstants.QUEUE_TASK_CONFIG_CMD, msg);
        } catch (Exception e) {
            LOG.error("无法发送 ADD_CAR 到 TaskConfigurator: {}", e.getMessage());
        }

        // 2. 同时 push ADD_CAR 到 taskQueue 通知 Controller
        Map<String, String> extra = java.util.Map.of("x", String.valueOf(x), "y", String.valueOf(y));
        blackboard.pushTask("ADD_CAR", carId, extra);
        LOG.info("ADD_CAR: carId={}, pos=({},{}), machine={}, 已推送到 taskQueue", carId, x, y, machine);
    }

    private void handleRemoveCar(JSONObject json) {
        String carId = json.getString("carId");
        if (carId == null || carId.isEmpty()) {
            LOG.warn("REMOVE_CAR: carId 为空");
            return;
        }
        // 1. 通过 TaskConfigurator 增量移除
        try {
            JSONObject data = new JSONObject();
            data.put("removeCar", true);
            data.put("removeCarId", carId);
            MQMessage msg = new MQMessage("FORWARD_CONFIG", data);
            messageBus.sendToQueue(ConfigConstants.QUEUE_TASK_CONFIG_CMD, msg);
        } catch (Exception e) {
            LOG.error("无法发送 REMOVE_CAR 到 TaskConfigurator: {}", e.getMessage());
        }

        // 2. 同时 push REMOVE_CAR 到 taskQueue
        blackboard.pushTask("REMOVE_CAR", carId, null);
        LOG.info("REMOVE_CAR: carId={}, 已推送到 taskQueue", carId);
    }

    private void handleToggleObstacle(JSONObject json) {
        int x = json.getIntValue("x");
        int y = json.getIntValue("y");
        try {
            // 切换障碍物状态：SETBIT翻转
            try (var jedis = blackboard.getJedis()) {
                boolean cur = jedis.getbit(ConfigConstants.KEY_MAP_BLOCKED, (long) y * blackboard.getMapWidth() + x);
                jedis.setbit(ConfigConstants.KEY_MAP_BLOCKED, (long) y * blackboard.getMapWidth() + x, !cur);
                LOG.info("障碍物切换: ({},{}) {} → {}", x, y, cur ? "ON" : "OFF", !cur ? "ON" : "OFF");
            }
        } catch (Exception e) {
            LOG.error("切换障碍物失败", e);
        }
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

    private void handleAuth(WebSocket conn, JSONObject json) {
        String token = json.getString("token");
        if (token == null || token.isEmpty()) {
            conn.close(4001, "缺少认证令牌");
            return;
        }

        // 调用 AuthServer 验证 token（URL 由 Display 的 --auth-host 参数决定）
        try {
            String authHost = System.getProperty("auth.host", "localhost");
            int authPort = Integer.parseInt(System.getProperty("auth.port", "8890"));
            java.net.URI uri = new java.net.URI("http", null, authHost, authPort,
                    "/api/auth/verify", "token=" + token, null);
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(uri).GET().build();
            java.net.http.HttpResponse<String> resp = client.send(req,
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                conn.close(4001, "认证失败");
                return;
            }
            com.alibaba.fastjson2.JSONObject verifyResp =
                    com.alibaba.fastjson2.JSON.parseObject(resp.body());
            if (!verifyResp.getBooleanValue("success", false)) {
                conn.close(4001, "认证失败");
                return;
            }

            ConnState state = connStates.get(conn);
            state.username = verifyResp.getString("username");
            state.role = verifyResp.getString("role");
            state.machineId = machineId;

            // 推送当前状态给新连接（避免白屏等待下一次 REFRESH_ALL）
            if (stateBroadcaster != null) {
                stateBroadcaster.sendCurrentState(conn);
            }

            // 回复 AUTH_OK，告知前端 machine
            com.alibaba.fastjson2.JSONObject ok = new com.alibaba.fastjson2.JSONObject();
            ok.put("type", "AUTH_OK");
            ok.put("machine", machineId);
            conn.send(ok.toJSONString());

            LOG.info("认证成功: username={}, role={}, machineId={}",
                    state.username, state.role, state.machineId);
        } catch (Exception e) {
            LOG.error("AUTH 验证失败", e);
            conn.close(4001, "认证服务不可用");
        }
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
