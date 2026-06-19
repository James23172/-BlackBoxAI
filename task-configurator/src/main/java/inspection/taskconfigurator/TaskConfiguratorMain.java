package inspection.taskconfigurator;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DeliverCallback;
import inspection.common.client.BlackboardClient;
import inspection.common.client.MessageBusClient;
import inspection.common.config.ConfigConstants;
import inspection.common.enums.CarStatus;
import inspection.common.model.MQMessage;
import inspection.common.model.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 任务配置器
 * 监听 TaskConfigCmd 队列，处理 FORWARD_CONFIG 请求
 * 负责初始化仿真环境：清除旧数据、生成障碍物、初始化小车、声明队列
 */
public class TaskConfiguratorMain {
    private static final Logger LOG = LoggerFactory.getLogger(TaskConfiguratorMain.class);
    private static final String TASK_CONFIG_QUEUE = "TaskConfigCmd";

    private BlackboardClient blackboard;
    private MessageBusClient messageBus;
    private final Random random = new Random();

    public static void main(String[] args) throws Exception {
        new TaskConfiguratorMain().start();
    }

    public void start() throws Exception {
        blackboard = new BlackboardClient(ConfigConstants.REDIS_HOST, ConfigConstants.REDIS_PORT);

        String rabbitHost = ConfigConstants.RABBITMQ_HOST;
        int rabbitPort = ConfigConstants.RABBITMQ_PORT;
        String rabbitUser = getConfigOrDefault("RABBITMQ_USER", "guest");
        String rabbitPass = getConfigOrDefault("RABBITMQ_PASS", "guest");
        String rabbitVhost = getConfigOrDefault("RABBITMQ_VHOST", "/");
        messageBus = new MessageBusClient(rabbitHost, rabbitPort, rabbitUser, rabbitPass, rabbitVhost);

        Channel channel = messageBus.getChannel();
        channel.queueDeclare(TASK_CONFIG_QUEUE, true, false, false, null);

        LOG.info("任务配置器已启动，监听队列: {}", TASK_CONFIG_QUEUE);

        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
            try {
                handleForwardConfig(message);
            } catch (Exception e) {
                LOG.error("处理消息失败: {}", message, e);
            } finally {
                channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
            }
        };

        channel.basicConsume(TASK_CONFIG_QUEUE, false, deliverCallback, consumerTag -> {
        });
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

    private void handleForwardConfig(String messageJson) {
        MQMessage msg = JSON.parseObject(messageJson, MQMessage.class);
        String cmd = msg.getCmd();
        if (!"FORWARD_CONFIG".equals(cmd)) {
            LOG.warn("忽略非 FORWARD_CONFIG 命令: {}", cmd);
            return;
        }

        JSONObject data = JSONObject.parseObject(JSON.toJSONString(msg.getData()));

        int mapWidth = data.getIntValue("mapWidth", ConfigConstants.DEFAULT_MAP_WIDTH);
        int mapHeight = data.getIntValue("mapHeight", ConfigConstants.DEFAULT_MAP_HEIGHT);
        int carCount = data.getIntValue("carCount", 1);
        Double od = data.getDouble("obstacleDensity");
        double obstacleDensity = od != null ? od : ConfigConstants.DEFAULT_OBSTACLE_DENSITY;

        LOG.info("收到配置: mapWidth={}, mapHeight={}, carCount={}, density={}",
                mapWidth, mapHeight, carCount, obstacleDensity);

        // 1. 清空 Redis
        blackboard.clearAll();
        blackboard.setMapSize(mapWidth, mapHeight);
        LOG.info("Redis 已清空，地图尺寸: {}x{}", mapWidth, mapHeight);

        // 2. 初始化未探索区域索引（全部格子加入 unexplored:set，障碍物生成时自动移除）
        blackboard.initUnexploredSet(mapWidth, mapHeight);

        // 3. 计算网格分区出生点（将车辆均匀分散到地图各区域）
        List<Point> spawnPoints = computeSpawnPoints(mapWidth, mapHeight, carCount);

        // 4. 构建禁区集合（所有出生点及其周围 3x3 区域，避免障碍物生成在出生点）
        Set<Point> forbidden = buildForbiddenZone(spawnPoints, mapWidth, mapHeight);

        // 5. 生成随机障碍物（避开禁区，自动从 unexplored:set 移除）
        int obstacleCount = generateObstacles(mapWidth, mapHeight, obstacleDensity, forbidden);

        // 6. 初始化所有小车（放置在网格分区出生点，并点亮出生区域）
        List<String> carIds = new ArrayList<>();
        for (int i = 1; i <= carCount; i++) {
            String carId = String.format("Car%03d", i);
            carIds.add(carId);
            Point spawn = spawnPoints.get(i - 1);
            blackboard.setCarPosition(carId, spawn.x, spawn.y);
            blackboard.setCarStatus(carId, CarStatus.IDLE);
            blackboard.setCarSteps(carId, 0);
            // 点亮出生点及其周围 3×3 区域（避免出生点被视为"未探索"目标）
            blackboard.illuminateArea(spawn.x, spawn.y);
            LOG.info("已初始化小车: carId={}, position=({},{})", carId, spawn.x, spawn.y);
        }

        // 7. 写入任务配置
        Map<String, String> config = new HashMap<>();
        config.put("mapWidth", String.valueOf(mapWidth));
        config.put("mapHeight", String.valueOf(mapHeight));
        config.put("carCount", String.valueOf(carCount));
        config.put("cars", JSON.toJSONString(carIds));
        config.put("obstacleDensity", String.valueOf(obstacleDensity));
        String routeAlgorithm = data.getString("routeAlgorithm");
        config.put("routeAlgorithm", routeAlgorithm != null ? routeAlgorithm : "BFS");
        boolean startActive = data.getBooleanValue("active", false);
        config.put("taskActive", startActive ? "1" : "0");
        blackboard.setTaskConfig(config);

        // 7.5 初始化 FIFO 任务队列（每辆车入队 ROUTE_NEEDED）
        blackboard.clearTaskQueue();
        for (String carId : carIds) {
            blackboard.pushTask("ROUTE_NEEDED", carId, null);
        }
        LOG.info("已初始化 taskQueue: {} 个 ROUTE_NEEDED 任务", carIds.size());

        // 8. 声明系统队列和 Exchange
        try {
            messageBus.declareAllSystemQueues();
        } catch (IOException e) {
            LOG.error("声明系统队列失败: {}", e.getMessage(), e);
        }

        LOG.info("初始化完成: mapWidth={}, mapHeight={}, carCount={}, obstacleCount={}, " +
                "taskQueue 已填入 {} 个 ROUTE_NEEDED 任务。等待用户 Start 激活 Controller",
                mapWidth, mapHeight, carCount, obstacleCount, carIds.size());
    }

