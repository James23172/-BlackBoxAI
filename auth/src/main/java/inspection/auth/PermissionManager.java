package inspection.auth;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import inspection.auth.model.Role;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PermissionManager {
    private static final Logger log = LoggerFactory.getLogger(PermissionManager.class);
    private static final String PERM_KEY = "auth:permissions";
    private static final byte[] AES_KEY = System.getProperty(
        "auth.aes.key", "BlackBoxAI!2024!").getBytes(StandardCharsets.UTF_8);  // 恰好 16 字节
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
        } catch (Exception e) { log.error("保存权限配置失败", e); }
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
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(AES_KEY, "AES"), spec);
        byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] result = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(ct, 0, result, iv.length, ct.length);
        return result;
    }

    private String aesDecrypt(byte[] encrypted) throws Exception {
        byte[] iv = java.util.Arrays.copyOfRange(encrypted, 0, 12);
        byte[] ct = java.util.Arrays.copyOfRange(encrypted, 12, encrypted.length);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(AES_KEY, "AES"), spec);
        return new String(c.doFinal(ct), StandardCharsets.UTF_8);
    }
}
