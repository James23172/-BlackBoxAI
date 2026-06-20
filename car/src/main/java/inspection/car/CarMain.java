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
        String carId = ConfigConstants.CAR_ID;
        String redisHost = ConfigConstants.REDIS_HOST; int redisPort = ConfigConstants.REDIS_PORT;
        String rabbitHost = ConfigConstants.RABBITMQ_HOST; int rabbitPort = ConfigConstants.RABBITMQ_PORT;
        String rabbitUser = ConfigConstants.RABBITMQ_USER; String rabbitPass = ConfigConstants.RABBITMQ_PASS;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--car-id": carId = args[++i]; break;
                case "--redis-host": redisHost = args[++i]; break;
                case "--redis-port": redisPort = Integer.parseInt(args[++i]); break;
                case "--mq-host": rabbitHost = args[++i]; break;
                case "--mq-port": rabbitPort = Integer.parseInt(args[++i]); break;
                case "--mq-user": rabbitUser = args[++i]; break;
                case "--mq-pass": rabbitPass = args[++i]; break;
            }
        }
        if (carId == null || carId.isEmpty()) carId = ConfigConstants.CAR_ID;
        final String finalCarId = carId;
        log.info("[Car:{}] 启动中... (Redis={}:{} RabbitMQ={}:{})", finalCarId, redisHost, redisPort, rabbitHost, rabbitPort);

        final BlackboardClient bb = new BlackboardClient(redisHost, redisPort);
        final MessageBusClient mq = new MessageBusClient(rabbitHost, rabbitPort,
                rabbitUser, rabbitPass, ConfigConstants.RABBITMQ_VHOST);

        final CarAgent agent = new CarAgent(finalCarId, bb, mq);

        final String queueName = ConfigConstants.carQueueName(finalCarId);
        log.info("[Car:{}] 订阅队列 {}", finalCarId, queueName);

        AtomicLong tickCounter = new AtomicLong(0);

        mq.subscribe(queueName, message -> {
            try {
                String cmd = message.getCmd();
                log.debug("[Car:{}] 收到命令: {}", finalCarId, cmd);

                if (CommandType.MOVE_STEP.name().equals(cmd)) {
                    long tick = tickCounter.incrementAndGet();
                    agent.handleTickMove(tick);
                } else {
                    log.warn("[Car:{}] 未知命令: {}", finalCarId, cmd);
                }
            } catch (Exception e) {
                log.error("[Car:{}] 处理消息异常: {}", finalCarId, e.getMessage(), e);
            }
        });

        log.info("[Car:{}] 启动完成，等待命令...", finalCarId);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("[Car:{}] 关闭中...", finalCarId);
            mq.close();
            bb.close();
        }));
    }
}
