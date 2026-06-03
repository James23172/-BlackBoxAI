package inspection.common.enums;

/**
 * 小车状态枚举
 *
 * 状态流转:
 *   IDLE → (分配目标) → WAITING_ROUTE → (路径规划完) → READY → (收到TICK_MOVE) → MOVING
 *   MOVING → (还有剩余步骤) → READY  或  (无剩余步骤) → IDLE
 *   MOVING → (遇阻) → BLOCKED → (超时恢复) → IDLE
 */
public enum CarStatus {
    /** 空闲，等待分配目标 */
    IDLE,
    /** 已分配目标，等待路径规划 */
    WAITING_ROUTE,
    /** 路径就绪，可以移动 */
    READY,
    /** 正在移动中 */
    MOVING,
    /** 遇到障碍，等待 Controller 重新分配 */
    BLOCKED
}
