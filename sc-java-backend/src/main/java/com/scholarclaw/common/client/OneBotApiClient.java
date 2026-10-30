package com.scholarclaw.common.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.security.OneBotFileUrlSecurity;
import com.scholarclaw.common.security.OneBotFileUrlSecurity.ValidatedTarget;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.Socket;
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
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    /** get_file 可能把整个文件以 base64 放在 JSON 里，必须按文件上限放宽。 */
    private static final int MAX_FILE_ACTION_BODY_BYTES =
            (int) (MAX_FILE_BYTES * 4 / 3) + 1024 * 1024;
    private static final Pattern CQ_CODE = Pattern.compile("\\[CQ:[^\\]]+]");

    private final String baseUrl;
    private final String accessToken;
    private final Duration actionTimeout;
    private final Duration fileActionTimeout;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final OneBotFileUrlSecurity fileUrlSecurity;

    @Autowired
    public OneBotApiClient(
            @Value("${sc.onebot.base-url:}") String baseUrl,
            @Value("${sc.onebot.access-token:}") String accessToken,
            @Value("${sc.onebot.action-timeout-ms:5000}") long actionTimeoutMs,
            @Value("${sc.onebot.file-timeout-ms:30000}") long fileTimeoutMs,
            ObjectMapper objectMapper,
            OneBotFileUrlSecurity fileUrlSecurity) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.accessToken = accessToken == null ? "" : accessToken.trim();
        this.actionTimeout = Duration.ofMillis(Math.max(1000, actionTimeoutMs));
        this.fileActionTimeout = Duration.ofMillis(Math.max(1000, fileTimeoutMs));
        this.objectMapper = objectMapper;
        this.fileUrlSecurity = fileUrlSecurity;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                // OneBot/NapCat 位于内部网络；不能被宿主代理环境变量带偏（RISK-28）。
                .proxy(ProxySelector.of(null))
                .build();
    }

    /** 兼容直接构造（单测/旧调用）：默认不放行任何私网文件 Origin。 */
    public OneBotApiClient(String baseUrl, String accessToken, long actionTimeoutMs,
                           ObjectMapper objectMapper) {
        this(baseUrl, accessToken, actionTimeoutMs, 30_000, objectMapper,
                new OneBotFileUrlSecurity("", InetAddress::getAllByName));
    }

    public OneBotApiClient(String baseUrl, String accessToken, long actionTimeoutMs,
                           ObjectMapper objectMapper, OneBotFileUrlSecurity fileUrlSecurity) {
        this(baseUrl, accessToken, actionTimeoutMs, 30_000, objectMapper, fileUrlSecurity);
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
     * 发送群聊文本（群聊 {@code @} 回复必须回到原群，而不是私聊发送者）。
     *
     * <p>调用方负责把 {@code @} 片段从正文中剥离；本方法只发送纯文本。
     */
    public void sendGroupMsg(Long groupId, String text) {
        if (groupId == null || groupId <= 0) {
            throw new IllegalArgumentException("OneBot 群聊目标群号非法");
        }
        if (text == null || text.isBlank()) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("group_id", groupId);
        body.put("message", text.trim());
        JsonNode response = postAction("send_group_msg", body,
                ErrorCode.INTERNAL_ERROR, "OneBot 群聊回复发送失败，请稍后在 Web 工作台操作。");
        requireOk(response, ErrorCode.INTERNAL_ERROR,
                "OneBot 群聊回复发送失败，请稍后在 Web 工作台操作。");
    }

    /**
     * 仅通过 {@code get_file} 解析文件名，不下载内容。
     *
     * <p>用于事件只带 {@code file_id}、没有 {@code name/file_name} 的情况：先拿到协议端
     * 返回的 {@code file_name} 做扩展名白名单校验，再决定是否下载。调用方必须先完成
     * DB-07 Embedding 前置检查；本方法不会触发文件内容传输。
     */
    public String resolveFileName(String fileReference) {
        if (fileReference == null || fileReference.isBlank()) {
            return null;
        }
        String reference = fileReference.trim();
        if (reference.startsWith("base64://")) {
            return null;
        }
        if (isHttpUrl(reference)) {
            try {
                URI uri = URI.create(reference);
                String path = uri.getPath();
                if (path == null || path.isBlank() || path.endsWith("/")) {
                    return null;
                }
                String segment = path.substring(path.lastIndexOf('/') + 1);
                return segment.isBlank() ? null : segment;
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("file_id", reference);
        body.put("file", reference);
        JsonNode response = postAction("get_file", body,
                ErrorCode.E_IM_02, ErrorCode.E_IM_02.defaultMessage(),
                fileActionTimeout, MAX_FILE_ACTION_BODY_BYTES);
        JsonNode data = requireOk(response, ErrorCode.E_IM_02,
                ErrorCode.E_IM_02.defaultMessage());
        return firstText(data, "file_name", "name");
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

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("file_id", reference);
        body.put("file", reference);
        JsonNode response = postAction("get_file", body,
                ErrorCode.E_IM_02, ErrorCode.E_IM_02.defaultMessage(),
                fileActionTimeout, MAX_FILE_ACTION_BODY_BYTES);
        JsonNode data = requireOk(response, ErrorCode.E_IM_02,
                ErrorCode.E_IM_02.defaultMessage());
        String fileName = firstText(data, "file_name", "name");
        if (fileName == null) {
            fileName = fallbackName;
        }
        long size = firstLong(data, "file_size", "size");
        if (size > MAX_FILE_BYTES) {
            throw new BusinessException(ErrorCode.FILE_SIZE_EXCEEDED);
        }
        // NapCat enableLocalFile2Url=true 时，get_file 会额外返回 base64 字段；
        // 这样协议端不必把本机路径暴露给 Java，也不依赖文件 URL / SSRF 白名单。
        String base64 = firstText(data, "base64");
        if (base64 != null) {
            if ((long) base64.length() > MAX_FILE_BYTES * 4 / 3 + 4) {
                throw new BusinessException(ErrorCode.FILE_SIZE_EXCEEDED);
            }
            return decodeBase64(base64, fileName);
        }
        String file = firstText(data, "file", "url");
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
            String value = encoded == null ? "" : encoded.trim();
            if (value.startsWith("base64://")) {
                value = value.substring("base64://".length());
            } else if (value.startsWith("data:")) {
                int comma = value.indexOf(',');
                if (comma < 0) {
                    throw new IllegalArgumentException("data URI 缺少逗号");
                }
                value = value.substring(comma + 1);
            }
            byte[] content = Base64.getDecoder().decode(value);
            return checkedFile(fileName, content, null);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.E_IM_02);
        }
    }

    /**
     * 下载已经过 SSRF 校验的 HTTP(S) 文件。
     *
     * <p><b>为什么不用 {@link HttpClient}：</b>需要把连接钉扎到 {@link OneBotFileUrlSecurity}
     * 已解析并校验的 IP，避免“校验时是公网、建连时被 DNS rebinding 指向内网”。这里使用
     * HTTP/1.1 原始连接（禁止代理/重定向、{@code Connection: close}、{@code Accept-Encoding: identity}），
     * 目标地址只从钉扎列表中选择；HTTPS 使用默认信任库并强制主机名校验。
     */
    private DownloadedFile download(String url, String fileName, Long declaredSize) {
        if (declaredSize != null && declaredSize > MAX_FILE_BYTES) {
            throw new BusinessException(ErrorCode.FILE_SIZE_EXCEEDED);
        }
        ValidatedTarget target = fileUrlSecurity.validate(url);
        Socket raw = connectPinned(target);
        try {
            Socket socket = target.https() ? startTls(raw, target) : raw;
            writeGet(socket, target);
            return readResponse(new BufferedInputStream(socket.getInputStream()), fileName);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.E_IM_02);
        } finally {
            closeQuietly(raw);
        }
    }

    private static Socket connectPinned(ValidatedTarget target) {
        IOException last = null;
        for (InetAddress address : target.addresses()) {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(address, target.port()), 5000);
                socket.setSoTimeout(20_000);
                return socket;
            } catch (IOException e) {
                last = e;
                closeQuietly(socket);
            }
        }
        log.warn("OneBot 文件下载无法连接任一已校验地址 count={} error={}",
                target.addresses().size(),
                last == null ? "unknown" : last.getClass().getSimpleName());
        throw new BusinessException(ErrorCode.E_IM_02);
    }

    private static Socket startTls(Socket raw, ValidatedTarget target) throws IOException {
        try {
            SSLSocketFactory factory = SSLContext.getDefault().getSocketFactory();
            String peerHost = stripBrackets(target.hostHeader());
            SSLSocket ssl = (SSLSocket) factory.createSocket(
                    raw, peerHost, target.port(), true);
            SSLParameters parameters = ssl.getSSLParameters();
            // 证书主机名校验必须保留；连接本身已经钉扎到校验过的 IP。
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.setSSLParameters(parameters);
            ssl.startHandshake();
            return ssl;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("TLS 初始化失败", e);
        }
    }

    private static void writeGet(Socket socket, ValidatedTarget target) throws IOException {
        String path = target.uri().getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        String query = target.uri().getRawQuery();
        if (query != null && !query.isEmpty()) {
            path = path + "?" + query;
        }
        int defaultPort = target.https() ? 443 : 80;
        String host = target.hostHeader()
                + (target.port() == defaultPort ? "" : ":" + target.port());
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "User-Agent: ScholarClaw-OneBot/1.0\r\n"
                + "Accept: */*\r\n"
                + "Accept-Encoding: identity\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream output = socket.getOutputStream();
        output.write(request.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static DownloadedFile readResponse(InputStream input, String fileName)
            throws IOException {
        String statusLine = readLine(input, 4096);
        if (statusLine == null || !statusLine.startsWith("HTTP/1.")) {
            throw new BusinessException(ErrorCode.E_IM_02);
        }
        String[] statusParts = statusLine.split(" ", 3);
        if (statusParts.length < 2 || !"200".equals(statusParts[1])) {
            throw new BusinessException(ErrorCode.E_IM_02);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        int totalHeaderBytes = 0;
        while (true) {
            String line = readLine(input, 8192);
            if (line == null) {
                throw new BusinessException(ErrorCode.E_IM_02);
            }
            if (line.isEmpty()) {
                break;
            }
            totalHeaderBytes += line.length();
            if (totalHeaderBytes > 32 * 1024) {
                throw new BusinessException(ErrorCode.E_IM_02);
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            headers.putIfAbsent(key, value);
        }

        String contentEncoding = headers.get("content-encoding");
        if (contentEncoding != null && !contentEncoding.isBlank()
                && !"identity".equalsIgnoreCase(contentEncoding)) {
            // 不实现 gzip/br 解码，避免把压缩字节当作 PDF/MD/TXT 写入知识库。
            throw new BusinessException(ErrorCode.E_IM_02);
        }

        byte[] content;
        String transferEncoding = headers.get("transfer-encoding");
        if (transferEncoding != null
                && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")) {
            content = readChunked(input);
        } else {
            String contentLength = headers.get("content-length");
            if (contentLength != null) {
                long length;
                try {
                    length = Long.parseLong(contentLength);
                } catch (NumberFormatException e) {
                    throw new BusinessException(ErrorCode.E_IM_02);
                }
                if (length < 0) {
                    throw new BusinessException(ErrorCode.E_IM_02);
                }
                if (length > MAX_FILE_BYTES) {
                    throw new BusinessException(ErrorCode.FILE_SIZE_EXCEEDED);
                }
                content = readExactly(input, (int) length);
            } else {
                content = readUntilEof(input);
            }
        }
        return checkedFile(fileName, content, headers.get("content-type"));
    }

    private static byte[] readExactly(InputStream input, int length) throws IOException {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(buffer, offset, length - offset);
            if (read < 0) {
                throw new BusinessException(ErrorCode.E_IM_02);
            }
            offset += read;
        }
        return buffer;
    }

    private static byte[] readUntilEof(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > MAX_FILE_BYTES) {
                throw new BusinessException(ErrorCode.FILE_SIZE_EXCEEDED);
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static byte[] readChunked(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        long total = 0;
        while (true) {
            String sizeLine = readLine(input, 256);
            if (sizeLine == null) {
                throw new BusinessException(ErrorCode.E_IM_02);
            }
            int semicolon = sizeLine.indexOf(';');
            String sizeHex = (semicolon >= 0 ? sizeLine.substring(0, semicolon) : sizeLine).trim();
            int size;
            try {
                size = Integer.parseInt(sizeHex, 16);
            } catch (NumberFormatException e) {
                throw new BusinessException(ErrorCode.E_IM_02);
            }
            if (size < 0) {
                throw new BusinessException(ErrorCode.E_IM_02);
            }
            if (size == 0) {
                // 消费 trailer，直到空行；忽略 trailer 内容。
                while (true) {
                    String trailer = readLine(input, 8192);
                    if (trailer == null || trailer.isEmpty()) {
                        return output.toByteArray();
                    }
                }
            }
            total += size;
            if (total > MAX_FILE_BYTES) {
                throw new BusinessException(ErrorCode.FILE_SIZE_EXCEEDED);
            }
            output.write(readExactly(input, size));
            String ending = readLine(input, 16);
            if (ending == null || !ending.isEmpty()) {
                throw new BusinessException(ErrorCode.E_IM_02);
            }
        }
    }

    private static String readLine(InputStream input, int maxLength) throws IOException {
        StringBuilder builder = new StringBuilder();
        int value;
        while ((value = input.read()) >= 0) {
            if (value == '\n') {
                int length = builder.length();
                if (length > 0 && builder.charAt(length - 1) == '\r') {
                    builder.setLength(length - 1);
                }
                return builder.toString();
            }
            if (builder.length() >= maxLength) {
                throw new BusinessException(ErrorCode.E_IM_02);
            }
            builder.append((char) value);
        }
        return builder.isEmpty() ? null : builder.toString();
    }

    private static String stripBrackets(String host) {
        if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
            return host.substring(1, host.length() - 1);
        }
        return host;
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
        return postAction(action, body, errorCode, message,
                actionTimeout, MAX_ACTION_BODY_BYTES);
    }

    private JsonNode postAction(String action, Object body, ErrorCode errorCode,
                                String message, Duration timeout, int maxBodyBytes) {
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
                .timeout(timeout)
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
                    || response.body().length() > maxBodyBytes) {
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

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 已准备返回失败；关闭失败不影响结果。
        }
    }

    /** 已下载文件；{@code contentType} 仅作参考，安全校验仍由 knowledge 模块执行。 */
    public record DownloadedFile(String fileName, String contentType, byte[] content) {
    }
}
