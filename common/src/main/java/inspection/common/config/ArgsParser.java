package inspection.common.config;

import java.util.HashMap;
import java.util.Map;

/**
 * 命令行参数解析工具
 * 用法: ArgsParser args = new ArgsParser(args);
 *       String host = args.get("--redis-host", ConfigConstants.REDIS_HOST);
 */
public class ArgsParser {
    private final Map<String, String> map = new HashMap<>();

    public ArgsParser(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length) {
                map.put(args[i], args[i + 1]);
                i++;
            }
        }
    }

    public String get(String key, String defaultValue) {
        return map.getOrDefault(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        String v = map.get(key);
        return v != null ? Integer.parseInt(v) : defaultValue;
    }
}
