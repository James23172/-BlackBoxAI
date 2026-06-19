package inspection.display;

import com.alibaba.fastjson2.JSON;
import inspection.common.client.BlackboardClient;
import inspection.common.client.MessageBusClient;
import inspection.common.config.ConfigConstants;
import inspection.common.model.CarState;
import inspection.common.model.Point;
import inspection.common.model.SimulationState;
import org.java_websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 状态广播器
 * 订阅 UpdateView Fanout Exchange，收到 REFRESH_ALL 时读取黑板全量状态
 * 构建 SimulationState JSON 推送给所有连接的浏览器。
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

    /** 向单个 WebSocket 客户端发送当前状态快照（新连接时调用） */
    public void sendCurrentState(WebSocket conn) {
        try {
            String json = JSON.toJSONString(buildState());
            conn.send(json);
            LOG.debug("已发送当前状态到新客户端: {}", conn.getRemoteSocketAddress());
        } catch (Exception e) {
            LOG.warn("发送初始状态失败: {}", e.getMessage());
        }
    }

    private SimulationState buildState() {
        int mapWidth = blackboard.getMapWidth();
        int mapHeight = blackboard.getMapHeight();
        boolean[][] mapView = blackboard.getMapView();
        List<Point> obstacles = blackboard.getAllBlocked();

        List<CarState> carStates = new ArrayList<>();
        // 动态获取所有已注册的小车 ID（支持运行时增减）
        List<String> carIds = blackboard.getAllCarIds();
        for (String carId : carIds) {
            CarState cs = new CarState(carId);
            cs.setStatus(blackboard.getCarStatus(carId));
            cs.setPosition(blackboard.getCarPosition(carId));
            cs.setTarget(blackboard.getCarTarget(carId));
            cs.setSteps(blackboard.getCarSteps(carId));
            cs.setBlockedTick(blackboard.getBlockedTick(carId));
            carStates.add(cs);
        }

        int exploredCount = blackboard.getExploredCount();
        int obstacleCount = blackboard.getObstacleCount();
        int totalExplorable = mapWidth * mapHeight - obstacleCount;
        double exploredRate = totalExplorable > 0
                ? (double) exploredCount / totalExplorable
                : 0.0;

        SimulationState state = new SimulationState();
        state.setMapView(mapView);
        state.setMapWidth(mapWidth);
        state.setMapHeight(mapHeight);
        state.setObstacles(obstacles);
        state.setCars(carStates);
        state.setExploredRate(exploredRate);
        state.setTaskActive(blackboard.isTaskActive());
        state.setTick(0);
        state.setCompleted(exploredRate >= 0.999);
        return state;
    }

    private void broadcastState() {
        String json = JSON.toJSONString(buildState());
        wsServer.broadcast(json);
    }
}
