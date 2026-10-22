package com.scholarclaw.modules.knowledge.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/** `API-INT-JAVA-001` 响应数据。 */
public record ParseContextVO(
        boolean allowProceed,
        String abortReason,
        @JsonSerialize(using = ToStringSerializer.class) Long userId,
        @JsonSerialize(using = ToStringSerializer.class) Long courseId,
        @JsonSerialize(using = ToStringSerializer.class) Long docId,
        String docName,
        String fileExt,
        String storageRelPath,
        int parseVersion,
        int courseIndexVersion,
        EmbeddingConfigVO embeddingConfig) {

    /** 仅内部响应返回；调用方不得落库、打印或转发。 */
    public record EmbeddingConfigVO(String provider, String baseUrl, String apiKey,
                                    String modelName, int dim) {
    }

    public static ParseContextVO abort(Long userId, Long courseId, Long docId, String docName,
                                       String fileExt, String storageRelPath, int parseVersion,
                                       int courseIndexVersion, String reason) {
        return new ParseContextVO(false, reason, userId, courseId, docId, docName, fileExt,
                storageRelPath, parseVersion, courseIndexVersion, null);
    }
}
