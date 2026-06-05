package inspection.car;

import inspection.blackboard.RedisBlackboardClient;
import inspection.messaging.RabbitMQClient;
import inspection.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.locks.Lock;

public class CarKnowledgeSource {
    private static final Logger log = LoggerFactory.getLogger(CarKnowledgeSource.class);

    private final String carId;
    private final RedisBlackboardClient bb;
    private final RabbitMQClient mq;
    private int currentTick = 0;

    public CarKnowledgeSource(String carId) {
        this.carId = carId;
        this.bb = new RedisBlackboardClient();
        this.mq = new RabbitMQClient(carId);
    }

    public void start() {
        try {
            mq.connect();
            log.info("[Car:{}] Connected to RabbitMQ and Redis", carId);

            mq.startConsuming(this::onMessage);
            log.info("[Car:{}] Car knowledge source started, waiting for TICK_MOVE...", carId);
        } catch (Exception e) {
            log.error("[Car:{}] Failed to start: {}", carId, e.getMessage(), e);
        }
    }

    public void shutdown() {
        mq.close();
        bb.close();
        log.info("[Car:{}] Shutdown complete", carId);
    }

    private void onMessage(String text) {
        try {
            TickMoveMessage msg = TickMoveMessage.fromJson(text);
            if (!"TICK_MOVE".equals(msg.getCmd())) {
                log.warn("[Car:{}] Unknown cmd: {}", carId, msg.getCmd());
                return;
            }
            currentTick = (int) msg.getTimestamp();
            handleTickMove();
        } catch (Exception e) {
            log.error("[Car:{}] Error processing message: {}", carId, e.getMessage(), e);
        }
    }

    private void handleTickMove() {
        log.info("[Car:{}] handleTickMove at tick {}", carId, currentTick);

        // 1. Check car status
        CarStatus status = bb.getCarStatus(carId);
        if (status != CarStatus.READY) {
            log.info("[Car:{}] Status is {}, not READY, ignore", carId, status);
            return;
        }

        // 2. tryLock — spec: 非阻塞获取锁，失败则跳过本 tick
        Lock carLock = bb.getCarLock(carId);
        if (!carLock.tryLock()) {
            log.warn("[Car:{}] Cannot acquire lock, skip tick {}", carId, currentTick);
            return;
        }
        try {
            // 3. Peek next step
            Point next = bb.peekNextStep(carId);
            if (next == null) {
                log.info("[Car:{}] No more steps in route", carId);
                handleRouteDone();
                return;
            }

            // 4. Check if blocked
            if (bb.isBlocked(next.getX(), next.getY())) {
                log.info("[Car:{}] Next step {} is blocked", carId, next);
                handleBlocked(currentTick);
                return;
            }

            // 5. Set status to MOVING
            bb.setCarStatus(carId, CarStatus.MOVING);

            // 6. Get old position before moving
            Point oldPos = bb.getCarPosition(carId);

            // 7. Pop next step (consume it)
            Point actual = bb.popNextStep(carId);
            if (actual == null) {
                log.warn("[Car:{}] popNextStep returned null unexpectedly", carId);
                handleRouteDone();
                return;
            }

            // 8. Clear dynamic obstacle at old position
            if (oldPos != null) {
                bb.clearBlocked(oldPos.getX(), oldPos.getY());
                log.info("[Car:{}] Cleared dynamic obstacle at old position {}", carId, oldPos);
            }

            // 9. Set new position
            bb.setCarPosition(carId, actual.getX(), actual.getY());

            // 10. Set dynamic obstacle at new position
            bb.setBlocked(actual.getX(), actual.getY());

            // 11. Illuminate 3x3 area
            bb.illuminateArea(actual.getX(), actual.getY());

            // 12. Increment steps
            bb.incrementCarSteps(carId);
            int steps = bb.getCarSteps(carId);

            log.info("[Car:{}] Moved to {}. Steps: {}", carId, actual, steps);

            // 13. Check if more steps remain
            Point stillNext = bb.peekNextStep(carId);
            if (stillNext != null) {
                bb.setCarStatus(carId, CarStatus.READY);
                mq.sendToController(CarMessage.buildMoved(carId, actual.getX(), actual.getY(), steps, currentTick).toJson());
                log.info("[Car:{}] Sent MOVED, status set to READY for next step", carId);
            } else {
                handleRouteDone();
            }
        } catch (Exception e) {
            log.error("[Car:{}] Error in handleTickMove: {}", carId, e.getMessage(), e);
        } finally {
            carLock.unlock();
        }
    }

    private void handleBlocked(int tick) {
        try {
            bb.setCarStatus(carId, CarStatus.BLOCKED);
            bb.setBlockedTick(carId, tick);
            mq.sendToController(CarMessage.buildBlocked(carId, tick, tick).toJson());
            log.info("[Car:{}] Sent BLOCKED at tick {}", carId, tick);
        } catch (Exception e) {
            log.error("[Car:{}] Error in handleBlocked: {}", carId, e.getMessage(), e);
        }
    }

    private void handleRouteDone() {
        try {
            bb.setCarStatus(carId, CarStatus.IDLE);
            bb.clearCarTarget(carId);
            bb.clearRoute(carId);
            mq.sendToController(CarMessage.buildRouteDone(carId, currentTick).toJson());
            log.info("[Car:{}] Sent ROUTE_DONE, status set to IDLE", carId);
        } catch (Exception e) {
            log.error("[Car:{}] Error in handleRouteDone: {}", carId, e.getMessage(), e);
        }
    }

    public RedisBlackboardClient getBb() { return bb; }
    public RabbitMQClient getMq() { return mq; }
}
