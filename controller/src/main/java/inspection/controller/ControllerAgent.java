package inspection.controller;

import com.alibaba.fastjson2.JSONObject;
import inspection.common.client.BlackboardClient;
import inspection.common.client.DistributedLock;
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

/**
 * Controller 核心调度器 — 纯 Redis taskQueue 驱动 + 周期性广播
 *
 * 架构 (C2 模式 + 黑板风格):
 *   1. 不监听任何 RabbitMQ 队列
 *   2. 专用线程 BLPOP 阻塞等待 Redis taskQueue 新任务 → 即时处理
 *   3. 根据任务类型分发:
 *        ROUTE_NEEDED  → 发 NAVIGATE 到 Navigator4CarID
 *        MOVE_READY    → 发 MOVE_STEP 到 Car:{id}
 *        BLOCKED       → 超时处理
 *        START/PAUSE   → 控制 taskActive
 *        SET_CONFIG    → 转发到 TaskConfigurator
 *        RESET         → 转发到 TaskConfigurator
 *   4. 每 500ms 广播 REFRESH_ALL 给 Display（与任务处理解耦）
 */
public class ControllerAgent {
    private static final Logger log = LoggerFactory.getLogger(ControllerAgent.class);

    private final BlackboardClient bb;
    private final MessageBusClient mq;
    private final ScheduledExecutorService broadcastScheduler = Executors.newSingleThreadScheduledExecutor();
    private final Object taskWakeLock = new Object();
    private final int instanceId;
    private final int totalInstances;
    private volatile boolean running = false;
    private volatile boolean taskActive = false;
    private volatile boolean userActivated = false;
    private Thread taskProcessor;
    private long tickCount = 0;

    public ControllerAgent(BlackboardClient bb, MessageBusClient mq) {
        this(bb, mq, 0, 1);
    }

    public ControllerAgent(BlackboardClient bb, MessageBusClient mq,
                           int instanceId, int totalInstances) {
        this.bb = bb;
        this.mq = mq;
        this.instanceId = instanceId;
        this.totalInstances = totalInstances;
    }

    // ==================== 启动 / 停止 ====================

    public void start() {
        running = true;

        // 启动事件驱动任务处理线程（BLPOP 阻塞等待 Redis taskQueue）
        taskProcessor = new Thread(this::taskProcessLoop, "controller-task-processor");
        taskProcessor.setDaemon(true);
        taskProcessor.start();

        // 启动周期性广播调度器（每 500ms 刷新 Display）
        broadcastScheduler.scheduleAtFixedRate(this::broadcastTick,
                ConfigConstants.TICK_INTERVAL_MS, ConfigConstants.TICK_INTERVAL_MS, TimeUnit.MILLISECONDS);

        log.info("Controller 已启动: 纯 Redis taskQueue 驱动 + {}ms 周期性广播", ConfigConstants.TICK_INTERVAL_MS);
    }

