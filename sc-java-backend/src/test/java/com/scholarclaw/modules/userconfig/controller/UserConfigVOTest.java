package com.scholarclaw.modules.userconfig.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.scholarclaw.modules.userconfig.dto.UserConfigVO;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 当前用户配置视图的序列化细节（{@code 04} §2.5 / `API-USER-001`）。
 *
 * <p>这些断言来自容器 E2E 里真实踩到的两处偏差：`LocalTime#toString` 会把 `23:30:00` 省略成
 * `"23:30"`；`LocalDateTime#toString` 不带时区偏移量。契约要求 `"HH:mm:ss"` 与带偏移量的 ISO-8601，
 * 所以用单测把它钉住，而不是靠人肉眼看响应体。
 */
class UserConfigVOTest {

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
        UserConfigVO vo = UserConfigController.toVO(entity());

        assertThat(vo.dndStartTime()).isEqualTo("23:30:00");
        assertThat(vo.dndEndTime()).isEqualTo("07:30:00");
        assertThat(vo.updatedAt()).isEqualTo("2026-10-19T18:47:15.025+08:00");
    }

    @Test
    @DisplayName("未配置密钥时 has_* 为 false 且掩码为 null；VO 不含任何密文字段")
    void doesNotLeakSecretMaterial() {
        UserConfigVO vo = UserConfigController.toVO(entity());

        assertThat(vo.hasChatApiKey()).isFalse();
        assertThat(vo.hasEmbeddingApiKey()).isFalse();
        assertThat(vo.chatApiKeyMasked()).isNull();
        assertThat(vo.embeddingApiKeyMasked()).isNull();
        // record 的组件即对外字段集合：不存在 password_hash / *_cipher / key_version
        assertThat(UserConfigVO.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("passwordHash", "chatApiKeyCipher", "embeddingApiKeyCipher", "keyVersion");
    }
}
