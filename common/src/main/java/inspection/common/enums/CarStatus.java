package inspection.common.enums;

/**
 * 小车状态枚举 (3 状态)
 *
 * 状态流转:
 *   IDLE → (Controller 发 MOVE_STEP) → MOVING
 *   MOVING → (路径耗尽 / 目标已探索) → IDLE
 *   MOVING → (遇阻) → BLOCKED → (超时恢复) → IDLE
 */
public enum CarStatus {
    /** 空闲，等待路径规划与移动指令 */
    IDLE,
    /** 正在移动中 */
    MOVING,
    /** 遇到障碍，等待超时恢复 */
    BLOCKED
}
