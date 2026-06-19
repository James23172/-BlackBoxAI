package inspection.common.enums;

/**
 * 全部 RabbitMQ 命令枚举
 *
 * 用于 MQMessage.cmd 字段，所有模块统一使用
 * Controller 不监听任何队列，只通过 Redis taskQueue 接收反馈
 */
public enum CommandType {
    // ===== Controller → 各知识源 =====
    /** 下发导航命令（目标选择+路径规划）→ Navigator4CarID 队列 */
    NAVIGATE,
    /** 下发小车移动命令 → Car:{carId} 队列 */
    MOVE_STEP,
    /** 配置转发 → TaskConfigCmd 队列 */
    FORWARD_CONFIG,

    // ===== Display 相关 =====
    /** 刷新前端全部状态 → UpdateView Fanout */
    REFRESH_ALL,
    /** 前端设置配置 → Redis taskQueue */
    SET_CONFIG,
    /** 前端重置 → Redis taskQueue */
    RESET,

    // ===== 全局 =====
    /** 巡检完成 */
    TASK_COMPLETE
}
