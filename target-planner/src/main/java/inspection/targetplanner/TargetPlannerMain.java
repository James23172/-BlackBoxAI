package inspection.targetplanner;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DeliverCallback;
import inspection.common.client.BlackboardClient;
import inspection.common.client.MessageBusClient;
import inspection.common.config.ConfigConstants;
import inspection.common.model.MQMessage;
import inspection.common.model.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 目标规划器
 * 监听 TargetPlannerCmd 队列，处理 ASSIGN_TARGET 请求
 * 扫描未探索区域，贪心分配最近目标
 */
public class TargetPlannerMain {
    private static final Logger LOG = LoggerFactory.getLogger(TargetPlannerMain.class);
    private static final String TARGET_PLANNER_QUEUE = "TargetPlannerCmd";
    private static final String CONTROLLER_QUEUE = "ControllerCmd";

    private BlackboardClient blackboard;
    private MessageBusClient messageBus;

    public static void main(String[] args) throws Exception {
        new TargetPlannerMain().start();
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
        channel.queueDeclare(TARGET_PLANNER_QUEUE, true, false, false, null);
        channel.queueDeclare(CONTROLLER_QUEUE, true, false, false, null);

        LOG.info("目标规划器已启动，监听队列: {}", TARGET_PLANNER_QUEUE);

        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
            try {
                handleAssignTarget(message);
            } catch (Exception e) {
                LOG.error("处理消息失败: {}", message, e);
            } finally {
                channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
            }
        };

        channel.basicConsume(TARGET_PLANNER_QUEUE, false, deliverCallback, consumerTag -> {});
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

    private void handleAssignTarget(String messageJson) {
        MQMessage msg = JSON.parseObject(messageJson, MQMessage.class);
        String cmd = msg.getCmd();
        if (!"ASSIGN_TARGET".equals(cmd)) {
            LOG.warn("忽略非 ASSIGN_TARGET 命令: {}", cmd);
            return;
        }

        JSONObject data = JSONObject.parseObject(JSON.toJSONString(msg.getData()));
        String carId = data.getString("carId");
        LOG.info("收到目标分配请求: carId={}", carId);

        int mapWidth = blackboard.getMapWidth();
        int mapHeight = blackboard.getMapHeight();

        // 1. 扫描所有未探索且无障碍的格子（批量获取 bitmap，避免 N² Redis 调用）
        boolean[][] explored = blackboard.getMapView();
        boolean[][] blocked = blackboard.getMapBlocked();
        List<Point> unexplored = new ArrayList<>();
        for (int y = 0; y < mapHeight; y++) {
            for (int x = 0; x < mapWidth; x++) {
                if (!explored[y][x] && !blocked[y][x]) {
                    unexplored.add(new Point(x, y));
                }
            }
        }

        if (unexplored.isEmpty()) {
            LOG.info("无未探索区域，无法分配目标");
            sendTargetAssignedResponse(carId, -1, -1);
            return;
        }

        // 2. 获取小车位置
        Point carPos = blackboard.getCarPosition(carId);
        if (carPos == null) {
            LOG.error("小车 {} 位置为空", carId);
            return;
        }

        // 3. 排除已被其他车分配为目标的点
        Set<Point> otherTargets = new HashSet<>();
        for (String cid : getKnownCarIds()) {
            if (!cid.equals(carId)) {
                Point t = blackboard.getCarTarget(cid);
                if (t != null) otherTargets.add(t);
            }
        }

        List<Point> candidates = new ArrayList<>();
        for (Point p : unexplored) {
            if (!otherTargets.contains(p)) {
                candidates.add(p);
            }
        }

        // 4. 距离 >= 10 规则（剩余 > 1 时）
        if (candidates.size() > 1) {
            List<Point> farEnough = new ArrayList<>();
            for (Point p : candidates) {
                if (p.distanceTo(carPos) >= 10) {
                    farEnough.add(p);
                }
            }
            if (!farEnough.isEmpty()) {
                candidates = farEnough;
            }
        }

        // 5. 选择距离最近的点
        Point best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Point p : candidates) {
            int dist = p.distanceTo(carPos);
            if (dist < bestDist) {
                bestDist = dist;
                best = p;
            }
        }

        if (best == null) {
            LOG.warn("无法筛选出有效目标");
            return;
        }

        // 6. 写入目标
        blackboard.setCarTarget(carId, best.getX(), best.getY());
        LOG.info("已分配目标: carId={}, target=({},{})", carId, best.getX(), best.getY());

        // 7. 发送 TARGET_ASSIGNED
        sendTargetAssignedResponse(carId, best.getX(), best.getY());
    }

    private List<String> getKnownCarIds() {
        List<String> ids = new ArrayList<>();
        try {
            Map<String, String> config = blackboard.getTaskConfig();
            if (config != null && !config.isEmpty()) {
                int carCount = Integer.parseInt(config.getOrDefault("carCount", "1"));
                for (int i = 1; i <= carCount; i++) {
                    ids.add(String.format("Car%03d", i));
                }
            } else {
                ids.add(ConfigConstants.CAR_ID);
            }
        } catch (Exception e) {
            ids.add(ConfigConstants.CAR_ID);
        }
        return ids;
    }

    private void sendTargetAssignedResponse(String carId, int targetX, int targetY) {
        JSONObject assignedCar = new JSONObject();
        assignedCar.put("carId", carId);
        assignedCar.put("targetX", targetX);
        assignedCar.put("targetY", targetY);

        JSONArray assignedCars = new JSONArray();
        assignedCars.add(assignedCar);

        JSONObject responseData = new JSONObject();
        responseData.put("assignedCars", assignedCars);

        MQMessage response = new MQMessage();
        response.setCmd("TARGET_ASSIGNED");
        response.setData(responseData);
        response.setTimestamp(System.currentTimeMillis());

        messageBus.sendToQueue(CONTROLLER_QUEUE, response);
        LOG.info("发送 TARGET_ASSIGNED: carId={}, target=({},{})", carId, targetX, targetY);
    }
}
