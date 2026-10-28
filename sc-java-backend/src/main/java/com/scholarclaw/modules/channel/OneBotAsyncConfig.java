package com.scholarclaw.modules.channel;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * OneBot 入站异步线程池（TASK-803、{@code NFR-PERF-01}）。
 *
 * <p>Webhook 同步阶段只做验签、白名单与 Redis 去重；业务处理（模型路由/文件下载入库）
 * 必须离开请求线程，避免拖慢 ACK。队列满时使用 {@link ThreadPoolExecutor.CallerRunsPolicy}：
 * 宁可让单个请求变慢，也不静默丢弃已 ACK 的消息；重投由 OneBot 侧与接入幂等键兜底。
 */
@Configuration
public class OneBotAsyncConfig {

    @Bean("oneBotExecutor")
    public ThreadPoolTaskExecutor oneBotExecutor(
            @Value("${sc.onebot.executor.core-size:2}") int coreSize,
            @Value("${sc.onebot.executor.max-size:4}") int maxSize,
            @Value("${sc.onebot.executor.queue-capacity:200}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, coreSize));
        executor.setMaxPoolSize(Math.max(Math.max(1, coreSize), maxSize));
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setThreadNamePrefix("onebot-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
