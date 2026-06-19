package inspection.controller;

import inspection.common.client.BlackboardClient;
import inspection.common.client.DistributedLock;
import inspection.common.client.MessageBusClient;
import inspection.common.config.ConfigConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ControllerMain {
    private static final Logger log = LoggerFactory.getLogger(ControllerMain.class);
    private static DistributedLock lock;
    private static ScheduledExecutorService heartbeat;

    public static void main(String[] args) {
        try {
            // 1. 连接黑板（Redis）
            BlackboardClient blackboard = new BlackboardClient(
                    ConfigConstants.REDIS_HOST,
                    ConfigConstants.REDIS_PORT
            );

            // 2. 连接消息总线（RabbitMQ）
            MessageBusClient messageBus = new MessageBusClient(
                    ConfigConstants.RABBITMQ_HOST,
                    ConfigConstants.RABBITMQ_PORT,
                    ConfigConstants.RABBITMQ_USER,
                    ConfigConstants.RABBITMQ_PASS,
                    ConfigConstants.RABBITMQ_VHOST
            );
            // 如果 MessageBusClient 构造后自动连接，无需额外调用 connect()

            // 3. 获取分布式锁（单实例保证）
            lock = blackboard.getControllerLock();
            int lockExpireSeconds = ConfigConstants.LOCK_EXPIRE_SECONDS;
            if (!lock.tryLock(lockExpireSeconds)) {
                log.error("❌ 系统中已有 Controller 实例运行，退出。");
                System.exit(1);
            }
            log.info("✅ Controller 单实例锁获取成功");

            // 4. 心跳续期
            heartbeat = Executors.newSingleThreadScheduledExecutor();
            heartbeat.scheduleAtFixedRate(() -> {
                try {
                    lock.renew(lockExpireSeconds);
                } catch (Exception e) {
                    log.error("续期失败", e);
                }
            }, lockExpireSeconds / 3, lockExpireSeconds / 3, TimeUnit.SECONDS);

            // 5. 启动调度器
            ControllerAgent controller = new ControllerAgent(blackboard, messageBus);
            controller.start();

            // 6. 关闭钩子
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down Controller...");
                controller.stop();
                heartbeat.shutdown();
                lock.unlock();
                try {
                    messageBus.close();
                } catch (Exception e) {
                    log.error("关闭 MessageBus 失败", e);
                }
                blackboard.close();
                log.info("Controller stopped.");
            }));
        } catch (Exception e) {
            log.error("Controller 启动失败", e);
            System.exit(1);
        }
    }
}