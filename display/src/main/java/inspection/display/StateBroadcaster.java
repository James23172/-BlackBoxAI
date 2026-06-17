package inspection.display;

import com.alibaba.fastjson2.JSON;
import inspection.common.client.BlackboardClient;
import inspection.common.client.MessageBusClient;
import inspection.common.config.ConfigConstants;
import inspection.common.model.CarState;
import inspection.common.model.Point;
import inspection.common.model.SimulationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 状态广播器
 * 订阅 UpdateView Fanout Exchange，收到 REFRESH_ALL 时读取黑板全量状态
 * 构建 SimulationState JSON 推送给所有连接的浏览器
 */
public class StateBroadcaster {
    private static final Logger LOG = LoggerFactory.getLogger(StateBroadcaster.class);

    private final BlackboardClient blackboard;
    private final MessageBusClient messageBus;
    private final CommandReceiver wsServer;

    public StateBroadcaster(BlackboardClient blackboard, MessageBusClient messageBus, CommandReceiver wsServer) {
        this.blackboard = blackboard;
        this.messageBus = messageBus;
        this.wsServer = wsServer;
    }

    public void start() {
        messageBus.subscribeFanout(ConfigConstants.EXCHANGE_UPDATE_VIEW, msg -> {
            if ("REFRESH_ALL".equals(msg.getCmd())) {
                try {
                    broadcastState();
                } catch (Exception e) {
                    LOG.error("广播状态失败: {}", e.getMessage(), e);
                }
            }
        });
        LOG.info("StateBroadcaster 已订阅 Exchange: {}", ConfigConstants.EXCHANGE_UPDATE_VIEW);
    }

    private void broadcastState() {
        // 1. 读取地图
        int mapWidth = blackboard.getMapWidth();
        int mapHeight = blackboard.getMapHeight();
        boolean[][] mapView = new boolean[mapHeight][mapWidth];
        for (int y = 0; y < mapHeight; y++) {
            for (int x = 0; x < mapWidth; x++) {
                mapView[y][x] = blackboard.isExplored(x, y);
            }
        }
        // 2. 读取障碍物列表
        List<Point> obstacles = blackboard.getAllBlocked();

        // 3. 读取所有小车状态
        List<CarState> carStates = new ArrayList<>();
        int carCount = 1;
        try {
            Map<String, String> config = blackboard.getTaskConfig();
            if (config != null && !config.isEmpty()) {
                carCount = Integer.parseInt(config.getOrDefault("carCount", "1"));
            }
        } catch (Exception e) {
            carCount = 1;
        }

        for (int i = 1; i <= carCount; i++) {
            String carId = String.format("Car%03d", i);
            CarState cs = new CarState(carId);
            cs.setStatus(blackboard.getCarStatus(carId));
            cs.setPosition(blackboard.getCarPosition(carId));
            cs.setTarget(blackboard.getCarTarget(carId));
            cs.setSteps(blackboard.getCarSteps(carId));
            cs.setBlockedTick(blackboard.getBlockedTick(carId));
            carStates.add(cs);
        }

        // 4. 计算探索率
        int exploredCount = blackboard.getExploredCount();
        int obstacleCount = blackboard.getObstacleCount();
        int totalExplorable = mapWidth * mapHeight - obstacleCount;
        double exploredRate = totalExplorable > 0
                ? (double) exploredCount / totalExplorable
                : 0.0;

        // 5. 构建 SimulationState
        SimulationState state = new SimulationState();
        state.setMapView(mapView);
        state.setMapWidth(mapWidth);
        state.setMapHeight(mapHeight);
        state.setObstacles(obstacles);
        state.setCars(carStates);
        state.setExploredRate(exploredRate);
        state.setTaskActive(blackboard.isTaskActive());
        state.setTick(System.currentTimeMillis());
        state.setCompleted(exploredRate >= 0.999);

        // 6. 广播给所有浏览器
        String json = JSON.toJSONString(state);
        wsServer.broadcast(json);
    }
}
