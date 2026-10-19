package com.scholarclaw.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BYOK 主密钥加解密（{@code 03} §7.3、{@code 04} §4.2.3）。
 *
 * <p>钉住三件容易出错的事：密文格式带版本前缀、每次加密用独立随机 IV、主密钥缺失/非法时失败关闭。
 */
class AesGcmCryptoServiceTest {

    private static final String KEY_A = Base64.getEncoder().encodeToString(fill((byte) 1));
    private static final String KEY_B = Base64.getEncoder().encodeToString(fill((byte) 2));

    private static byte[] fill(byte value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }

    @Test
    @DisplayName("密文格式为 v1:{base64 12 字节 IV}:{base64 密文+标签}，且可解回原文")
    void encryptsInLockedFormat() {
        AesGcmCryptoService service = new AesGcmCryptoService(KEY_A);

        String cipherText = service.encrypt("sk-1234567890abcdef");

        assertThat(cipherText).startsWith("v1:");
        String[] parts = cipherText.split(":");
        assertThat(parts).hasSize(3);
        assertThat(Base64.getDecoder().decode(parts[1])).hasSize(12);
        assertThat(service.decrypt(cipherText)).isEqualTo("sk-1234567890abcdef");
    }

    @Test
    @DisplayName("同一明文两次加密得到不同密文（每次独立随机 IV）")
    void usesFreshIvOnEveryEncryption() {
        AesGcmCryptoService service = new AesGcmCryptoService(KEY_A);

        String first = service.encrypt("sk-same-key");
        String second = service.encrypt("sk-same-key");

        assertThat(first).isNotEqualTo(second);
        assertThat(service.decrypt(first)).isEqualTo(service.decrypt(second));
    }

    @Test
    @DisplayName("密文被篡改或换用其他主密钥时解密失败，且异常消息不含明文")
    void rejectsTamperedOrForeignCipherText() {
        AesGcmCryptoService service = new AesGcmCryptoService(KEY_A);
        String cipherText = service.encrypt("sk-1234567890abcdef");
        String tampered = cipherText.substring(0, cipherText.length() - 2) + "AA";

        assertThatThrownBy(() -> service.decrypt(tampered))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INTERNAL_ERROR))
                .hasMessageNotContaining("sk-1234567890abcdef");

        assertThatThrownBy(() -> new AesGcmCryptoService(KEY_B).decrypt(cipherText))
                .isInstanceOf(BusinessException.class)
                .hasMessageNotContaining("sk-1234567890abcdef");
    }

    @Test
    @DisplayName("拒绝未知版本前缀与坏格式，而不是猜测性解析")
    void rejectsUnknownVersionOrMalformedCipher() {
        AesGcmCryptoService service = new AesGcmCryptoService(KEY_A);

        assertThatThrownBy(() -> service.decrypt("v2:abc:def"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.decrypt("v1:only-one-part"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("掩码只保留前 3 后 4，过短 Key 只回 ****")
    void masksSecretsWithoutLeakingTheMiddle() {
        assertThat(AesGcmCryptoService.mask("sk-1234567890abcdef8a9f")).isEqualTo("sk-****8a9f");
        assertThat(AesGcmCryptoService.mask("12345678")).isEqualTo("****");
        assertThat(AesGcmCryptoService.mask("short")).isEqualTo("****");
        assertThat(AesGcmCryptoService.mask(null)).isEqualTo("****");
    }

    @Test
    @DisplayName("主密钥缺失/非 Base64/非 32 字节时构造失败（拒绝启动）")
    void failsClosedWhenMasterKeyIsMissingOrInvalid() {
        assertThatThrownBy(() -> new AesGcmCryptoService(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SC_MASTER_KEY");
        assertThatThrownBy(() -> new AesGcmCryptoService("not-base64!!"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AesGcmCryptoService(
                Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }
}
