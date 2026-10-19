package com.scholarclaw.modules.userconfig.dto;

/** 模型探测响应（{@code 04} `API-USER-002` 响应 `data`）。 */
public record ModelProbeVO(boolean reachable, String probeType, String modelName,
                           Integer actualDim, int latencyMs) {
}
