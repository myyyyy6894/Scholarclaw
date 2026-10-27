package com.scholarclaw.modules.ddl.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.time.OffsetDateTime;

/**
 * 重复通知待确认变更草案（{@code 04} `TaskChangeDraftVO`）。
 *
 * <p>TASK-703 只定义响应结构并返回空列表；草案的生成、读取与决策属 TASK-704。
 */
public record TaskChangeDraftVO(
        @JsonSerialize(using = ToStringSerializer.class) Long targetTaskId,
        String newTaskTitle,
        String newTimePrecision,
        String newDueDate,
        String newDueTime,
        OffsetDateTime newDueAt,
        boolean newTimeInferred,
        String newTimeAmbiguityReason,
        String newDeliverableFormat,
        String newSubmissionChannel,
        String rawSourceText,
        String rawTimeText,
        /** 草案新截止时间所属的用户时区快照；前端不得在缺失时静默使用浏览器时区。 */
        String userTimezone,
        OffsetDateTime expiresAt) {
}
