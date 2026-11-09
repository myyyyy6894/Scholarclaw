package com.scholarclaw.modules.schedule.controller;

import com.scholarclaw.common.response.Result;
import com.scholarclaw.common.security.SecurityContextUtils;
import com.scholarclaw.modules.schedule.dto.ScheduleImportDtos.*;
import com.scholarclaw.modules.schedule.service.ScheduleImportService;
import com.scholarclaw.modules.schedule.service.ScheduleService;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Authenticated, owner-scoped import tasks. Source content is never cacheable. */
@RestController
@RequestMapping("/api/v1/schedule/imports")
public class ScheduleImportController {
    private final ScheduleImportService service;
    public ScheduleImportController(ScheduleImportService service) { this.service = service; }

    @PostMapping("/text")
    public ResponseEntity<Result<ImportView>> createText(@RequestBody TextImportRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                .body(Result.ok("导入任务已接收", service.createText(user(), request)));
    }
    @GetMapping("/term/{termId}")
    public ResponseEntity<Result<List<ImportView>>> recent(@PathVariable String termId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.ok(service.recent(user(), ScheduleService.parseId(termId))));
    }
    @GetMapping("/{importId}")
    public ResponseEntity<Result<ImportView>> get(@PathVariable String importId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.ok(service.get(user(), ScheduleService.parseId(importId))));
    }
    @PostMapping("/{importId}/retry")
    public ResponseEntity<Result<ImportView>> retry(@PathVariable String importId, @RequestBody VersionRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.ok("导入任务已重试", service.retry(user(), ScheduleService.parseId(importId), request)));
    }
    @DeleteMapping("/{importId}")
    public ResponseEntity<Result<ImportView>> cancel(@PathVariable String importId, @RequestBody VersionRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.ok("导入任务已取消", service.cancel(user(), ScheduleService.parseId(importId), request)));
    }
    @PostMapping("/{importId}/confirm")
    public Result<Receipt> confirm(@PathVariable String importId, @RequestBody ConfirmRequest request) {
        return Result.ok("课程已保存", service.confirm(user(), ScheduleService.parseId(importId), request));
    }
    private static long user() { return SecurityContextUtils.requireCurrentUserId(); }
}
