package com.scholarclaw.modules.planning.controller;

import com.scholarclaw.common.response.Result;
import com.scholarclaw.common.security.SecurityContextUtils;
import com.scholarclaw.modules.planning.dto.PlanningInputDtos.*;
import com.scholarclaw.modules.planning.service.PlanningInputService;
import com.scholarclaw.modules.schedule.service.ScheduleService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** owner 来自 JWT；计划资料不修改 DDL 截止日期。 */
@RestController
@RequestMapping("/api/v1/planning")
public class PlanningInputController {
    private final PlanningInputService service;
    public PlanningInputController(PlanningInputService service) { this.service = service; }
    @GetMapping("/preferences")
    public Result<PreferenceView> preference() { return Result.ok(service.preference(user())); }
    @PutMapping("/preferences")
    public Result<PreferenceView> savePreference(@RequestBody PreferenceWrite request) {
        return Result.ok("作息已保存", service.savePreference(user(), request));
    }
    @GetMapping("/events")
    public Result<List<EventView>> events() { return Result.ok(service.events(user())); }
    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    public Result<EventView> createEvent(@RequestBody EventWrite request) {
        return Result.ok("日程已添加", service.createEvent(user(), request));
    }
    @PutMapping("/events/{eventId}")
    public Result<EventView> updateEvent(@PathVariable String eventId, @RequestBody EventWrite request) {
        return Result.ok("日程已更新", service.updateEvent(user(), id(eventId), request));
    }
    @DeleteMapping("/events/{eventId}")
    public Result<Void> deleteEvent(@PathVariable String eventId, @RequestParam Long version) {
        service.deleteEvent(user(), id(eventId), version); return Result.ok("日程已删除", null);
    }
    @GetMapping("/tasks")
    public Result<List<ProfileView>> profiles() { return Result.ok(service.profiles(user())); }
    @PutMapping("/tasks/{taskId}")
    public Result<ProfileView> saveProfile(@PathVariable String taskId, @RequestBody ProfileWrite request) {
        return Result.ok("工时资料已保存", service.saveProfile(user(), id(taskId), request));
    }
    @DeleteMapping("/tasks/{taskId}")
    public Result<Void> deleteProfile(@PathVariable String taskId, @RequestParam Long version) {
        service.deleteProfile(user(), id(taskId), version); return Result.ok("工时资料已删除", null);
    }
    private static long user() { return SecurityContextUtils.requireCurrentUserId(); }
    private static long id(String value) { return ScheduleService.parseId(value); }
}
