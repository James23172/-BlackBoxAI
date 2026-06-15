package inspection.common.model;

import java.util.Map;

/**
 * RabbitMQ 消息模型
 * 所有模块通过此类收发消息
 *
 * 格式: {"cmd": "PLAN_ROUTE", "data": {...}, "timestamp": 1717401600000}
 */
public class MQMessage {
    public String cmd;
    public Map<String, Object> data;
    public long timestamp;

    public MQMessage() {}

    public MQMessage(String cmd, Map<String, Object> data) {
        this.cmd = cmd;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    public MQMessage(String cmd, Map<String, Object> data, long timestamp) {
        this.cmd = cmd;
        this.data = data;
        this.timestamp = timestamp;
    }

    public String getCmd() { return cmd; }
    public void setCmd(String cmd) { this.cmd = cmd; }

    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> data) { this.data = data; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    /** 从 data 中获取字符串字段 */
    public String getDataString(String key) {
        Object val = data != null ? data.get(key) : null;
        return val != null ? val.toString() : null;
    }

    /** 从 data 中获取整数字段 */
    public Integer getDataInt(String key) {
        Object val = data != null ? data.get(key) : null;
        if (val instanceof Number) return ((Number) val).intValue();
        return val != null ? Integer.parseInt(val.toString()) : null;
    }

    /** 从 data 中获取布尔字段 */
    public Boolean getDataBoolean(String key) {
        Object val = data != null ? data.get(key) : null;
        if (val instanceof Boolean) return (Boolean) val;
        return val != null ? Boolean.parseBoolean(val.toString()) : null;
    }

    @Override
    public String toString() {
        return "MQMessage{cmd='" + cmd + "', data=" + data + ", ts=" + timestamp + "}";
    }
}
