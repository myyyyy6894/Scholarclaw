package com.scholarclaw.modules.planning.dto;

import com.scholarclaw.modules.planning.service.PlanningEngine;
import com.scholarclaw.modules.planning.service.PlanningSnapshotService;
import java.time.LocalDate;
import java.util.List;

/** 公开业务 ID 为字符串，绝对时间均包含明确偏移。 */
public final class StudyPlanDtos {
    private StudyPlanDtos() { }
    public record CreatePlan(LocalDate fromDate, LocalDate toDate, String clientRequestId, Boolean confirmNoActiveTerm) { }
    public record AcceptPlan(Long revision, String clientRequestId, List<String> blockIds) { }
    public record BlockView(String blockId, String taskId, String title, String startAt, String endAt,
                            String state, boolean locked, boolean overdue, long version) { }
    public record PlanSummary(String planId, LocalDate fromDate, LocalDate toDate, String timezone, String state,
                              long revision, String createdAt) { }
    public record PlanView(String planId, LocalDate fromDate, LocalDate toDate, String timezone, String state,
                           boolean stale, long revision, List<String> warnings,
                           List<PlanningEngine.Shortage> shortages,
                           List<PlanningSnapshotService.UnavailableTask> unavailableTasks,
                           List<BlockView> blocks) { }
}