    /**
     * 计算网格分区出生点，将 N 辆车均匀分散到地图不同区域。
     * 网格划分: cols = ceil(sqrt(N)), rows = ceil(N / cols)
     * 每辆车分配到对应格子的中心点。
     */
    private List<Point> computeSpawnPoints(int mapWidth, int mapHeight, int carCount) {
        int cols = (int) Math.ceil(Math.sqrt(carCount));
        int rows = (int) Math.ceil((double) carCount / cols);
        int cellW = mapWidth / cols;
        int cellH = mapHeight / rows;

        LOG.info("网格分区: {}x{} 地图, {} 辆车 → {}列×{}行, 每格 {}×{}",
                mapWidth, mapHeight, carCount, cols, rows, cellW, cellH);

        List<Point> spawns = new ArrayList<>();
        int idx = 0;
        for (int row = 0; row < rows && idx < carCount; row++) {
            for (int col = 0; col < cols && idx < carCount; col++) {
                // 格子中心作为出生点
                int cx = col * cellW + cellW / 2;
                int cy = row * cellH + cellH / 2;
                // 确保在边界内
                cx = Math.max(1, Math.min(mapWidth - 2, cx));
                cy = Math.max(1, Math.min(mapHeight - 2, cy));
                spawns.add(new Point(cx, cy));
                idx++;
            }
        }
        return spawns;
    }

    /**
     * 构建禁区集合：所有出生点及其周围 3x3 区域不得生成障碍物
     */
    private Set<Point> buildForbiddenZone(List<Point> spawnPoints, int mapWidth, int mapHeight) {
        Set<Point> forbidden = new HashSet<>();
        for (Point spawn : spawnPoints) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = spawn.x + dx;
                    int ny = spawn.y + dy;
                    if (nx >= 0 && nx < mapWidth && ny >= 0 && ny < mapHeight) {
                        forbidden.add(new Point(nx, ny));
                    }
                }
            }
        }
        LOG.info("禁区集合: {} 个出生点, {} 个禁止放置格", spawnPoints.size(), forbidden.size());
        return forbidden;
    }

    private int generateObstacles(int mapWidth, int mapHeight, double obstacleDensity, Set<Point> forbidden) {
        int totalCells = mapWidth * mapHeight;
        int targetObstacleCount = (int) (totalCells * obstacleDensity);

        int placed = 0;
        int maxAttempts = targetObstacleCount * 20;
        int attempts = 0;

        while (placed < targetObstacleCount && attempts < maxAttempts) {
            int x = random.nextInt(mapWidth);
            int y = random.nextInt(mapHeight);
            Point p = new Point(x, y);
            attempts++;

            if (!forbidden.contains(p) && !blackboard.isBlocked(x, y)) {
                blackboard.setBlocked(x, y);
                placed++;
            }
        }

        if (placed < targetObstacleCount) {
            LOG.warn("障碍物生成不足: 目标={}, 实际={}", targetObstacleCount, placed);
        }
        LOG.info("已生成 {} 个障碍物 (目标: {})", placed, targetObstacleCount);
        return placed;
    }
}
