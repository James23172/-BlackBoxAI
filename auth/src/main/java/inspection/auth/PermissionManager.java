package inspection.auth;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import inspection.auth.model.Role;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class PermissionManager {
    private static final String PERM_KEY = "auth:permissions";
    private static final byte[] AES_KEY = "BlackBoxAI!2024!!".getBytes(StandardCharsets.UTF_8); // 16 bytes
    private final JedisPool pool;

    // 默认权限矩阵
    public static final Map<Role, List<String>> DEFAULT_PERMISSIONS = Map.of(
        Role.CONFIGURATOR, List.of("START", "PAUSE", "RESET", "SET_CONFIG", "ADD_CAR", "REMOVE_CAR",
                                    "TOGGLE_OBSTACLE", "RECORD_START", "RECORD_STOP", "VIEW_ALL"),
        Role.OPERATOR,     List.of("START", "PAUSE", "VIEW_ALL"),
        Role.ANALYST,      List.of("VIEW_ALL", "REPLAY_PLAY", "REPLAY_SEEK")
    );

    public PermissionManager(JedisPool pool) { this.pool = pool; }

    public boolean canDo(Role role, String action) {
        List<String> perms = DEFAULT_PERMISSIONS.getOrDefault(role, Collections.emptyList());
        return perms.contains(action);
    }

    public void savePermissions(Map<Role, List<String>> perms) {
        try (Jedis jedis = pool.getResource()) {
            String json = JSON.toJSONString(perms);
            byte[] encrypted = aesEncrypt(json);
            jedis.set(PERM_KEY.getBytes(StandardCharsets.UTF_8), encrypted);
        } catch (Exception e) { /* ignore */ }
    }

    @SuppressWarnings("unchecked")
    public Map<String, List<String>> loadPermissions() {
        try (Jedis jedis = pool.getResource()) {
            byte[] data = jedis.get(PERM_KEY.getBytes(StandardCharsets.UTF_8));
            if (data == null) return null;
            String json = aesDecrypt(data);
            return JSON.parseObject(json, Map.class);
        } catch (Exception e) { return null; }
    }

    private byte[] aesEncrypt(String plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(AES_KEY, "AES"));
        return c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
    }

    private String aesDecrypt(byte[] encrypted) throws Exception {
        Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(AES_KEY, "AES"));
        return new String(c.doFinal(encrypted), StandardCharsets.UTF_8);
    }
}
