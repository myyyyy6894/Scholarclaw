package com.scholarclaw.modules.channel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.modules.channel.dto.OneBotEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TASK-803 OneBot 事件解析：私聊/群 @/文件/单层转发/忽略规则。 */
class OneBotEventParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OneBotEventParser parser = new OneBotEventParser();

    @Test
    @DisplayName("私聊文本：提取消息 ID、发送者与文本")
    void parsesPrivateText() throws Exception {
        OneBotEvent event = parse("""
                {"post_type":"message","message_type":"private","message_id":188992211,
                 "user_id":24681012,"time":1760509200,
                 "message":[{"type":"text","data":{"text":"数据库第3次作业 10月25日交"}}]}
                """);

        assertThat(event.ignored()).isFalse();
        assertThat(event.groupMessage()).isFalse();
        assertThat(event.messageId()).isEqualTo("188992211");
        assertThat(event.senderId()).isEqualTo("24681012");
        assertThat(event.text()).isEqualTo("数据库第3次作业 10月25日交");
        assertThat(event.eventTime()).isEqualTo(1760509200L);
    }

    @Test
    @DisplayName("群聊 @ 机器人：拼接其余文本")
    void parsesGroupMessageAddressedToBot() throws Exception {
        OneBotEvent event = parse("""
                {"post_type":"message","message_type":"group","message_id":11,
                 "user_id":24681012,"self_id":987654321,"time":1760509200,
                 "message":[
                   {"type":"at","data":{"qq":"987654321"}},
                   {"type":"text","data":{"text":" /list "}},
                   {"type":"at","data":{"qq":"111"}}
                 ]}
                """);

        assertThat(event.ignored()).isFalse();
        assertThat(event.groupMessage()).isTrue();
        assertThat(event.text()).isEqualTo("/list");
    }

    @Test
    @DisplayName("群聊未 @ 机器人：合法但忽略，不进入业务")
    void ignoresGroupMessageWithoutAtSelf() throws Exception {
        OneBotEvent event = parse("""
                {"post_type":"message","message_type":"group","message_id":12,
                 "user_id":24681012,"self_id":987654321,
                 "message":[{"type":"text","data":{"text":"大家好"}}]}
                """);

        assertThat(event.ignored()).isTrue();
        assertThat(event.ignoredReason()).isEqualTo("group_not_at_bot");
    }

    @Test
    @DisplayName("私聊文件消息段：提取文件引用、名称与大小")
    void parsesPrivateFileSegment() throws Exception {
        OneBotEvent event = parse("""
                {"post_type":"message","message_type":"private","message_id":13,
                 "user_id":24681012,"message":[
                   {"type":"file","data":{"file_id":"file-1","name":"第5章_事务处理.pdf",
                                          "file_size":12345}}
                 ]}
                """);

        assertThat(event.ignored()).isFalse();
        assertThat(event.hasFile()).isTrue();
        assertThat(event.fileReference()).isEqualTo("file-1");
        assertThat(event.fileName()).isEqualTo("第5章_事务处理.pdf");
        assertThat(event.fileSize()).isEqualTo(12345L);
    }

    @Test
    @DisplayName("群文件上传通知：用 file.id 作为消息幂等键")
    void parsesGroupUploadNotice() throws Exception {
        OneBotEvent event = parse("""
                {"post_type":"notice","notice_type":"group_upload","user_id":24681012,
                 "group_id":888888,"time":1760509200,
                 "file":{"id":"group-file-1","name":"讲义.md","size":2048,"busid":102}}
                """);

        assertThat(event.ignored()).isFalse();
        assertThat(event.postType()).isEqualTo("notice");
        assertThat(event.noticeType()).isEqualTo("group_upload");
        assertThat(event.messageId()).isEqualTo("group-file-1");
        assertThat(event.fileReference()).isEqualTo("group-file-1");
        assertThat(event.fileName()).isEqualTo("讲义.md");
        assertThat(event.fileSize()).isEqualTo(2048L);
    }

    @Test
    @DisplayName("合并转发消息段：只提取 id，交由 Action 客户端拉取单层文本")
    void parsesForwardSegment() throws Exception {
        OneBotEvent event = parse("""
                {"post_type":"message","message_type":"private","message_id":14,
                 "user_id":24681012,"message":[
                   {"type":"forward","data":{"id":"forward-1"}}
                 ]}
                """);

        assertThat(event.hasForward()).isTrue();
        assertThat(event.forwardId()).isEqualTo("forward-1");
    }

    @Test
    @DisplayName("字符串消息与 CQ 码：检测群 @ 并剥离 CQ 码")
    void parsesStringMessageWithCqCodes() throws Exception {
        OneBotEvent event = parse("""
                {"post_type":"message","message_type":"group","message_id":15,
                 "user_id":24681012,"self_id":987654321,
                 "message":"[CQ:at,qq=987654321] [CQ:face,id=1] 本周五交作业",
                 "raw_message":"[CQ:at,qq=987654321] [CQ:face,id=1] 本周五交作业"}
                """);

        assertThat(event.ignored()).isFalse();
        assertThat(event.text()).isEqualTo("本周五交作业");
    }

    @Test
    @DisplayName("心跳事件：直接标记 meta_event")
    void parsesMetaEvent() throws Exception {
        OneBotEvent event = parse("""
                {"post_type":"meta_event","meta_event_type":"heartbeat","time":1760509200,
                 "self_id":987654321}
                """);

        assertThat(event.metaEvent()).isTrue();
        assertThat(event.ignored()).isTrue();
    }

    @Test
    @DisplayName("机器人自己的消息：忽略，避免自回复循环")
    void ignoresSelfMessage() throws Exception {
        OneBotEvent event = parse("""
                {"post_type":"message","message_type":"private","message_id":16,
                 "user_id":987654321,"self_id":987654321,
                 "message":[{"type":"text","data":{"text":"hi"}}]}
                """);

        assertThat(event.ignored()).isTrue();
        assertThat(event.ignoredReason()).isEqualTo("self_message");
    }

    @Test
    @DisplayName("缺 message_id 的消息非法")
    void rejectsMessageWithoutId() {
        assertThatThrownBy(() -> parse("""
                {"post_type":"message","message_type":"private","user_id":24681012,
                 "message":[{"type":"text","data":{"text":"hi"}}]}
                """))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("未知 notice 类型忽略")
    void ignoresUnsupportedNotice() throws Exception {
        OneBotEvent event = parse("""
                {"post_type":"notice","notice_type":"group_increase","user_id":24681012}
                """);

        assertThat(event.ignored()).isTrue();
        assertThat(event.ignoredReason()).isEqualTo("unsupported_notice_type");
    }

    private OneBotEvent parse(String json) throws Exception {
        JsonNode payload = objectMapper.readTree(json);
        return parser.parse(payload);
    }
}
