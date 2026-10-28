package com.scholarclaw.common.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Java → OneBot 11 Action API 的唯一出口（TASK-803、{@code 02} §4.3）。
 *
 * <p>只封装 MVP 需要的三个动作：
 * <ul>
 *   <li>{@code send_private_msg}：回复私聊卡片；</li>
 *   <li>{@code get_forward_msg}：拉取合并转发，单层纯文本节点；嵌套转发明确失败；</li>
 *   <li>{@code get_file} + 临时 URL/base64：拉取单文件内容（≤ 50MB）。</li>
 * </ul>
 *
 * <p><b>安全边界：</b>只接受 {@code http}/{@code https} 文件地址，禁用代理与重定向，
 * 限制响应大小；NapCat 返回本机路径时明确失败并引导 Web 上传，绝不读取任意宿主机文件。
 * 令牌只放在请求头，**不打印请求体、响应体或令牌**。
 *
 * <p><b>TASK-804 边界：</b>本类不做令牌桶、随机抖动、重试与 {@code FALLBACK_WEB_ONLY}
 * 降级；这些由 TASK-804 的出站调度器统一处理。当前方法失败直接抛出稳定错误。
 */
@Component
public class OneBotApiClient {

    private static final Logger log = LoggerFactory.getLogger(OneBotApiClient.class);

    public static final long MAX_FILE_BYTES = 52_428_800L;
    private static final int MAX_ACTION_BODY_BYTES = 64 * 1024;
    private static final Pattern CQ_CODE = Pattern.compile("\\[CQ:[^\\]]+]");

