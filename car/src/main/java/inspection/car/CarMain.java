package inspection.car;

import inspection.common.client.BlackboardClient;
import inspection.common.client.MessageBusClient;
import inspection.common.config.ConfigConstants;
import inspection.common.enums.CommandType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

public class CarMain {
    private static final Logger log = LoggerFactory.getLogger(CarMain.class);

    public static void main(String[] args) {
        String carId = args.length > 0 ? args[0] : ConfigConstants.CAR_ID;
        log.info("[Car:{}] 启动中...", carId);

        BlackboardClient bb = new BlackboardClient(
                ConfigConstants.REDIS_HOST, ConfigConstants.REDIS_PORT);
        MessageBusClient mq = new MessageBusClient();

        CarAgent agent = new CarAgent(carId, bb, mq);

        String queueName = ConfigConstants.carQueueName(carId);
        log.info("[Car:{}] 订阅队列 {}", carId, queueName);

        AtomicLong tickCounter = new AtomicLong(0);

        mq.subscribe(queueName, message -> {
            try {
                String cmd = message.getCmd();
                log.debug("[Car:{}] 收到命令: {}", carId, cmd);

                if (CommandType.TICK_MOVE.name().equals(cmd)) {
                    long tick = tickCounter.incrementAndGet();
                    agent.handleTickMove(tick);
                } else {
                    log.warn("[Car:{}] 未知命令: {}", carId, cmd);
                }
            } catch (Exception e) {
                log.error("[Car:{}] 处理消息异常: {}", carId, e.getMessage(), e);
            }
        });

        log.info("[Car:{}] 启动完成，等待命令...", carId);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("[Car:{}] 关闭中...", carId);
            mq.close();
            bb.close();
        }));
    }
}
