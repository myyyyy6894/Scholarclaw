package com.scholarclaw.modules.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scholarclaw.modules.agent.dto.AgentCommand;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TASK-706 第一级确定性斜杠指令解析。 */
class SlashCommandRouterTest {

    private final SlashCommandRouter router = new SlashCommandRouter();

    @Test
    @DisplayName("六个受支持指令确定性解析，零 LLM")
    void parsesSupportedCommands() {
        AgentCommand ddl = router.parse("/ddl 本周五下午五点前交实验报告").orElseThrow();
        assertThat(ddl.kind()).isEqualTo(AgentCommand.Kind.DDL);
        assertThat(ddl.argument()).isEqualTo("本周五下午五点前交实验报告");

        assertThat(router.parse("/kb 什么是两阶段锁").orElseThrow().kind())
                .isEqualTo(AgentCommand.Kind.KB);
        assertThat(router.parse("/list").orElseThrow().kind())
                .isEqualTo(AgentCommand.Kind.LIST);
        assertThat(router.parse("/list PENDING_INBOX").orElseThrow().argument())
                .isEqualTo("PENDING_INBOX");
        assertThat(router.parse("/done T102").orElseThrow().taskId()).isEqualTo(102L);
        assertThat(router.parse("/done #T103").orElseThrow().taskId()).isEqualTo(103L);
        assertThat(router.parse("/DONE t104").orElseThrow().taskId()).isEqualTo(104L);

        AgentCommand confirm = router.parse("/confirm T104 2026-10-25 22:00").orElseThrow();
        assertThat(confirm.kind()).isEqualTo(AgentCommand.Kind.CONFIRM);
        assertThat(confirm.taskId()).isEqualTo(104L);
        assertThat(confirm.dueDate()).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(confirm.dueTime()).isEqualTo(LocalTime.of(22, 0));

        // `/confirm T923`：只给编号，沿用任务已有截止时间。
        AgentCommand confirmOnlyId = router.parse("/confirm T923").orElseThrow();
        assertThat(confirmOnlyId.kind()).isEqualTo(AgentCommand.Kind.CONFIRM);
        assertThat(confirmOnlyId.taskIds()).containsExactly(923L);
        assertThat(confirmOnlyId.dueDate()).isNull();
        assertThat(confirmOnlyId.dueTime()).isNull();

        AgentCommand edit = router.parse("/edit T105 2026-10-26 08:30:15").orElseThrow();
        assertThat(edit.kind()).isEqualTo(AgentCommand.Kind.EDIT);
        assertThat(edit.dueTime()).isEqualTo(LocalTime.of(8, 30, 15));
    }

    @Test
    @DisplayName("不受支持的斜杠文本与非斜杠文本交给自然语言分类")
    void ignoresUnknownCommands() {
        assertThat(router.parse("/unknown 文本")).isEmpty();
        assertThat(router.parse("请帮我完成 T102")).isEmpty();
        assertThat(router.parse("")).isEmpty();
    }

    @Test
    @DisplayName("多任务完整解析：不允许静默只处理第一个")
    void parsesAllTaskIds() {
        AgentCommand done = router.parse("/done T95 T96").orElseThrow();
        assertThat(done.taskIds()).containsExactly(95L, 96L);

        AgentCommand deduped = router.parse("/done T95 T95").orElseThrow();
        assertThat(deduped.taskIds()).containsExactly(95L);

        AgentCommand edit = router.parse("/edit T85 2026-12-01 22:00 T86").orElseThrow();
        assertThat(edit.taskIds()).containsExactly(85L, 86L);
        assertThat(edit.dueDate()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(edit.dueTime()).isEqualTo(LocalTime.of(22, 0));

        AgentCommand confirm = router.parse("/confirm T85 T86 2026-12-01 22:00").orElseThrow();
        assertThat(confirm.taskIds()).containsExactly(85L, 86L);
    }

    @Test
    @DisplayName("日期/时刻之后的未知参数与多组日期必须报错，不能静默忽略")
    void rejectsTrailingUnknownArguments() {
        assertThatThrownBy(() -> router.parse("/done T95 T96 extra"))
                .isInstanceOf(AgentCommandException.class);
        assertThatThrownBy(() -> router.parse("/edit T85 2026-12-01 22:00 T86 bug"))
                .isInstanceOf(AgentCommandException.class);
        assertThatThrownBy(() -> router.parse("/edit T85 2026-12-01 22:00 2026-12-02 23:00"))
                .isInstanceOf(AgentCommandException.class);
        assertThatThrownBy(() -> router.parse("/done T95 2026-12-01 22:00"))
                .isInstanceOf(AgentCommandException.class);
    }

    @Test
    @DisplayName("已知指令参数非法：抛出可读错误，不猜测执行")
    void rejectsMalformedKnownCommands() {
        assertThatThrownBy(() -> router.parse("/ddl 短"))
                .isInstanceOf(AgentCommandException.class)
                .hasMessageContaining("至少 4 个字符");
        assertThatThrownBy(() -> router.parse("/done"))
                .isInstanceOf(AgentCommandException.class);
        assertThatThrownBy(() -> router.parse("/done TABC"))
                .isInstanceOf(AgentCommandException.class);
        assertThatThrownBy(() -> router.parse("/confirm T923 T924"))
                .isInstanceOf(AgentCommandException.class)
                .hasMessageContaining("一次只能确认一个");
        assertThatThrownBy(() -> router.parse("/confirm T104 2026-10-25"))
                .isInstanceOf(AgentCommandException.class);
        assertThatThrownBy(() -> router.parse("/confirm T104 2026/10/25 22:00"))
                .isInstanceOf(AgentCommandException.class);
        assertThatThrownBy(() -> router.parse("/confirm T104 2026-10-25 25:00"))
                .isInstanceOf(AgentCommandException.class);
    }
}
