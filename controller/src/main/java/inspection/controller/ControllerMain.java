package inspection.controller;

import inspection.common.client.BlackboardClient;
import inspection.common.client.MessageBusClient;
import inspection.common.config.ConfigConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controller 入口（支持多实例）
 *
 * 运行方式:
 *   java -jar controller.jar                           # 单实例（默认）
 *   java -jar controller.jar --instance-id 0 --total-instances 1
 *   java -jar controller.jar --instance-id 1 --total-instances 2
 *
 * 多实例时按小车索引取模分片，各实例独立驱动分配到的车辆。
 * 仅 instance-id=0 的实例负责广播 REFRESH_ALL 给 Display。
 */
public class ControllerMain {
    private static final Logger log = LoggerFactory.getLogger(ControllerMain.class);

    public static void main(String[] args) {
        try {
            // ──── 解析 CLI 参数 ────
            int instanceId = 0;
            int totalInstances = 1;
            for (int i = 0; i < args.length; i++) {
                if ("--instance-id".equals(args[i]) && i + 1 < args.length) {
                    instanceId = Integer.parseInt(args[i + 1]);
                } else if ("--total-instances".equals(args[i]) && i + 1 < args.length) {
                    totalInstances = Integer.parseInt(args[i + 1]);
                }
            }
            final int finalInstanceId = instanceId;
            final int finalTotalInstances = totalInstances;

            // 1. 连接黑板（Redis）
            final BlackboardClient blackboard = new BlackboardClient(
                    ConfigConstants.REDIS_HOST,
                    ConfigConstants.REDIS_PORT
            );

            // 2. 连接消息总线（RabbitMQ）
            final MessageBusClient messageBus = new MessageBusClient(
                    ConfigConstants.RABBITMQ_HOST,
                    ConfigConstants.RABBITMQ_PORT,
                    ConfigConstants.RABBITMQ_USER,
                    ConfigConstants.RABBITMQ_PASS,
                    ConfigConstants.RABBITMQ_VHOST
            );

            log.info("Controller 实例 {}/{} 已启动（无单实例锁）", finalInstanceId + 1, finalTotalInstances);

            // 3. 启动调度器
            final ControllerAgent controller = new ControllerAgent(blackboard, messageBus,
                    finalInstanceId, finalTotalInstances);
            controller.start();

            // 4. 关闭钩子
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down Controller {}/{} ...", finalInstanceId + 1, finalTotalInstances);
                controller.stop();
                try {
                    messageBus.close();
                } catch (Exception e) {
                    log.error("关闭 MessageBus 失败", e);
                }
                blackboard.close();
                log.info("Controller {}/{} stopped.", finalInstanceId + 1, finalTotalInstances);
            }));
        } catch (Exception e) {
            log.error("Controller 启动失败", e);
            System.exit(1);
        }
    }
}
