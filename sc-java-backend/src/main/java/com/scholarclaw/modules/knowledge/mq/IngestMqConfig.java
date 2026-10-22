package com.scholarclaw.modules.knowledge.mq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * `MQ-INGEST-001` 队列与死信队列声明（{@code 04} §4.9.9）。
 *
 * <p>业务队列持久化；消费失败由 Python 侧 `basic_nack(requeue=false)` 经命名 direct
 * 交换机 `rag.ingest.exchange` 路由到死信队列。声明只在生产者启用时创建，避免单元测试
 * 上下文连接真实 broker。
 */
@Configuration
@ConditionalOnProperty(name = "sc.mq.ingest.enabled", havingValue = "true", matchIfMissing = true)
public class IngestMqConfig {

    @Bean
    public DirectExchange ingestExchange(
            @Value("${sc.mq.ingest.exchange:rag.ingest.exchange}") String exchange) {
        return new DirectExchange(exchange, true, false);
    }

    @Bean
    public Queue ingestQueue(@Value("${sc.mq.ingest.queue:rag.ingest.queue}") String queue,
                             @Value("${sc.mq.ingest.dlq:rag.ingest.dlq}") String dlq,
                             @Value("${sc.mq.ingest.exchange:rag.ingest.exchange}") String exchange) {
        return QueueBuilder.durable(queue)
                .deadLetterExchange(exchange)
                .deadLetterRoutingKey(dlq)
                .build();
    }

    @Bean
    public Queue ingestDeadLetterQueue(@Value("${sc.mq.ingest.dlq:rag.ingest.dlq}") String dlq) {
        return QueueBuilder.durable(dlq).build();
    }

    @Bean
    public Binding ingestBinding(Queue ingestQueue, DirectExchange ingestExchange,
                                 @Value("${sc.mq.ingest.queue:rag.ingest.queue}") String queue) {
        return BindingBuilder.bind(ingestQueue).to(ingestExchange).with(queue);
    }

    @Bean
    public Binding ingestDeadLetterBinding(Queue ingestDeadLetterQueue,
                                           DirectExchange ingestExchange,
                                           @Value("${sc.mq.ingest.dlq:rag.ingest.dlq}") String dlq) {
        return BindingBuilder.bind(ingestDeadLetterQueue).to(ingestExchange).with(dlq);
    }

    /** 显式声明队列；生产启动时连接失败会由 Spring AMQP 记录并重试，不影响应用启动。 */
    @Bean
    public RabbitAdmin ingestRabbitAdmin(ConnectionFactory connectionFactory) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.setAutoStartup(true);
        return admin;
    }
}
