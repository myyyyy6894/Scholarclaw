package com.scholarclaw.modules.schedule.controller;

import com.scholarclaw.common.response.Result;
import com.scholarclaw.common.security.SecurityContextUtils;
import com.scholarclaw.modules.schedule.dto.ScheduleDtos.*;
import com.scholarclaw.modules.schedule.service.ScheduleService;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 课表资源始终按 JWT 当前用户隔离，不接受请求体中的 user_id。 */
@RestController
@RequestMapping("/api/v1/schedule")
public class ScheduleController {
    private final ScheduleService service;
    public ScheduleController(ScheduleService service) { this.service = service; }

    @GetMapping("/terms")
    public Result<List<TermView>> terms() { return Result.ok(service.terms(user())); }

    @PostMapping("/terms")
    @ResponseStatus(HttpStatus.CREATED)
    public Result<TermView> createTerm(@RequestBody TermWrite request) {
        return Result.ok("学期已创建", service.createTerm(user(), request));
    }

    @PutMapping("/terms/{termId}")
    public Result<TermView> updateTerm(@PathVariable String termId, @RequestBody TermWrite request) {
        return Result.ok("学期已更新", service.updateTerm(user(), id(termId), request));
    }

    @GetMapping("/terms/{termId}/periods")
    public Result<List<Period>> periods(@PathVariable String termId) {
        return Result.ok(service.periods(user(), id(termId)));
    }

    @PostMapping("/terms/{termId}/periods/preview")
    public Result<PeriodPreview> previewPeriods(@PathVariable String termId, @RequestBody PeriodsWrite request) {
        return Result.ok(service.previewPeriods(user(), id(termId), request));
    }

    @PutMapping("/terms/{termId}/periods")
    public Result<PeriodPreview> savePeriods(@PathVariable String termId, @RequestBody PeriodsWrite request) {
        return Result.ok("节次已保存", service.savePeriods(user(), id(termId), request));
    }

    @GetMapping("/terms/{termId}/rules")
    public Result<List<RuleView>> rules(@PathVariable String termId) {
        return Result.ok(service.rules(user(), id(termId)));
    }

    @PostMapping("/rules")
    @ResponseStatus(HttpStatus.CREATED)
    public Result<RuleView> createRule(@RequestBody RuleWrite request) {
        return Result.ok("课程已添加", service.createRule(user(), request));
    }

    @PutMapping("/rules/{ruleId}")
    public Result<RuleView> updateRule(@PathVariable String ruleId, @RequestBody RuleWrite request) {
        return Result.ok("课程已更新", service.updateRule(user(), id(ruleId), request));
    }

    @DeleteMapping("/rules/{ruleId}")
    public Result<Void> deleteRule(@PathVariable String ruleId, @RequestParam Long version) {
        service.deleteRule(user(), id(ruleId), version);
        return Result.ok("课程已删除", null);
    }

    @GetMapping("/terms/{termId}/exams")
    public Result<List<ExamView>> exams(@PathVariable String termId) {
        return Result.ok(service.exams(user(), id(termId)));
    }

    @PutMapping("/rules/{ruleId}/exam")
    public Result<ExamView> saveExam(@PathVariable String ruleId, @RequestBody ExamWrite request) {
        return Result.ok(service.saveExam(user(), id(ruleId), request));
    }

    @DeleteMapping("/rules/{ruleId}/exam")
    public Result<Void> deleteExam(@PathVariable String ruleId, @RequestParam Long version) {
        service.deleteExam(user(), id(ruleId), version);
        return Result.ok(null);
    }

    @GetMapping("/rules/{ruleId}/exceptions")
    public Result<List<ExceptionView>> exceptions(@PathVariable String ruleId) {
        return Result.ok(service.exceptions(user(), id(ruleId)));
    }

    @PutMapping("/rules/{ruleId}/exceptions/{originalDate}")
    public Result<ExceptionView> saveException(@PathVariable String ruleId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate originalDate,
            @RequestBody ExceptionWrite request) {
        return Result.ok("停调课已保存", service.saveException(user(), id(ruleId), originalDate, request));
    }

    @DeleteMapping("/rules/{ruleId}/exceptions/{originalDate}")
    public Result<Void> deleteException(@PathVariable String ruleId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate originalDate,
            @RequestParam Long version) {
        service.deleteException(user(), id(ruleId), originalDate, version);
        return Result.ok("停调课已撤销", null);
    }

    @GetMapping("/occurrences")
    public Result<OccurrencesView> occurrences(@RequestParam("term_id") String termId,
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return Result.ok(service.occurrences(user(), id(termId), from, to));
    }
    private static long user() { return SecurityContextUtils.requireCurrentUserId(); }
    private static long id(String value) { return ScheduleService.parseId(value); }
}