    public void stop() {
        running = false;
        taskActive = false;
        wakeTaskProcessor();
        broadcastScheduler.shutdown();
        if (taskProcessor != null) {
            try { taskProcessor.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    // ==================== 任务处理循环 ====================

    private void taskProcessLoop() {
        log.info("🔧 任务处理线程启动 (纯 Redis BLPOP), taskActive={}", taskActive);
        while (running) {
            try {
                if (!taskActive) {
                    synchronized (taskWakeLock) {
                        if (!taskActive && running) {
                            taskWakeLock.wait(1000);
                        }
                    }
                    // 每次超时唤醒后也检查 Redis 中的 taskActive，允许 Display 通过 Redis 唤醒 Controller
                    if (!taskActive && running) {
                        try {
                            boolean redisActive = bb.isTaskActive();
                            if (redisActive) {
                                log.info("🔓 检测到 Redis taskActive=true, 激活任务处理器");
                                taskActive = true;
                            }
                        } catch (Exception e) { /* ignore, will retry next cycle */ }
                    }
                    continue;
                }

                Map<String, String> task = bb.blockingPopTask(2);
                if (task != null) {
                    processTask(task);
                    // 连续清空剩余任务
                    Map<String, String> next;
                    while ((next = bb.popTask()) != null) {
                        processTask(next);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("任务处理异常", e);
                try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        log.info("任务处理线程退出");
    }

    private void wakeTaskProcessor() {
        synchronized (taskWakeLock) {
            taskWakeLock.notifyAll();
        }
    }

    // ==================== 任务分发 ====================

    private void processTask(Map<String, String> task) {
        String type = task.get("type");
        String carId = task.get("carId");

        // 全局任务（无 carId）
        switch (type) {
            case "START":
                handleStartTask();
                return;
            case "PAUSE":
                handlePauseTask();
                return;
            case "SET_CONFIG":
                handleSetConfigTask(task);
                return;
            case "RESET":
                handleResetTask(task);
                return;
        }

        // 车辆相关任务
        if (carId == null) {
            log.warn("taskQueue 任务缺少 carId: {}", task);
            return;
        }

        // 多实例分片：只处理分配给本实例的车辆
        if (!isMyCar(getAllCarIds(), carId)) return;

        switch (type) {
            case "ROUTE_NEEDED":
                log.info("🎯 [ROUTE_NEEDED] 发 NAVIGATE → Navigator4CarID, carId={}", carId);
                requestNavigate(carId);
                break;

            case "MOVE_READY":
                log.info("🚗 [MOVE_READY] 发 MOVE_STEP → Car:{}, carId={}", carId);
                JSONObject moveData = new JSONObject();
                moveData.put("carId", carId);
                sendCommand(CommandType.MOVE_STEP, moveData, ConfigConstants.carQueueName(carId));
                break;

            case "ADD_CAR":
                log.info("➕ [ADD_CAR] 新增小车: carId={}，触发导航", carId);
                requestNavigate(carId);
                break;

            case "REMOVE_CAR":
                log.info("➖ [REMOVE_CAR] 移除小车: carId={}，已从系统中注销", carId);
                break;

            case "BLOCKED":
                handleBlockedTimeout(carId);
                break;

            default:
                log.warn("taskQueue 未知任务类型: {}", type);
        }
    }

    // ==================== 全局任务处理 (START/PAUSE/SET_CONFIG/RESET) ====================

    private void handleStartTask() {
        // 检查 Redis 中是否已有任务配置（而非靠 queueLen 计数，因为 START 本身刚被出队）
        Map<String, String> existingConfig = bb.getTaskConfig();
        boolean hasConfig = existingConfig != null && !existingConfig.isEmpty();
        log.info("🚀 Start: hasConfig={}, redisTaskActive={}, 当前taskActive={}",
                hasConfig, bb.isTaskActive(), taskActive);
        if (!hasConfig) {
            // 从未初始化过 → 触发完整初始化（TaskConfigurator 生成地图/障碍物/放置小车）
            log.warn("⚠️ 配置不存在，触发完整初始化");
            userActivated = true;
            JSONObject initData = new JSONObject();
            initData.put("mapWidth", bb.getMapWidth());
            initData.put("mapHeight", bb.getMapHeight());
            initData.put("carCount", Math.max(4, bb.getAllCarIds().size()));
            initData.put("obstacleDensity", ConfigConstants.DEFAULT_OBSTACLE_DENSITY);
            initData.put("routeAlgorithm", bb.getRouteAlgorithm());
            initData.put("active", true);
            taskActive = false;
            sendCommand(CommandType.FORWARD_CONFIG, initData, ConfigConstants.QUEUE_TASK_CONFIG_CMD);
        } else {
            // 配置已存在 → 直接激活，不重新生成地图
            userActivated = true;
            bb.setTaskActive(true);
            taskActive = true;
            wakeTaskProcessor();
        }
    }

    private void handlePauseTask() {
        log.info("⏸ Pause: 停用任务处理器");
        userActivated = false;
        bb.setTaskActive(false);
        taskActive = false;
    }

    private void handleSetConfigTask(Map<String, String> task) {
        JSONObject data = new JSONObject();
        data.put("mapWidth", Integer.parseInt(task.getOrDefault("mapWidth", String.valueOf(ConfigConstants.DEFAULT_MAP_WIDTH))));
        data.put("mapHeight", Integer.parseInt(task.getOrDefault("mapHeight", String.valueOf(ConfigConstants.DEFAULT_MAP_HEIGHT))));
        data.put("carCount", Integer.parseInt(task.getOrDefault("carCount",
                String.valueOf(Math.max(4, bb.getAllCarIds().size())))));
        data.put("obstacleDensity", Double.parseDouble(task.getOrDefault("obstacleDensity", String.valueOf(ConfigConstants.DEFAULT_OBSTACLE_DENSITY))));
        data.put("routeAlgorithm", task.getOrDefault("routeAlgorithm", "BFS"));
        data.put("active", false);
        log.info("📋 SET_CONFIG → 转发到 TaskConfigurator: {}x{}, carCount={}",
                data.get("mapWidth"), data.get("mapHeight"), data.get("carCount"));
        taskActive = false;
        sendCommand(CommandType.FORWARD_CONFIG, data, ConfigConstants.QUEUE_TASK_CONFIG_CMD);
    }

    private void handleResetTask(Map<String, String> task) {
        JSONObject data = new JSONObject();
        data.put("mapWidth", Integer.parseInt(task.getOrDefault("mapWidth", String.valueOf(ConfigConstants.DEFAULT_MAP_WIDTH))));
        data.put("mapHeight", Integer.parseInt(task.getOrDefault("mapHeight", String.valueOf(ConfigConstants.DEFAULT_MAP_HEIGHT))));
        data.put("carCount", Integer.parseInt(task.getOrDefault("carCount",
                String.valueOf(Math.max(4, bb.getAllCarIds().size())))));
        data.put("obstacleDensity", Double.parseDouble(task.getOrDefault("obstacleDensity", String.valueOf(ConfigConstants.DEFAULT_OBSTACLE_DENSITY))));
        data.put("reset", true);
        data.put("active", false);
        log.info("🔄 RESET → 转发到 TaskConfigurator");
        userActivated = false;
        taskActive = false;
        sendCommand(CommandType.FORWARD_CONFIG, data, ConfigConstants.QUEUE_TASK_CONFIG_CMD);
    }

    // ==================== 周期性广播 ====================

    private void broadcastTick() {
        if (!running) return;

        try {
            if (taskActive) {
                // 双保险判定：unexplored:set 为空 且 bitmap 探索率 ≥ 99.9%
                // 防止 Redis 重启导致 unexplored:set 丢失而误判完成
                long unexploredCount = getUnexploredCount();
                double explored = getExploredPercent();
                if (unexploredCount == 0 && explored >= 99.9) {
                    log.info("🏁 巡检完成！未探索格子=0, 探索率={}%", explored);
                    taskActive = false;
                    bb.setTaskActive(false);
                    wakeTaskProcessor();
                }
                fallbackBlockedCheck();
                // 每 tick 主动推进所有 IDLE 且有路径的小车（每个 tick 最多 1 步）
                tickDriveCars();
                tickCount++;
                if (tickCount % 20 == 0) {
                    log.info("节拍 #{} 完成，未探索剩余: {}, 探索率: {}%", tickCount, unexploredCount, explored);
                }
            }
            broadcastViewRefresh();
        } catch (Exception e) {
            log.error("广播节拍出错", e);
        }
    }

    // ==================== 超时和辅助 ====================

    private void handleBlockedTimeout(String carId) {
        long blockedTick;
        try { blockedTick = bb.getCarBlockedTick(carId); } catch (Exception e) { blockedTick = 0; }
        long diff = tickCount - blockedTick;
        if (diff >= ConfigConstants.BLOCKED_TIMEOUT_TICKS) {
            log.info("小车 {} 已阻塞 {} 个节拍，清空路径/目标，转为 IDLE", carId, diff);
            DistributedLock lock = bb.getCarLock(carId);
            if (!lock.tryLock()) {
                log.warn("获取小车 {} 锁失败，延迟处理", carId);
                return;
            }
            try {
                bb.clearCarRoute(carId);
                bb.clearCarTarget(carId);
                bb.setCarStatus(carId, CarStatus.IDLE);
                bb.pushTask("ROUTE_NEEDED", carId, null);
            } finally {
                lock.unlock();
            }
        }
    }

    private void fallbackBlockedCheck() {
        List<String> carIds = getAllCarIds();
        for (String carId : carIds) {
            if (!isMyCar(carIds, carId)) continue;
            try {
                if (bb.getCarStatus(carId) == CarStatus.BLOCKED) {
                    handleBlockedTimeout(carId);
                }
            } catch (Exception e) {
                log.warn("兜底检查车辆 {} 失败", carId, e);
            }
        }
    }

    /** 判断一辆车是否属于当前 Controller 实例的分片 */
    private boolean isMyCar(List<String> carIds, String carId) {
        if (totalInstances <= 1) return true;
        int idx = carIds.indexOf(carId);
        return idx >= 0 && (idx % totalInstances) == instanceId;
    }

    /** 每个 tick 推进所有 IDLE 且有剩余路径的小车（每车每 tick 最多 1 步） */
    private void tickDriveCars() {
        List<String> carIds = getAllCarIds();
        for (String carId : carIds) {
            if (!isMyCar(carIds, carId)) continue;
            try {
                if (bb.getCarStatus(carId) != CarStatus.IDLE) continue;
                Point next = bb.peekNextStep(carId);
                if (next == null) continue;
                log.debug("tickDrive: carId={}, next=({},{})", carId, next.x, next.y);
                JSONObject moveData = new JSONObject();
                sendCommand(CommandType.MOVE_STEP, moveData, ConfigConstants.carQueueName(carId));
            } catch (Exception e) {
                log.warn("tickDrive 车辆 {} 失败", carId, e);
            }
        }
    }

    // ==================== 命令发送 ====================

    private void requestNavigate(String carId) {
        JSONObject data = new JSONObject();
        data.put("carId", carId);
        sendCommand(CommandType.NAVIGATE, data, ConfigConstants.QUEUE_NAVIGATOR_4_CAR_ID);
    }

    private void broadcastViewRefresh() {
        // 仅 instance 0 负责广播，避免多 Controller 重复发送
        if (instanceId != 0) return;
        JSONObject data = new JSONObject();
        data.put("tick", tickCount);
        sendCommand(CommandType.REFRESH_ALL, data, ConfigConstants.EXCHANGE_UPDATE_VIEW, true);
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

    // ==================== 查询辅助 ====================

    private double getExploredPercent() {
        try { return bb.getExploredPercent(); } catch (Exception e) { return 0.0; }
    }

    private List<String> getAllCarIds() {
        try { return bb.getAllCarIds(); } catch (Exception e) {
            return Arrays.asList("Car001", "Car002", "Car003", "Car004");
        }
    }

    private String getRouteAlgorithm() {
        try { return bb.getRouteAlgorithm(); } catch (Exception e) { return "BFS"; }
    }

    private long getUnexploredCount() {
        try { return bb.getUnexploredCount(); } catch (Exception e) { return Long.MAX_VALUE; }
    }
}
