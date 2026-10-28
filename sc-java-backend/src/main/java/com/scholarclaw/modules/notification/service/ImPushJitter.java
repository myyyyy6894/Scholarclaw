package com.scholarclaw.modules.notification.service;

import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * IM 主动发送的随机抖动（TASK-804、{@code NFR-SEC-01}）。
 *
 * <p>真实发送前随机等待 1.5~3.5 秒，降低同一时刻多条提醒被平台判定为机器行为的概率。
 * 单独抽成组件便于测试注入零抖动，避免单元测试真实睡眠。
 */
@Component
public class ImPushJitter {

    private final long minMillis;
    private final long maxMillis;
    private final long rateDelayMinMillis;
    private final long rateDelayMaxMillis;

    public ImPushJitter(@Value("${sc.notification.im.jitter-min-ms:1500}") long minMillis,
                        @Value("${sc.notification.im.jitter-max-ms:3500}") long maxMillis,
                        @Value("${sc.notification.im.rate-delay-min-ms:5000}")
                        long rateDelayMinMillis,
                        @Value("${sc.notification.im.rate-delay-max-ms:15000}")
                        long rateDelayMaxMillis) {
        long lower = Math.max(0, Math.min(minMillis, maxMillis));
        long upper = Math.max(lower, Math.max(minMillis, maxMillis));
        this.minMillis = lower;
        this.maxMillis = upper;
        long rateLower = Math.max(0, Math.min(rateDelayMinMillis, rateDelayMaxMillis));
        long rateUpper = Math.max(rateLower, Math.max(rateDelayMinMillis, rateDelayMaxMillis));
        this.rateDelayMinMillis = rateLower;
        this.rateDelayMaxMillis = rateUpper;
    }

    /** 发送前抖动；线程被中断时抛出，由调度器中止本轮。 */
    public void sleepBeforeSend() throws InterruptedException {
        long delay = randomDelayMillis();
        if (delay > 0) {
            Thread.sleep(delay);
        }
    }

    /** 供限流/重试顺延使用，也可在测试中单独断言范围。 */
    public long randomDelayMillis() {
        if (maxMillis <= minMillis) {
            return minMillis;
        }
        return ThreadLocalRandom.current().nextLong(minMillis, maxMillis + 1);
    }

    /** 限流顺延的 5~15 秒随机延迟。 */
    public long randomRateDelayMillis() {
        if (rateDelayMaxMillis <= rateDelayMinMillis) {
            return rateDelayMinMillis;
        }
        return ThreadLocalRandom.current().nextLong(rateDelayMinMillis, rateDelayMaxMillis + 1);
    }
}
