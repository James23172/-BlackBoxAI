package inspection.navigator;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DeliverCallback;
import inspection.common.client.BlackboardClient;
import inspection.common.client.DistributedLock;
import inspection.common.client.MessageBusClient;
import inspection.common.config.ConfigConstants;
import inspection.common.enums.CarStatus;
import inspection.common.model.MQMessage;
import inspection.common.model.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 导航器 — 目标选择 + 路径规划
 * 监听共享竞争队列 Navigator4CarID，接收 NAVIGATE 请求
 * 按照架构文档：自行扫描未探索区域、选目标、加权 BFS 规划路径
 * 不回复 ControllerCmd，只通过 Redis taskQueue 反馈结果
 */
public class NavigatorMain {
    private static final Logger LOG = LoggerFactory.getLogger(NavigatorMain.class);

    private BlackboardClient blackboard;
    private MessageBusClient messageBus;
    private final PathPlanner bfsPlanner = new BFSPlanner();

    public static void main(String[] args) throws Exception {
        new NavigatorMain().start();
    }

    public void start() throws Exception {
        blackboard = new BlackboardClient(ConfigConstants.REDIS_HOST, ConfigConstants.REDIS_PORT);

        String rabbitHost = ConfigConstants.RABBITMQ_HOST;
        int rabbitPort = ConfigConstants.RABBITMQ_PORT;
        String rabbitUser = getConfigOrDefault("RABBIT_USER", "guest");
        String rabbitPass = getConfigOrDefault("RABBIT_PASS", "guest");
        String rabbitVhost = getConfigOrDefault("RABBIT_VHOST", "/");
        messageBus = new MessageBusClient(rabbitHost, rabbitPort, rabbitUser, rabbitPass, rabbitVhost);

        String navQueue = ConfigConstants.QUEUE_NAVIGATOR_4_CAR_ID;
        Channel channel = messageBus.getChannel();
        channel.queueDeclare(navQueue, true, false, false, null);

        LOG.info("导航器已启动，监听队列: {}", navQueue);

        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
            try {
                handleNavigate(message);
            } catch (Exception e) {
                LOG.error("处理消息失败: {}", message, e);
            } finally {
                channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
            }
        };

