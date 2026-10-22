package com.scholarclaw.modules.knowledge.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IngestMessageFactoryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("消息字段为 snake_case、BIGINT 为字符串且不含 api_key")
    void buildsContractCompliantJson() throws Exception {
        IngestMessage message = IngestMessageFactory.from(
                doc(), IngestJobType.INITIAL_INGEST, "job_20261022_120000123_abcdef");

        String json = MAPPER.writeValueAsString(message);
        JsonNode node = MAPPER.readTree(json);

        assertThat(node.get("ingest_job_id").asText()).isEqualTo("job_20261022_120000123_abcdef");
        assertThat(node.get("job_type").asText()).isEqualTo("INITIAL_INGEST");
        assertThat(node.get("user_id").isTextual()).isTrue();
        assertThat(node.get("course_id").asText()).isEqualTo("2001");
        assertThat(node.get("doc_id").asText()).isEqualTo("50001");
        assertThat(node.get("course_index_version").asInt()).isEqualTo(1);
        assertThat(node.get("parse_version").asInt()).isEqualTo(1);
        assertThat(node.get("storage_rel_path").asText()).isEqualTo("raw/10001/2001/50001.pdf");
        assertThat(node.has("api_key")).isFalse();
        assertThat(json).doesNotContain("sk-");
    }

    @Test
    @DisplayName("未落盘或缺少 SHA-256 的文档不能入队")
    void rejectsIncompleteDocs() {
        KnowledgeDocEntity pending = doc();
        pending.setStorageRelPath("pending");
        assertThatThrownBy(() -> IngestMessageFactory.from(
                pending, IngestJobType.INITIAL_INGEST, "job_x"))
                .isInstanceOf(IllegalStateException.class);

        KnowledgeDocEntity noSha = doc();
        noSha.setFileSha256(" ");
        assertThatThrownBy(() -> IngestMessageFactory.from(
                noSha, IngestJobType.REINDEX, "job_x"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("任务 ID 格式固定且每次不同")
    void generatesUniqueJobIds() {
        String first = IngestMessageFactory.newJobId();
        String second = IngestMessageFactory.newJobId();

        assertThat(first).matches("job_\\d{8}_\\d{9}_[0-9a-f]{6}");
        assertThat(second).isNotEqualTo(first);
    }

    private static KnowledgeDocEntity doc() {
        KnowledgeDocEntity doc = new KnowledgeDocEntity();
        doc.setDocId(50001L);
        doc.setUserId(10001L);
        doc.setCourseId(2001L);
        doc.setDocName("第5章_事务处理.pdf");
        doc.setStorageRelPath("raw/10001/2001/50001.pdf");
        doc.setFileSha256("e3b0c44298fc1c149afbf4c8996fb924");
        doc.setStatus("UPLOADED");
        doc.setParseVersion(1);
        doc.setCourseIndexVersion(1);
        doc.setRetryCount(0);
        return doc;
    }
}
