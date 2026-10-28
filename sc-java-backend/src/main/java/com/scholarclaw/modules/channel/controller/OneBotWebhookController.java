package com.scholarclaw.modules.channel.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.scholarclaw.modules.channel.dto.OneBotAck;
import com.scholarclaw.modules.channel.dto.WebhookDecision;
import com.scholarclaw.modules.channel.service.OneBotWebhookService;
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
 * 以常量时间比较 `Authorization: Bearer <SC_ONEBOT_ACCESS_TOKEN>`。同步阶段只做
 * 验签、白名单、Redis 去重与快速 ACK，业务处理由 `OneBotWebhookService` 交给线程池。
 */
@RestController
public class OneBotWebhookController {

    private final OneBotWebhookService webhookService;

    public OneBotWebhookController(OneBotWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/webhooks/onebot/v11")
    public ResponseEntity<OneBotAck> receive(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            String authorization,
            @RequestBody(required = false) JsonNode payload) {
        WebhookDecision decision = webhookService.accept(authorization, payload);
        if (decision.ack() == null) {
            return ResponseEntity.status(decision.httpStatus()).build();
        }
        return ResponseEntity.status(decision.httpStatus()).body(decision.ack());
    }
}
