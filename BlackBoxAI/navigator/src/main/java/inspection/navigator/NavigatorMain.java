package inspection.navigator;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DeliverCallback;
import inspection.common.client.BlackboardClient;
import inspection.common.client.MessageBusClient;
import inspection.common.model.MQMessage;
import inspection.common.model.Point;
import inspection.common.config.ConfigConstants;
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
    private PathPlanner bfsPlanner;
    private PathPlanner aStarPlanner;

    public static void main(String[] args) throws Exception {
        new NavigatorMain().start();
    }

    public void start() throws Exception {
        // 初始化黑板客户端
        blackboard = new BlackboardClient(ConfigConstants.REDIS_HOST, ConfigConstants.REDIS_PORT);
        // 初始化消息总线客户端
        messageBus = new MessageBusClient(ConfigConstants.RABBIT_HOST, ConfigConstants.RABBIT_PORT);

        bfsPlanner = new BFSPlanner();
        aStarPlanner = new AStarPlanner();

        // 声明并绑定队列（由 TaskConfigurator 负责声明，这里仅确保队列存在）
        Channel channel = messageBus.getChannel();  // 需在 MessageBusClient 中提供 getChannel()
        channel.queueDeclare(NAVIGATOR_QUEUE, true, false, false, null);
        channel.queueDeclare(CONTROLLER_QUEUE, true, false, false, null);

        LOG.info("导航器已启动，监听队列: {}", NAVIGATOR_QUEUE);

        // 设置消息回调
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
     * 处理 PLAN_ROUTE 消息
     * 消息格式: {"cmd":"PLAN_ROUTE","data":{"carId":"Car001","algorithm":"BFS"},"timestamp":123}
     */
    private void handlePlanRouteMessage(String messageJson) {
        MQMessage msg = JSON.parseObject(messageJson, MQMessage.class);
        if (!"PLAN_ROUTE".equals(msg.getCmd())) {
            LOG.warn("忽略非 PLAN_ROUTE 命令: {}", msg.getCmd());
            return;
        }

        JSONObject data = (JSONObject) msg.getData();
        String carId = data.getString("carId");
        String algorithm = data.getString("algorithm");
        boolean useAStar = "A_STAR".equalsIgnoreCase(algorithm);

        LOG.info("收到路径规划请求: carId={}, algorithm={}", carId, algorithm);

        // 1. 获取小车位置和目标
        Point start = blackboard.getCarPosition(carId);
        Point target = blackboard.getCarTarget(carId);
        if (start == null || target == null) {
            LOG.error("小车 {} 位置或目标为空: start={}, target={}", carId, start, target);
            sendRoutePlannedResponse(carId, false, 0);
            return;
        }

        // 2. 检查目标点是否被阻塞
        if (blackboard.isBlocked(target.getX(), target.getY())) {
            LOG.info("目标点 ({},{}) 被阻塞，无法规划路径", target.getX(), target.getY());
            sendRoutePlannedResponse(carId, false, 0);
            return;
        }

        // 3. 获取地图障碍物信息
        boolean[][] mapBlock = blackboard.getMapView();  // 注意: getMapView 返回 boolean[][], true=已探索或障碍? 根据设计，我们需要区分障碍物。
        // 根据 common 定义，isBlocked 用于判断障碍，这里单独获取障碍矩阵
        int width = ConfigConstants.MAP_WIDTH;
        int height = ConfigConstants.MAP_HEIGHT;
        boolean[][] obstacles = new boolean[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                obstacles[y][x] = blackboard.isBlocked(x, y);
            }
        }

        // 4. 规划路径
        PathPlanner planner = useAStar ? aStarPlanner : bfsPlanner;
        List<Point> path = planner.plan(start, target, obstacles, width, height);

        if (path == null || path.isEmpty()) {
            LOG.warn("无法找到从 {} 到 {} 的路径", start, target);
            sendRoutePlannedResponse(carId, false, 0);
            return;
        }

        // 路径不包含起点，只包含从下一步到目标点的序列（小车已位于起点）
        // 注意：BFS/A* 返回的路径通常包含起点，我们需要 pop 掉起点
        if (!path.isEmpty() && path.get(0).equals(start)) {
            path.remove(0);
        }

        // 5. 加锁写入 Redis (LPUSH 整条路径)
        if (blackboard.getCarLock(carId).tryLock()) {
            try {
                blackboard.clearRoute(carId);           // 清空旧路径
                blackboard.pushRoute(carId, path);      // LPUSH 整条路径
                LOG.info("路径规划成功: carId={}, pathLength={}, 首步={}", carId, path.size(),
                        path.isEmpty() ? "无" : path.get(0));
                sendRoutePlannedResponse(carId, true, path.size());
            } finally {
                blackboard.getCarLock(carId).unlock();
            }
        } else {
            LOG.warn("获取小车 {} 锁失败，放弃写入路径", carId);
            sendRoutePlannedResponse(carId, false, 0);
        }
    }

    /**
     * 发送 ROUTE_PLANNED 响应到 ControllerCmd 队列
     * @param routeFound true 表示找到路径，false 表示未找到
     * @param routeLength 路径长度（仅当 routeFound=true 时有效）
     */
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

        messageBus.sendToQueue(CONTROLLER_QUEUE, response);  // 假设 MessageBusClient 有此方法
        LOG.info("已发送 ROUTE_PLANNED: carId={}, routeFound={}", carId, routeFound);
    }
}
