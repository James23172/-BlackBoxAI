package inspection.common.client;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import inspection.common.config.ConfigConstants;

/**
 * 基于 Redis 的分布式锁
 *
 * 使用 SET NX EX 实现，防止死锁
 *
 * 每次操作从连接池获取/归还连接，避免泄漏。
 *
 * 用法:
 *   DistributedLock lock = bb.getCarLock("Car001");
 *   if (lock.tryLock()) {
 *       try { ... } finally { lock.unlock(); }
 *   }
 */
public class DistributedLock {
    private static final Logger log = LoggerFactory.getLogger(DistributedLock.class);

    private final JedisPool pool;
    private final String lockKey;
    private final String lockValue;  // 用于安全释放

    public DistributedLock(JedisPool pool, String lockKey) {
        this.pool = pool;
        this.lockKey = lockKey;
        this.lockValue = java.util.UUID.randomUUID().toString() + ":" + Thread.currentThread().getId();
    }

    /**
     * 尝试获取锁（非阻塞）
     * @return true = 获取成功
     */
    public boolean tryLock() {
        try (Jedis jedis = pool.getResource()) {
            SetParams params = SetParams.setParams()
                    .nx()
                    .ex(ConfigConstants.LOCK_EXPIRE_SECONDS);
            String result = jedis.set(lockKey, lockValue, params);
            boolean locked = "OK".equals(result);
            if (locked) {
                log.debug("锁获取成功: {}", lockKey);
            } else {
                log.debug("锁获取失败（已被占用）: {}", lockKey);
            }
            return locked;
        }
    }

    /**
     * 尝试获取锁（阻塞，带超时）
     * @param timeoutMs 最大等待毫秒
     * @return true = 获取成功
     */
    public boolean tryLock(long timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (tryLock()) return true;
            try {
                Thread.sleep(ConfigConstants.LOCK_RETRY_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * 释放锁（只有持有者才能释放）
     */
    public void unlock() {
        // Lua 脚本保证原子性: 只有 value 匹配才删除
        String script = "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                        "return redis.call('del', KEYS[1]) else return 0 end";
        try (Jedis jedis = pool.getResource()) {
            jedis.eval(script, 1, lockKey, lockValue);
        }
        log.debug("锁已释放: {}", lockKey);
    }

    /**
     * 续期：延长锁的过期时间
     * @param seconds 续期秒数
     */
    public void renew(int seconds) {
        String script = "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                        "return redis.call('expire', KEYS[1], ARGV[2]) else return 0 end";
        try (Jedis jedis = pool.getResource()) {
            jedis.eval(script, 1, lockKey, lockValue, String.valueOf(seconds));
        }
    }

    public String getLockKey() { return lockKey; }
}
