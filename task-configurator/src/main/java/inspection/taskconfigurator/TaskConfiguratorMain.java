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
    private static final String CONTROLLER_QUEUE = "ControllerCmd";

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
        channel.queueDeclare(CONTROLLER_QUEUE, true, false, false, null);

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

        channel.basicConsume(TASK_CONFIG_QUEUE, false, deliverCallback, consumerTag -> {});
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

        // 2. 生成随机障碍物
        int obstacleCount = generateObstacles(mapWidth, mapHeight, obstacleDensity, carCount);

        // 3. 初始化所有小车（分散起始位置避免拥堵）
        int[][] startOffsets = {{0,0}, {2,0}, {0,2}, {-2,0}, {0,-2}};
        List<String> carIds = new ArrayList<>();
        for (int i = 1; i <= carCount; i++) {
            String carId = String.format("Car%03d", i);
            carIds.add(carId);
            int[] off = startOffsets[(i - 1) % startOffsets.length];
            int startX = Math.max(1, Math.min(mapWidth - 2, mapWidth / 2 + off[0]));
            int startY = Math.max(1, Math.min(mapHeight - 2, mapHeight / 2 + off[1]));
            blackboard.setCarPosition(carId, startX, startY);
            blackboard.setCarStatus(carId, CarStatus.IDLE);
            blackboard.setCarSteps(carId, 0);
            LOG.info("已初始化小车: carId={}, position=({},{})", carId, startX, startY);
        }

        // 4. 写入任务配置
        Map<String, String> config = new HashMap<>();
        config.put("mapWidth", String.valueOf(mapWidth));
        config.put("mapHeight", String.valueOf(mapHeight));
        config.put("carCount", String.valueOf(carCount));
        config.put("cars", JSON.toJSONString(carIds));
        config.put("obstacleDensity", String.valueOf(obstacleDensity));
        boolean startActive = data.getBooleanValue("active", false);
        config.put("taskActive", startActive ? "1" : "0");
        blackboard.setTaskConfig(config);

        // 5. 声明系统队列和 Exchange
        try {
            messageBus.declareAllSystemQueues();
        } catch (IOException e) {
            LOG.error("声明系统队列失败: {}", e.getMessage(), e);
        }

        // 6. 发送 TASK_READY
        JSONObject responseData = new JSONObject();
        responseData.put("mapWidth", mapWidth);
        responseData.put("mapHeight", mapHeight);
        responseData.put("carCount", carCount);
        responseData.put("obstacleCount", obstacleCount);

        MQMessage response = new MQMessage();
        response.setCmd("TASK_READY");
        response.setData(responseData);
        response.setTimestamp(System.currentTimeMillis());

        messageBus.sendToQueue(CONTROLLER_QUEUE, response);
        LOG.info("发送 TASK_READY: mapWidth={}, mapHeight={}, carCount={}, obstacleCount={}",
                mapWidth, mapHeight, carCount, obstacleCount);
    }

    private int generateObstacles(int mapWidth, int mapHeight, double obstacleDensity, int carCount) {
        int totalCells = mapWidth * mapHeight;
        int targetObstacleCount = (int) (totalCells * obstacleDensity);

        // 避开小车初始位置（中心）的 3x3 区域
        int centerX = mapWidth / 2;
        int centerY = mapHeight / 2;
        Set<Point> forbidden = new HashSet<>();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int nx = centerX + dx;
                int ny = centerY + dy;
                if (nx >= 0 && nx < mapWidth && ny >= 0 && ny < mapHeight) {
                    forbidden.add(new Point(nx, ny));
                }
            }
        }

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
