package com.scholarclaw.modules.ddl.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 请求级幂等键公式（03 §4.5 / 04 API-DDL-002）。 */
class DdlIdempotencyKeyTest {

    @Test
    @DisplayName("Web 幂等键固定为 sha256(user_id:WEB:client_request_id:DDL)")
    void webKeyMatchesDocumentedFormula() {
        assertThat(DdlIdempotencyKey.forWebRequest(10001L, "req_manual_001"))
                .isEqualTo("bd72c3fa3d6ab10897ee98b33987a6ab5ea5d9c68fe606646d0948a89cc8f5b6");
    }

    @Test
    @DisplayName("force_create 语义不改变键公式：同一 ID 始终得到同一键")
    void sameRequestIdAlwaysProducesSameKey() {
        assertThat(DdlIdempotencyKey.forWebRequest(10001L, "req_manual_001"))
                .isEqualTo(DdlIdempotencyKey.forWebRequest(10001L, "req_manual_001"));
    }
}
