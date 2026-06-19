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
 * 导航器 — 纯路径规划（目标选择已拆分到 TargetPlanner）
 *
 * 系统功能:
 *   1. 消费 RabbitMQ 共享竞争队列 Navigator4CarID
 *   2. 收到 NAVIGATE 请求 → 从 Redis 读取已分配的 car:{id}:target → 加权 BFS 规划路径
 *   3. 若目标不存在 → 发 GET_TARGET 到 TargetPlannerCmd（解耦目标选择）
 *   4. 写入路径 + pushTask("MOVE_READY") 到 taskQueue
 *
 * 服务能力:
 *   - BFS 路径规划 O(W×H)，30×30 ≈ 0.3~0.8ms
 *   - 多实例并行: 共享 RabbitMQ 队列自动 round-robin，无全局锁
 *   - 每实例独立 Redis/Jedis 连接
 *
 * 输入接口:
 *   - RabbitMQ 队列 Navigator4CarID ── 接收 NAVIGATE {carId}
 *   - Redis car:{id}:target             ── 读取已分配目标
 *
 * 输出接口:
 *   - RabbitMQ 队列 TargetPlannerCmd    ── 发送 GET_TARGET（若目标缺失）
 *   - Redis car:{id}:route              ── 写入规划路径
 *   - Redis taskQueue                   ── pushTask("MOVE_READY")
 *
 * 通信协议:
 *   - 输入: AMQP 0-9-1（手动 ACK）
 *   - 输出: AMQP (basicPublish) + RESP (Redis)
 *
 * 约束条件:
 *   1. 路径不可达时标记目标为已探索，重新入队 ROUTE_NEEDED
 *   2. 依赖 TargetPlanner 提供目标（若单独使用需 TargetPlanner 实例运行）
 *   3. 依赖 Redis 和 RabbitMQ 服务可用
 *
 * 运行形式:
 *   产物: navigator-1.0-SNAPSHOT.jar (fat-jar)
 *   独立启动: java -jar navigator-1.0-SNAPSHOT.jar
 *   多实例: 可启动 1~N 个，RabbitMQ 自动 round-robin 分发
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

    // ==================== NAVIGATE 处理：纯路径规划 ====================

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

        Point carPos = blackboard.getCarPosition(carId);
        if (carPos == null) {
            LOG.error("小车 {} 位置为空", carId);
            handleNavigateFailed(carId);
            return;
        }

        // ──── 1. 从 Redis 读取目标（由 TargetPlanner 分配） ────
        Point target = blackboard.getCarTarget(carId);
        if (target == null) {
            // 尚无目标 → 请求 TargetPlanner 分配
            LOG.info("🔄 [Navigator] carId={} 无目标，发 GET_TARGET → TargetPlannerCmd", carId);
            JSONObject getTargetData = new JSONObject();
            getTargetData.put("carId", carId);
            MQMessage getTargetMsg = new MQMessage("GET_TARGET", getTargetData);
            messageBus.sendToQueue(ConfigConstants.QUEUE_TARGET_PLANNER_CMD, getTargetMsg);
            return;
        }

        LOG.info("已读取目标: carId={}, target=({},{})", carId, target.x, target.y);

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
}
