package com.scholarclaw.modules.knowledge.mq;

import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * parking 队列限速自动重放（P9 复核第 2 项）。
 *
 * <p><b>为什么手动 ACK：</b>只有“重新投递已被 broker 确认”之后才 ACK 原 parking 消息；
 * 崩溃时原消息仍会重投。绝不能先 ACK 再发布，否则会在两步之间静默丢失。
 *
 * <p><b>限速：</b>每次处理前等待 {@code parking-replay-interval-ms}，避免依赖仍不可用时
 * 瞬间耗尽全部重试次数。经过 {@code max-attempts} 次仍失败的消息转入
 * {@code rag.ingest.parking.manual} 并输出 ERROR，等待人工处理。
 *
 * <p><b>来源：</b>parking 消息头 {@link IngestParkingPublisher#SOURCE_HEADER} 记录原始消费队列；
 * 缺失来源的消息无法判断应投递到哪里，直接转人工队列，绝不盲目投递。
 */
@Component
@ConditionalOnProperty(name = "sc.mq.ingest.enabled", havingValue = "true",
        matchIfMissing = true)
public class ParkingReplayListener {

    private static final Logger log = LoggerFactory.getLogger(ParkingReplayListener.class);

    private final IngestParkingPublisher publisher;
    private final long replayIntervalMillis;
    private final int maxAttempts;
    private final AtomicLong nextAllowedAt = new AtomicLong(0);

    public ParkingReplayListener(
            IngestParkingPublisher publisher,
            @Value("${sc.mq.ingest.parking-replay-interval-ms:60000}")
            long replayIntervalMillis,
            @Value("${sc.mq.ingest.parking-replay-max-attempts:3}") int maxAttempts) {
        this.publisher = publisher;
        this.replayIntervalMillis = Math.max(1000, replayIntervalMillis);
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @RabbitListener(
            queues = "${sc.mq.ingest.parking:rag.ingest.parking}",
            containerFactory = "parkingReplayContainerFactory",
            autoStartup = "${sc.mq.ingest.parking-replay-enabled:true}")
    public void onParked(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            awaitNextSlot();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            channel.basicNack(deliveryTag, false, true);
            return;
        }

        String source = stringHeader(message, IngestParkingPublisher.SOURCE_HEADER);
        int attempt = intHeader(message, IngestParkingPublisher.ATTEMPT_HEADER);
        String traceId = stringHeader(message, "X-Trace-Id");
        byte[] body = message.getBody();

        if (source == null || source.isBlank()) {
            // 来源缺失（历史消息或非法停放）：不能猜测目标，转人工队列保留证据。
            boolean moved = publisher.moveToManual(body, traceId, null, attempt);
            if (!moved) {
                log.error("parking 消息缺少来源且转人工失败，交回队列重试 traceId={}", traceId);
                channel.basicNack(deliveryTag, false, true);
                return;
            }
            channel.basicAck(deliveryTag, false);
            log.error("[PARKING_MANUAL_INTERVENTION_REQUIRED] parking 消息缺少来源，"
                    + "已转人工队列 traceId={}", traceId);
            return;
        }

        if (attempt >= maxAttempts) {
            boolean moved = publisher.moveToManual(body, traceId, source, attempt);
            if (!moved) {
                log.error("parking 消息超过重放上限且转人工失败，交回队列重试 source={} "
                        + "attempt={}", source, attempt);
                channel.basicNack(deliveryTag, false, true);
                return;
            }
            channel.basicAck(deliveryTag, false);
            log.error("[PARKING_MANUAL_INTERVENTION_REQUIRED] parking 自动重放达到上限，"
                    + "已转人工队列 source={} attempt={} traceId={}", source, attempt, traceId);
            return;
        }

        boolean republished = publisher.republish(body, source, traceId, source, attempt + 1);
        if (!republished) {
            log.warn("parking 自动重放发布失败，交回队列重试 source={} attempt={}",
                    source, attempt);
            channel.basicNack(deliveryTag, false, true);
            return;
        }
        channel.basicAck(deliveryTag, false);
        log.info("parking 自动重放已投递 source={} attempt={} traceId={}",
                source, attempt + 1, traceId);
    }

    /** 同一消费者串行处理；按间隔限速，避免依赖不可用时瞬间耗尽重试。 */
    private void awaitNextSlot() throws InterruptedException {
        long now = System.currentTimeMillis();
        long next = nextAllowedAt.get();
        if (now < next) {
            Thread.sleep(next - now);
        }
        nextAllowedAt.set(System.currentTimeMillis() + replayIntervalMillis);
    }

    private static String stringHeader(Message message, String name) {
        Object value = message.getMessageProperties().getHeaders().get(name);
        return value == null ? null : String.valueOf(value);
    }

    private static int intHeader(Message message, String name) {
        Object value = message.getMessageProperties().getHeaders().get(name);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
