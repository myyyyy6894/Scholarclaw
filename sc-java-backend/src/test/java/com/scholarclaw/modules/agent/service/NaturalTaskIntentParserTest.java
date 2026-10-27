package com.scholarclaw.modules.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.scholarclaw.modules.agent.dto.AgentToolAction;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TASK-706 自然语言任务动作的安全解析。 */
class NaturalTaskIntentParserTest {

    private final NaturalTaskIntentParser parser = new NaturalTaskIntentParser();

    @Test
    @DisplayName("查询/疑问/否定语义一律归为只读查询")
    void querySignalsAreReadOnly() {
        assertThat(parser.parse("查询 T36").action()).isEqualTo(AgentToolAction.TASK_QUERY);
        assertThat(parser.parse("T102 完成了吗").action()).isEqualTo(AgentToolAction.TASK_QUERY);
        assertThat(parser.parse("T37 没有完成").action()).isEqualTo(AgentToolAction.TASK_QUERY);
        assertThat(parser.parse("T37 尚未完成").action()).isEqualTo(AgentToolAction.TASK_QUERY);
        assertThat(parser.parse("T37 完成情况怎么样？").action())
                .isEqualTo(AgentToolAction.TASK_QUERY);
        assertThat(parser.parse("看看 T36 的详情").action()).isEqualTo(AgentToolAction.TASK_QUERY);
    }

    @Test
    @DisplayName("否定词与动作词同分句：不完成/不用删除/无需完成/不必完成都不写库")
    void negatedActionsAreReadOnly() {
        assertThat(parser.parse("不完成 T87").action()).isEqualTo(AgentToolAction.TASK_QUERY);
        assertThat(parser.parse("不要完成 T87").action()).isEqualTo(AgentToolAction.TASK_QUERY);
        assertThat(parser.parse("不用删除 T37").action()).isEqualTo(AgentToolAction.TASK_QUERY);
        assertThat(parser.parse("不必删除 T37").action()).isEqualTo(AgentToolAction.TASK_QUERY);
        assertThat(parser.parse("无需完成 T37").action()).isEqualTo(AgentToolAction.TASK_QUERY);
        assertThat(parser.parse("不想完成 T37").action()).isEqualTo(AgentToolAction.TASK_QUERY);
        assertThat(parser.parse("还没完成 T37").action()).isEqualTo(AgentToolAction.TASK_QUERY);
        assertThat(parser.parse("尚未确认 T37").action()).isEqualTo(AgentToolAction.TASK_QUERY);
        assertThat(parser.parse("无需完成").action()).isEqualTo(AgentToolAction.CLARIFY);
        assertThat(parser.parse("不用删除").action()).isEqualTo(AgentToolAction.CLARIFY);
    }

    @Test
    @DisplayName("不同分句的否定不影响本分句的写操作？保守处理：任一分句否定即整体只读")
    void negationInAnyClauseMakesWholeCommandReadOnly() {
        assertThat(parser.parse("完成 T36，不删除 T37").action())
                .isEqualTo(AgentToolAction.TASK_QUERY);
        assertThat(parser.parse("删除 T37，不要完成 T36").action())
                .isEqualTo(AgentToolAction.TASK_QUERY);
    }

    @Test
    @DisplayName("混合写动作必须澄清，不得按固定优先级任选一个动作")
    void mixedWriteActionsAreClarified() {
        assertThat(parser.parse("完成 T36，删除 T37").action())
                .isEqualTo(AgentToolAction.CLARIFY);
        assertThat(parser.parse("完成 T36，修改 T37").action())
                .isEqualTo(AgentToolAction.CLARIFY);
        assertThat(parser.parse("删除 T37，完成 T36").action())
                .isEqualTo(AgentToolAction.CLARIFY);
        assertThat(parser.parse("完成 T36 然后删除 T37").action())
                .isEqualTo(AgentToolAction.CLARIFY);
        assertThat(parser.parse("完成 T36 T37").action())
                .isEqualTo(AgentToolAction.TASK_DONE);
    }

    @Test
    @DisplayName("非法任务编号不参与写操作；完成 T87abc / xT87 / T87.5 必须澄清")
    void malformedTaskCodesAreRejected() {
        assertThat(parser.parse("完成 T87abc").taskIds()).isEmpty();
        assertThat(parser.parse("完成 T87abc").action()).isEqualTo(AgentToolAction.CLARIFY);
        assertThat(parser.parse("完成 xT87").taskIds()).isEmpty();
        assertThat(parser.parse("完成 T87.5").taskIds()).isEmpty();
        assertThat(parser.parse("完成 #T87").taskIds()).containsExactly(87L);
    }

    @Test
    @DisplayName("吗/呢/问号统一识别为只读查询")
    void questionMarkersAreReadOnly() {
        assertThat(parser.parse("完成 T36 吗").action()).isEqualTo(AgentToolAction.TASK_QUERY);
        assertThat(parser.parse("完成 T36 吗").taskIds()).containsExactly(36L);
        assertThat(parser.parse("删除 T37 呢").action()).isEqualTo(AgentToolAction.TASK_QUERY);
        assertThat(parser.parse("完成 T36?").action()).isEqualTo(AgentToolAction.TASK_QUERY);
        assertThat(parser.parse("完成 T36？").action()).isEqualTo(AgentToolAction.TASK_QUERY);
    }

