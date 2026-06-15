package inspection.common;

import inspection.common.client.BlackboardClient;
import inspection.common.client.MessageBusClient;
import inspection.common.client.DistributedLock;
import inspection.common.config.ConfigConstants;
import inspection.common.enums.CarStatus;
import inspection.common.enums.CommandType;
import inspection.common.model.*;
import com.alibaba.fastjson2.JSON;

/**
 * Common 模块自测入口
 *
 * 右键 Run → 观察控制台输出 + redis-cli 验证
 */
public class TestMain {
    public static void main(String[] args) {
        System.out.println("========== Common 模块自测开始 ==========\n");

        // ===== 1. 数据模型测试 =====
        System.out.println("--- 1. Point/数据模型 ---");
        Point p1 = new Point(5, 3);
        Point p2 = new Point(8, 7);
        System.out.println("p1: " + p1 + ", p2: " + p2);
        System.out.println("距离: " + p1.distanceTo(p2) + " (预期: 7)");
        System.out.println("equals: " + p1.equals(new Point(5, 3)) + " (预期: true)");

        // MQMessage 序列化
        MQMessage msg = new MQMessage(CommandType.TICK_MOVE.name(),
                java.util.Map.of("carId", "Car001"));
        System.out.println("MQMessage JSON: " + JSON.toJSONString(msg));

        // ===== 2. 枚举测试 =====
        System.out.println("\n--- 2. 枚举 ---");
        System.out.println("CarStatus 值: " + java.util.Arrays.toString(CarStatus.values()));
        System.out.println("CommandType 值: " + java.util.Arrays.toString(CommandType.values()));

        // ===== 3. ConfigConstants 测试 =====
        System.out.println("\n--- 3. 配置常量 ---");
        System.out.println("carStatusKey(Car001): " + ConfigConstants.carStatusKey("Car001"));
        System.out.println("carPositionKey(Car001): " + ConfigConstants.carPositionKey("Car001"));
        System.out.println("carRouteKey(Car001): " + ConfigConstants.carRouteKey("Car001"));

        // ===== 4. BlackboardClient 测试（需要 Redis 运行） =====
        System.out.println("\n--- 4. BlackboardClient ---");
        try {
            BlackboardClient bb = new BlackboardClient(
                    ConfigConstants.REDIS_HOST, ConfigConstants.REDIS_PORT);

            // 4a. 写位置
            bb.setCarPosition("Car001", 5, 3);
            Point pos = bb.getCarPosition("Car001");
            System.out.println("位置: (" + pos.getX() + "," + pos.getY() + ") (预期: 5,3)");

            // 4b. 写状态
            bb.setCarStatus("Car001", CarStatus.IDLE);
            CarStatus st = bb.getCarStatus("Car001");
            System.out.println("状态: " + st + " (预期: IDLE)");

            // 4c. 点亮 3×3
            bb.illuminateArea(10, 10);
            System.out.println("点亮后 (10,10) 已探索: " + bb.isExplored(10, 10) + " (预期: true)");
            System.out.println("点亮后 (11,11) 已探索: " + bb.isExplored(11, 11) + " (预期: true)");

            // 4d. 障碍物
            bb.setBlocked(5, 5);
            System.out.println("(5,5) 阻塞: " + bb.isBlocked(5, 5) + " (预期: true)");
            bb.clearBlocked(5, 5);
            System.out.println("清除后 (5,5) 阻塞: " + bb.isBlocked(5, 5) + " (预期: false)");

            // 4e. 路径
            java.util.List<Point> path = java.util.Arrays.asList(
                    new Point(1, 1), new Point(2, 1), new Point(3, 1));
            bb.pushRoute("Car001", path);
            Point next = bb.peekNextStep("Car001");
            System.out.println("peek 下一步: " + next + " (预期: 1,1)");
            Point popped = bb.popNextStep("Car001");
            System.out.println("pop 下一步: " + popped + " (预期: 1,1)");
            System.out.println("剩余路径: " + bb.getFullRoute("Car001"));

            // 4f. 锁
            DistributedLock lock = bb.getCarLock("Car001");
            boolean locked = lock.tryLock();
            System.out.println("获取锁: " + locked + " (预期: true)");
            if (locked) lock.unlock();

            // 4g. 探索统计
            int explored = bb.getExploredCount();
            System.out.println("已探索格子数: " + explored);

            bb.close();
        } catch (Exception e) {
            System.out.println("⚠️ Redis 连接失败 (确保 Redis 已启动): " + e.getMessage());
        }

        // ===== 5. MessageBusClient 测试（需要 RabbitMQ 运行） =====
        System.out.println("\n--- 5. MessageBusClient ---");
        try {
            MessageBusClient mq = new MessageBusClient(
                    ConfigConstants.RABBITMQ_HOST, ConfigConstants.RABBITMQ_PORT,
                    ConfigConstants.RABBITMQ_USER, ConfigConstants.RABBITMQ_PASS,
                    ConfigConstants.RABBITMQ_VHOST);

            mq.declareQueue("TestQueue");
            mq.sendToQueue("TestQueue", new MQMessage("TEST", null));
            System.out.println("消息发送成功");

            mq.close();
        } catch (Exception e) {
            System.out.println("⚠️ RabbitMQ 连接失败 (确保 RabbitMQ 已启动): " + e.getMessage());
        }

        System.out.println("\n========== Common 模块自测结束 ==========");
    }
}
