package inspection.controller;

import inspection.common.client.BlackboardClient;
import inspection.common.client.MessageBusClient;
import inspection.common.config.ArgsParser;
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
            ArgsParser argsParser = new ArgsParser(args);
            String redisHost = argsParser.get("--redis-host", ConfigConstants.REDIS_HOST);
            int redisPort = argsParser.getInt("--redis-port", ConfigConstants.REDIS_PORT);
            String mqHost = argsParser.get("--mq-host", ConfigConstants.RABBITMQ_HOST);
            int mqPort = argsParser.getInt("--mq-port", ConfigConstants.RABBITMQ_PORT);

            int instanceId = argsParser.getInt("--instance-id", 0);
            int totalInstances = argsParser.getInt("--total-instances", 1);

            BlackboardClient blackboard = new BlackboardClient(redisHost, redisPort);
            MessageBusClient messageBus = new MessageBusClient(mqHost, mqPort,
                    ConfigConstants.RABBITMQ_USER, ConfigConstants.RABBITMQ_PASS, ConfigConstants.RABBITMQ_VHOST);

            log.info("Controller 实例 {}/{} 已启动（无单实例锁）", instanceId + 1, totalInstances);

            // 3. 启动调度器
            final ControllerAgent controller = new ControllerAgent(blackboard, messageBus,
                    instanceId, totalInstances);
            controller.start();

            // 4. 关闭钩子
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down Controller {}/{} ...", instanceId + 1, totalInstances);
                controller.stop();
                try {
                    messageBus.close();
                } catch (Exception e) {
                    log.error("关闭 MessageBus 失败", e);
                }
                blackboard.close();
                log.info("Controller {}/{} stopped.", instanceId + 1, totalInstances);
            }));
        } catch (Exception e) {
            log.error("Controller 启动失败", e);
            System.exit(1);
        }
    }
}
