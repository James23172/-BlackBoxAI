package inspection.targetplanner;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DeliverCallback;
import inspection.common.client.BlackboardClient;
import inspection.common.client.MessageBusClient;
import inspection.common.config.ArgsParser;
import inspection.common.config.ConfigConstants;
import inspection.common.model.MQMessage;
import inspection.common.model.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 目标规划器 — 独立构件，负责为小车选择下一个探索目标
 *
 * 系统功能:
 *   1. 消费 RabbitMQ 共享竞争队列 TargetPlannerCmd
 *   2. 收到 GET_TARGET 请求 → 扫描未探索区域 → 贪心选择目标 → 写入 Redis
 *   3. 目标选定后 pushTask("ROUTE_NEEDED") 到 taskQueue
 *
 * 服务能力:
 *   - 每次选择: O(W×H) 扫描全地图，30×30 ≈ 0.1~0.3ms
 *   - 并行调度: 多实例共享 RabbitMQ 队列，round-robin 自动分发
 *   - 无全局锁: 每辆车目标独立写入 Redis，天然隔离
 *
 * 输入接口:
 *   - RabbitMQ 队列 TargetPlannerCmd ── 接收 GET_TARGET {carId}
 *   - Redis                                 ── 读取 map:view / car:{id}:position / car:{id}:target
 *
 * 输出接口:
 *   - Redis car:{id}:target                ── 写入选定目标坐标
 *   - Redis taskQueue                      ── pushTask("ROUTE_NEEDED")
 *
 * 通信协议:
 *   - 输入: AMQP 0-9-1（手动 ACK）
 *   - 输出: RESP (Redis HSET/RPUSH)
 *
 * 约束条件:
 *   1. 无未探索区域（巡检完成）时返回 null，不写 target
 *   2. 多实例部署时 RabbitMQ round-robin 分发，无需额外锁
 *   3. 依赖 Redis 和 RabbitMQ 服务可用
 *
 * 运行形式:
 *   产物: target-planner-1.0-SNAPSHOT.jar (fat-jar)
 *   独立启动: java -jar target-planner-1.0-SNAPSHOT.jar
 *   多实例: 可启动 1~N 个
 */
public class TargetPlannerMain {
    private static final Logger LOG = LoggerFactory.getLogger(TargetPlannerMain.class);

    private BlackboardClient blackboard;
    private MessageBusClient messageBus;

    public static void main(String[] args) throws Exception {
        new TargetPlannerMain().start(args);
    }

    public void start(String[] args) throws Exception {
        ArgsParser argsParser = new ArgsParser(args);
        String redisHost = argsParser.get("--redis-host", ConfigConstants.REDIS_HOST);
        int redisPort = argsParser.getInt("--redis-port", ConfigConstants.REDIS_PORT);
        String mqHost = argsParser.get("--mq-host", ConfigConstants.RABBITMQ_HOST);
        int mqPort = argsParser.getInt("--mq-port", ConfigConstants.RABBITMQ_PORT);

        blackboard = new BlackboardClient(redisHost, redisPort);
        messageBus = new MessageBusClient(mqHost, mqPort,
                ConfigConstants.RABBITMQ_USER, ConfigConstants.RABBITMQ_PASS, ConfigConstants.RABBITMQ_VHOST);

        String plannerQueue = ConfigConstants.QUEUE_TARGET_PLANNER_CMD;
        Channel channel = messageBus.getChannel();
        channel.queueDeclare(plannerQueue, true, false, false, null);

        LOG.info("TargetPlanner 已启动，监听队列: {}", plannerQueue);

        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
            try {
                handleGetTarget(message);
            } catch (Exception e) {
                LOG.error("处理消息失败: {}", message, e);
            } finally {
                channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
            }
        };

        channel.basicConsume(plannerQueue, false, deliverCallback, consumerTag -> {});
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

    // ==================== GET_TARGET 处理 ====================

    private void handleGetTarget(String messageJson) {
        MQMessage msg = JSON.parseObject(messageJson, MQMessage.class);
        String cmd = msg.getCmd();
        if (!"GET_TARGET".equals(cmd)) {
            LOG.warn("忽略非 GET_TARGET 命令: {}", cmd);
            return;
        }

        JSONObject data = JSONObject.parseObject(JSON.toJSONString(msg.getData()));
        String carId = data.getString("carId");
        LOG.info("🎯 [GET_TARGET] 收到: carId={}", carId);

        Point carPos = blackboard.getCarPosition(carId);
        if (carPos == null) {
            LOG.error("小车 {} 位置为空", carId);
            return;
        }

        Point target = selectTarget(carId, carPos);
        if (target == null) {
            LOG.info("无未探索区域可供 {} 探索", carId);
            return;
        }

        blackboard.setCarTarget(carId, target.getX(), target.getY());
        LOG.info("已分配目标: carId={}, target=({},{})", carId, target.x, target.y);

        // 推 ROUTE_NEEDED 到 taskQueue，Controller 收到后发 NAVIGATE 给 Navigator
        blackboard.pushTask("ROUTE_NEEDED", carId, null);
        LOG.info("📤 [TargetPlanner] pushTask(ROUTE_NEEDED) → Redis taskQueue, carId={}", carId);
    }

    // ==================== 贪心目标选择 ====================

    private Point selectTarget(String carId, Point carPos) {
        int w = blackboard.getMapWidth();
        int h = blackboard.getMapHeight();

        blackboard.invalidateBitmapCache();
        boolean[][] explored = blackboard.getMapView();
        boolean[][] blocked  = blackboard.getMapBlocked();  // 一次性读, 替代循环内 1600 次 isBlocked()

        // 1. 扫描候选池（排除障碍物 + 该车不可达）
        List<Point> candidates = new ArrayList<>();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (!explored[y][x]
                        && !blocked[y][x]
                        && !blackboard.isCarUnreachable(carId, x, y))
                    candidates.add(new Point(x, y));

        if (candidates.isEmpty()) return null;

        // 2. 排除其他车已分配的目标
        Set<Point> claimed = new HashSet<>();
        for (String cid : getAllCarIds())
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

    /** 从 Redis config:task 动态获取所有已知小车 ID */
    private List<String> getAllCarIds() {
        try {
            return blackboard.getAllCarIds();
        } catch (Exception e) {
            return Collections.singletonList("Car001");
        }
    }
}
