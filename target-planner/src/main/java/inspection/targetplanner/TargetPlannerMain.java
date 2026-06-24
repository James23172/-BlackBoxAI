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
    private final Random random = new Random();

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
        boolean[][] obstacles = blackboard.getMapBlocked();
        boolean[][] unreachable = blackboard.getCarUnreachableBitmap(carId);

        // 1. 扫描所有未探索的格子 → 候选池（排除障碍物 + 该车不可达）
        //    使用本地数组判断替代逐格 Redis GETBIT，40×40 地图从 3200 次降至 3 次 Redis 调用
        List<Point> candidates = new ArrayList<>();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (!explored[y][x] && !obstacles[y][x] && !unreachable[y][x])
                    candidates.add(new Point(x, y));

        if (candidates.isEmpty()) return null;

        // 2. BFS 计算从车位置出发的静态可达集合（1 次 flood fill，非逐候选 BFS）
        Set<Point> reachable = computeReachableSet(carPos, obstacles, w, h);

        // 3. 过滤候选：只保留实际可达的格子（排除曼哈顿近但被障碍物隔离的）
        List<Point> reachableCandidates = new ArrayList<>();
        for (Point p : candidates) {
            if (reachable.contains(p)) reachableCandidates.add(p);
        }
        if (reachableCandidates.isEmpty()) {
            LOG.info("carId={} 候选 {} 个但无一静态可达（可能被障碍物隔离）", carId, candidates.size());
            return null;  // 触发 Navigator 失败 → 标记不可达 → FIFO 淘汰后续重试
        }

        // 4. 排除其他车已分配的目标
        Set<Point> claimed = new HashSet<>();
        for (String cid : getAllCarIds())
            if (!cid.equals(carId)) {
                Point t = blackboard.getCarTarget(cid);
                if (t != null) claimed.add(t);
            }

        // 5. 根据阻塞等级选择策略（方案 C 多策略降级）
        int blocked = blackboard.getConsecutiveBlocked(carId);
        return selectByStrategy(reachableCandidates, carPos, claimed, explored, w, h, blocked);
    }

    /**
     * 从起点做 BFS flood fill，返回所有静态可达格子集合。
     * 注意：getMapBlocked() 返回的 bitmap 包含所有车的当前位置（车自己 + 其他车），
     * 因此起点（车自己站着）在 obstacles 中是 true。BFS 必须把起点视为可通行，
     * 否则会直接返回空集合导致所有候选都被判为"不可达"。
     * 邻居扩展仍检查 obstacles，确保不会穿过其他车或静态障碍物。
     */
    private Set<Point> computeReachableSet(Point start, boolean[][] obstacles, int w, int h) {
        Set<Point> reachable = new HashSet<>();
        if (start == null || obstacles == null) return reachable;
        if (start.x < 0 || start.x >= w || start.y < 0 || start.y >= h) return reachable;
        // 不检查 obstacles[start] —— 车自己站的位置必然是 blocked=true（CarAgent.setObstacle），但车确实在那里

        int[][] dirs = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
        Deque<Point> queue = new ArrayDeque<>();
        queue.add(start);
        reachable.add(start);

        while (!queue.isEmpty()) {
            Point cur = queue.poll();
            for (int[] d : dirs) {
                int nx = cur.x + d[0];
                int ny = cur.y + d[1];
                Point np = new Point(nx, ny);
                if (nx >= 0 && nx < w && ny >= 0 && ny < h
                        && !obstacles[ny][nx]
                        && !reachable.contains(np)) {
                    reachable.add(np);
                    queue.add(np);
                }
            }
        }
        return reachable;
    }

    /**
     * 根据连续阻塞次数选择目标选择策略（方案 C 多策略降级）。
     * 所有策略共用同一个 consecutiveBlocked 计数器（由 Navigator 递增）。
     */
    private Point selectByStrategy(List<Point> candidates, Point carPos,
                                    Set<Point> claimed, boolean[][] explored,
                                    int w, int h, int blocked) {
        String mode;
        if (blocked < 5) mode = "NORMAL";
        else if (blocked < 10) mode = "CONSERVATIVE";
        else if (blocked < 15) mode = "EXTENDED";
        else if (blocked < 20) mode = "REMOTE";
        else mode = "RESCUE";
        LOG.info("策略: {} (连续阻塞={})", mode, blocked);

        switch (mode) {
            case "NORMAL":
                // 原逻辑：选距离 ≤ 3 的最近，其次全局最近
                return pickNearest(candidates, carPos, claimed, 0, Integer.MAX_VALUE);

            case "CONSERVATIVE":
                // 跳过 ≤3 的近距陷阱，选距离 4-10 的最近
                Point p = pickNearest(candidates, carPos, claimed, 4, 10);
                return p != null ? p : pickNearest(candidates, carPos, claimed, 0, Integer.MAX_VALUE);

            case "EXTENDED":
                // 扩展候选到已探索区域的边界格子（绕过障碍物）
                List<Point> extended = new ArrayList<>(candidates);
                extended.addAll(collectFrontierEdges(explored, candidates, w, h));
                return pickNearest(extended, carPos, claimed, 0, Integer.MAX_VALUE);

            case "REMOTE":
                // 选地图对侧的可达未探索格，强制长距离移动打破局部死锁
                return pickRemote(candidates, carPos, claimed, w, h);

            case "RESCUE":
            default:
                // 随机选一个可达未探索格，打破死锁
                return pickRandom(candidates, claimed);
        }
    }

    /** 选距离在 [minDist, maxDist] 范围内最近的候选 */
    private Point pickNearest(List<Point> candidates, Point carPos, Set<Point> claimed, int minDist, int maxDist) {
        Point best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Point p : candidates) {
            if (claimed.contains(p)) continue;
            int dist = p.distanceTo(carPos);
            if (dist >= minDist && dist <= maxDist && dist < bestDist) {
                bestDist = dist;
                best = p;
            }
        }
        return best;
    }

    /** 收集已探索区域的边界格子（已探索但 4 邻域有未探索候选的格子） */
    private List<Point> collectFrontierEdges(boolean[][] explored, List<Point> candidates, int w, int h) {
        List<Point> edges = new ArrayList<>();
        int[][] dirs = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
        // 用 boolean[][] 替代 HashSet<Point>，避免每次查找 new Point 对象
        boolean[][] isCandidate = new boolean[h][w];
        for (Point p : candidates) isCandidate[p.y][p.x] = true;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (explored[y][x]) {
                    for (int[] d : dirs) {
                        int nx = x + d[0], ny = y + d[1];
                        if (nx >= 0 && nx < w && ny >= 0 && ny < h && isCandidate[ny][nx]) {
                            edges.add(new Point(x, y));
                            break;
                        }
                    }
                }
        return edges;
    }

    /** 选地图对侧的候选（强制长距离移动） */
    private Point pickRemote(List<Point> candidates, Point carPos, Set<Point> claimed, int w, int h) {
        int cx = w / 2, cy = h / 2;
        boolean wantRight = carPos.x < cx;
        boolean wantBottom = carPos.y < cy;
        Point best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Point p : candidates) {
            if (claimed.contains(p)) continue;
            // 对角或对边任一即可（原 && 过于严格，常退化成 pickNearest）
            if ((p.x >= cx) == wantRight || (p.y >= cy) == wantBottom) {
                int dist = p.distanceTo(carPos);
                if (dist < bestDist) { bestDist = dist; best = p; }
            }
        }
        return best != null ? best : pickNearest(candidates, carPos, claimed, 0, Integer.MAX_VALUE);
    }

    /** 随机选一个候选 */
    private Point pickRandom(List<Point> candidates, Set<Point> claimed) {
        List<Point> avail = new ArrayList<>();
        for (Point p : candidates) if (!claimed.contains(p)) avail.add(p);
        if (avail.isEmpty()) return null;
        return avail.get(random.nextInt(avail.size()));
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
