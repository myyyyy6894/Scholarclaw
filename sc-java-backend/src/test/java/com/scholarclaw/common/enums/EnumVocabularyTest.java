package com.scholarclaw.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import com.scholarclaw.common.exception.ErrorCode;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 锁定枚举词汇表的漂移防护（{@code 06} §2 验收要求：不得发明同义字段或枚举）。
 *
 * <p>这些取值同时出现在 MySQL 的 CHECK 约束、JSON 契约、Python Pydantic 模型与前端联合类型里，
 * 任何一处改名都会造成跨语言不一致，因此用精确断言冻结。
 */
class EnumVocabularyTest {

    @Test
    @DisplayName("DocumentStatus 六态取值锁定")
    void documentStatusVocabularyIsLocked() {
        assertThat(namesOf(DocumentStatus.class))
                .containsExactly("UPLOADED", "PARSING", "READY", "FAILED", "DELETING", "DELETED");
    }

    @Test
    @DisplayName("TaskStatus 六态取值锁定")
    void taskStatusVocabularyIsLocked() {
        assertThat(namesOf(TaskStatus.class))
                .containsExactly("PENDING_TIME", "PENDING_CONFIRM", "ACTIVE", "OVERDUE", "COMPLETED", "CANCELED");
    }

    @Test
    @DisplayName("InboundStatus 六态取值锁定")
    void inboundStatusVocabularyIsLocked() {
        assertThat(namesOf(InboundStatus.class))
                .containsExactly("RECEIVED", "DUPLICATE_IGNORED", "AUTH_REJECTED", "PROCESSING", "PROCESSED", "FAILED");
    }

    @Test
    @DisplayName("OutboundStatus 四态取值锁定")
    void outboundStatusVocabularyIsLocked() {
        assertThat(namesOf(OutboundStatus.class))
                .containsExactly("PENDING_IM", "RATE_LIMITED_DELAYED", "SENT_IM", "FALLBACK_WEB_ONLY");
    }

    @Test
    @DisplayName("JobType 三值取值锁定")
    void jobTypeVocabularyIsLocked() {
        assertThat(namesOf(JobType.class)).containsExactly("INITIAL_INGEST", "RETRY_FAILED", "REINDEX");
    }

    @Test
    @DisplayName("TimePrecision 四级取值锁定")
    void timePrecisionVocabularyIsLocked() {
        assertThat(namesOf(TimePrecision.class))
                .containsExactly("EXACT_DATETIME", "DATE_ONLY", "VAGUE_OR_MISSING", "INVALID_OR_PAST");
    }

    @Test
    @DisplayName("IndexStatus 三态取值锁定")
    void indexStatusVocabularyIsLocked() {
        assertThat(namesOf(IndexStatus.class)).containsExactly("ACTIVE", "REBUILDING", "REBUILD_FAILED");
    }

    @Test
    @DisplayName("错误码字典：码值唯一且均有脱敏文案")
    void errorCodeDictionaryIsConsistent() {
        List<String> codes = Arrays.stream(ErrorCode.values()).map(ErrorCode::code).toList();

        assertThat(codes).doesNotHaveDuplicates();
        assertThat(codes).allSatisfy(code -> assertThat(code).isNotBlank());
        assertThat(Arrays.stream(ErrorCode.values()).map(ErrorCode::defaultMessage).toList())
                .allSatisfy(message -> assertThat(message).isNotBlank());
    }

    @Test
    @DisplayName("错误码字典：按 code 与别名均可反查")
    void errorCodeIsResolvableByCodeAndAlias() {
        assertThat(ErrorCode.fromCode("E-SYS-02")).isEqualTo(ErrorCode.E_SYS_02);
        assertThat(ErrorCode.fromCode("SCAN_PDF_OCR_NOT_ENABLED")).isEqualTo(ErrorCode.E_RAG_01);
        assertThat(ErrorCode.fromCode("NOT_A_CODE")).isNull();
        assertThat(ErrorCode.fromCode(null)).isNull();
    }

    private static <E extends Enum<E>> List<String> namesOf(Class<E> enumType) {
        return Arrays.stream(enumType.getEnumConstants()).map(Enum::name).toList();
    }
}
