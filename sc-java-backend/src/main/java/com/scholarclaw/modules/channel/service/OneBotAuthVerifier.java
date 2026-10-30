package com.scholarclaw.modules.channel.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * OneBot Webhook 令牌校验（TASK-803、{@code 04} §4.8.1）。
 *
 * <p>同时接受两种 OneBot 11 鉴权方式：
 * <ol>
 *   <li>{@code Authorization: Bearer <SC_ONEBOT_ACCESS_TOKEN>}：常量时间字符串比较；</li>
 *   <li>{@code X-Signature: sha1=<hex>}：NapCat HTTP 客户端默认方式，
 *       对**原始请求体字节**计算 {@code HMAC-SHA1(token, body)}；</li>
 * </ol>
 *
 * <p>令牌未注入时**失败关闭**。HMAC 必须基于未重新序列化的原始 body 计算，否则
 * JSON 空白/字段顺序变化会导致签名永远不匹配；因此控制器把原始 {@code byte[]} 传进来。
 * 日志与异常信息都不包含令牌或签名。
 */
@Component
public class OneBotAuthVerifier {

    private static final String BEARER = "bearer ";
    private static final String SIGNATURE_PREFIX = "sha1=";
    private static final String HMAC_ALGORITHM = "HmacSHA1";

    private final byte[] expectedToken;

    public OneBotAuthVerifier(@Value("${sc.onebot.access-token:}") String accessToken) {
        this.expectedToken = accessToken == null ? new byte[0]
                : accessToken.trim().getBytes(StandardCharsets.UTF_8);
    }

    /** Bearer 兼容入口（保留既有调用与测试）。 */
    public boolean isAuthorized(String authorizationHeader) {
        return isAuthorized(authorizationHeader, null, null);
    }

    /**
     * 校验 Bearer 或 X-Signature。
     *
     * @param authorizationHeader {@code Authorization} 头，可为空
     * @param signatureHeader     {@code X-Signature} 头（{@code sha1=<hex>}），可为空
     * @param rawBody             原始请求体字节；HMAC 校验必需
     */
    public boolean isAuthorized(String authorizationHeader, String signatureHeader,
                                byte[] rawBody) {
        if (expectedToken.length == 0) {
            return false;
        }
        if (matchesBearer(authorizationHeader)) {
            return true;
        }
        return matchesSignature(signatureHeader, rawBody);
    }

    private boolean matchesBearer(String authorizationHeader) {
        if (authorizationHeader == null) {
            return false;
        }
        String header = authorizationHeader.trim();
        if (header.length() <= BEARER.length()
                || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return false;
        }
        byte[] actual = header.substring(BEARER.length()).trim()
                .getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedToken, actual);
    }

    private boolean matchesSignature(String signatureHeader, byte[] rawBody) {
        if (signatureHeader == null || rawBody == null || rawBody.length == 0) {
            return false;
        }
        String header = signatureHeader.trim();
        if (header.length() != SIGNATURE_PREFIX.length() + 40
                || !header.regionMatches(true, 0, SIGNATURE_PREFIX, 0,
                SIGNATURE_PREFIX.length())) {
            return false;
        }
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(
                    header.substring(SIGNATURE_PREFIX.length()).toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(expectedToken, HMAC_ALGORITHM));
            byte[] expected = mac.doFinal(rawBody);
            return MessageDigest.isEqual(expected, provided);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
