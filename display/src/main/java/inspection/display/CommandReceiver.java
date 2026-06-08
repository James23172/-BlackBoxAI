package inspection.display;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import inspection.common.client.MessageBusClient;
import inspection.common.model.MQMessage;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * WebSocket 服务器
 * 接收浏览器命令（SET_CONFIG / RESET），转发到 ControllerCmd 队列
 * 继承 broadcast(String) 用于 StateBroadcaster 推送状态给所有浏览器
 */
public class CommandReceiver extends WebSocketServer {
    private static final Logger LOG = LoggerFactory.getLogger(CommandReceiver.class);
    private static final String CONTROLLER_QUEUE = "ControllerCmd";

    private final MessageBusClient messageBus;

    public CommandReceiver(InetSocketAddress address, MessageBusClient messageBus) {
        super(address);
        this.messageBus = messageBus;
        setConnectionLostTimeout(30);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        LOG.info("浏览器已连接: {}, 当前连接数: {}",
                conn.getRemoteSocketAddress(), getConnections().size());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        LOG.info("浏览器已断开: {}, 当前连接数: {}",
                conn.getRemoteSocketAddress(), getConnections().size());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        LOG.debug("收到浏览器消息: {}", message);
        try {
            JSONObject json = JSON.parseObject(message);
            String type = json.getString("type");

            if ("SET_CONFIG".equals(type)) {
                // 转发到 ControllerCmd，Controller 的 tick loop 会转发 FORWARD_CONFIG 到 TaskConfigCmd
                JSONObject data = new JSONObject();
                data.put("mapWidth", json.getIntValue("mapWidth"));
                data.put("mapHeight", json.getIntValue("mapHeight"));
                data.put("carCount", json.getIntValue("carCount"));
                data.put("obstacleDensity", json.getDoubleValue("obstacleDensity"));

                MQMessage mqMsg = new MQMessage("SET_CONFIG", data);
                messageBus.sendToQueue(CONTROLLER_QUEUE, mqMsg);
                LOG.info("转发 SET_CONFIG → ControllerCmd: mapWidth={}, mapHeight={}, carCount={}",
                        data.get("mapWidth"), data.get("mapHeight"), data.get("carCount"));

            } else if ("RESET".equals(type)) {
                JSONObject data = new JSONObject();
                data.put("mapWidth", json.getIntValue("mapWidth"));
                data.put("mapHeight", json.getIntValue("mapHeight"));
                data.put("carCount", json.getIntValue("carCount"));
                data.put("obstacleDensity", json.getDoubleValue("obstacleDensity"));

                MQMessage mqMsg = new MQMessage("RESET", data);
                messageBus.sendToQueue(CONTROLLER_QUEUE, mqMsg);
                LOG.info("转发 RESET → ControllerCmd: mapWidth={}, mapHeight={}, carCount={}",
                        data.get("mapWidth"), data.get("mapHeight"), data.get("carCount"));

            } else if ("START".equals(type)) {
                JSONObject data = new JSONObject();
                data.put("active", true);
                data.put("mapWidth", json.getIntValue("mapWidth"));
                data.put("mapHeight", json.getIntValue("mapHeight"));
                data.put("carCount", json.getIntValue("carCount"));
                data.put("obstacleDensity", json.getDoubleValue("obstacleDensity"));
                MQMessage mqMsg = new MQMessage("SET_CONFIG", data);
                messageBus.sendToQueue(CONTROLLER_QUEUE, mqMsg);
                LOG.info("转发 START → ControllerCmd: mapWidth={}, mapHeight={}, carCount={}",
                        data.get("mapWidth"), data.get("mapHeight"), data.get("carCount"));

            } else if ("PAUSE".equals(type)) {
                JSONObject data = new JSONObject();
                data.put("active", false);
                MQMessage mqMsg = new MQMessage("SET_CONFIG", data);
                messageBus.sendToQueue(CONTROLLER_QUEUE, mqMsg);
                LOG.info("转发 PAUSE: active=false");

            } else {
                LOG.warn("未知浏览器命令: {}", type);
            }

        } catch (Exception e) {
            LOG.error("处理浏览器消息失败: {}", e.getMessage(), e);
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        LOG.error("WebSocket 错误: {}", ex.getMessage(), ex);
    }

    @Override
    public void onStart() {
        LOG.info("WebSocket 服务器已启动: {}", getAddress());
    }
}
