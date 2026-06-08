package inspection.controller;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import inspection.common.client.BlackboardClient;
import inspection.common.client.MessageBusClient;
import inspection.common.config.ConfigConstants;
import inspection.common.enums.CarStatus;
import inspection.common.enums.CommandType;
import inspection.common.model.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static inspection.common.enums.CommandType.*;

public class ControllerAgent {
    private static final Logger log = LoggerFactory.getLogger(ControllerAgent.class);

    private final BlackboardClient bb;
    private final MessageBusClient mq;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean running = false;
    private long tickCount = 0;
    private final long tickIntervalMs = ConfigConstants.TICK_INTERVAL_MS;
    private final Set<String> pendingTargetRequests = new HashSet<>();
    private final Set<String> pendingRouteRequests = new HashSet<>();

    public ControllerAgent(BlackboardClient bb, MessageBusClient mq) {
        this.bb = bb;
        this.mq = mq;
    }

    public void start() {
        // 使用 subscribeText 接收原始 JSON 字符串（适配 handleReply(String)）
        try {
            mq.subscribeText(ConfigConstants.QUEUE_CONTROLLER_CMD, this::handleReply);
        } catch (Exception e) {
            log.error("订阅队列失败", e);
        }
        running = true;
        scheduler.scheduleAtFixedRate(this::tick, 0, tickIntervalMs, TimeUnit.MILLISECONDS);
        log.info("Controller 节拍循环已启动，间隔={}ms", tickIntervalMs);
    }

    public void stop() {
        running = false;
        scheduler.shutdown();
    }

    private void tick() {
        if (!running) return;

        try {
            if (!bb.isTaskActive()) {
                log.debug("任务未激活，等待...");
                return;
            }

            double explored = getExploredPercent();
            if (explored >= 99.9) {
                log.info("🏁 巡检完成！探索率 = {}%", explored);
                running = false;
                scheduler.shutdown();
                return;
            }

            List<String> carIds = getAllCarIds();
            Map<String, CarStatus> statusMap = new HashMap<>();
            for (String carId : carIds) {
                statusMap.put(carId, bb.getCarStatus(carId));
            }

            for (String carId : carIds) {
                CarStatus status = statusMap.get(carId);
                switch (status) {
                    case IDLE:
                        if (!pendingTargetRequests.contains(carId)) {
                            requestTargetAssignment(carId);
                            pendingTargetRequests.add(carId);
                        }
                        break;
                    case WAITING_ROUTE:
                        if (!pendingRouteRequests.contains(carId)) {
                            requestRoutePlan(carId);
                            pendingRouteRequests.add(carId);
                        }
                        break;
                    case READY:
                        break;
                    case MOVING:
                        bb.setCarStatus(carId, CarStatus.READY);
                        log.warn("小车 {} 状态异常(MOVING)，已重置为 READY", carId);
                        break;
                    case BLOCKED:
                        handleBlockedTimeout(carId);
                        break;
                    default:
                        log.warn("未知状态: {}", status);
                }
            }

            List<String> readyCars = new ArrayList<>();
            for (String carId : carIds) {
                if (bb.getCarStatus(carId) == CarStatus.READY) {
                    readyCars.add(carId);
                }
            }
            if (!readyCars.isEmpty()) {
                broadcastTickMove(readyCars);
            }

            broadcastViewRefresh();

            tickCount++;
            if (tickCount % 20 == 0) {
                log.info("节拍 #{} 完成，探索率: {}%", tickCount, explored);
            }
        } catch (Exception e) {
            log.error("节拍执行出错", e);
        }
    }

    // ========== 临时包装（若 BlackboardClient 缺少方法可暂时这样用）==========
    private double getExploredPercent() {
        try {
            return bb.getExploredPercent();
        } catch (Exception e) {
            return 0.0;
        }
    }

    private List<String> getAllCarIds() {
        try {
            return bb.getAllCarIds();
        } catch (Exception e) {
            return Arrays.asList("Car001", "Car002", "Car003", "Car004", "Car005");
        }
    }

    private String getRouteAlgorithm() {
        try {
            return bb.getRouteAlgorithm();
        } catch (Exception e) {
            return "BFS";
        }
    }
    // ================================================================

    private void requestTargetAssignment(String carId) {
        JSONObject data = new JSONObject();
        data.put("carId", carId);
        sendCommand(ASSIGN_TARGET, data, ConfigConstants.QUEUE_TARGET_PLANNER_CMD);
    }

    private void requestRoutePlan(String carId) {
        String algorithm = getRouteAlgorithm();
        JSONObject data = new JSONObject();
        data.put("carId", carId);
        data.put("algorithm", algorithm);
        sendCommand(PLAN_ROUTE, data, ConfigConstants.QUEUE_NAVIGATOR_CMD);
    }

    private void broadcastTickMove(List<String> carIds) {
        JSONObject data = new JSONObject();
        for (String carId : carIds) {
            sendCommand(TICK_MOVE, data, ConfigConstants.carQueueName(carId));
        }
        log.debug("已发送 TICK_MOVE 给 {} 辆车", carIds.size());
    }

