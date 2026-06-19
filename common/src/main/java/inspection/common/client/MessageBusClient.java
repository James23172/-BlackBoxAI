package inspection.common.client;

import com.alibaba.fastjson2.JSON;
import com.rabbitmq.client.*;
import inspection.common.config.ConfigConstants;
import inspection.common.model.MQMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * 消息总线客户端 — 封装全部 RabbitMQ 收发
 *
 * 这是 common 模块最核心的类之一，所有模块只通过此类发送和接收 MQ 消息。
 *
 * 队列拓扑:
 *   Navigator4CarID     → Navigator 订阅（共享竞争，接收 NAVIGATE）
 *   TaskConfigCmd       → TaskConfigurator 订阅（接收 FORWARD_CONFIG）
 *   Car:{carId}          → 各小车订阅（接收 MOVE_STEP）
 *
 *   UpdateView Exchange  → Fanout 广播（Display 订阅 REFRESH_ALL）
 *   Controller 不订阅任何队列，通过 Redis taskQueue 轮询接收反馈
 */
public class MessageBusClient {
    private static final Logger log = LoggerFactory.getLogger(MessageBusClient.class);

    private Connection connection;
    private Channel channel;

    public MessageBusClient() {
        this(ConfigConstants.RABBITMQ_HOST, ConfigConstants.RABBITMQ_PORT,
                ConfigConstants.RABBITMQ_USER, ConfigConstants.RABBITMQ_PASS,
                ConfigConstants.RABBITMQ_VHOST);
    }

    public MessageBusClient(String host, int port, String user, String pass, String vhost) {
        try {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(host);
            factory.setPort(port);
            factory.setUsername(user);
            factory.setPassword(pass);
            factory.setVirtualHost(vhost);
            // 自动恢复连接
            factory.setAutomaticRecoveryEnabled(true);
            factory.setNetworkRecoveryInterval(5000);
            this.connection = factory.newConnection();
            this.channel = connection.createChannel();
            log.info("MessageBusClient 已连接 RabbitMQ {}:{}", host, port);
        } catch (IOException | TimeoutException e) {
            log.error("连接 RabbitMQ 失败: {}", e.getMessage(), e);
            throw new RuntimeException("无法连接 RabbitMQ", e);
        }
    }

    public Channel getChannel() {
        return channel;
    }

    // ==================== 队列声明 ====================

    /**
     * 声明一个持久化队列
     * @param queueName 队列名
     */
    public void declareQueue(String queueName) throws IOException {
        channel.queueDeclare(queueName, true, false, false, null);
        log.info("队列已声明: {}", queueName);
    }

    /**
     * 声明 Fanout Exchange（用于广播）
     */
    public void declareFanoutExchange(String exchangeName) throws IOException {
        channel.exchangeDeclare(exchangeName, BuiltinExchangeType.FANOUT, true);
        log.info("Fanout Exchange 已声明: {}", exchangeName);
    }

    /**
     * 绑定队列到 Fanout Exchange
     */
    public void bindQueueToExchange(String queueName, String exchangeName) throws IOException {
        channel.queueBind(queueName, exchangeName, "");
        log.info("队列 {} 已绑定到 Exchange {}", queueName, exchangeName);
    }

    /**
     * 声明所有系统队列（TaskConfigurator 启动时调用）
     */
    public void declareAllSystemQueues() throws IOException {
        declareQueue(ConfigConstants.QUEUE_NAVIGATOR_4_CAR_ID);
        declareQueue(ConfigConstants.QUEUE_TASK_CONFIG_CMD);
        // 声明所有可能的小车队列（Car:001 ~ Car:004）
        for (int i = 1; i <= 4; i++) {
            declareQueue(ConfigConstants.carQueueName(String.format("Car%03d", i)));
        }
        // 广播 Exchange
        declareFanoutExchange(ConfigConstants.EXCHANGE_UPDATE_VIEW);
        log.info("所有系统队列/Exchange 已声明");
    }

    // ==================== 消息发送 ====================

    /**
     * 发送 JSON 消息到指定队列
     * @param queueName  目标队列
     * @param message    MQMessage 对象
     */
    public void sendToQueue(String queueName, MQMessage message) {
        try {
            String json = JSON.toJSONString(message);
            channel.basicPublish("", queueName,
                    MessageProperties.PERSISTENT_TEXT_PLAIN,
                    json.getBytes(StandardCharsets.UTF_8));
            log.debug("发送 → {}: {}", queueName, message.getCmd());
        } catch (IOException e) {
            log.error("发送消息失败 → {}: {}", queueName, e.getMessage(), e);
        }
    }

    /**
     * 发送原始 JSON 字符串到指定队列（别名 publish，兼容队友代码）
     */
    public void publish(String queueName, String message) {
        sendRaw(queueName, message);
    }

    /**
     * 发送原始 JSON 字符串到指定队列
     */
    public void sendRaw(String queueName, String json) {
        try {
            // 兜底声明队列（持久化），防止 RabbitMQ 重启后队列丢失导致消息静默丢弃
            channel.queueDeclare(queueName, true, false, false, null);
            channel.basicPublish("", queueName,
                    MessageProperties.PERSISTENT_TEXT_PLAIN,
                    json.getBytes(StandardCharsets.UTF_8));
            log.debug("发送原始 → {}", queueName);
        } catch (IOException e) {
            log.error("发送原始消息失败 → {}: {}", queueName, e.getMessage(), e);
        }
    }

