package com.scholarclaw.common.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TASK-803 OneBot Action API：鉴权头、私聊回复、文件 base64/URL 与单层转发。 */
class OneBotApiClientTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("send_private_msg：携带 Bearer 令牌、user_id 与 message")
    void sendsPrivateMessage() {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        context("/send_private_msg", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(read(exchange));
            respond(exchange, 200, "{\"status\":\"ok\",\"retcode\":0,\"data\":{}}");
        });

        client("token-1", 5000).sendPrivateMsg(24681012L, "提醒内容");

        assertThat(authorization.get()).isEqualTo("Bearer token-1");
        assertThat(body.get()).contains("\"user_id\":24681012")
                .contains("\"message\":\"提醒内容\"");
    }

    @Test
    @DisplayName("Action 返回 retcode != 0：抛出脱敏业务异常")
    void rejectsFailedAction() {
        context("/send_private_msg", exchange -> {
            read(exchange);
            respond(exchange, 200, "{\"status\":\"failed\",\"retcode\":1400}");
        });

        assertThatThrownBy(() -> client("token-1", 5000)
                .sendPrivateMsg(24681012L, "提醒内容"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    @Test
    @DisplayName("get_forward_msg：拼接单层纯文本节点")
    void readsForwardText() {
        context("/get_forward_msg", exchange -> {
            read(exchange);
            respond(exchange, 200, """
                    {"status":"ok","retcode":0,"data":{"messages":[
                      {"message":[{"type":"text","data":{"text":"第一条"}}]},
                      {"message":[{"type":"text","data":{"text":"第二条"}}],"raw_message":"第二条"}
                    ]}}
                    """);
        });

        assertThat(client("token-1", 5000).getForwardText("forward-1"))
                .isEqualTo("第一条\n第二条");
    }

    @Test
    @DisplayName("合并转发含嵌套 forward：明确 E-IM-03，不递归解析")
    void rejectsNestedForward() {
        context("/get_forward_msg", exchange -> {
            read(exchange);
            respond(exchange, 200, """
                    {"status":"ok","retcode":0,"data":{"messages":[
                      {"message":[{"type":"forward","data":{"id":"nested-1"}}]}
                    ]}}
                    """);
        });

        assertThatThrownBy(() -> client("token-1", 5000).getForwardText("forward-1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.E_IM_03));
    }

    @Test
    @DisplayName("get_file：解码 base64:// 文件内容")
    void fetchesBase64File() {
        context("/get_file", exchange -> {
            read(exchange);
            respond(exchange, 200, """
                    {"status":"ok","retcode":0,"data":{
                      "file_name":"a.pdf","file_size":5,"file":"base64://SGVsbG8="}}
                    """);
        });

        OneBotApiClient.DownloadedFile file = client("token-1", 5000)
                .fetchFile("file-1", "fallback.pdf");

        assertThat(file.fileName()).isEqualTo("a.pdf");
        assertThat(new String(file.content(), StandardCharsets.UTF_8)).isEqualTo("Hello");
    }

    @Test
    @DisplayName("get_file：下载 HTTP 临时地址，并校验大小")
    void fetchesHttpFile() {
        context("/download", exchange -> respond(exchange, 200, "Hello"));
        context("/get_file", exchange -> {
            read(exchange);
            respond(exchange, 200, """
                    {"status":"ok","retcode":0,"data":{
                      "file_name":"a.pdf","file_size":5,
                      "file":"%s/download"}}
                    """.formatted(baseUrl));
        });

        OneBotApiClient.DownloadedFile file = client("token-1", 5000)
                .fetchFile("file-1", "fallback.pdf");

        assertThat(new String(file.content(), StandardCharsets.UTF_8)).isEqualTo("Hello");
    }

    @Test
    @DisplayName("get_file 返回协议端本机路径：拒绝读取并返回 E-IM-02")
    void rejectsLocalPath() {
        context("/get_file", exchange -> {
            read(exchange);
            respond(exchange, 200, """
                    {"status":"ok","retcode":0,"data":{
                      "file_name":"a.pdf","file_size":5,"file":"/app/data/a.pdf"}}
                    """);
        });

        assertThatThrownBy(() -> client("token-1", 5000).fetchFile("file-1", "a.pdf"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.E_IM_02));
    }

    @Test
    @DisplayName("未配置 base-url：所有 Action 失败关闭")
    void unconfiguredClientFailsClosed() {
        OneBotApiClient client = new OneBotApiClient("", "token-1", 5000, new ObjectMapper());

        assertThat(client.isConfigured()).isFalse();
        assertThatThrownBy(() -> client.sendPrivateMsg(1L, "x"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> client.getForwardText("f"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> client.fetchFile("f", "a.pdf"))
                .isInstanceOf(BusinessException.class);
    }

    private OneBotApiClient client(String token, long timeoutMs) {
        return new OneBotApiClient(baseUrl, token, timeoutMs, new ObjectMapper());
    }

    private void context(String path, Handler handler) {
        server.createContext(path, exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
    }

    private static String read(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
