package com.scholarclaw.modules.agent.dto;

import java.util.List;

/** `API-INT-AI-003` 请求体（TASK-604）。 */
public record RagQaInternalRequest(
        String userId,
        String courseId,
        String courseName,
        int courseIndexVersion,
        List<String> validDocIds,
        String query,
        List<HistoryTurn> chatHistoryWindow,
        ChatConfig chatConfig,
        EmbeddingConfig embeddingConfig) {

    public record HistoryTurn(String role, String content) {
    }

    public record ChatConfig(String provider, String baseUrl, String apiKey, String modelName) {
    }

    public record EmbeddingConfig(String provider, String baseUrl, String apiKey,
                                  String modelName, int dim) {
    }
}