    /**
     * 广播原始字符串到 Fanout Exchange（兼容队友代码）
     */
    public void fanoutPublish(String exchangeName, String message) {
        try {
            channel.exchangeDeclare(exchangeName, BuiltinExchangeType.FANOUT, true);
            channel.basicPublish(exchangeName, "",
                    MessageProperties.PERSISTENT_TEXT_PLAIN,
                    message.getBytes(StandardCharsets.UTF_8));
            log.debug("Fanout广播 → {}", exchangeName);
        } catch (IOException e) {
            log.error("Fanout广播失败 → {}: {}", exchangeName, e.getMessage(), e);
        }
    }

    /**
     * 广播消息到 Fanout Exchange
     */
    public void broadcast(String exchangeName, MQMessage message) {
        try {
            String json = JSON.toJSONString(message);
            channel.basicPublish(exchangeName, "",
                    MessageProperties.PERSISTENT_TEXT_PLAIN,
                    json.getBytes(StandardCharsets.UTF_8));
            log.debug("广播 → {}: {}", exchangeName, message.getCmd());
        } catch (IOException e) {
            log.error("广播失败 → {}: {}", exchangeName, e.getMessage(), e);
        }
    }

    // ==================== 消息订阅 ====================

    /**
     * 订阅指定队列，接收原始 JSON 字符串（兼容队友代码）
     * 注：Java 泛型擦除限制，不能与 Consumer&lt;MQMessage&gt; 同名，故用 subscribeText
     * @param queueName 队列名
     * @param handler   消息处理回调（接收原始 String）
     */
    public void subscribeText(String queueName, Consumer<String> handler) {
        try {
            declareQueue(queueName);
            channel.basicQos(1);
            DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                String body = new String(delivery.getBody(), StandardCharsets.UTF_8);
                try {
                    handler.accept(body);
                } catch (Exception e) {
                    log.error("处理消息异常: {}", e.getMessage(), e);
                }
            };
            channel.basicConsume(queueName, true, deliverCallback, consumerTag -> {});
            log.info("已订阅队列(String): {}", queueName);
        } catch (IOException e) {
            log.error("订阅队列失败 → {}: {}", queueName, e.getMessage(), e);
        }
    }

    /**
     * 订阅指定队列，自动 ACK
     * @param queueName 队列名
     * @param handler   消息处理回调
     */
    public void subscribe(String queueName, Consumer<MQMessage> handler) {
        subscribe(queueName, handler, true);
    }

    /**
     * 订阅指定队列
     * @param queueName 队列名
     * @param handler   消息处理回调
     * @param autoAck   是否自动确认（生产环境建议 false）
     */
    public void subscribe(String queueName, Consumer<MQMessage> handler, boolean autoAck) {
        try {
            // 确保队列存在
            declareQueue(queueName);

            // 公平分发：每次只给一个消息
            channel.basicQos(1);

            DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                String body = new String(delivery.getBody(), StandardCharsets.UTF_8);
                try {
                    MQMessage msg = JSON.parseObject(body, MQMessage.class);
                    log.debug("收到 ← {}: {}", queueName,
                            msg != null ? msg.getCmd() : "parse-error");
                    if (msg != null) {
                        handler.accept(msg);
                    }
                } catch (Exception e) {
                    log.error("处理消息异常: {}", e.getMessage(), e);
                } finally {
                    if (!autoAck) {
                        channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                    }
                }
            };

            channel.basicConsume(queueName, autoAck, deliverCallback, consumerTag -> {});
            log.info("已订阅队列: {}", queueName);
        } catch (IOException e) {
            log.error("订阅队列失败 → {}: {}", queueName, e.getMessage(), e);
        }
    }

    /**
     * 订阅 Fanout Exchange 广播消息
     * @param exchangeName Exchange 名称
     * @param handler      消息处理回调
     * @return 临时队列名称
     */
    public String subscribeFanout(String exchangeName, Consumer<MQMessage> handler) {
        try {
            // 声明 Exchange
            declareFanoutExchange(exchangeName);

            // 创建临时独占队列
            String queueName = channel.queueDeclare().getQueue();
            channel.queueBind(queueName, exchangeName, "");

            channel.basicConsume(queueName, true, (consumerTag, delivery) -> {
                String body = new String(delivery.getBody(), StandardCharsets.UTF_8);
                try {
                    MQMessage msg = JSON.parseObject(body, MQMessage.class);
                    if (msg != null) handler.accept(msg);
                } catch (Exception e) {
                    log.error("广播消息处理异常: {}", e.getMessage(), e);
                }
            }, consumerTag -> {});

            log.info("已订阅 Fanout Exchange: {}", exchangeName);
            return queueName;
        } catch (IOException e) {
            log.error("订阅 Exchange 失败 → {}: {}", exchangeName, e.getMessage(), e);
            return null;
        }
    }

    // ==================== 管理 ====================

    public void close() {
        try {
            if (channel != null && channel.isOpen()) channel.close();
            if (connection != null && connection.isOpen()) connection.close();
            log.info("MessageBusClient 已关闭");
        } catch (IOException | TimeoutException e) {
            log.error("关闭连接异常: {}", e.getMessage(), e);
        }
    }
}