    private void broadcastViewRefresh() {
        JSONObject data = new JSONObject();
        data.put("tick", tickCount);
        sendCommand(REFRESH_ALL, data, ConfigConstants.EXCHANGE_UPDATE_VIEW, true);
    }

    private void handleBlockedTimeout(String carId) {
        long blockedTick;
        try {
            blockedTick = bb.getCarBlockedTick(carId);
        } catch (Exception e) {
            blockedTick = 0;
        }
        long diff = tickCount - blockedTick;
        if (diff >= ConfigConstants.BLOCKED_TIMEOUT_TICKS) {
            log.info("小车 {} 已阻塞 {} 个节拍，清空路径/目标，转为 IDLE", carId, diff);
            try {
                bb.clearCarRoute(carId);
                bb.clearCarTarget(carId);
            } catch (Exception e) {
                log.warn("清空路径/目标失败", e);
            }
            bb.setCarStatus(carId, CarStatus.IDLE);
        }
    }

    private void handleReply(String message) {
        JSONObject json = JSONObject.parseObject(message);
        String cmd = json.getString("cmd");
        JSONObject data = json.getJSONObject("data");
        try {
            CommandType type = CommandType.valueOf(cmd);
            switch (type) {
                case TASK_READY:
                    log.info("收到 TASK_READY，仿真任务已就绪");
                    break;
                case TARGET_ASSIGNED:
                    handleTargetAssigned(data);
                    break;
                case ROUTE_PLANNED:
                    handleRoutePlanned(data);
                    break;
                case MOVED:
                    String movedCar = data.getString("carId");
                    log.debug("小车 {} 移动至 ({},{})", movedCar, data.getInteger("x"), data.getInteger("y"));
                    break;
                case CAR_BLOCKED:          // 枚举中为 CAR_BLOCKED，不是 BLOCKED
                    String blockedCar = data.getString("carId");
                    log.warn("小车 {} 报告受阻", blockedCar);
                    break;
                case ROUTE_DONE:
                    String doneCar = data.getString("carId");
                    pendingTargetRequests.remove(doneCar);
                    pendingRouteRequests.remove(doneCar);
                    log.info("小车 {} 已完成路径", doneCar);
                    break;
                case SET_CONFIG:
                    forwardToTaskConfigurator(FORWARD_CONFIG, data);
                    break;
                case RESET:
                    // 枚举中没有 FORWARD_RESET，暂时使用 FORWARD_CONFIG 并在 data 中加 reset 标记
                    data.put("reset", true);
                    forwardToTaskConfigurator(FORWARD_CONFIG, data);
                    break;
                default:
                    log.warn("未处理的消息类型: {}", cmd);
            }
        } catch (IllegalArgumentException e) {
            log.warn("未知命令类型: {}", cmd);
        } catch (Exception e) {
            log.error("处理回复消息异常: {}", message, e);
        }
    }

    private void handleTargetAssigned(JSONObject data) {
        JSONArray assigned = data.getJSONArray("assignedCars");
        for (int i = 0; i < assigned.size(); i++) {
            JSONObject item = assigned.getJSONObject(i);
            String carId = item.getString("carId");
            int targetX = item.getIntValue("targetX");
            int targetY = item.getIntValue("targetY");
            try {
                bb.setCarTarget(carId, new Point(targetX, targetY));
            } catch (Exception e) {
                log.warn("设置目标失败", e);
            }
            bb.setCarStatus(carId, CarStatus.WAITING_ROUTE);
            pendingTargetRequests.remove(carId);
            log.info("小车 {} 获得目标 ({}, {})，状态 → WAITING_ROUTE", carId, targetX, targetY);
        }
    }

    private void handleRoutePlanned(JSONObject data) {
        String carId = data.getString("carId");
        boolean routeFound = data.getBooleanValue("routeFound");
        pendingRouteRequests.remove(carId);
        if (routeFound) {
            bb.setCarStatus(carId, CarStatus.READY);
            log.info("小车 {} 路径规划成功，状态 → READY", carId);
        } else {
            bb.setCarStatus(carId, CarStatus.IDLE);
            try {
                bb.clearCarTarget(carId);
            } catch (Exception e) {
                log.warn("清空目标失败", e);
            }
            log.warn("小车 {} 路径规划失败，无可用路径，状态 → IDLE", carId);
        }
    }

    private void forwardToTaskConfigurator(CommandType cmd, JSONObject data) {
        sendCommand(cmd, data, ConfigConstants.QUEUE_TASK_CONFIG_CMD);
        log.info("转发命令 {} 到 TaskConfigurator", cmd);
    }

    private void sendCommand(CommandType cmd, JSONObject data, String destination) {
        sendCommand(cmd, data, destination, false);
    }

    private void sendCommand(CommandType cmd, JSONObject data, String destination, boolean fanout) {
        JSONObject msg = new JSONObject();
        msg.put("cmd", cmd.name());
        msg.put("data", data);
        msg.put("timestamp", System.currentTimeMillis());
        try {
            if (fanout) {
                mq.fanoutPublish(destination, msg.toJSONString());
            } else {
                mq.publish(destination, msg.toJSONString());
            }
        } catch (Exception e) {
            log.error("发送消息失败: {} -> {}", cmd, destination, e);
        }
    }
}