    private final String baseUrl;
    private final String accessToken;
    private final Duration actionTimeout;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OneBotApiClient(
            @Value("${sc.onebot.base-url:}") String baseUrl,
            @Value("${sc.onebot.access-token:}") String accessToken,
            @Value("${sc.onebot.action-timeout-ms:5000}") long actionTimeoutMs,
            ObjectMapper objectMapper) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.accessToken = accessToken == null ? "" : accessToken.trim();
        this.actionTimeout = Duration.ofMillis(Math.max(1000, actionTimeoutMs));
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                // OneBot/NapCat 位于内部网络；不能被宿主代理环境变量带偏（RISK-28）。
                .proxy(ProxySelector.of(null))
                .build();
    }

    public boolean isConfigured() {
        return !baseUrl.isBlank();
    }

    /** 发送私聊文本；失败抛出脱敏异常，调用方负责记录与降级。 */
    public void sendPrivateMsg(Long imUid, String text) {
        if (imUid == null || imUid <= 0) {
            throw new IllegalArgumentException("OneBot 私聊目标 QQ 号非法");
        }
        if (text == null || text.isBlank()) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_id", imUid);
        body.put("message", text.trim());
        JsonNode response = postAction("send_private_msg", body,
                ErrorCode.INTERNAL_ERROR, "OneBot 回复发送失败，请稍后在 Web 工作台操作。");
        requireOk(response, ErrorCode.INTERNAL_ERROR,
                "OneBot 回复发送失败，请稍后在 Web 工作台操作。");
    }

    /**
     * 拉取单层合并转发并拼接纯文本节点。
     *
     * @throws BusinessException {@code E-IM-03}：嵌套转发、为空或无法读取
     */
    public String getForwardText(String forwardId) {
        if (forwardId == null || forwardId.isBlank()) {
            throw new BusinessException(ErrorCode.E_IM_03);
        }
        Map<String, Object> body = Map.of("id", forwardId.trim());
        JsonNode response = postAction("get_forward_msg", body,
                ErrorCode.E_IM_03, ErrorCode.E_IM_03.defaultMessage());
        JsonNode data = requireOk(response, ErrorCode.E_IM_03,
                ErrorCode.E_IM_03.defaultMessage());
        JsonNode messages = data.isArray() ? data : data.path("messages");
        if (!messages.isArray() || messages.isEmpty()) {
            throw new BusinessException(ErrorCode.E_IM_03);
        }
        List<String> texts = new ArrayList<>();
        for (JsonNode node : messages) {
            String text = textOfForwardNode(node);
            if (text != null && !text.isBlank()) {
                texts.add(text.trim());
            }
        }
        if (texts.isEmpty()) {
            throw new BusinessException(ErrorCode.E_IM_03);
        }
        return String.join("\n", texts);
    }

    /**
     * 获取单个文件内容。
     *
     * @param fileReference {@code file_id}、{@code base64://...} 或 HTTP(S) 临时地址
     * @param fallbackName  事件中的文件名（Action 响应缺失时使用）
     * @throws BusinessException {@code E-IM-02}：下载失败/路径不可读；{@code FILE_SIZE_EXCEEDED}：超过 50MB
     */
    public DownloadedFile fetchFile(String fileReference, String fallbackName) {
        if (fileReference == null || fileReference.isBlank()) {
            throw new BusinessException(ErrorCode.E_IM_02);
        }
        String reference = fileReference.trim();
        if (reference.startsWith("base64://")) {
            return decodeBase64(reference.substring("base64://".length()), fallbackName);
        }
        if (isHttpUrl(reference)) {
            return download(reference, fallbackName, null);
        }

        Map<String, Object> body = Map.of("file_id", reference);
        JsonNode response = postAction("get_file", body,
                ErrorCode.E_IM_02, ErrorCode.E_IM_02.defaultMessage());
        JsonNode data = requireOk(response, ErrorCode.E_IM_02,
                ErrorCode.E_IM_02.defaultMessage());
        String fileName = firstText(data, "file_name", "name");
        if (fileName == null) {
            fileName = fallbackName;
        }
        String file = firstText(data, "file", "url");
        long size = firstLong(data, "file_size", "size");
        if (size > MAX_FILE_BYTES) {
            throw new BusinessException(ErrorCode.FILE_SIZE_EXCEEDED);
        }
        if (file == null) {
            throw new BusinessException(ErrorCode.E_IM_02);
        }
        if (file.startsWith("base64://")) {
            return decodeBase64(file.substring("base64://".length()), fileName);
        }
        if (isHttpUrl(file)) {
            return download(file, fileName, size > 0 ? size : null);
        }
        // NapCat 也可能返回协议端本机路径；不应读取任意宿主机文件。
        log.warn("OneBot get_file 返回非 URL/base64 路径，拒绝读取（请改用 Web 上传）");
        throw new BusinessException(ErrorCode.E_IM_02);
    }

    private String textOfForwardNode(JsonNode node) {
        JsonNode message = node.path("message");
        if (message.isTextual()) {
            String raw = message.asText();
            if (raw.contains("[CQ:forward")) {
                throw new BusinessException(ErrorCode.E_IM_03);
            }
            return CQ_CODE.matcher(raw).replaceAll("").trim();
        }
        if (message.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode segment : message) {
                String type = segment.path("type").asText("");
                if ("forward".equals(type)) {
                    throw new BusinessException(ErrorCode.E_IM_03);
                }
                if ("text".equals(type)) {
                    builder.append(segment.path("data").path("text").asText(""));
                }
            }
            return builder.toString();
        }
        String raw = node.path("raw_message").asText("");
        if (raw.contains("[CQ:forward")) {
            throw new BusinessException(ErrorCode.E_IM_03);
        }
        return CQ_CODE.matcher(raw).replaceAll("").trim();
    }

    private DownloadedFile decodeBase64(String encoded, String fileName) {
        try {
            byte[] content = Base64.getDecoder().decode(encoded);
            return checkedFile(fileName, content, null);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.E_IM_02);
        }
    }

    private DownloadedFile download(String url, String fileName, Long declaredSize) {
        if (declaredSize != null && declaredSize > MAX_FILE_BYTES) {
            throw new BusinessException(ErrorCode.FILE_SIZE_EXCEEDED);
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.E_IM_02);
        }
        if (!"http".equalsIgnoreCase(uri.getScheme())
                && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new BusinessException(ErrorCode.E_IM_02);
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                closeQuietly(response.body());
                throw new BusinessException(ErrorCode.E_IM_02);
            }
            long length = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (length > MAX_FILE_BYTES) {
                closeQuietly(response.body());
                throw new BusinessException(ErrorCode.FILE_SIZE_EXCEEDED);
            }
            try (InputStream input = response.body()) {
                byte[] content = input.readNBytes((int) MAX_FILE_BYTES + 1);
                if (content.length > MAX_FILE_BYTES) {
                    throw new BusinessException(ErrorCode.FILE_SIZE_EXCEEDED);
                }
                return checkedFile(fileName, content, response.headers()
                        .firstValue("Content-Type").orElse(null));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.E_IM_02);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.E_IM_02);
        }
    }

    private static DownloadedFile checkedFile(String fileName, byte[] content, String contentType) {
        if (content.length == 0) {
            throw new BusinessException(ErrorCode.E_IM_02);
        }
        if (content.length > MAX_FILE_BYTES) {
            throw new BusinessException(ErrorCode.FILE_SIZE_EXCEEDED);
        }
        if (fileName == null || fileName.isBlank()) {
            throw new BusinessException(ErrorCode.E_IM_02);
        }
        return new DownloadedFile(fileName, contentType, content);
    }

    private JsonNode postAction(String action, Object body, ErrorCode errorCode,
                                String message) {
        if (!isConfigured()) {
            throw new BusinessException(errorCode, message);
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new BusinessException(errorCode, message);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/" + action))
                .timeout(actionTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (!accessToken.isBlank()) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        try {
            HttpResponse<String> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200
                    || response.body() == null
                    || response.body().length() > MAX_ACTION_BODY_BYTES) {
                throw new BusinessException(errorCode, message);
            }
            return objectMapper.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(errorCode, message);
        } catch (IOException | IllegalArgumentException e) {
            throw new BusinessException(errorCode, message);
        }
    }

    private static JsonNode requireOk(JsonNode response, ErrorCode errorCode, String message) {
        if (response == null) {
            throw new BusinessException(errorCode, message);
        }
        String status = response.path("status").asText("");
        int retcode = response.path("retcode").asInt(-1);
        if (!"ok".equalsIgnoreCase(status) || retcode != 0) {
            throw new BusinessException(errorCode, message);
        }
        JsonNode data = response.path("data");
        return data.isMissingNode() ? response : data;
    }

    private static String firstText(JsonNode data, String... fields) {
        for (String field : fields) {
            JsonNode value = data.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText().trim();
            }
        }
        return null;
    }

    private static long firstLong(JsonNode data, String... fields) {
        for (String field : fields) {
            JsonNode value = data.path(field);
            if (value.isNumber()) {
                return value.asLong();
            }
            if (value.isTextual() && value.asText().matches("\\d{1,19}")) {
                return Long.parseLong(value.asText());
            }
        }
        return -1L;
    }

    private static boolean isHttpUrl(String value) {
        return value.startsWith("http://") || value.startsWith("https://");
    }

    private static String trimTrailingSlash(String value) {
        String trimmed = value == null ? "" : value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // 已准备返回失败；关闭失败不影响结果。
        }
    }

    /** 已下载文件；{@code contentType} 仅作参考，安全校验仍由 knowledge 模块执行。 */
    public record DownloadedFile(String fileName, String contentType, byte[] content) {
    }
}
