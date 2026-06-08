package inspection.common.client;

import com.alibaba.fastjson2.JSON;
import inspection.common.config.ConfigConstants;
import inspection.common.enums.CarStatus;
import inspection.common.model.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.time.Duration;
import java.util.*;

/**
 * 黑板客户端 — 封装全部 Redis Key 读写
 *
 * 这是 common 模块最核心的类，所有其他模块只通过此类访问 Redis。
 *
 * Redis Key 约定（参见 ConfigConstants）:
 *   map:view             → Bitmap   (已探索=1)
 *   map:blocked          → Bitmap   (障碍物/小车=1)
 *   car:{carId}:status   → String   (CarStatus.name())
 *   car:{carId}:position → Hash     {x, y}
 *   car:{carId}:steps    → String   (整数)
 *   car:{carId}:target   → String   (JSON of Point)
 *   car:{carId}:route    → List     (Point JSON，LPUSH/RPOP)
 *   car:{carId}:blocked_tick → String
 *   config:task          → Hash     (taskActive, mapWidth, mapHeight, ...)
 *   lock:car:{carId}     → String   (分布式锁)
 *   lock:controller      → String   (单实例锁)
 */
public class BlackboardClient {
    private static final Logger log = LoggerFactory.getLogger(BlackboardClient.class);

    private final JedisPool pool;
    private final int mapWidth;
    private final int mapHeight;

    public BlackboardClient(String host, int port) {
        this(host, port, ConfigConstants.DEFAULT_MAP_WIDTH, ConfigConstants.DEFAULT_MAP_HEIGHT);
    }

