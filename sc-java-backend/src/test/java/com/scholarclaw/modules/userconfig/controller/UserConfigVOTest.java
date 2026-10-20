package com.scholarclaw.modules.userconfig.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.config.JacksonConfig;
import com.scholarclaw.common.security.AesGcmCryptoService;
import com.scholarclaw.modules.userconfig.dto.UserConfigVO;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * 当前用户配置视图的序列化细节（{@code 04} §2.5 / `API-USER-001`）。
 *
 * <p>这些断言来自容器 E2E 里真实踩到的两处偏差：`LocalTime#toString` 会把 `23:30:00` 省略成
 * `"23:30"`；`LocalDateTime#toString` 不带时区偏移量。契约要求 `"HH:mm:ss"` 与带偏移量的 ISO-8601，
 * 所以用单测把它钉住，而不是靠人肉眼看响应体。
 */
class UserConfigVOTest {

    /** 32 字节全零密钥：只用于单元测试，不参与任何真实加密。 */
    private static final AesGcmCryptoService CRYPTO =
            new AesGcmCryptoService(Base64.getEncoder().encodeToString(new byte[32]));

    private static UserConfigEntity entity() {
        UserConfigEntity entity = new UserConfigEntity();
        entity.setUserId(10001L);
        entity.setUsername("zhangsan_cs");
        entity.setAccountStatus("ACTIVE");
        entity.setTimezone("Asia/Shanghai");
        entity.setDndStartTime(LocalTime.of(23, 30));
        entity.setDndEndTime(LocalTime.of(7, 30));
        entity.setUpdatedAt(LocalDateTime.of(2026, 10, 19, 18, 47, 15, 25_000_000));
        return entity;
    }

    @Test
    @DisplayName("纯时刻输出 HH:mm:ss，完整时间戳带 +08:00 偏移量")
    void formatsTimesPerContract() {
        UserConfigVO vo = UserConfigController.toVO(entity(), CRYPTO);

        assertThat(vo.dndStartTime()).isEqualTo("23:30:00");
        assertThat(vo.dndEndTime()).isEqualTo("07:30:00");
        assertThat(vo.updatedAt()).isEqualTo("2026-10-19T18:47:15.025+08:00");
    }

    @Test
    @DisplayName("未配置密钥时 has_* 为 false 且掩码为 null；VO 不含任何密文字段")
    void doesNotLeakSecretMaterial() {
        UserConfigVO vo = UserConfigController.toVO(entity(), CRYPTO);

        assertThat(vo.hasChatApiKey()).isFalse();
        assertThat(vo.hasEmbeddingApiKey()).isFalse();
        assertThat(vo.chatApiKeyMasked()).isNull();
        assertThat(vo.embeddingApiKeyMasked()).isNull();
        // record 的组件即对外字段集合：不存在 password_hash / *_cipher / key_version
        assertThat(UserConfigVO.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("passwordHash", "chatApiKeyCipher", "embeddingApiKeyCipher", "keyVersion");
    }

    @Test
    @DisplayName("已保存 Key 只回显首 3 位 + 后 4 位，绝不回明文或密文")
    void masksSavedKey() throws Exception {
        UserConfigEntity entity = entity();
        entity.setChatApiKeyCipher(CRYPTO.encrypt("sk-1234567890abcdef8a9f"));
        entity.setEmbeddingApiKeyCipher(CRYPTO.encrypt("sk-embedding-3c2d"));

        UserConfigVO vo = UserConfigController.toVO(entity, CRYPTO);

        assertThat(vo.hasChatApiKey()).isTrue();
        assertThat(vo.chatApiKeyMasked()).isEqualTo("sk-****8a9f");
        assertThat(vo.embeddingApiKeyMasked()).isEqualTo("sk-****3c2d");

        String json = mapper().writeValueAsString(vo);
        assertThat(json).doesNotContain("sk-1234567890abcdef8a9f", "v1:", "embedding-3c2d");
    }

    @Test
    @DisplayName("旧密文无法解密时返回占位掩码，配置页仍可打开并重新保存")
    void corruptSavedCipherDegradesToPlaceholderMask() {
        UserConfigEntity entity = entity();
        entity.setChatApiKeyCipher("v1:corrupted:corrupted");

        UserConfigVO vo = UserConfigController.toVO(entity, CRYPTO);

        assertThat(vo.hasChatApiKey()).isTrue();
        assertThat(vo.chatApiKeyMasked()).isEqualTo("****");
    }

    @Test
    @DisplayName("existing_course_count 只在保存响应中出现，GET 视图整字段省略")
    void existingCourseCountIsOnlyPresentOnSaveResponse() throws Exception {
        String getJson = mapper().writeValueAsString(UserConfigController.toVO(entity(), CRYPTO));
        String saveJson = mapper().writeValueAsString(UserConfigController.toVO(entity(), CRYPTO, 0));

        assertThat(getJson).doesNotContain("existing_course_count");
        assertThat(saveJson).contains("\"existing_course_count\":0");
    }

    @Test
    @DisplayName("同一 Key 两次加密得到不同密文（独立随机 IV）")
    void sameKeyEncryptsToDifferentCipherText() {
        String first = CRYPTO.encrypt("sk-1234567890abcdef8a9f");
        String second = CRYPTO.encrypt("sk-1234567890abcdef8a9f");

        assertThat(first).startsWith("v1:").isNotEqualTo(second);
        assertThat(CRYPTO.decrypt(first)).isEqualTo("sk-1234567890abcdef8a9f");
    }

    private static ObjectMapper mapper() {
        return JacksonConfig.applyTo(new Jackson2ObjectMapperBuilder(), ZoneId.of("Asia/Shanghai")).build();
    }
}
