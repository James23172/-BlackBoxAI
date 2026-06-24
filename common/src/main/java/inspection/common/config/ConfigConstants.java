package inspection.common.config;

/**
 * 全局常量配置
 * 所有模块引用此文件获取统一的配置值
 */
public final class ConfigConstants {

    private ConfigConstants() {}

    // ==================== Redis 连接 ====================
    public static final String REDIS_HOST = "localhost";
    public static final int REDIS_PORT = 6379;
    public static final int REDIS_TIMEOUT_MS = 2000;

    // ==================== RabbitMQ 连接 ====================
    public static final String RABBITMQ_HOST = "localhost";
    public static final int RABBITMQ_PORT = 5672;
    public static final String RABBITMQ_USER = "guest";
    public static final String RABBITMQ_PASS = "guest";
    public static final String RABBITMQ_VHOST = "/";

    // ==================== MQ 队列名称 ====================
    /** Navigator 共享竞争队列：接收导航请求 */
    public static final String QUEUE_NAVIGATOR_4_CAR_ID = "Navigator4CarID";
    /** TargetPlanner 共享竞争队列：接收目标选择请求（可 1~N 实例） */
    public static final String QUEUE_TARGET_PLANNER_CMD = "TargetPlannerCmd";
    /** TaskConfigurator 订阅：接收配置请求 */
    public static final String QUEUE_TASK_CONFIG_CMD = "TaskConfigCmd";

    /** Car 订阅队列前缀，完整名称: Car:{carId} */
    public static final String QUEUE_CAR_PREFIX = "Car:";

    // ==================== MQ Exchange ====================
    /** Display 广播用的 Fanout Exchange */
    public static final String EXCHANGE_UPDATE_VIEW = "UpdateView";

    // ==================== Redis Key 前缀 ====================
    /** 地图 bitmap key */
    public static final String KEY_MAP_VIEW = "map:view";
    /** 地图障碍物 bitmap key */
    public static final String KEY_MAP_BLOCKED = "map:blocked";
    /** 小车独立不可达 bitmap key: car:{carId}:unreachable */
    public static final String KEY_CAR_UNREACHABLE = "car:%s:unreachable";
    /** 小车状态 key: car:{carId}:status */
    public static final String KEY_CAR_STATUS = "car:%s:status";
    /** 小车位置 key: car:{carId}:position */
    public static final String KEY_CAR_POSITION = "car:%s:position";
    /** 小车步数 key: car:{carId}:steps */
    public static final String KEY_CAR_STEPS = "car:%s:steps";
    /** 小车目标 key: car:{carId}:target */
    public static final String KEY_CAR_TARGET = "car:%s:target";
    /** 小车路径列表 key: car:{carId}:route */
    public static final String KEY_CAR_ROUTE = "car:%s:route";
    /** 小车受阻节拍 key: car:{carId}:blocked_tick */
    public static final String KEY_CAR_BLOCKED_TICK = "car:%s:blocked_tick";
    /** 任务配置 hash key */
    public static final String KEY_TASK_CONFIG = "config:task";
    /** 小车分布式锁 key */
    public static final String KEY_LOCK_CAR = "lock:car:%s";
    /** Controller 分布式锁 key */
    public static final String KEY_LOCK_CONTROLLER = "lock:controller";
    /** 未探索坐标索引 Set key（Navigator/TargetPlanner O(1) 随机选取） */
    public static final String KEY_UNEXPLORED_SET = "unexplored:set";
    /** @deprecated 目标分配已由 TargetPlanner 模块接管，无全局锁 */
    @Deprecated
    public static final String KEY_LOCK_TARGET_ALLOCATION = "lock:target_allocation";
    /** Bitmap 版本号 key（用于跨进程缓存失效检测） */
    public static final String KEY_MAP_VIEW_VERSION = "map:view:version";
    public static final String KEY_MAP_BLOCKED_VERSION = "map:blocked:version";
    /** FIFO 任务队列 key（Controller LPOP/RPUSH） */
    public static final String KEY_TASK_QUEUE = "taskQueue";
    /** 小车阻塞冷却截止时间 key: car:{carId}:blocked_until */
    public static final String KEY_CAR_BLOCKED_UNTIL = "car:%s:blocked_until";

