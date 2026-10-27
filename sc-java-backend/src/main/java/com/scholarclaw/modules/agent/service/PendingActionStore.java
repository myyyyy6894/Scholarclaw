package com.scholarclaw.modules.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.agent.dto.PendingAction;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 待二次确认动作（Redis {@code sc:u:{user_id}:pending_action}，TTL 10 分钟）。
 *
 * <p>`FR-DDL-004` 要求删除/取消、批量修改与低置信度自然语言修改必须二次确认。
 * 动作本身只在用户回复“确认”后执行；超时自动失效。
 */
@Service
public class PendingActionStore {

    private static final Logger log = LoggerFactory.getLogger(PendingActionStore.class);
    static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public PendingActionStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void save(Long userId, PendingAction action) {
        try {
            redisTemplate.opsForValue().set(key(userId),
                    objectMapper.writeValueAsString(action), TTL);
        } catch (RuntimeException | JsonProcessingException e) {
            log.error("待确认动作暂存失败 userId={}", userId);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "暂时无法保存待确认操作，请稍后重试。");
        }
    }

    public Optional<PendingAction> load(Long userId) {
        try {
            String json = redisTemplate.opsForValue().get(key(userId));
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, PendingAction.class));
        } catch (JsonProcessingException e) {
            clear(userId);
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("待确认动作读取失败 userId={}", userId);
            return Optional.empty();
        }
    }

    public void clear(Long userId) {
        try {
            redisTemplate.delete(key(userId));
        } catch (RuntimeException e) {
            log.warn("待确认动作清除失败 userId={}", userId);
        }
    }

    static String key(Long userId) {
        return "sc:u:" + userId + ":pending_action";
    }
}
