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
    private int stagnantTicks = 0;              // 层 1-C: 连续停滞 tick 计数
    private String lastStagnantFp = null;       // 停滞检测用：上一 tick 指纹
    private String lastSnapshotFp = null;       // 录制去重用：上一已录帧指纹
    private boolean taskCompleted = false;      // 巡检完成标志（供快照 completed 字段使用）

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
        // 清除上次运行残留的活跃状态
        bb.setTaskActive(false);
        bb.clearTaskQueue();

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
                        } catch (Exception e) { log.debug("检查 Redis taskActive 失败，下个周期重试: {}", e.getMessage()); }
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
            case "RECORD_START":
                log.info("⏺ 开始录制快照");
                recording = true;
                return;
            case "RECORD_STOP":
                log.info("⏹ 停止录制快照");
                recording = false;
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
                // ── 暂停检查 ──
                if (bb.isGlobalPaused()) {
                    log.debug("全局暂停中，跳过 carId={}", carId);
                    break;
                }
                String owner = bb.getCarOwner(carId);
                if (owner != null && !owner.isEmpty() && bb.isOperatorPaused(owner)) {
                    log.debug("运行员 {} 暂停中，跳过 carId={}", owner, carId);
                    break;
                }
                log.info("🚗 [MOVE_READY] 发 MOVE_STEP → Car:{}", carId);
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
        boolean hasConfig = existingConfig != null
                && existingConfig.containsKey("mapWidth")
                && existingConfig.containsKey("mapHeight")
                && existingConfig.containsKey("cars")
                && existingConfig.containsKey("taskActive");
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
            initData.put("forceReset", true);  // 配置不完整时必须强制重建，避免残留 key 导致 TaskConfigurator 跳过
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
        // 自动开始录制快照（回放功能需要）
        recording = true;
        stagnantTicks = 0;       // 重置停滞检测
        lastStagnantFp = null;   // 重置停滞指纹
        lastSnapshotFp = null;   // 重置录制指纹（强制下一帧录制）
        taskCompleted = false;   // 重置完成标志
        log.info("⏺ 自动开始录制快照");
    }

    private void handlePauseTask() {
        // 全局暂停由 CommandReceiver 通过 BlackboardClient.setGlobalPause(true) 直接操作 Redis
        // ControllerAgent 只需更新本地 taskActive 以阻止任务处理循环
        log.info("⏸ Pause: 停用任务处理器");
        userActivated = false;
        taskActive = false;
        // 暂停时保持录制（暂停状态也是回放的一部分）
    }

    private void handleSetConfigTask(Map<String, String> task) {
        JSONObject data = new JSONObject();
        data.put("mapWidth", Integer.parseInt(task.getOrDefault("mapWidth", String.valueOf(ConfigConstants.DEFAULT_MAP_WIDTH))));
        data.put("mapHeight", Integer.parseInt(task.getOrDefault("mapHeight", String.valueOf(ConfigConstants.DEFAULT_MAP_HEIGHT))));
        data.put("carCount", Integer.parseInt(task.getOrDefault("carCount",
                String.valueOf(Math.max(4, bb.getAllCarIds().size())))));
        data.put("obstacleDensity", Double.parseDouble(task.getOrDefault("obstacleDensity", String.valueOf(ConfigConstants.DEFAULT_OBSTACLE_DENSITY))));
        data.put("routeAlgorithm", task.getOrDefault("routeAlgorithm", "BFS"));
        data.put("forceReset", true);  // SET_CONFIG 必须重建地图，否则 TaskConfigurator 会因已有配置而跳过
        data.put("active", false);
        log.info("📋 SET_CONFIG → 转发到 TaskConfigurator: {}x{}, carCount={}",
                data.get("mapWidth"), data.get("mapHeight"), data.get("carCount"));
        taskActive = false;
        bb.clearTaskQueue();
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
        data.put("forceReset", true);
        data.put("active", false);
        log.info("🔄 RESET → 转发到 TaskConfigurator (forceReset=true)");
        userActivated = false;
        taskActive = false;
        // 停止录制并清除旧快照（重置后旧数据无效）
        recording = false;
        try { try (redis.clients.jedis.Jedis j = bb.getJedis()) { j.del("replay:snapshots"); } } catch (Exception e) { log.debug("清除回放快照失败: {}", e.getMessage()); }
        tickCount = 0;
        taskCompleted = false;
        bb.clearTaskQueue();
        sendCommand(CommandType.FORWARD_CONFIG, data, ConfigConstants.QUEUE_TASK_CONFIG_CMD);
    }

    // ==================== 周期性广播 ====================

    private void broadcastTick() {
        if (!running) return;

        try {
            if (taskActive) {
                long unexploredCount = getUnexploredCount();
                double explored = getExploredPercent();

                // 条件 A: 理想全探索（原双保险判定）
                if (unexploredCount == 0 && explored >= 99.9) {
                    completeTask("A(理想全探索)", unexploredCount, explored);
                    return;
                }

                // 条件 B: 全车永久阻塞 — 所有车 consecutiveBlocked >= 阈值
                if (isAllCarsPermanentlyBlocked()) {
                    completeTask("B(全车永久阻塞)", unexploredCount, explored);
                    return;
                }

                fallbackBlockedCheck();
                // 每 tick 主动推进所有 IDLE 且有路径的小车（每个 tick 最多 1 步）
                if (!bb.isGlobalPaused()) {
                    tickDriveCars();
                }

                // 条件 C: 持续停滞 — 连续 STAGNANT_TICKS 状态指纹不变
                String fp = computeFingerprint();
                if (fp.equals(lastStagnantFp)) {
                    stagnantTicks++;
                } else {
                    stagnantTicks = 0;
                    lastStagnantFp = fp;
                }
                if (stagnantTicks >= ConfigConstants.STAGNANT_TICKS) {
                    completeTask("C(持续停滞" + stagnantTicks + "tick)", unexploredCount, explored);
                    return;
                }

                tickCount++;
                // 快照录制（用于回放）— 传入指纹用于去重
                saveSnapshotIfRecording(fp);
                if (tickCount % 20 == 0) {
                    log.info("节拍 #{} 完成，未探索剩余: {}, 探索率: {}%", tickCount, unexploredCount, explored);
                }
            }
            broadcastViewRefresh();
        } catch (Exception e) {
            log.error("广播节拍出错", e);
        }
    }

    /** 检查所有车是否都已永久阻塞（consecutiveBlocked >= PERMANENT_BLOCK_THRESHOLD） */
    private boolean isAllCarsPermanentlyBlocked() {
        List<String> carIds = getAllCarIds();
        if (carIds.isEmpty()) return false;
        for (String carId : carIds) {
            try {
                // 不再要求 status==BLOCKED：连续失败 20 次即为永久阻塞
                // 无论当前是 BLOCKED（分支1）还是 IDLE（分支2冷却重试中）
                if (bb.getConsecutiveBlocked(carId) < ConfigConstants.PERMANENT_BLOCK_THRESHOLD) return false;
            } catch (Exception e) {
                return false; // 读取失败时不判定为全阻塞
            }
        }
        return true;
    }

    /** 巡检完成处理：停止任务、停止录制、标记不可达残区 */
    private void completeTask(String reason, long unexploredCount, double explored) {
        // 层 3: 标记不可达残区（unexplored:set 剩余格子 = 不可达区域）
        int unreachableCount = 0;
        try { unreachableCount = bb.markUnreachableResidual(); } catch (Exception e) { log.warn("标记不可达残区失败", e); }
        log.info("🏁 巡检完成！触发条件={}, 探索率={}%, 未探索={}, 不可达残区={}",
                reason, String.format("%.1f", explored), unexploredCount, unreachableCount);
        taskActive = false;
        bb.setTaskActive(false);
        recording = false;
        stagnantTicks = 0;
        lastStagnantFp = null;
        lastSnapshotFp = null;
        taskCompleted = true;
        log.info("⏹ 探索完成，自动停止录制");
        wakeTaskProcessor();
    }

    // ==================== 超时和辅助 ====================

    private void handleBlockedTimeout(String carId) {
        long blockedAt;
        try { blockedAt = bb.getCarBlockedTick(carId); } catch (Exception e) { blockedAt = 0; }
        long elapsed = System.currentTimeMillis() - blockedAt;
        if (elapsed >= ConfigConstants.BLOCKED_TIMEOUT_MS) {
            // 检查连续阻塞次数，超过阈值不再自动恢复
            int consecutive = bb.getConsecutiveBlocked(carId);
            if (consecutive >= ConfigConstants.PERMANENT_BLOCK_THRESHOLD) {
                log.warn("小车 {} 已连续阻塞 {} 次，跳过自动恢复，需人工干预", carId, consecutive);
                return;
            }
            log.info("小车 {} 已阻塞 {}ms，清空路径/目标，转为 IDLE", carId, elapsed);
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
        // 全局暂停检查
        boolean globalPaused = bb.isGlobalPaused();

        List<String> carIds = getAllCarIds();
        for (String carId : carIds) {
            if (!isMyCar(carIds, carId)) continue;
            try {
                if (bb.getCarStatus(carId) != CarStatus.IDLE) continue;

                // ── 暂停检查 ──
                if (globalPaused) continue;
                String owner = bb.getCarOwner(carId);
                if (owner != null && !owner.isEmpty() && bb.isOperatorPaused(owner)) {
                    continue;  // 该运行员暂停了自己的车
                }

                Point next = bb.peekNextStep(carId);
                if (next == null) continue;
                log.debug("tickDrive: carId={}, next=({},{})", carId, next.x, next.y);
                JSONObject moveData = new JSONObject();
                moveData.put("carId", carId);
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

    // ==================== 快照录制 ====================

    private volatile boolean recording = false;

    public void setRecording(boolean r) { this.recording = r; }

    /** 计算当前状态指纹：mapView 位图 + 各车位置/状态/连续阻塞数。用于录制去重和停滞检测。 */
    private String computeFingerprint() {
        try {
            var map = bb.getMapView();
            int w = bb.getMapWidth(), h = bb.getMapHeight();
            var sb = new StringBuilder(w * h + 64);
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    sb.append(map[y][x] ? '1' : '0');
            sb.append('|');
            for (String id : getAllCarIds()) {
                var p = bb.getCarPosition(id);
                sb.append(id).append(':')
                  .append(bb.getCarStatus(id).name()).append(':')
                  .append(p != null ? p.x + "," + p.y : "null").append(':')
                  .append(bb.getConsecutiveBlocked(id)).append(';');
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(System.currentTimeMillis()); // 出错时退化为不重复
        }
    }

    private void saveSnapshotIfRecording(String fingerprint) {
        if (!recording) return;
        // 层 2: 状态指纹去重 — 相同帧跳过录制，避免回放数据被重复帧撑爆
        if (fingerprint.equals(lastSnapshotFp)) return;
        lastSnapshotFp = fingerprint;
        try {
            com.alibaba.fastjson2.JSONObject snap = new com.alibaba.fastjson2.JSONObject();
            snap.put("tick", tickCount);
            snap.put("timestamp", System.currentTimeMillis());
            snap.put("mapWidth", bb.getMapWidth());
            snap.put("mapHeight", bb.getMapHeight());
            snap.put("exploredRate", getExploredPercent() / 100.0);
            snap.put("taskActive", taskActive);
            // 车辆状态
            var cars = new com.alibaba.fastjson2.JSONArray();
            for (String id : getAllCarIds()) {
                var cs = new com.alibaba.fastjson2.JSONObject();
                cs.put("carId", id);
                cs.put("status", bb.getCarStatus(id).name());
                var p = bb.getCarPosition(id);
                cs.put("position", p != null ? com.alibaba.fastjson2.JSON.toJSON(p) : null);
                cs.put("steps", bb.getCarSteps(id));
                // 包含 target 和 owner 用于回放渲染
                var t = bb.getCarTarget(id);
                cs.put("target", t != null ? com.alibaba.fastjson2.JSON.toJSON(t) : null);
                cs.put("owner", bb.getCarOwner(id));
                cars.add(cs);
            }
            snap.put("cars", cars);
            // 探索位图（mapView）
            var map = bb.getMapView();
            var sb = new StringBuilder();
            for (int y = 0; y < bb.getMapHeight(); y++)
                for (int x = 0; x < bb.getMapWidth(); x++)
                    sb.append(map[y][x] ? '1' : '0');
            snap.put("mapBits", sb.toString());
            // 障碍物位图（mapBlocked）— 回放时需要渲染障碍物
            var blocked = bb.getMapBlocked();
            var sb2 = new StringBuilder();
            for (int y = 0; y < bb.getMapHeight(); y++)
                for (int x = 0; x < bb.getMapWidth(); x++)
                    sb2.append(blocked[y][x] ? '1' : '0');
            snap.put("blockedBits", sb2.toString());
            // 全局暂停状态
            snap.put("globalPaused", bb.isGlobalPaused());
            // 探索完成状态
            snap.put("completed", taskCompleted);
            try (redis.clients.jedis.Jedis j = bb.getJedis()) {
                j.rpush("replay:snapshots", snap.toJSONString());
                j.ltrim("replay:snapshots", -12000, -1);  // 保留最近 12000 个快照（约 20 分钟，TICK_INTERVAL_MS=100ms）
            }
        } catch (Exception e) { log.debug("快照录制失败: {}", e.getMessage()); }
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