    // ==================== 地图参数默认值 ====================
    public static final int DEFAULT_MAP_WIDTH = 40;
    public static final int DEFAULT_MAP_HEIGHT = 40;
    public static final double DEFAULT_OBSTACLE_DENSITY = 0.1;
    public static final int ILLUMINATE_RADIUS = 1;      // 3×3 点亮（架构文档规定）
    /** @deprecated 已替换为 {@link #BLOCKED_TIMEOUT_MS}，统一使用毫秒时间戳 */
    @Deprecated
    public static final int BLOCKED_TIMEOUT_TICKS = 2;
    public static final long BLOCKED_TIMEOUT_MS = 3000;   // 受阻超时毫秒（Navigator 无解后 3 秒再重试）

    // ==================== 寻路与阻塞恢复参数 ====================
    /** 导航失败时扫描周围区域的半径（实际扫描 (2R+1)×(2R+1) 格） */
    public static final int NAVIGATE_SCAN_RADIUS = 15;       // 31×31 = 961 格
    /** 连续阻塞永久放弃阈值（Navigator 与 Controller 必须同步） */
    public static final int PERMANENT_BLOCK_THRESHOLD = 20;
    /** 每车不可达标记 FIFO 上限（超过则淘汰最旧的，给瞬时不可达格重试机会） */
    public static final int UNREACHABLE_MAX_COUNT = 200;
    /** 不可达标记顺序记录 key: car:{carId}:unreachable_order（Redis List） */
    public static final String KEY_CAR_UNREACHABLE_ORDER = "car:%s:unreachable_order";

    // ==================== 节拍参数 ====================
    public static final int TICK_INTERVAL_MS = 100;
    /** 系统停滞判定阈值：连续 N tick 状态指纹不变则判定完成（100 tick = 10 秒） */
    public static final int STAGNANT_TICKS = 100;

    // ==================== 分布式锁参数 ====================
    public static final int LOCK_EXPIRE_SECONDS = 30;

    /** RabbitMQ 别名常量（兼容队友代码） */
    public static final String RABBIT_USER  = RABBITMQ_USER;
    public static final String RABBIT_PASS  = RABBITMQ_PASS;
    public static final String RABBIT_VHOST = RABBITMQ_VHOST;
    public static final int LOCK_RETRY_INTERVAL_MS = 100;
    public static final int LOCK_MAX_RETRIES = 50;

    // ==================== CarID ====================
    public static final String CAR_ID = "Car001";

    // ==================== 辅助方法 ====================
    public static String carStatusKey(String carId) {
        return String.format(KEY_CAR_STATUS, carId);
    }

    public static String carPositionKey(String carId) {
        return String.format(KEY_CAR_POSITION, carId);
    }

    public static String carStepsKey(String carId) {
        return String.format(KEY_CAR_STEPS, carId);
    }

    public static String carTargetKey(String carId) {
        return String.format(KEY_CAR_TARGET, carId);
    }

    public static String carRouteKey(String carId) {
        return String.format(KEY_CAR_ROUTE, carId);
    }

    public static String carBlockedTickKey(String carId) {
        return String.format(KEY_CAR_BLOCKED_TICK, carId);
    }

    public static String carBlockedUntilKey(String carId) {
        return String.format(KEY_CAR_BLOCKED_UNTIL, carId);
    }

    public static String carUnreachableKey(String carId) {
        return String.format(KEY_CAR_UNREACHABLE, carId);
    }

    public static String carUnreachableOrderKey(String carId) {
        return String.format(KEY_CAR_UNREACHABLE_ORDER, carId);
    }

    public static String carQueueName(String carId) {
        return QUEUE_CAR_PREFIX + carId;
    }

    public static String carLockKey(String carId) {
        return String.format(KEY_LOCK_CAR, carId);
    }
}
