package inspection.common.enums;

/**
 * 全部 RabbitMQ 命令枚举
 *
 * 用于 MQMessage.cmd 字段，所有模块统一使用
 */
public enum CommandType {
    // ===== Controller → 各知识源 =====
    /** 下发分配目标命令 → TargetPlannerCmd 队列 */
    ASSIGN_TARGET,
    /** 下发路径规划命令 → NavigatorCmd 队列 */
    PLAN_ROUTE,
    /** 下发小车移动命令 → Car_{carId} 队列 */
    TICK_MOVE,
    /** 配置转发 → TaskConfigCmd 队列 */
    FORWARD_CONFIG,

    // ===== 知识源 → Controller (回复) =====
    /** 目标已分配 → ControllerCmd 队列 */
    TARGET_ASSIGNED,
    /** 路径已规划 → ControllerCmd 队列 */
    ROUTE_PLANNED,
    /** 小车已移动 → ControllerCmd 队列 */
    MOVED,
    /** 小车遇阻 → ControllerCmd 队列 */
    CAR_BLOCKED,
    /** 路径走完 → ControllerCmd 队列 */
    ROUTE_DONE,
    /** 任务配置就绪 → ControllerCmd 队列 */
    TASK_READY,

    // ===== Display 相关 =====
    /** 刷新前端全部状态 → UpdateView Fanout */
    REFRESH_ALL,
    /** 前端设置配置 → ControllerCmd 队列 */
    SET_CONFIG,
    /** 前端重置 → ControllerCmd 队列 */
    RESET,

    // ===== 全局 =====
    /** 巡检完成 */
    TASK_COMPLETE
}
