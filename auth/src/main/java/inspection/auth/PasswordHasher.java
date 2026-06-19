package inspection.auth;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public class PasswordHasher {
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 两层 SHA-256 存储哈希
     * @param password 前端 SHA-256 后传输的 transHash（非明文密码）
     * @return "salt:hash" 格式字符串，其中 hash = SHA-256(salt + transHash)
     */
    public static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = sha256(salt, password);
        return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(hash);
    }

    public static boolean verify(String password, String stored) {
        try {
            String[] parts = stored.split(":");
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            byte[] expected = Base64.getDecoder().decode(parts[1]);
            byte[] actual = sha256(salt, password);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) { return false; }
    }

    private static byte[] sha256(byte[] salt, String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            return md.digest(password.getBytes("UTF-8"));
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
