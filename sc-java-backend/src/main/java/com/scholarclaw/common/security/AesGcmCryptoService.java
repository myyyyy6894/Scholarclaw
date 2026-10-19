package com.scholarclaw.common.security;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * BYOK API Key 的 AES-256-GCM 加解密（{@code 03} §7.3、{@code 04} §4.2.3）。
 *
 * <p><b>密文格式（`03` §7.3 锁定）：</b>{@code v1:{base64(12 字节随机 IV)}:{base64(密文 + 128bit 认证标签)}}。
 * 每次加密都用 {@link SecureRandom} 生成**全新 IV**，因此同一把 Key 保存两次得到完全不同的密文
 * （`04` 的测试要点之一）。
 *
 * <p><b>失败关闭：</b>{@code SC_MASTER_KEY} 未注入或不是 32 字节 Base64 时**启动即失败**——
 * 主密钥是唯一能解开用户密钥的东西，缺失时既不能加密也不能解密，启动起来只会把库写坏。
 *
 * <p><b>为什么单独建服务而不是散落在 Service 里：</b>加解密是实现细节，业务代码只应看到
 * "明文进、密文出"；把 IV 生成、标签长度、版本前缀这些容易写错的地方收敛到一处，
 * 并用单元测试钉住格式。
 */
@Service
public class AesGcmCryptoService {

    private static final Logger log = LoggerFactory.getLogger(AesGcmCryptoService.class);

    /** 密文版本前缀（`03` §7.3）；将来轮换主密钥时递增并保留旧版本解密分支。 */
    public static final String VERSION_PREFIX = "v1:";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesGcmCryptoService(@Value("${sc.master-key:}") String base64MasterKey) {
        byte[] keyBytes = decodeMasterKey(base64MasterKey);
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    /** 加密明文；返回值即落库的密文（不含任何明文片段）。 */
    public String encrypt(String plaintext) {
        requireText(plaintext, "待加密内容");
        byte[] iv = new byte[IV_BYTES];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder encoder = Base64.getEncoder();
            return VERSION_PREFIX + encoder.encodeToString(iv) + ":" + encoder.encodeToString(ciphertext);
        } catch (Exception e) {
            // 不把明文或异常细节带进日志/响应
            log.error("API Key 加密失败 error={}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "密钥加密失败，请稍后重试。");
        }
    }

    /**
     * 解密密文。
     *
     * <p>密文被篡改、版本未知或主密钥不匹配都会抛 {@link ErrorCode#INTERNAL_ERROR}：
     * 对外只说"配置读取失败"，不暴露"哪一段不对"（避免成为探测手段）。
     */
    public String decrypt(String cipherText) {
        requireText(cipherText, "密文");
        if (!cipherText.startsWith(VERSION_PREFIX)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "密钥版本不受支持，请重新保存模型配置。");
        }
        String body = cipherText.substring(VERSION_PREFIX.length());
        int separator = body.indexOf(':');
        if (separator <= 0) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "密钥密文格式非法。");
        }
        try {
            byte[] iv = Base64.getDecoder().decode(body.substring(0, separator));
            byte[] ciphertext = Base64.getDecoder().decode(body.substring(separator + 1));
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            log.error("API Key 解密失败：认证标签不匹配（密钥不匹配或被篡改）");
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "密钥配置无法解密，请重新保存模型配置。");
        } catch (Exception e) {
            log.error("API Key 解密失败 error={}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "密钥配置无法解密，请重新保存模型配置。");
        }
    }

    /**
     * 掩码回显（`04` `API-USER-001`：如 `sk-****8a9f`）。
     *
     * <p>只取前 3 位与后 4 位；过短的 Key 一律只回 `****`，避免"掩码 = 明文"。
     */
    public static String mask(String plaintext) {
        if (plaintext == null || plaintext.length() <= 8) {
            return "****";
        }
        return plaintext.substring(0, 3) + "****" + plaintext.substring(plaintext.length() - 4);
    }

    private static byte[] decodeMasterKey(String base64MasterKey) {
        String value = base64MasterKey == null ? "" : base64MasterKey.trim();
        if (value.isEmpty()) {
            throw new IllegalStateException(
                    "SC_MASTER_KEY 未注入，拒绝启动（03 §7.3：主密钥是唯一能解开用户密钥的东西）");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length != KEY_BYTES) {
                throw new IllegalStateException(
                        "SC_MASTER_KEY 必须是 32 字节 Base64（AES-256），当前解码后 " + decoded.length + " 字节");
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("SC_MASTER_KEY 不是合法的 Base64 字符串", e);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, field + "不能为空。");
        }
    }
}