    @Test
    @DisplayName("同分句没有否定词时仍正常识别写操作")
    void affirmativeActionsStillWork() {
        assertThat(parser.parse("完成 T36").action()).isEqualTo(AgentToolAction.TASK_DONE);
        assertThat(parser.parse("删除 T37").action()).isEqualTo(AgentToolAction.TASK_CANCEL);
        assertThat(parser.parse("特别提醒：完成 T36").action())
                .isEqualTo(AgentToolAction.TASK_DONE);
    }

    @Test
    @DisplayName("取消/删除进入待确认；提交语义与完成区分")
    void cancelAndComplete() {
        assertThat(parser.parse("取消 T37").action()).isEqualTo(AgentToolAction.TASK_CANCEL);
        assertThat(parser.parse("删除 T37").action()).isEqualTo(AgentToolAction.TASK_CANCEL);
        assertThat(parser.parse("完成 T36").action()).isEqualTo(AgentToolAction.TASK_DONE);
        assertThat(parser.parse("把 T36 标记为已交").action())
                .isEqualTo(AgentToolAction.TASK_DONE);
    }

    @Test
    @DisplayName("“取消完成”不能被误判为取消任务")
    void uncompleteIsNotCancel() {
        assertThat(parser.parse("取消完成 T102").action()).isEqualTo(AgentToolAction.CLARIFY);
    }

    @Test
    @DisplayName("提取全部任务编号，保持顺序并去重")
    void extractsAllTaskIds() {
        assertThat(parser.parse("完成 T36 T37").taskIds()).containsExactly(36L, 37L);
        assertThat(parser.parse("完成 T36 #T36 T102").taskIds()).containsExactly(36L, 102L);
        assertThat(parser.parse("列出任务").taskIds()).isEmpty();
    }

    @Test
    @DisplayName("修改/确认需要 ISO 日期与时刻")
    void updateParsesDateTime() {
        var command = parser.parse("把 T102 改到 2026-10-25 22:00");
        assertThat(command.action()).isEqualTo(AgentToolAction.TASK_UPDATE);
        assertThat(command.taskIds()).containsExactly(102L);
        assertThat(command.dueDate()).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(command.dueTime()).isEqualTo(LocalTime.of(22, 0));
    }

    @Test
    @DisplayName("列表语义与无法识别的文本")
    void listAndUnknown() {
        assertThat(parser.parse("列出所有任务").action()).isEqualTo(AgentToolAction.TASK_LIST);
        assertThat(parser.parse("帮我处理一下").action()).isEqualTo(AgentToolAction.CLARIFY);
    }

    @Test
    @DisplayName("非法编号与合法编号混合：整条命令澄清，绝不写任何合法编号")
    void invalidTokenBlocksWholeCommand() {
        for (String text : new String[]{
                "完成 T87abc，完成 T36",
                "完成 T87.5，完成 T36",
                "完成 xT87，完成 T36",
                "完成 T36，T87abc",
                "完成 T36 和 T87_foo",
                "完成 T36，T99999999999999999999"}) {
            var command = parser.parse(text);
            assertThat(command.action())
                    .as(text)
                    .isEqualTo(AgentToolAction.CLARIFY);
        }
    }

    @Test
    @DisplayName("编号边界：T87.5 / xT87 / T87abc / 溢出编号都不是有效任务编号")
    void taskCodeBoundaries() {
        assertThat(parser.parse("完成 T87.5").taskIds()).isEmpty();
        assertThat(parser.parse("完成 xT87").taskIds()).isEmpty();
        assertThat(parser.parse("完成 T87abc").taskIds()).isEmpty();
        assertThat(parser.parse("完成 T99999999999999999999").taskIds()).isEmpty();
        assertThat(parser.parse("完成 T0000").taskIds()).isEmpty();
        assertThat(parser.parse("完成 #T87").taskIds()).containsExactly(87L);
    }

    @Test
    @DisplayName("分句混合动作：写动作与查询/其他写动作出现在不同分句也必须澄清")
    void mixedActionsAcrossClausesAreClarified() {
        assertThat(parser.parse("完成 T36，删除 T37").action())
                .isEqualTo(AgentToolAction.CLARIFY);
        assertThat(parser.parse("查询 T36，完成 T37").action())
                .isEqualTo(AgentToolAction.CLARIFY);
        assertThat(parser.parse("完成 T36 吗，删除 T37").action())
                .isEqualTo(AgentToolAction.CLARIFY);
        assertThat(parser.parse("完成 T36，T37").action())
                .isEqualTo(AgentToolAction.CLARIFY);
    }

    @Test
    @DisplayName("合法多任务批量操作：同一分句内全部编号绑定到该写动作")
    void validBatchWriteBindsAllIds() {
        var done = parser.parse("完成 T36 T37");
        assertThat(done.action()).isEqualTo(AgentToolAction.TASK_DONE);
        assertThat(done.taskIds()).containsExactly(36L, 37L);

        var cancel = parser.parse("删除 T36、T37");
        assertThat(cancel.action()).isEqualTo(AgentToolAction.TASK_CANCEL);
        assertThat(cancel.taskIds()).containsExactly(36L, 37L);
    }

    @Test
    @DisplayName("问句 + 写动作：只读优先，绝不执行写操作")
    void questionWithWriteIsReadOnlyOrClarify() {
        assertThat(parser.parse("完成 T36 吗").action()).isEqualTo(AgentToolAction.TASK_QUERY);
        assertThat(parser.parse("完成 T36 吗，删除 T37").action())
                .isEqualTo(AgentToolAction.CLARIFY);
    }
}
