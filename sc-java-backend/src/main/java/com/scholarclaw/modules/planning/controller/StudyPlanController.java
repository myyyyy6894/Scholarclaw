package com.scholarclaw.modules.planning.controller;

import com.scholarclaw.common.response.Result;
import com.scholarclaw.common.security.SecurityContextUtils;
import com.scholarclaw.modules.planning.dto.StudyPlanDtos.*;
import com.scholarclaw.modules.planning.service.StudyPlanService;
import com.scholarclaw.modules.schedule.service.ScheduleService;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** 计划只在用户明确采纳后生成已安排时间块；所有 owner 均来自 JWT。 */
@RestController
@RequestMapping("/api/v1/planning/plans")
public class StudyPlanController {
    private final StudyPlanService service;
    public StudyPlanController(StudyPlanService service) { this.service = service; }
    @PostMapping
    public Result<PlanView> create(@RequestBody CreatePlan request) {
        return Result.ok("计划建议已生成", service.create(user(), request));
    }
    @GetMapping
    public Result<List<PlanSummary>> list() { return Result.ok(service.list(user())); }
    @GetMapping("/{planId}")
    public Result<PlanView> get(@PathVariable String planId) { return Result.ok(service.get(user(), id(planId))); }
    @PostMapping("/{planId}/accept")
    public Result<PlanView> accept(@PathVariable String planId, @RequestBody AcceptPlan request) {
        return Result.ok("已采纳所选时间块", service.accept(user(), id(planId), request));
    }
    @DeleteMapping("/{planId}")
    public Result<Void> cancel(@PathVariable String planId, @RequestParam Long revision) {
        service.cancelDraft(user(), id(planId), revision); return Result.ok("计划草稿已取消", null);
    }
    private static long user() { return SecurityContextUtils.requireCurrentUserId(); }
    private static long id(String value) { return ScheduleService.parseId(value); }
}
