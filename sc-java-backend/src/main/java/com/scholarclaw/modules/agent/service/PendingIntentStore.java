package com.scholarclaw.modules.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.agent.dto.PendingIntent;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 低置信度待澄清上下文（`01` `E-IM-04`、`04` §7）。
 *
 * <p>键：{@code sc:u:{user_id}:pending_intent}，TTL 30 分钟。Redis 故障时**失败关闭**：
 * 无法暂存原文就不能假装已经进入澄清流程，否则用户回复 1/2/3 时无上下文可恢复。
 */
@Service
public class PendingIntentStore {

    private static final Logger log = LoggerFactory.getLogger(PendingIntentStore.class);
    static final Duration TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public PendingIntentStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void save(Long userId, PendingIntent intent) {
        try {
            redisTemplate.opsForValue().set(key(userId),
                    objectMapper.writeValueAsString(intent), TTL);
        } catch (RuntimeException | JsonProcessingException e) {
            log.error("待澄清意图暂存失败 userId={}", userId);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "暂时无法保存待确认消息，请稍后重试。");
        }
    }

    /** 读取；不存在/损坏/Redis 故障均返回空（调用方按“无待确认上下文”处理）。 */
    public Optional<PendingIntent> load(Long userId) {
        try {
            String json = redisTemplate.opsForValue().get(key(userId));
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, PendingIntent.class));
        } catch (JsonProcessingException e) {
            clear(userId);
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("待澄清意图读取失败 userId={}", userId);
            return Optional.empty();
        }
    }

    public void clear(Long userId) {
        try {
            redisTemplate.delete(key(userId));
        } catch (RuntimeException e) {
            log.warn("待澄清意图清除失败 userId={}", userId);
        }
    }

    static String key(Long userId) {
        return "sc:u:" + userId + ":pending_intent";
    }
}
