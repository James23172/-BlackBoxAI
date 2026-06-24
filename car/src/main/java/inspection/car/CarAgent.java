package inspection.car;

import com.alibaba.fastjson2.JSONObject;
import inspection.common.client.BlackboardClient;
import inspection.common.client.MessageBusClient;
import inspection.common.config.ConfigConstants;
import inspection.common.enums.CarStatus;
import inspection.common.enums.CommandType;
import inspection.common.model.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 小车代理（每车一个进程）
 * 按照架构文档：收到 MOVE_STEP → 自主判断最优路径 → 移动 → 点亮
 * 知识源反馈只通过 Redis taskQueue，不通过 RabbitMQ 回复 Controller
 */
public class CarAgent {
    private static final Logger log = LoggerFactory.getLogger(CarAgent.class);

    private final String carId;
    private final BlackboardClient bb;
    private final MessageBusClient mq;
    private final Illuminator illuminator;
    private final DynamicObstacleManager obstacleManager;
    private final AtomicLong currentTick = new AtomicLong(0);

    public CarAgent(String carId, BlackboardClient bb, MessageBusClient mq) {
        this.carId = carId;
        this.bb = bb;
        this.mq = mq;
        this.illuminator = new Illuminator(bb);
        this.obstacleManager = new DynamicObstacleManager(bb);
    }

    public void handleTickMove(long tick) {
        currentTick.set(tick);

        CarStatus status = bb.getCarStatus(carId);
        Point curPos = bb.getCarPosition(carId);
        // 如果 RESET 后位置被清除，等待 TaskConfigurator 重新初始化
        if (curPos == null) {
            log.debug("[Car:{}] 位置不存在（可能正在重置），跳过", carId);
            return;
        }
        log.info("📩 [Car:{}] 收到 MOVE_STEP tick={}, 状态={}, pos=({},{})",
                carId, tick, status,
                curPos.x, curPos.y);

        // 全局暂停或当前车所属操作员被暂停时，跳过本次移动
        if (bb.isGlobalPaused() || bb.isCarPausedByOwner(carId)) {
            log.info("[Car:{}] 暂停中（全局或车主操作员），跳过移动", carId);
            return;
        }

        // 不加分布式锁——Navigator 已移除锁，小车自己不需要锁自己
        Point next = bb.peekNextStep(carId);
            if (next == null) {
                log.info("[Car:{}] 📭 peekNextStep=null, 路径走完", carId);
                handleRouteDone();
                return;
            }

            log.info("[Car:{}] 🔍 peekNextStep=({},{}), isBlocked? {}", carId, next.x, next.y, bb.isBlocked(next.x, next.y));

            if (bb.isBlocked(next.x, next.y)) {
                log.warn("[Car:{}] 🧱 下一步({},{})被阻塞!", carId, next.x, next.y);
                handleBlocked();
                return;
            }

            Point oldPos = bb.getCarPosition(carId);

            bb.setCarStatus(carId, CarStatus.MOVING);
            bb.popNextStep(carId);
            obstacleManager.clearObstacle(oldPos);
            bb.setCarPosition(carId, next.x, next.y);

            illuminator.illuminate(next.x, next.y);
            log.info("[Car:{}] 💡 illuminate({},{})", carId, next.x, next.y);
            bb.incrementCarSteps(carId);

            // 移动 + 点亮后立即触发 Display 刷新（消除 500ms 广播延迟）
            triggerImmediateRefresh();

            // 两步前瞻
            Point stillNext = bb.peekNextStep(carId);
            if (stillNext != null) {
                if (bb.isBlocked(stillNext.x, stillNext.y)) {
                    log.warn("[Car:{}] 两步前瞻: 第二步({},{})被阻塞，清空剩余路径重新规划",
                            carId, stillNext.x, stillNext.y);
                    bb.clearRoute(carId);
                    handleRouteDone();
                    return;
                }
                bb.setCarStatus(carId, CarStatus.IDLE);
                log.info("[Car:{}] 🚗 移动至 ({},{})，两步前瞻通过", carId, next.x, next.y);
            } else {
                handleRouteDone();
                log.info("[Car:{}] 移动至 ({},{}) 后路径走完", carId, next.x, next.y);
            }
    }

    private void handleBlocked() {
        bb.setCarStatus(carId, CarStatus.BLOCKED);
        bb.setBlockedTick(carId, currentTick.get());
        bb.pushTask("BLOCKED", carId, java.util.Map.of("blockedTick", String.valueOf(currentTick.get())));
        log.warn("[Car:{}] 遇阻 → BLOCKED, tick={}, 已入队BLOCKED", carId, currentTick.get());
    }

    private void handleRouteDone() {
        bb.setCarStatus(carId, CarStatus.IDLE);
        bb.clearCarTarget(carId);
        bb.clearRoute(carId);
        bb.pushTask("ROUTE_NEEDED", carId, null);
        log.info("[Car:{}] 路径完成 → IDLE, 已入队ROUTE_NEEDED", carId);
    }

    /** 小车移动+点亮后立即通过 Fanout Exchange 触发 Display 刷新，消除广播周期延迟 */

    private void triggerImmediateRefresh() {
        try {
            JSONObject data = new JSONObject();
            data.put("tick", currentTick.get());
            JSONObject msg = new JSONObject();
            msg.put("cmd", CommandType.REFRESH_ALL.name());
            msg.put("data", data);
            msg.put("timestamp", System.currentTimeMillis());
            mq.fanoutPublish(ConfigConstants.EXCHANGE_UPDATE_VIEW, msg.toJSONString());
        } catch (Exception e) {
            log.debug("[Car:{}] 即时刷新发送失败（不影响移动）: {}", carId, e.getMessage());
        }
    }

    public String getCarId() { return carId; }
}
