package com.scholarclaw.modules.agent.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG SSE 链路的专用线程池（TASK-604）。
 *
 * <p>流式读取必须离开 Servlet 线程；首包超时检查需要独立调度线程。
 * 两者均使用有界/固定线程数，避免高并发下无限创建线程。
 */
@Configuration
public class RagStreamConfig {

    private static final int STREAM_THREADS = 4;

    @Bean(name = "ragStreamExecutor", destroyMethod = "shutdown")
    public ExecutorService ragStreamExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        return Executors.newFixedThreadPool(STREAM_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "rag-stream-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean(name = "ragStreamScheduler", destroyMethod = "shutdown")
    public ScheduledExecutorService ragStreamScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "rag-stream-timeout");
            thread.setDaemon(true);
            return thread;
        });
    }
}
