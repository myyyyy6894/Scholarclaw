package com.scholarclaw.modules.channel.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.modules.channel.dto.OneBotAck;
import com.scholarclaw.modules.channel.dto.WebhookDecision;
import com.scholarclaw.modules.channel.service.OneBotWebhookService;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * OneBot 11 入站 Webhook（{@code 04} §4.8.1 `API-WEBHOOK-001`，TASK-803）。
 *
 * <p>鉴权使用 OneBot 令牌而非 JWT；`SecurityConfig` 对该路径 permitAll，控制器内部
 * 接受 {@code Authorization: Bearer <token>} 或 NapCat HTTP 客户端默认的
 * {@code X-Signature: sha1=HMAC-SHA1(token, rawBody)}。同步阶段只做验签、白名单、
 * Redis 去重与快速 ACK，业务处理由 `OneBotWebhookService` 交给线程池。
 */
@RestController
public class OneBotWebhookController {

    private static final String SIGNATURE_HEADER = "X-Signature";

    private final OneBotWebhookService webhookService;
    private final ObjectMapper objectMapper;

    public OneBotWebhookController(OneBotWebhookService webhookService,
                                   ObjectMapper objectMapper) {
        this.webhookService = webhookService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/webhooks/onebot/v11")
    public ResponseEntity<OneBotAck> receive(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            String authorization,
            @RequestHeader(value = SIGNATURE_HEADER, required = false)
            String signature,
            @RequestBody(required = false) byte[] rawBody) {
        JsonNode payload = parse(rawBody);
        WebhookDecision decision = webhookService.accept(
                authorization, signature, rawBody, payload);
        if (decision.ack() == null) {
            return ResponseEntity.status(decision.httpStatus()).build();
        }
        return ResponseEntity.status(decision.httpStatus()).body(decision.ack());
    }

    private JsonNode parse(byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0) {
            return null;
        }
        try {
            return objectMapper.readTree(rawBody);
        } catch (IOException e) {
            // 交给 service 按“报文非法”返回 FAILED；不要在这里泄露解析细节。
            return null;
        }
    }
}
