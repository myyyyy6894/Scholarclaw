package com.scholarclaw.modules.knowledge.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 停放/补偿队列积压巡检（P6 复核补强）。
 *
 * <p>`rag.ingest.parking` 与 `rag.ingest.vector-compensation` 当前**没有自动消费者**，
 * 由 TASK-902 GC 重放。若不监控，故障会以“记录已持久化但无人处理”的形式静默累积，
 * 陈旧向量还会占用 Top-K 降低召回。本巡检只负责发现并告警，**不宣称已自动恢复**。
 */
@Component
@ConditionalOnProperty(name = "sc.mq.ingest.enabled", havingValue = "true",
        matchIfMissing = true)
public class IngestQueueBacklogMonitor {

    private static final Logger log = LoggerFactory.getLogger(IngestQueueBacklogMonitor.class);

    private final RabbitAdmin rabbitAdmin;
    private final String parkingQueue;
    private final String compensationQueue;
    private final int warnThreshold;

    public IngestQueueBacklogMonitor(
            RabbitAdmin rabbitAdmin,
            @Value("${sc.mq.ingest.parking:rag.ingest.parking}") String parkingQueue,
            @Value("${sc.mq.ingest.vector-compensation:rag.ingest.vector-compensation}")
            String compensationQueue,
            @Value("${sc.mq.ingest.backlog-monitor.warn-threshold:1}") int warnThreshold) {
        this.rabbitAdmin = rabbitAdmin;
        this.parkingQueue = parkingQueue;
        this.compensationQueue = compensationQueue;
        this.warnThreshold = warnThreshold;
    }

    @Scheduled(
            initialDelayString = "${sc.mq.ingest.backlog-monitor.initial-delay-ms:60000}",
            fixedDelayString = "${sc.mq.ingest.backlog-monitor.interval-ms:300000}")
    public void reportBacklog() {
        checkQueue(parkingQueue, "死信停放");
        checkQueue(compensationQueue, "向量清理补偿");
    }

    /** 返回队列积压条数；查询失败返回 -1（broker 不可用时不影响业务）。 */
    int checkQueue(String queue, String label) {
        try {
            QueueInformation info = rabbitAdmin.getQueueInfo(queue);
            if (info == null) {
                return -1;
            }
            int count = info.getMessageCount();
            if (count >= warnThreshold) {
                log.warn("MQ 积压待人工处理 queue={} label={} messages={} "
                                + "（当前无自动消费者，由 TASK-902 重放；陈旧向量可能占用 Top-K 降低召回）",
                        queue, label, count);
            }
            return count;
        } catch (RuntimeException error) {
            log.debug("MQ 积压查询失败 queue={} error={}", queue,
                    error.getClass().getSimpleName());
            return -1;
        }
    }
}
