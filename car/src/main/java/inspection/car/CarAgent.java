package inspection.car;

import inspection.common.client.BlackboardClient;
import inspection.common.client.DistributedLock;
import inspection.common.client.MessageBusClient;
import inspection.common.config.ConfigConstants;
import inspection.common.enums.CarStatus;
import inspection.common.enums.CommandType;
import inspection.common.model.MQMessage;
import inspection.common.model.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

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
        if (status != CarStatus.READY) {
            log.info("[Car:{}] 忽略 TICK_MOVE, 当前状态={}", carId, status);
            return;
        }

        DistributedLock lock = bb.getCarLock(carId);
        if (!lock.tryLock()) {
            log.warn("[Car:{}] 获取锁失败，跳过本拍", carId);
            return;
        }

        try {
            Point next = bb.peekNextStep(carId);
            if (next == null) {
                handleRouteDone();
                return;
            }

            if (bb.isBlocked(next.x, next.y)) {
                handleBlocked();
                return;
            }

            Point oldPos = bb.getCarPosition(carId);

            bb.setCarStatus(carId, CarStatus.MOVING);

            bb.popNextStep(carId);

            obstacleManager.clearObstacle(oldPos);

            bb.setCarPosition(carId, next.x, next.y);

            // 注意: 不把自己的位置设为障碍物，否则会挡住自己回路
            // obstacleManager.setObstacle(next);  ← 删掉这行！

            illuminator.illuminate(next.x, next.y);

            bb.incrementCarSteps(carId);

            Point stillNext = bb.peekNextStep(carId);
            if (stillNext != null) {
                bb.setCarStatus(carId, CarStatus.READY);
                sendReply(CommandType.MOVED.name(), Map.of("carId", carId, "x", next.x, "y", next.y));
                log.info("[Car:{}] 移动至 ({},{}), 剩余步数>0, 状态=READY", carId, next.x, next.y);
            } else {
                handleRouteDone();
                log.info("[Car:{}] 移动至 ({},{}) 后路径走完", carId, next.x, next.y);
            }
        } finally {
            lock.unlock();
        }
    }

    private void handleBlocked() {
        bb.setCarStatus(carId, CarStatus.BLOCKED);
        bb.setBlockedTick(carId, currentTick.get());
        sendReply(CommandType.CAR_BLOCKED.name(), Map.of("carId", carId, "tick", currentTick.get()));
        log.warn("[Car:{}] 遇阻 → BLOCKED, tick={}", carId, currentTick.get());
    }

    private void handleRouteDone() {
        bb.setCarStatus(carId, CarStatus.IDLE);
        bb.clearCarTarget(carId);
        bb.clearRoute(carId);
        sendReply(CommandType.ROUTE_DONE.name(), Map.of("carId", carId));
        log.info("[Car:{}] 路径完成 → IDLE", carId);
    }

    private void sendReply(String cmd, Map<String, Object> data) {
        mq.sendToQueue(ConfigConstants.QUEUE_CONTROLLER_CMD, new MQMessage(cmd, data));
    }

    public String getCarId() { return carId; }
}
