package inspection.auth;

import com.alibaba.fastjson2.JSON;
import inspection.auth.model.Role;
import inspection.auth.model.User;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

public class UserManager {
    private static final String KEY_PREFIX = "auth:user:";
    private final JedisPool pool;

    public UserManager(JedisPool pool) { this.pool = pool; }

    public boolean register(String username, String password, Role role) {
        try (Jedis jedis = pool.getResource()) {
            String key = KEY_PREFIX + username;
            if (jedis.exists(key)) return false;
            String hash = PasswordHasher.hash(password);
            User user = new User(username, hash, role);
            jedis.set(key, JSON.toJSONString(user));
            return true;
        }
    }

    public User login(String username, String password) {
        try (Jedis jedis = pool.getResource()) {
            String json = jedis.get(KEY_PREFIX + username);
            if (json == null) return null;
            User user = JSON.parseObject(json, User.class);
            if (!PasswordHasher.verify(password, user.getPasswordHash())) return null;
            return user;
        }
    }

    public User getUser(String username) {
        try (Jedis jedis = pool.getResource()) {
            String json = jedis.get(KEY_PREFIX + username);
            return json != null ? JSON.parseObject(json, User.class) : null;
        }
    }

    /** 获取所有用户列表（配置员管理用） */
    public java.util.List<User> listAll() {
        java.util.List<User> result = new java.util.ArrayList<>();
        try (Jedis jedis = pool.getResource()) {
            for (String key : jedis.keys(KEY_PREFIX + "*")) {
                String json = jedis.get(key);
                if (json != null) {
                    result.add(JSON.parseObject(json, User.class));
                }
            }
        }
        return result;
    }

    /** 删除用户（禁止删除管理员） */
    public boolean delete(String username) {
        if ("admin".equals(username)) return false;
        try (Jedis jedis = pool.getResource()) {
            return jedis.del(KEY_PREFIX + username) > 0;
        }
    }
}
