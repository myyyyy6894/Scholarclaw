package com.scholarclaw.modules.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.agent.dto.AgentInboundMessage;
import com.scholarclaw.modules.agent.dto.AgentReply;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/** Short-lived context and replay protection for a verified direct QQ session. */
@Service
public class QqConversationStore {
    private static final Duration CONTEXT_TTL = Duration.ofMinutes(5);
    private static final Duration MESSAGE_TTL = Duration.ofMinutes(10);
    private static final Duration PROCESS_TTL = Duration.ofMinutes(2);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) end return 0", Long.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public QqConversationStore(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public AgentReply once(AgentInboundMessage message, Supplier<AgentReply> action) {
        String scope = scope(message);
        String lock = scope + ":processing";
        String token = UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lock, token, PROCESS_TTL))) {
            return QqNaturalLanguageService.guide("上一条消息仍在处理，请稍后再发。");
        }
        try {
            String key = scope + ":processed:" + hash(message.sourceMsgId());
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, "1", MESSAGE_TTL))) {
                return QqNaturalLanguageService.guide("这条消息已处理。");
            }
            return action.get();
        } finally {
            // The lease bounds the lock after a process or Redis failure.
            try { redis.execute(RELEASE, List.of(lock), token); }
            catch (RuntimeException ignored) { }
        }
    }

    QqNaturalLanguageService.Turn load(String scope) {
        String key = scope + ":turn";
        String stored = redis.opsForValue().get(key);
        if (stored == null) return null;
        try { return mapper.readValue(stored, QqNaturalLanguageService.Turn.class); }
        catch (JsonProcessingException e) {
            redis.delete(key);
            return null;
        }
    }

    void save(String scope, QqNaturalLanguageService.Turn turn) {
        try { redis.opsForValue().set(scope + ":turn", mapper.writeValueAsString(turn), CONTEXT_TTL); }
        catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "暂时无法保存本轮操作。");
        }
    }

    void clear(String scope) { redis.delete(scope + ":turn"); }

    static String scope(AgentInboundMessage message) {
        var session = message.qqSession();
        String identity = message.sourcePlatform() + ":" + session.appId() + ":" + session.bindingVersion()
                + ":" + session.openid();
        return "sc:u:" + message.userId() + ":qq:" + hash(identity);
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
