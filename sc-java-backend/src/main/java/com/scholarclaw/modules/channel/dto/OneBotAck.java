package com.scholarclaw.modules.channel.dto;

import com.scholarclaw.common.enums.InboundStatus;

/**
 * OneBot 11 标准快速 ACK 响应（{@code 04} §4.8.1）。
 *
 * <p>不使用业务 {@code Result<T>} 信封：OneBot 协议要求顶层 `status/retcode`。
 * {@code inboundStatus} / {@code traceId} 由全局 snake_case 序列化为
 * `inbound_status` / `trace_id`。
 */
public record OneBotAck(String status, int retcode, String inboundStatus, String traceId) {

    public static OneBotAck of(InboundStatus inboundStatus, String traceId) {
        return new OneBotAck("ok", 0, inboundStatus.name(), traceId);
    }
}