    public BlackboardClient(String host, int port, int mapWidth, int mapHeight) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(20);
        config.setMaxIdle(5);
        config.setMinIdle(1);
        config.setTestOnBorrow(true);
        this.pool = new JedisPool(config, host, port,
                ConfigConstants.REDIS_TIMEOUT_MS, null);
        this.mapWidth = mapWidth;
        this.mapHeight = mapHeight;
        log.info("BlackboardClient 已连接 Redis {}:{}", host, port);
    }

    /** 获取底层 Jedis 连接（供 DistributedLock 等使用） */
    public Jedis getJedis() {
        return pool.getResource();
    }

    public int getMapWidth() { return mapWidth; }
    public int getMapHeight() { return mapHeight; }

    // ==================== 地图操作 ====================

    /** 将 (x,y) 转成 bitmap 偏移 */
    private long bitmapOffset(int x, int y) {
        return (long) y * mapWidth + x;
    }

    /** 获取整个地图的探索状态（boolean 二维数组） */
    public boolean[][] getMapView() {
        boolean[][] view = new boolean[mapHeight][mapWidth];
        try (Jedis jedis = pool.getResource()) {
            for (int y = 0; y < mapHeight; y++) {
                for (int x = 0; x < mapWidth; x++) {
                    view[y][x] = jedis.getbit(ConfigConstants.KEY_MAP_VIEW, bitmapOffset(x, y));
                }
            }
        }
        return view;
    }

    /** 设置地图某位的探索状态 */
    public void setMapViewBit(int x, int y) {
        try (Jedis jedis = pool.getResource()) {
            jedis.setbit(ConfigConstants.KEY_MAP_VIEW, bitmapOffset(x, y), true);
        }
    }

    /** 检查 (x,y) 是否已被探索 */
    public boolean isExplored(int x, int y) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.getbit(ConfigConstants.KEY_MAP_VIEW, bitmapOffset(x, y));
        }
    }

    /** 3x3 点亮：以 (cx,cy) 为中心照亮周围 */
    public void illuminateArea(int cx, int cy) {
        int r = ConfigConstants.ILLUMINATE_RADIUS;
        try (Jedis jedis = pool.getResource()) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    int nx = cx + dx;
                    int ny = cy + dy;
                    if (nx >= 0 && nx < mapWidth && ny >= 0 && ny < mapHeight) {
                        jedis.setbit(ConfigConstants.KEY_MAP_VIEW, bitmapOffset(nx, ny), true);
                    }
                }
            }
        }
    }

    // ------ 障碍物 ------

    /** 检查 (x,y) 是否被阻塞（静态障碍物或动态小车） */
    public boolean isBlocked(int x, int y) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.getbit(ConfigConstants.KEY_MAP_BLOCKED, bitmapOffset(x, y));
        }
    }

    /** 设置障碍物 */
    public void setBlocked(int x, int y) {
        try (Jedis jedis = pool.getResource()) {
            jedis.setbit(ConfigConstants.KEY_MAP_BLOCKED, bitmapOffset(x, y), true);
        }
    }

    /** 清除障碍物 */
    public void clearBlocked(int x, int y) {
        try (Jedis jedis = pool.getResource()) {
            jedis.setbit(ConfigConstants.KEY_MAP_BLOCKED, bitmapOffset(x, y), false);
        }
    }

    /** 获取所有障碍物坐标列表 */
    public List<Point> getAllBlocked() {
        List<Point> list = new ArrayList<>();
        try (Jedis jedis = pool.getResource()) {
            for (int y = 0; y < mapHeight; y++) {
                for (int x = 0; x < mapWidth; x++) {
                    if (jedis.getbit(ConfigConstants.KEY_MAP_BLOCKED, bitmapOffset(x, y))) {
                        list.add(new Point(x, y));
                    }
                }
            }
        }
        return list;
    }

    /** 获取地图阻挡状态二维数组（供 Navigator 路径规划使用） */
    public boolean[][] getMapBlocked() {
        boolean[][] blocked = new boolean[mapHeight][mapWidth];
        try (Jedis jedis = pool.getResource()) {
            for (int y = 0; y < mapHeight; y++) {
                for (int x = 0; x < mapWidth; x++) {
                    blocked[y][x] = jedis.getbit(ConfigConstants.KEY_MAP_BLOCKED, bitmapOffset(x, y));
                }
            }
        }
        return blocked;
    }

    // ==================== 小车状态 ====================

    public CarStatus getCarStatus(String carId) {
        try (Jedis jedis = pool.getResource()) {
            String val = jedis.get(ConfigConstants.carStatusKey(carId));
            return val != null ? CarStatus.valueOf(val) : CarStatus.IDLE;
        }
    }

    /** ⚠️ 只有 Controller 和 Car 调用此方法 */
    public void setCarStatus(String carId, CarStatus status) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(ConfigConstants.carStatusKey(carId), status.name());
        }
    }

    public Point getCarPosition(String carId) {
        try (Jedis jedis = pool.getResource()) {
            Map<String, String> map = jedis.hgetAll(ConfigConstants.carPositionKey(carId));
            if (map.isEmpty()) return null;
            int x = Integer.parseInt(map.getOrDefault("x", "0"));
            int y = Integer.parseInt(map.getOrDefault("y", "0"));
            return new Point(x, y);
        }
    }

    public void setCarPosition(String carId, int x, int y) {
        try (Jedis jedis = pool.getResource()) {
            Map<String, String> map = new HashMap<>();
            map.put("x", String.valueOf(x));
            map.put("y", String.valueOf(y));
            jedis.hset(ConfigConstants.carPositionKey(carId), map);
        }
    }

    public int getCarSteps(String carId) {
        try (Jedis jedis = pool.getResource()) {
            String val = jedis.get(ConfigConstants.carStepsKey(carId));
            return val != null ? Integer.parseInt(val) : 0;
        }
    }

    public void incrementCarSteps(String carId) {
        try (Jedis jedis = pool.getResource()) {
            jedis.incr(ConfigConstants.carStepsKey(carId));
        }
    }

    public void setCarSteps(String carId, int steps) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(ConfigConstants.carStepsKey(carId), String.valueOf(steps));
        }
    }

    // ==================== 小车任务 ====================

    public Point getCarTarget(String carId) {
        try (Jedis jedis = pool.getResource()) {
            String json = jedis.get(ConfigConstants.carTargetKey(carId));
            return json != null ? JSON.parseObject(json, Point.class) : null;
        }
    }

    public void setCarTarget(String carId, int x, int y) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(ConfigConstants.carTargetKey(carId), JSON.toJSONString(new Point(x, y)));
        }
    }

    /** Point 版本（队友接口要求） */
    public void setCarTarget(String carId, Point target) {
        if (target != null) {
            setCarTarget(carId, target.getX(), target.getY());
        }
    }

    public void clearCarTarget(String carId) {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(ConfigConstants.carTargetKey(carId));
        }
    }

    // ==================== 路径操作 ====================

    /** Navigator 调用：LPUSH 整条路径（从起点到终点） */
    public void pushRoute(String carId, List<Point> path) {
        try (Jedis jedis = pool.getResource()) {
            String key = ConfigConstants.carRouteKey(carId);
            // 先清旧路径
            jedis.del(key);
            if (path != null && !path.isEmpty()) {
                // 逆序压入，保证 RPOP 时按顺序取出
                String[] jsons = new String[path.size()];
                for (int i = path.size() - 1; i >= 0; i--) {
                    jsons[path.size() - 1 - i] = JSON.toJSONString(path.get(i));
                }
                jedis.lpush(key, jsons);
            }
        }
    }

    /** Car 调用：RPOP 下一步 */
    public Point popNextStep(String carId) {
        try (Jedis jedis = pool.getResource()) {
            String json = jedis.rpop(ConfigConstants.carRouteKey(carId));
            return json != null ? JSON.parseObject(json, Point.class) : null;
        }
    }

    /** Car 调用：查看下一步但不消费（LRANGE -1 -1） */
    public Point peekNextStep(String carId) {
        try (Jedis jedis = pool.getResource()) {
            List<String> list = jedis.lrange(ConfigConstants.carRouteKey(carId), -1, -1);
            if (list.isEmpty()) return null;
            return JSON.parseObject(list.get(0), Point.class);
        }
    }

    /** 获取完整路径列表 */
    public List<Point> getFullRoute(String carId) {
        List<Point> path = new ArrayList<>();
        try (Jedis jedis = pool.getResource()) {
            List<String> jsons = jedis.lrange(ConfigConstants.carRouteKey(carId), 0, -1);
            // RPOP 顺序：list 中第一个是最后 push 的，需要反转
            for (int i = jsons.size() - 1; i >= 0; i--) {
                path.add(JSON.parseObject(jsons.get(i), Point.class));
            }
        }
        return path;
    }

    /** Controller/Car 调用：清空路径 */
    public void clearRoute(String carId) {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(ConfigConstants.carRouteKey(carId));
        }
    }

    /** 别名：clearCarRoute */
    public void clearCarRoute(String carId) {
        clearRoute(carId);
    }

    // ==================== 受阻 ====================

    public void setBlockedTick(String carId, long tick) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(ConfigConstants.carBlockedTickKey(carId), String.valueOf(tick));
        }
    }

    public long getBlockedTick(String carId) {
        try (Jedis jedis = pool.getResource()) {
            String val = jedis.get(ConfigConstants.carBlockedTickKey(carId));
            return val != null ? Long.parseLong(val) : 0;
        }
    }

    /** 别名：getCarBlockedTick */
    public long getCarBlockedTick(String carId) {
        return getBlockedTick(carId);
    }

    // ==================== 全局配置 ====================

    public Map<String, String> getTaskConfig() {
        try (Jedis jedis = pool.getResource()) {
            return jedis.hgetAll(ConfigConstants.KEY_TASK_CONFIG);
        }
    }

    public void setTaskConfig(Map<String, String> config) {
        try (Jedis jedis = pool.getResource()) {
            jedis.hset(ConfigConstants.KEY_TASK_CONFIG, config);
        }
    }

    public boolean isTaskActive() {
        try (Jedis jedis = pool.getResource()) {
            String val = jedis.hget(ConfigConstants.KEY_TASK_CONFIG, "taskActive");
            return "1".equals(val) || "true".equalsIgnoreCase(val);
        }
    }

    public void setTaskActive(boolean active) {
        try (Jedis jedis = pool.getResource()) {
            jedis.hset(ConfigConstants.KEY_TASK_CONFIG, "taskActive", active ? "1" : "0");
        }
    }

    /** 获取探索数量 */
    public int getExploredCount() {
        int count = 0;
        try (Jedis jedis = pool.getResource()) {
            count = (int) jedis.bitcount(ConfigConstants.KEY_MAP_VIEW);
        }
        return count;
    }

    /** 获取探索百分比 (0.0 ~ 100.0) */
    public double getExploredPercent() {
        int total = mapWidth * mapHeight;
        if (total == 0) return 0.0;
        return getExploredCount() * 100.0 / total;
    }

    /** 获取所有小车 ID 列表 */
    public List<String> getAllCarIds() {
        try (Jedis jedis = pool.getResource()) {
            String carsStr = jedis.hget(ConfigConstants.KEY_TASK_CONFIG, "cars");
            if (carsStr != null && !carsStr.isEmpty()) {
                return JSON.parseArray(carsStr, String.class);
            }
        }
        // 默认返回配置中的 Car001
        return Collections.singletonList(ConfigConstants.CAR_ID);
    }

    /** 获取路径规划算法名 */
    public String getRouteAlgorithm() {
        try (Jedis jedis = pool.getResource()) {
            String algo = jedis.hget(ConfigConstants.KEY_TASK_CONFIG, "routeAlgorithm");
            return algo != null ? algo : "BFS";
        }
    }

    /** 获取障碍物数量 */
    public int getObstacleCount() {
        try (Jedis jedis = pool.getResource()) {
            return (int) jedis.bitcount(ConfigConstants.KEY_MAP_BLOCKED);
        }
    }

    // ==================== 分布式锁 ====================

    public DistributedLock getCarLock(String carId) {
        return new DistributedLock(getJedis(), ConfigConstants.carLockKey(carId));
    }

    public DistributedLock getControllerLock() {
        return new DistributedLock(getJedis(), ConfigConstants.KEY_LOCK_CONTROLLER);
    }

    // ==================== 管理 ====================

    /** 仅 TaskConfigurator 调用：清空所有数据 */
    public void clearAll() {
        try (Jedis jedis = pool.getResource()) {
            jedis.flushDB();
            log.info("Redis 数据库已清空 (FLUSHDB)");
        }
    }

    /** 关闭连接池 */
    public void close() {
        if (pool != null && !pool.isClosed()) {
            pool.close();
        }
    }
}
