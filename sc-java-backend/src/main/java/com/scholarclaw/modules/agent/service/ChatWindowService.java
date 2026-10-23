package com.scholarclaw.modules.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis 2 小时会话滑动窗口（`03` §1.3 / `04` §4.5.3，TASK-604）。
 *
 * <p>MySQL 仍是会话历史的权威源；Redis 只保存最近 12 条消息作为短期多轮上下文。
 * Redis 故障**不阻断**问答与 MySQL 落库，只记录告警。
 */
@Service
public class ChatWindowService {

    private static final Logger log = LoggerFactory.getLogger(ChatWindowService.class);
    private static final Duration TTL = Duration.ofHours(2);
    private static final int MAX_MESSAGES = 12;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public ChatWindowService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void append(Long userId, Long courseId, String role, String content) {
        String key = key(userId, courseId);
        try {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("role", role);
            entry.put("content", content == null ? "" : content);
            redisTemplate.opsForList().rightPush(key, objectMapper.writeValueAsString(entry));
            redisTemplate.opsForList().trim(key, -MAX_MESSAGES, -1);
            redisTemplate.expire(key, TTL);
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("对话滑动窗口写入失败 userId={} courseId={}", userId, courseId);
        }
    }

    public void clear(Long userId, Long courseId) {
        try {
            redisTemplate.delete(key(userId, courseId));
        } catch (RuntimeException e) {
            log.warn("对话滑动窗口清理失败 userId={} courseId={}", userId, courseId);
        }
    }

    static String key(Long userId, Long courseId) {
        return "sc:u:" + userId + ":chat_win:" + courseId;
    }
}
