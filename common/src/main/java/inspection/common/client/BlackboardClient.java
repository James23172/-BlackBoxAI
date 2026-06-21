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
    private int mapWidth;
    private int mapHeight;
    private long lastConfigRefresh = 0;

    // ===== Bitmap 缓存（减少重复 Redis GET） =====
    private byte[] cachedMapViewBytes;
    private long cachedMapViewVersion = -1;
    private byte[] cachedMapBlockedBytes;
    private long cachedMapBlockedVersion = -1;

    // ===== 分块缓存 =====
    private boolean[][][][] chunkViewCache;
    private boolean[][][][] chunkBlockedCache;
    private long[][] chunkViewVersions;
    private long[][] chunkBlockedVersions;
    private int totalChunkRows = -1;
    private int totalChunkCols = -1;

    /** 强制失效所有 bitmap 缓存（跨进程感知，Navigator 规划前调用） */
    public void invalidateBitmapCache() {
        this.cachedMapViewBytes = null;
        this.cachedMapViewVersion = -1;
        this.cachedMapBlockedBytes = null;
        this.cachedMapBlockedVersion = -1;
        invalidateAllChunkCache();
    }

    // ==================== 分块缓存管理 ====================

    private void ensureChunkCache(int width, int height) {
        int newTR = (height + ConfigConstants.CHUNK_SIZE - 1) / ConfigConstants.CHUNK_SIZE;
        int newTC = (width  + ConfigConstants.CHUNK_SIZE - 1) / ConfigConstants.CHUNK_SIZE;
        if (newTR == totalChunkRows && newTC == totalChunkCols
                && chunkViewCache != null && chunkBlockedCache != null) {
            return;
        }
        chunkViewCache    = new boolean[newTR][newTC][][];
        chunkBlockedCache = new boolean[newTR][newTC][][];
        chunkViewVersions    = new long[newTR][newTC];
        chunkBlockedVersions = new long[newTR][newTC];
        totalChunkRows = newTR;
        totalChunkCols = newTC;
    }

    public void invalidateAllChunkCache() {
        chunkViewCache = null;
        chunkBlockedCache = null;
        chunkViewVersions = null;
        chunkBlockedVersions = null;
        totalChunkRows = -1;
        totalChunkCols = -1;
    }

    private long chunkOffset(int lx, int ly) {
        return (long) ly * ConfigConstants.CHUNK_SIZE + lx;
    }

    private byte[] getChunkBytes(String key) {
        try (Jedis jedis = pool.getResource()) {
            byte[] data = jedis.get(key.getBytes());
            int cs = ConfigConstants.CHUNK_SIZE;
            int expectedBytes = (cs * cs + 7) / 8;
            if (data == null) {
                data = new byte[expectedBytes];
            } else if (data.length < expectedBytes) {
                byte[] newData = new byte[expectedBytes];
                System.arraycopy(data, 0, newData, 0, data.length);
                data = newData;
            }
            return data;
        } catch (Exception e) {
            log.warn("getChunkBytes failed for {}", key, e);
            return new byte[(ConfigConstants.CHUNK_SIZE * ConfigConstants.CHUNK_SIZE + 7) / 8];
        }
    }

    private boolean[][] decodeCachedChunk(byte[] data) {
        return decodeCachedBitmap(data, ConfigConstants.CHUNK_SIZE, ConfigConstants.CHUNK_SIZE);
    }

    private boolean[][] fetchViewChunk(int cr, int cc) {
        return decodeCachedChunk(getChunkBytes(ConfigConstants.mapViewChunkKey(cr, cc)));
    }

    private boolean[][] fetchBlockedChunk(int cr, int cc) {
        return decodeCachedChunk(getChunkBytes(ConfigConstants.mapBlockedChunkKey(cr, cc)));
    }

    private long getViewChunkVersion(int cr, int cc) {
        try (Jedis jedis = pool.getResource()) {
            String val = jedis.get(ConfigConstants.mapViewChunkVerKey(cr, cc));
            return val != null ? Long.parseLong(val) : 0;
        }
    }

    private long getBlockedChunkVersion(int cr, int cc) {
        try (Jedis jedis = pool.getResource()) {
            String val = jedis.get(ConfigConstants.mapBlockedChunkVerKey(cr, cc));
            return val != null ? Long.parseLong(val) : 0;
        }
    }

    private boolean[][] getOrFetchViewChunk(int cr, int cc) {
        long currentVer = getViewChunkVersion(cr, cc);
        if (chunkViewCache[cr][cc] == null
                || chunkViewVersions[cr][cc] != currentVer) {
            chunkViewCache[cr][cc] = fetchViewChunk(cr, cc);
            chunkViewVersions[cr][cc] = currentVer;
        }
        return chunkViewCache[cr][cc];
    }

    private boolean[][] getOrFetchBlockedChunk(int cr, int cc) {
        long currentVer = getBlockedChunkVersion(cr, cc);
        if (chunkBlockedCache[cr][cc] == null
                || chunkBlockedVersions[cr][cc] != currentVer) {
            chunkBlockedCache[cr][cc] = fetchBlockedChunk(cr, cc);
            chunkBlockedVersions[cr][cc] = currentVer;
        }
        return chunkBlockedCache[cr][cc];
    }

    public MapChunk getViewChunkData(int cr, int cc) {
        refreshMapConfig();
        ensureChunkCache(mapWidth, mapHeight);
        boolean[][] data = getOrFetchViewChunk(cr, cc);
        MapChunk mc = new MapChunk();
        mc.chunkRow = cr;
        mc.chunkCol = cc;
        mc.data = data;
        mc.version = chunkViewVersions[cr][cc];
        return mc;
    }

    public List<ChunkId> popModifiedChunks() {
        String script = "local m=redis.call('SMEMBERS',KEYS[1]);redis.call('DEL',KEYS[1]);return m";
        try (Jedis jedis = pool.getResource()) {
            @SuppressWarnings("unchecked")
            List<String> raw = (List<String>) jedis.eval(script,
                    java.util.Collections.singletonList(ConfigConstants.KEY_CHUNKS_MODIFIED),
                    java.util.Collections.emptyList());
            List<ChunkId> result = new java.util.ArrayList<>();
            for (String s : raw) {
                String[] parts = s.split(":");
                result.add(new ChunkId(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
            }
            return result;
        }
    }

    public BlackboardClient(String host, int port) {
        this(host, port, ConfigConstants.DEFAULT_MAP_WIDTH, ConfigConstants.DEFAULT_MAP_HEIGHT);
    }

    public BlackboardClient(String host, int port, int mapWidth, int mapHeight) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(20);
        config.setMaxIdle(5);
        config.setMinIdle(1);
        config.setTestOnBorrow(true);
        config.setTestWhileIdle(true);
        config.setTimeBetweenEvictionRuns(Duration.ofSeconds(30));
        config.setMinEvictableIdleDuration(Duration.ofSeconds(60));
        this.pool = new JedisPool(config, host, port,
                ConfigConstants.REDIS_TIMEOUT_MS, null);
        this.mapWidth = mapWidth;
        this.mapHeight = mapHeight;
        log.info("BlackboardClient 已连接 Redis {}:{}", host, port);
    }

    /** 从 Redis config:task 刷新地图尺寸（最多每秒一次） */
    public void refreshMapConfig() {
        long now = System.currentTimeMillis();
        if (now - lastConfigRefresh < 1000) return;
        try (Jedis jedis = pool.getResource()) {
            String w = jedis.hget(ConfigConstants.KEY_TASK_CONFIG, "mapWidth");
            String h = jedis.hget(ConfigConstants.KEY_TASK_CONFIG, "mapHeight");
            if (w != null) mapWidth = Integer.parseInt(w);
            if (h != null) mapHeight = Integer.parseInt(h);
        } catch (Exception e) {
            // config:task may not exist yet, ignore
        }
        lastConfigRefresh = now;
    }

    /** 更新地图尺寸（TaskConfigurator 在 clearAll 后调用） */
    public void setMapSize(int w, int h) {
        this.mapWidth = w;
        this.mapHeight = h;
        this.lastConfigRefresh = System.currentTimeMillis();
    }

    /** 获取底层 Jedis 连接（供 DistributedLock 等使用） */
    public Jedis getJedis() {
        return pool.getResource();
    }

    public int getMapWidth() { refreshMapConfig(); return mapWidth; }
    public int getMapHeight() { refreshMapConfig(); return mapHeight; }

    /** 获取 bitmap 版本号（跨进程缓存失效用） */
    private long getBitmapVersion(String versionKey) {
        try (Jedis jedis = pool.getResource()) {
            String val = jedis.get(versionKey);
            return val != null ? Long.parseLong(val) : 0;
        }
    }

    /** 递增 bitmap 版本号 */
    private void incrementBitmapVersion(String versionKey) {
        try (Jedis jedis = pool.getResource()) {
            jedis.incr(versionKey);
        }
    }

    // ==================== 地图操作 ====================

    /** 将 (x,y) 转成 bitmap 偏移 */
    private long bitmapOffset(int x, int y) {
        return (long) y * mapWidth + x;
    }

    /** 从 Redis 读取 bitmap 原始字节数组 */
    private byte[] getBitmapBytes(String key) {
        try (Jedis jedis = pool.getResource()) {
            byte[] data = jedis.get(key.getBytes());
            int expectedBytes = (mapWidth * mapHeight + 7) / 8;
            if (data == null) {
                data = new byte[expectedBytes];
            } else if (data.length < expectedBytes) {
                byte[] newData = new byte[expectedBytes];
                System.arraycopy(data, 0, newData, 0, data.length);
                data = newData;
            }
            return data;
        } catch (Exception e) {
            log.warn("getBitmapBytes failed for {}", key, e);
            return new byte[(mapWidth * mapHeight + 7) / 8];
        }
    }

    /** 从已读取的字节数组解码为 boolean 二维数组 */
    private boolean[][] decodeCachedBitmap(byte[] data, int width, int height) {
        boolean[][] grid = new boolean[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int offset = y * width + x;
                int byteIdx = offset / 8;
                if (byteIdx < data.length) {
                    // Redis 中 offset 0 对应字节 MSB (bit 7)
                    grid[y][x] = (data[byteIdx] & (1 << (7 - (offset % 8)))) != 0;
                }
            }
        }
        return grid;
    }

    /** @deprecated 分块后由 chunk 聚合方法替代，保留兼容旧调用 */
    @Deprecated
    public boolean[][] getBitmapAsGrid(String key, int width, int height) {
        byte[] data = getBitmapBytes(key);
        return decodeCachedBitmap(data, width, height);
    }

    /** 获取整个地图的探索状态（boolean 二维数组），chunk 聚合 + 二次 Pipeline */
    public boolean[][] getMapView() {
        refreshMapConfig();
        ensureChunkCache(mapWidth, mapHeight);

        // Pass 1: Pipeline 批量读取所有 chunk 版本号
        long[][] currentVersions = new long[totalChunkRows][totalChunkCols];
        try (Jedis jedis = pool.getResource()) {
            var pipeline = jedis.pipelined();
            var responses = new java.util.ArrayList<java.util.List<redis.clients.jedis.Response<String>>>();
            for (int cr = 0; cr < totalChunkRows; cr++) {
                var row = new java.util.ArrayList<redis.clients.jedis.Response<String>>();
                for (int cc = 0; cc < totalChunkCols; cc++) {
                    row.add(pipeline.get(ConfigConstants.mapViewChunkVerKey(cr, cc)));
                }
                responses.add(row);
            }
            pipeline.sync();
            for (int cr = 0; cr < totalChunkRows; cr++) {
                for (int cc = 0; cc < totalChunkCols; cc++) {
                    String val = responses.get(cr).get(cc).get();
                    currentVersions[cr][cc] = val != null ? Long.parseLong(val) : 0;
                }
            }
        }

        // Pass 2: 仅版本号变化的 chunk 批量 GET bitmap
        java.util.List<ChunkId> stale = new java.util.ArrayList<>();
        for (int cr = 0; cr < totalChunkRows; cr++) {
            for (int cc = 0; cc < totalChunkCols; cc++) {
                if (chunkViewCache[cr][cc] == null
                        || chunkViewVersions[cr][cc] != currentVersions[cr][cc]) {
                    stale.add(new ChunkId("v", cr, cc));
                }
            }
        }
        if (!stale.isEmpty()) {
            try (Jedis jedis = pool.getResource()) {
                var pipeline = jedis.pipelined();
                java.util.Map<ChunkId, redis.clients.jedis.Response<byte[]>> fresp = new java.util.LinkedHashMap<>();
                for (ChunkId ck : stale) {
                    fresp.put(ck, pipeline.get(ck.toKey().getBytes()));
                }
                pipeline.sync();
                int chunkBytes = (ConfigConstants.CHUNK_SIZE * ConfigConstants.CHUNK_SIZE + 7) / 8;
                for (ChunkId ck : stale) {
                    byte[] raw = fresp.get(ck).get();
                    if (raw == null) raw = new byte[chunkBytes];
                    chunkViewCache[ck.row][ck.col] = decodeCachedChunk(raw);
                    chunkViewVersions[ck.row][ck.col] = currentVersions[ck.row][ck.col];
                }
            }
        }

        // Pass 3: 内存拼接
        boolean[][] grid = new boolean[mapHeight][mapWidth];
        for (int y = 0; y < mapHeight; y++) {
            int cr = y / ConfigConstants.CHUNK_SIZE;
            int ly = y % ConfigConstants.CHUNK_SIZE;
            boolean[][] rowChunk = chunkViewCache[cr][0];
            boolean[] row = rowChunk[ly];
            int cc = 0;
            for (int x = 0; x < mapWidth; x++) {
                int ncc = x / ConfigConstants.CHUNK_SIZE;
                if (ncc != cc) {
                    cc = ncc;
                    rowChunk = chunkViewCache[cr][cc];
                    row = rowChunk[ly];
                }
                grid[y][x] = row[x % ConfigConstants.CHUNK_SIZE];
            }
        }
        return grid;
    }

    /** 设置地图某位的探索状态 */
    public void setMapViewBit(int x, int y) {
        int cr = y / ConfigConstants.CHUNK_SIZE, cc = x / ConfigConstants.CHUNK_SIZE;
        int lx = x % ConfigConstants.CHUNK_SIZE, ly = y % ConfigConstants.CHUNK_SIZE;
        try (Jedis jedis = pool.getResource()) {
            jedis.setbit(ConfigConstants.mapViewChunkKey(cr, cc), chunkOffset(lx, ly), true);
            removeFromUnexploredSet(jedis, x, y);
            jedis.incr(ConfigConstants.mapViewChunkVerKey(cr, cc));
            jedis.sadd(ConfigConstants.KEY_CHUNKS_MODIFIED, "v:" + cr + ":" + cc);
        }
        if (chunkViewCache != null && cr < chunkViewCache.length
                && cc < chunkViewCache[cr].length) {
            chunkViewCache[cr][cc] = null;
        }
    }

    /** 检查 (x,y) 是否已被探索 */
    public boolean isExplored(int x, int y) {
        int cr = y / ConfigConstants.CHUNK_SIZE, cc = x / ConfigConstants.CHUNK_SIZE;
        int lx = x % ConfigConstants.CHUNK_SIZE, ly = y % ConfigConstants.CHUNK_SIZE;
        try (Jedis jedis = pool.getResource()) {
            return jedis.getbit(ConfigConstants.mapViewChunkKey(cr, cc), chunkOffset(lx, ly));
        }
    }

    /** 3x3 点亮：Pipeline 批量写入 chunk，保证 9 格原子性，避免其他进程读到半成品 */
    public void illuminateArea(int cx, int cy) {
        refreshMapConfig();
        ensureChunkCache(mapWidth, mapHeight);
        int r = ConfigConstants.ILLUMINATE_RADIUS;
        try (Jedis jedis = pool.getResource()) {
            var pipeline = jedis.pipelined();
            java.util.Set<String> touchedChunks = new java.util.LinkedHashSet<>();
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    int nx = cx + dx;
                    int ny = cy + dy;
                    if (nx >= 0 && nx < mapWidth && ny >= 0 && ny < mapHeight
                            && !isBlocked(nx, ny)) {  // 障碍物不计入已探索，避免探索率超100%
                        int cr = ny / ConfigConstants.CHUNK_SIZE, cc = nx / ConfigConstants.CHUNK_SIZE;
                        int lx = nx % ConfigConstants.CHUNK_SIZE, ly = ny % ConfigConstants.CHUNK_SIZE;
                        pipeline.setbit(ConfigConstants.mapViewChunkKey(cr, cc), chunkOffset(lx, ly), true);
                        pipeline.srem(ConfigConstants.KEY_UNEXPLORED_SET, nx + "," + ny);
                        touchedChunks.add("v:" + cr + ":" + cc);
                    }
                }
            }
            for (String ck : touchedChunks) {
                String[] parts = ck.split(":");
                pipeline.incr(ConfigConstants.mapViewChunkVerKey(
                        Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
                pipeline.sadd(ConfigConstants.KEY_CHUNKS_MODIFIED, ck);
            }
            pipeline.sync();
            // 失效本地缓存
            for (String ck : touchedChunks) {
                String[] parts = ck.split(":");
                int cr = Integer.parseInt(parts[1]), cc = Integer.parseInt(parts[2]);
                if (chunkViewCache != null && cr < chunkViewCache.length
                        && cc < chunkViewCache[cr].length) {
                    chunkViewCache[cr][cc] = null;
                }
            }
        }
    }

    // ------ 障碍物 ------

    /** 检查 (x,y) 是否被阻塞（静态障碍物或动态小车） */
    public boolean isBlocked(int x, int y) {
        refreshMapConfig();
        int cr = y / ConfigConstants.CHUNK_SIZE, cc = x / ConfigConstants.CHUNK_SIZE;
        int lx = x % ConfigConstants.CHUNK_SIZE, ly = y % ConfigConstants.CHUNK_SIZE;
        try (Jedis jedis = pool.getResource()) {
            return jedis.getbit(ConfigConstants.mapBlockedChunkKey(cr, cc), chunkOffset(lx, ly));
        }
    }

    /** 批量初始化障碍物（TaskConfigurator 专用），Pipeline 聚合写入 */
    public void setBlockedBatch(List<Point> obstacles) {
        refreshMapConfig();
        java.util.Map<String, byte[]> chunkBuffers = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> chunkTypeMap = new java.util.LinkedHashMap<>();
        for (Point obs : obstacles) {
            int cr = obs.y / ConfigConstants.CHUNK_SIZE, cc = obs.x / ConfigConstants.CHUNK_SIZE;
            int lx = obs.x % ConfigConstants.CHUNK_SIZE, ly = obs.y % ConfigConstants.CHUNK_SIZE;
            String key = ConfigConstants.mapBlockedChunkKey(cr, cc);
            byte[] buf = chunkBuffers.get(key);
            if (buf == null) {
                buf = new byte[(ConfigConstants.CHUNK_SIZE * ConfigConstants.CHUNK_SIZE + 7) / 8];
                chunkBuffers.put(key, buf);
                chunkTypeMap.put(key, "b:" + cr + ":" + cc);
            }
            int offset = (int) chunkOffset(lx, ly);
            buf[offset / 8] |= (1 << (7 - (offset % 8)));
        }
        try (Jedis jedis = pool.getResource()) {
            var pipeline = jedis.pipelined();
            for (java.util.Map.Entry<String, byte[]> entry : chunkBuffers.entrySet()) {
                pipeline.set(entry.getKey().getBytes(), entry.getValue());
                String verKey = entry.getKey() + ":ver";
                pipeline.set(verKey, "1");
                pipeline.sadd(ConfigConstants.KEY_CHUNKS_MODIFIED, chunkTypeMap.get(entry.getKey()));
            }
            // 同步从 unexplored:set 移除
            for (Point obs : obstacles) {
                pipeline.srem(ConfigConstants.KEY_UNEXPLORED_SET, obs.x + "," + obs.y);
            }
            pipeline.sync();
        }
        invalidateAllChunkCache();
    }

    /** 设置障碍物，同步从 unexplored:set 移除 */
    public void setBlocked(int x, int y) {
        refreshMapConfig();
        int cr = y / ConfigConstants.CHUNK_SIZE, cc = x / ConfigConstants.CHUNK_SIZE;
        int lx = x % ConfigConstants.CHUNK_SIZE, ly = y % ConfigConstants.CHUNK_SIZE;
        try (Jedis jedis = pool.getResource()) {
            jedis.setbit(ConfigConstants.mapBlockedChunkKey(cr, cc), chunkOffset(lx, ly), true);
            removeFromUnexploredSet(jedis, x, y);
            jedis.incr(ConfigConstants.mapBlockedChunkVerKey(cr, cc));
            jedis.sadd(ConfigConstants.KEY_CHUNKS_MODIFIED, "b:" + cr + ":" + cc);
        }
        if (chunkBlockedCache != null && cr < chunkBlockedCache.length
                && cc < chunkBlockedCache[cr].length) {
            chunkBlockedCache[cr][cc] = null;
        }
    }

    /** 清除障碍物 */
    public void clearBlocked(int x, int y) {
        refreshMapConfig();
        int cr = y / ConfigConstants.CHUNK_SIZE, cc = x / ConfigConstants.CHUNK_SIZE;
        int lx = x % ConfigConstants.CHUNK_SIZE, ly = y % ConfigConstants.CHUNK_SIZE;
        try (Jedis jedis = pool.getResource()) {
            jedis.setbit(ConfigConstants.mapBlockedChunkKey(cr, cc), chunkOffset(lx, ly), false);
            jedis.incr(ConfigConstants.mapBlockedChunkVerKey(cr, cc));
            jedis.sadd(ConfigConstants.KEY_CHUNKS_MODIFIED, "b:" + cr + ":" + cc);
        }
        if (chunkBlockedCache != null && cr < chunkBlockedCache.length
                && cc < chunkBlockedCache[cr].length) {
            chunkBlockedCache[cr][cc] = null;
        }
    }

    /** 获取所有障碍物坐标列表 */
    public List<Point> getAllBlocked() {
        refreshMapConfig();
        ensureChunkCache(mapWidth, mapHeight);
        List<Point> list = new ArrayList<>();
        for (int cr = 0; cr < totalChunkRows; cr++) {
            for (int cc = 0; cc < totalChunkCols; cc++) {
                boolean[][] chunk = getOrFetchBlockedChunk(cr, cc);
                int baseY = cr * ConfigConstants.CHUNK_SIZE;
                int baseX = cc * ConfigConstants.CHUNK_SIZE;
                for (int ly = 0; ly < chunk.length; ly++) {
                    boolean[] row = chunk[ly];
                    for (int lx = 0; lx < row.length; lx++) {
                        if (row[lx]) {
                            int gx = baseX + lx;
                            int gy = baseY + ly;
                            if (gx < mapWidth && gy < mapHeight) {
                                list.add(new Point(gx, gy));
                            }
                        }
                    }
                }
            }
        }
        return list;
    }

    /** 获取地图阻挡状态二维数组（供 Navigator 路径规划使用），chunk 聚合 + 二次 Pipeline */
    public boolean[][] getMapBlocked() {
        refreshMapConfig();
        ensureChunkCache(mapWidth, mapHeight);

        // Pass 1: Pipeline 批量读取所有 chunk 版本号
        long[][] currentVersions = new long[totalChunkRows][totalChunkCols];
        try (Jedis jedis = pool.getResource()) {
            var pipeline = jedis.pipelined();
            var responses = new java.util.ArrayList<java.util.List<redis.clients.jedis.Response<String>>>();
            for (int cr = 0; cr < totalChunkRows; cr++) {
                var row = new java.util.ArrayList<redis.clients.jedis.Response<String>>();
                for (int cc = 0; cc < totalChunkCols; cc++) {
                    row.add(pipeline.get(ConfigConstants.mapBlockedChunkVerKey(cr, cc)));
                }
                responses.add(row);
            }
            pipeline.sync();
            for (int cr = 0; cr < totalChunkRows; cr++) {
                for (int cc = 0; cc < totalChunkCols; cc++) {
                    String val = responses.get(cr).get(cc).get();
                    currentVersions[cr][cc] = val != null ? Long.parseLong(val) : 0;
                }
            }
        }

        // Pass 2: 仅版本号变化的 chunk 批量 GET bitmap
        java.util.List<ChunkId> stale = new java.util.ArrayList<>();
        for (int cr = 0; cr < totalChunkRows; cr++) {
            for (int cc = 0; cc < totalChunkCols; cc++) {
                if (chunkBlockedCache[cr][cc] == null
                        || chunkBlockedVersions[cr][cc] != currentVersions[cr][cc]) {
                    stale.add(new ChunkId("b", cr, cc));
                }
            }
        }
        if (!stale.isEmpty()) {
            try (Jedis jedis = pool.getResource()) {
                var pipeline = jedis.pipelined();
                java.util.Map<ChunkId, redis.clients.jedis.Response<byte[]>> fresp = new java.util.LinkedHashMap<>();
                for (ChunkId ck : stale) {
                    fresp.put(ck, pipeline.get(ck.toKey().getBytes()));
                }
                pipeline.sync();
                int chunkBytes = (ConfigConstants.CHUNK_SIZE * ConfigConstants.CHUNK_SIZE + 7) / 8;
                for (ChunkId ck : stale) {
                    byte[] raw = fresp.get(ck).get();
                    if (raw == null) raw = new byte[chunkBytes];
                    chunkBlockedCache[ck.row][ck.col] = decodeCachedChunk(raw);
                    chunkBlockedVersions[ck.row][ck.col] = currentVersions[ck.row][ck.col];
                }
            }
        }

        // Pass 3: 内存拼接
        boolean[][] grid = new boolean[mapHeight][mapWidth];
        for (int y = 0; y < mapHeight; y++) {
            int cr = y / ConfigConstants.CHUNK_SIZE;
            int ly = y % ConfigConstants.CHUNK_SIZE;
            boolean[][] rowChunk = chunkBlockedCache[cr][0];
            boolean[] row = rowChunk[ly];
            int cc = 0;
            for (int x = 0; x < mapWidth; x++) {
                int ncc = x / ConfigConstants.CHUNK_SIZE;
                if (ncc != cc) {
                    cc = ncc;
                    rowChunk = chunkBlockedCache[cr][cc];
                    row = rowChunk[ly];
                }
                grid[y][x] = row[x % ConfigConstants.CHUNK_SIZE];
            }
        }
        return grid;
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
            jedis.hset(ConfigConstants.carPositionKey(carId), "x", String.valueOf(x));
            jedis.hset(ConfigConstants.carPositionKey(carId), "y", String.valueOf(y));
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
            jedis.del(key);
            if (path != null && !path.isEmpty()) {
                // 顺序压入，LPUSH 保证 RPOP 时按序（第一步先出）
                String[] jsons = new String[path.size()];
                for (int i = 0; i < path.size(); i++) {
                    jsons[i] = JSON.toJSONString(path.get(i));
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
            for (Map.Entry<String, String> entry : config.entrySet()) {
                jedis.hset(ConfigConstants.KEY_TASK_CONFIG, entry.getKey(), entry.getValue());
            }
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
        refreshMapConfig();
        int rows = (mapHeight + ConfigConstants.CHUNK_SIZE - 1) / ConfigConstants.CHUNK_SIZE;
        int cols = (mapWidth  + ConfigConstants.CHUNK_SIZE - 1) / ConfigConstants.CHUNK_SIZE;
        try (Jedis jedis = pool.getResource()) {
            var pipeline = jedis.pipelined();
            java.util.List<redis.clients.jedis.Response<Long>> responses = new java.util.ArrayList<>();
            for (int cr = 0; cr < rows; cr++) {
                for (int cc = 0; cc < cols; cc++) {
                    responses.add(pipeline.bitcount(ConfigConstants.mapViewChunkKey(cr, cc)));
                }
            }
            pipeline.sync();
            int count = 0;
            for (var resp : responses) {
                count += resp.get();
            }
            return count;
        }
    }

    /** 获取探索百分比 (0.0 ~ 100.0)，排除障碍物 */
    public double getExploredPercent() {
        refreshMapConfig();
        int obstacleCount = getObstacleCount();
        int explorable = mapWidth * mapHeight - obstacleCount;
        if (explorable <= 0) return 100.0;
        double pct = getExploredCount() * 100.0 / explorable;
        return Math.min(100.0, pct);
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

    // ==================== 动态小车管理 ====================

    /**
     * 增量添加小车（不触发 flushDB，保持探索状态）
     * @param carId 小车 ID (e.g., "Car005")
     * @param x     X 坐标
     * @param y     Y 坐标
     */
    public void addCar(String carId, int x, int y) {
        try (Jedis jedis = pool.getResource()) {
            jedis.hset(ConfigConstants.carPositionKey(carId), "x", String.valueOf(x));
            jedis.hset(ConfigConstants.carPositionKey(carId), "y", String.valueOf(y));
            jedis.set(ConfigConstants.carStatusKey(carId), CarStatus.IDLE.name());
            jedis.set(ConfigConstants.carStepsKey(carId), "0");

            // 更新 config:task 中的 cars 列表
            String carsJson = jedis.hget(ConfigConstants.KEY_TASK_CONFIG, "cars");
            List<String> cars;
            if (carsJson != null && !carsJson.isEmpty()) {
                cars = new ArrayList<>(JSON.parseArray(carsJson, String.class));
            } else {
                cars = new ArrayList<>();
            }
            if (!cars.contains(carId)) {
                cars.add(carId);
                jedis.hset(ConfigConstants.KEY_TASK_CONFIG, "cars", JSON.toJSONString(cars));
            }

            // 点亮出生点
            illuminateArea(x, y);
            // 推 ROUTE_NEEDED 触发导航
            pushTask("ROUTE_NEEDED", carId, null);
            log.info("已增量添加小车: carId={}, position=({},{})", carId, x, y);
        }
    }

    /**
     * 增量移除小车（不触发 flushDB，保持其他小车状态）
     * @param carId 小车 ID
     */
    public void removeCar(String carId) {
        try (Jedis jedis = pool.getResource()) {
            // 清理当前位置的动态障碍物标记
            Point pos = getCarPosition(carId);
            if (pos != null) {
                jedis.setbit(ConfigConstants.mapBlockedChunkKey(
                        pos.y / ConfigConstants.CHUNK_SIZE,
                        pos.x / ConfigConstants.CHUNK_SIZE),
                        chunkOffset(pos.x % ConfigConstants.CHUNK_SIZE,
                                pos.y % ConfigConstants.CHUNK_SIZE), false);
                jedis.incr(ConfigConstants.mapBlockedChunkVerKey(
                        pos.y / ConfigConstants.CHUNK_SIZE,
                        pos.x / ConfigConstants.CHUNK_SIZE));
                jedis.sadd(ConfigConstants.KEY_CHUNKS_MODIFIED, "b:"
                        + pos.y / ConfigConstants.CHUNK_SIZE + ":"
                        + pos.x / ConfigConstants.CHUNK_SIZE);
            }
            // 删除所有 car:{id}:* 键
            jedis.del(ConfigConstants.carStatusKey(carId));
            jedis.del(ConfigConstants.carPositionKey(carId));
            jedis.del(ConfigConstants.carStepsKey(carId));
            jedis.del(ConfigConstants.carTargetKey(carId));
            jedis.del(ConfigConstants.carRouteKey(carId));
            jedis.del(ConfigConstants.carBlockedTickKey(carId));
            // 删除锁键（可能残留）
            jedis.del(ConfigConstants.carLockKey(carId));
            // 从 cars 列表中移除
            String carsJson = jedis.hget(ConfigConstants.KEY_TASK_CONFIG, "cars");
            if (carsJson != null && !carsJson.isEmpty()) {
                List<String> cars = new ArrayList<>(JSON.parseArray(carsJson, String.class));
                cars.remove(carId);
                jedis.hset(ConfigConstants.KEY_TASK_CONFIG, "cars", JSON.toJSONString(cars));
            }
            log.info("已移除小车: carId={}", carId);
        }
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
        refreshMapConfig();
        int rows = (mapHeight + ConfigConstants.CHUNK_SIZE - 1) / ConfigConstants.CHUNK_SIZE;
        int cols = (mapWidth  + ConfigConstants.CHUNK_SIZE - 1) / ConfigConstants.CHUNK_SIZE;
        try (Jedis jedis = pool.getResource()) {
            var pipeline = jedis.pipelined();
            java.util.List<redis.clients.jedis.Response<Long>> responses = new java.util.ArrayList<>();
            for (int cr = 0; cr < rows; cr++) {
                for (int cc = 0; cc < cols; cc++) {
                    responses.add(pipeline.bitcount(ConfigConstants.mapBlockedChunkKey(cr, cc)));
                }
            }
            pipeline.sync();
            int count = 0;
            for (var resp : responses) {
                count += resp.get();
            }
            return count;
        }
    }

    // ==================== FIFO 任务队列 ====================

    /**
     * 向 taskQueue 队尾 RPUSH 一个任务
     * @param taskType  任务类型 (ROUTE_NEEDED / MOVE_READY / BLOCKED)
     * @param carId     关联车辆 ID
     * @param extra     附加字段 (可为 null)
     */
    public void pushTask(String taskType, String carId, Map<String, String> extra) {
        try (Jedis jedis = pool.getResource()) {
            Map<String, String> task = new LinkedHashMap<>();
            task.put("type", taskType);
            task.put("carId", carId);
            if (extra != null) {
                task.putAll(extra);
            }
            jedis.rpush(ConfigConstants.KEY_TASK_QUEUE, JSON.toJSONString(task));
        }
    }

    /**
     * 推送全局任务（无 carId），供 Display/Controller 使用
     * 任务类型: START / PAUSE / SET_CONFIG / RESET
     */
    public void pushTask(String taskType, Map<String, String> extra) {
        try (Jedis jedis = pool.getResource()) {
            Map<String, String> task = new LinkedHashMap<>();
            task.put("type", taskType);
            if (extra != null) {
                task.putAll(extra);
            }
            jedis.rpush(ConfigConstants.KEY_TASK_QUEUE, JSON.toJSONString(task));
        }
    }

    /**
     * 从 taskQueue 队首 LPOP 一个任务
     * @return 任务 Map, 队列为空返回 null
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> popTask() {
        try (Jedis jedis = pool.getResource()) {
            String json = jedis.lpop(ConfigConstants.KEY_TASK_QUEUE);
            if (json == null) return null;
            return JSON.parseObject(json, Map.class);
        }
    }

    /** BLPOP 阻塞式出队（事件驱动架构用），超时返回 null */
    @SuppressWarnings("unchecked")
    public Map<String, String> blockingPopTask(int timeoutSeconds) {
        try (Jedis jedis = pool.getResource()) {
            List<String> result = jedis.blpop(timeoutSeconds, ConfigConstants.KEY_TASK_QUEUE);
            if (result == null || result.size() < 2) return null;
            return JSON.parseObject(result.get(1), Map.class);
        }
    }

    /** 获取 taskQueue 当前长度 */
    public long getTaskQueueLength() {
        try (Jedis jedis = pool.getResource()) {
            return jedis.llen(ConfigConstants.KEY_TASK_QUEUE);
        }
    }

    /** 清空 taskQueue */
    public void clearTaskQueue() {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(ConfigConstants.KEY_TASK_QUEUE);
        }
    }

    // ==================== 分布式锁 ====================

    public DistributedLock getCarLock(String carId) {
        return new DistributedLock(pool, ConfigConstants.carLockKey(carId));
    }

    public DistributedLock getControllerLock() {
        return new DistributedLock(pool, ConfigConstants.KEY_LOCK_CONTROLLER);
    }

    /**
     * @deprecated 目标分配已由 TargetPlanner 模块接管，无全局锁
     */
    @Deprecated
    public DistributedLock getTargetAllocationLock() {
        return new DistributedLock(pool, ConfigConstants.KEY_LOCK_TARGET_ALLOCATION);
    }

    // ==================== 未探索区域索引 ====================

    /**
     * 初始化未探索区域索引（仅在 TaskConfigurator 调用 clearAll 后使用）
     * 将所有非障碍物格子加入 unexplored:set，供 O(1) 随机选取
     */
    public void initUnexploredSet(int width, int height) {
        try (Jedis jedis = pool.getResource()) {
            String key = ConfigConstants.KEY_UNEXPLORED_SET;
            jedis.del(key);
            String[] members = new String[width * height];
            int idx = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    members[idx++] = x + "," + y;
                }
            }
            // 分批 SADD，避免单次命令参数过多
            int batchSize = 1000;
            for (int i = 0; i < members.length; i += batchSize) {
                int end = Math.min(i + batchSize, members.length);
                jedis.sadd(key, java.util.Arrays.copyOfRange(members, i, end));
            }
        }
    }

    /** 从未探索索引中移除 (x,y)（内部使用，复用 Jedis 连接） */
    private void removeFromUnexploredSet(Jedis jedis, int x, int y) {
        jedis.srem(ConfigConstants.KEY_UNEXPLORED_SET, x + "," + y);
    }

    /** 从未探索索引中移除 (x,y) */
    public void removeFromUnexploredSet(int x, int y) {
        try (Jedis jedis = pool.getResource()) {
            removeFromUnexploredSet(jedis, x, y);
        }
    }

    /** O(1) 随机获取一个未探索坐标（供 TargetPlanner / Navigator 使用） */
    public Point getRandomUnexplored() {
        try (Jedis jedis = pool.getResource()) {
            String member = jedis.srandmember(ConfigConstants.KEY_UNEXPLORED_SET);
            if (member == null) return null;
            String[] parts = member.split(",");
            return new Point(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        }
    }

    /** 获取未探索坐标总数 */
    public long getUnexploredCount() {
        try (Jedis jedis = pool.getResource()) {
            return jedis.scard(ConfigConstants.KEY_UNEXPLORED_SET);
        }
    }

    // ==================== 管理 ====================

    /** 仅 TaskConfigurator 调用：清空所有数据 */
    public void clearAll() {
        try (Jedis jedis = pool.getResource()) {
            jedis.flushDB();
            // 清除本地缓存
            cachedMapViewBytes = null;
            cachedMapViewVersion = -1;
            cachedMapBlockedBytes = null;
            cachedMapBlockedVersion = -1;
            invalidateAllChunkCache();
            log.info("Redis 数据库已清空 (FLUSHDB)");
        }
    }

    // ==================== 多机归属与暂停 ====================

    public void setCarOwner(String carId, String machineId) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set("car:" + carId + ":owner", machineId);
        }
    }

    public String getCarOwner(String carId) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.get("car:" + carId + ":owner");
        }
    }

    public void setGlobalPause(boolean paused) {
        try (Jedis jedis = pool.getResource()) {
            if (paused) {
                jedis.set("pause:global", "true");
            } else {
                jedis.del("pause:global");
            }
        }
    }

    public boolean isGlobalPaused() {
        try (Jedis jedis = pool.getResource()) {
            return jedis.exists("pause:global");
        }
    }

    public void setOperatorPause(String machineId, boolean paused) {
        try (Jedis jedis = pool.getResource()) {
            String key = "pause:operator:" + machineId;
            if (paused) {
                jedis.set(key, "true");
            } else {
                jedis.del(key);
            }
        }
    }

    public boolean isOperatorPaused(String machineId) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.exists("pause:operator:" + machineId);
        }
    }

    /** 关闭连接池 */
    public void close() {
        if (pool != null && !pool.isClosed()) {
            pool.close();
        }
    }

    // ==================== 地图分块 ====================

    public static class ChunkId {
        public final String type;   // "v" | "b"
        public final int row, col;

        public ChunkId(String type, int row, int col) {
            this.type = type;
            this.row = row;
            this.col = col;
        }

        public String toKey() {
            if ("v".equals(type)) return ConfigConstants.mapViewChunkKey(row, col);
            return ConfigConstants.mapBlockedChunkKey(row, col);
        }

        public String toVerKey() {
            if ("v".equals(type)) return ConfigConstants.mapViewChunkVerKey(row, col);
            return ConfigConstants.mapBlockedChunkVerKey(row, col);
        }

        public String toModifiedMember() {
            return type + ":" + row + ":" + col;
        }
    }

    public static class MapChunk {
        public int chunkRow, chunkCol;
        public boolean[][] data;
        public long version;
    }
}