        channel.basicConsume(navQueue, false, deliverCallback, consumerTag -> {});
    }

    private String getConfigOrDefault(String fieldName, String defaultValue) {
        try {
            java.lang.reflect.Field field = ConfigConstants.class.getField(fieldName);
            Object value = field.get(null);
            return value != null ? value.toString() : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    // ==================== NAVIGATE 处理：目标选择 + 路径规划 ====================

    private void handleNavigate(String messageJson) {
        MQMessage msg = JSON.parseObject(messageJson, MQMessage.class);
        String cmd = msg.getCmd();
        if (!"NAVIGATE".equals(cmd)) {
            LOG.warn("忽略非 NAVIGATE 命令: {}", cmd);
            return;
        }

        JSONObject data = JSONObject.parseObject(JSON.toJSONString(msg.getData()));
        String carId = data.getString("carId");
        LOG.info("🧭 [NAVIGATE] 收到: carId={}", carId);

        // ──── 1. 目标选择（全局互斥，防止多车选同一目标） ────
        Point carPos = blackboard.getCarPosition(carId);
        if (carPos == null) {
            LOG.error("小车 {} 位置为空", carId);
            handleNavigateFailed(carId);
            return;
        }

        DistributedLock targetAllocLock = blackboard.getTargetAllocationLock();
        if (!targetAllocLock.tryLock(3000)) {
            LOG.warn("目标分配锁获取超时, carId={}", carId);
            handleNavigateFailed(carId);
            return;
        }
        Point target;
        try {
            target = selectTarget(carId, carPos);
            if (target == null) {
                LOG.info("无未探索区域可供 {} 探索", carId);
                return;  // finally 会 unlock
            }
            // 二次确认：重新检查是否有其他车刚占了这个目标
            for (String cid : getKnownCarIds()) {
                if (!cid.equals(carId)) {
                    Point t = blackboard.getCarTarget(cid);
                    if (t != null && t.equals(target)) {
                        LOG.warn("目标 ({},{}) 已被 {} 抢占，放弃", target.x, target.y, cid);
                        target = null;
                        return;
                    }
                }
            }
            blackboard.setCarTarget(carId, target.getX(), target.getY());
            LOG.info("已分配目标: carId={}, target=({},{})", carId, target.getX(), target.getY());
        } finally {
            targetAllocLock.unlock();
        }
        if (target == null) {
            handleNavigateFailed(carId);
            return;
        }

        // ──── 2. 路径规划 ────
        int width = blackboard.getMapWidth();
        int height = blackboard.getMapHeight();
        boolean[][] obstacles = blackboard.getMapBlocked();
        boolean[][] explored = blackboard.getMapView();

        List<Point> path = bfsPlanner.plan(carPos, target, obstacles, explored, width, height);
        if (path == null || path.isEmpty()) {
            LOG.warn("❌ 路径规划失败: {} -> {}", carPos, target);
            handleNavigateFailed(carId);
            return;
        }

        LOG.info("✅ 路径规划成功: carId={}, start=({},{}), target=({},{}), 步数={}",
                carId, carPos.x, carPos.y, target.x, target.y, path.size());

        // 移除起点
        if (!path.isEmpty() && path.get(0).equals(carPos)) {
            path = path.subList(1, path.size());
        }
        if (path.isEmpty()) {
            LOG.warn("⚠️路径移除起点后为空");
            handleNavigateFailed(carId);
            return;
        }

        // ──── 3. 写入路径 + 入队 MOVE_READY ────
        DistributedLock carLock = blackboard.getCarLock(carId);
        if (carLock.tryLock()) {
            try {
                blackboard.clearRoute(carId);
                blackboard.pushRoute(carId, path);
                blackboard.setCarStatus(carId, CarStatus.IDLE);
                blackboard.pushTask("MOVE_READY", carId, null);
                LOG.info("📤 [Navigator] pushTask(MOVE_READY) → Redis taskQueue, carId={}", carId);
            } finally {
                carLock.unlock();
            }
        } else {
            LOG.warn("获取小车 {} 锁失败", carId);
            handleNavigateFailed(carId);
        }
    }

    /** 路径规划失败：标记不可达目标为"已尝试"，清理，重新入队 ROUTE_NEEDED */
    private void handleNavigateFailed(String carId) {
        // BFS 不可达 → 目标被障碍物围死，标记为已探索以避免无限重试
        Point unreachable = blackboard.getCarTarget(carId);
        if (unreachable != null) {
            blackboard.setMapViewBit(unreachable.x, unreachable.y);
            LOG.info("标记不可达目标为已尝试: carId={}, target=({},{})", carId, unreachable.x, unreachable.y);
        }
        blackboard.setCarStatus(carId, CarStatus.IDLE);
        try { blackboard.clearCarTarget(carId); } catch (Exception e) { /* ignore */ }
        blackboard.pushTask("ROUTE_NEEDED", carId, null);
        LOG.info("导航失败: carId={}, 已清理目标，重新入队 ROUTE_NEEDED", carId);
    }

    // ==================== 贪心目标选择 ====================

    private Point selectTarget(String carId, Point carPos) {
        int w = blackboard.getMapWidth();
        int h = blackboard.getMapHeight();

        blackboard.invalidateBitmapCache();
        boolean[][] explored = blackboard.getMapView();

        // 1. 扫描所有未探索的格子 → 候选池
        List<Point> candidates = new ArrayList<>();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (!explored[y][x])
                    candidates.add(new Point(x, y));

        if (candidates.isEmpty()) return null;

        // 2. 排除其他车已分配的目标（已探索格自然不在候选池中）
        Set<Point> claimed = new HashSet<>();
        for (String cid : getKnownCarIds())
            if (!cid.equals(carId)) {
                Point t = blackboard.getCarTarget(cid);
                if (t != null) claimed.add(t);
            }

        // 3. 贪心：选距离 ≤ 3 的最近未探索格，其次全局最近
        Point best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Point p : candidates) {
            if (claimed.contains(p)) continue;
            int dist = p.distanceTo(carPos);
            if (dist <= 3 && dist < bestDist) { bestDist = dist; best = p; }
        }
        if (best == null) {
            for (Point p : candidates) {
                if (claimed.contains(p)) continue;
                int dist = p.distanceTo(carPos);
                if (dist < bestDist) { bestDist = dist; best = p; }
            }
        }
        return best;
    }

    private List<String> getKnownCarIds() {
        List<String> ids = new ArrayList<>();
        try {
            Map<String, String> config = blackboard.getTaskConfig();
            if (config != null && !config.isEmpty()) {
                int carCount = Integer.parseInt(config.getOrDefault("carCount", "4"));
                for (int i = 1; i <= carCount; i++) {
                    ids.add(String.format("Car%03d", i));
                }
            } else {
                ids.add("Car001");
            }
        } catch (Exception e) {
            ids.add("Car001");
        }
        return ids;
    }
}
