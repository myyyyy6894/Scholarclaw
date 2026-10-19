package com.scholarclaw.common.client;

/** 模型探测类型（{@code 04} §4.9.1 `probe_type`：`CHAT` / `EMBEDDING` / `BOTH`）。 */
public enum ModelProbeType {

    CHAT,
    EMBEDDING,
    BOTH;

    /** 本次探测是否包含对话模型。 */
    public boolean includesChat() {
        return this != EMBEDDING;
    }

    /** 本次探测是否包含向量模型。 */
    public boolean includesEmbedding() {
        return this != CHAT;
    }
}
