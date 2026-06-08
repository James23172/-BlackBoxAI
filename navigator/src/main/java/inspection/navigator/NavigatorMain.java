package inspection.navigator;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DeliverCallback;
import inspection.common.client.BlackboardClient;
import inspection.common.client.DistributedLock;
import inspection.common.client.MessageBusClient;
import inspection.common.config.ConfigConstants;
import inspection.common.model.MQMessage;
import inspection.common.model.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 导航器主类
 * 监听 NavigatorCmd 队列，处理 PLAN_ROUTE 请求
 */
public class NavigatorMain {
    private static final Logger LOG = LoggerFactory.getLogger(NavigatorMain.class);
    private static final String NAVIGATOR_QUEUE = "NavigatorCmd";
    private static final String CONTROLLER_QUEUE = "ControllerCmd";

    private BlackboardClient blackboard;
    private MessageBusClient messageBus;
    private final PathPlanner bfsPlanner = new BFSPlanner();
    private final PathPlanner aStarPlanner = new AStarPlanner();

    public static void main(String[] args) throws Exception {
        new NavigatorMain().start();
    }

    public void start() throws Exception {
        // 初始化黑板客户端（假设 BlackboardClient 构造为 (host, port)）
        blackboard = new BlackboardClient(ConfigConstants.REDIS_HOST, ConfigConstants.REDIS_PORT);

        // 初始化消息总线客户端：使用 5 参数构造
        String rabbitHost = ConfigConstants.RABBITMQ_HOST;
        int rabbitPort = ConfigConstants.RABBITMQ_PORT;
        String rabbitUser = getConfigOrDefault("RABBIT_USER", "guest");
        String rabbitPass = getConfigOrDefault("RABBIT_PASS", "guest");
        String rabbitVhost = getConfigOrDefault("RABBIT_VHOST", "/");
        messageBus = new MessageBusClient(rabbitHost, rabbitPort, rabbitUser, rabbitPass, rabbitVhost);

        Channel channel = messageBus.getChannel();   // 需确保 MessageBusClient 提供 getChannel()
        channel.queueDeclare(NAVIGATOR_QUEUE, true, false, false, null);
        channel.queueDeclare(CONTROLLER_QUEUE, true, false, false, null);

        LOG.info("导航器已启动，监听队列: {}", NAVIGATOR_QUEUE);

        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
            try {
                handlePlanRouteMessage(message);
            } catch (Exception e) {
                LOG.error("处理消息失败: {}", message, e);
            } finally {
                channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
            }
        };

        channel.basicConsume(NAVIGATOR_QUEUE, false, deliverCallback, consumerTag -> {});
    }

    /**
     * 从 ConfigConstants 读取常量，如果不存在则返回默认值
     */
    private String getConfigOrDefault(String fieldName, String defaultValue) {
        try {
            java.lang.reflect.Field field = ConfigConstants.class.getField(fieldName);
            Object value = field.get(null);
            return value != null ? value.toString() : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private void handlePlanRouteMessage(String messageJson) {
        MQMessage msg = JSON.parseObject(messageJson, MQMessage.class);
        String cmd = msg.getCmd();
        if (!"PLAN_ROUTE".equals(cmd)) {
            LOG.warn("忽略非 PLAN_ROUTE 命令: {}", cmd);
            return;
        }

        JSONObject data = JSONObject.parseObject(JSON.toJSONString(msg.getData()));
        String carId = data.getString("carId");
        String algorithm = data.getString("algorithm");

        LOG.info("收到路径规划请求: carId={}, algorithm={}", carId, algorithm);

        Point start = blackboard.getCarPosition(carId);
        Point target = blackboard.getCarTarget(carId);
        if (start == null || target == null) {
            LOG.error("小车 {} 位置或目标为空", carId);
            sendRoutePlannedResponse(carId, false, 0);
            return;
        }

        // 检查目标点是否被阻塞
        if (blackboard.isBlocked(target.getX(), target.getY())) {
            LOG.info("目标点 ({},{}) 被阻塞", target.getX(), target.getY());
            sendRoutePlannedResponse(carId, false, 0);
            return;
        }

        int width = blackboard.getMapWidth();
        int height = blackboard.getMapHeight();
        boolean[][] obstacles = blackboard.getMapBlocked();

        PathPlanner planner;
        if ("A_STAR".equals(algorithm)) {
            planner = aStarPlanner;
        } else {
            planner = bfsPlanner;
        }

        List<Point> path = planner.plan(start, target, obstacles, width, height);
        if (path == null || path.isEmpty()) {
            LOG.warn("无法找到路径: {} -> {}", start, target);
            sendRoutePlannedResponse(carId, false, 0);
            return;
        }

        // 移除起点（小车已经在该位置）
        if (!path.isEmpty() && path.get(0).equals(start)) {
            path = path.subList(1, path.size());
        }

        // 加锁写入路径
        DistributedLock carLock = blackboard.getCarLock(carId);
        if (carLock.tryLock()) {
            try {
                blackboard.clearRoute(carId);
                blackboard.pushRoute(carId, path);
                LOG.info("路径规划成功: carId={}, 步数={}", carId, path.size());
                sendRoutePlannedResponse(carId, true, path.size());
            } finally {
                carLock.unlock();
            }
        } else {
            LOG.warn("获取小车 {} 锁失败", carId);
            sendRoutePlannedResponse(carId, false, 0);
        }
    }

    private void sendRoutePlannedResponse(String carId, boolean routeFound, int routeLength) {
        JSONObject responseData = new JSONObject();
        responseData.put("carId", carId);
        responseData.put("routeFound", routeFound);
        if (routeFound) {
            responseData.put("routeLength", routeLength);
        }
        MQMessage response = new MQMessage();
        response.setCmd("ROUTE_PLANNED");
        response.setData(responseData);
        response.setTimestamp(System.currentTimeMillis());

        messageBus.sendToQueue(CONTROLLER_QUEUE, response);
        LOG.info("发送 ROUTE_PLANNED: carId={}, routeFound={}", carId, routeFound);
    }
}