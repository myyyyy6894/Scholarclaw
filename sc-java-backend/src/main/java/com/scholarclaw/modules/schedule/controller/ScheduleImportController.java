package com.scholarclaw.modules.schedule.controller;

import com.scholarclaw.common.response.Result;
import com.scholarclaw.common.security.SecurityContextUtils;
import com.scholarclaw.modules.schedule.dto.ScheduleImportDtos.*;
import com.scholarclaw.modules.schedule.service.ScheduleImportService;
import com.scholarclaw.modules.schedule.service.ScheduleFileImportService;
import com.scholarclaw.modules.schedule.service.ScheduleImageImportService;
import com.scholarclaw.modules.schedule.service.ScheduleImageModelResolver;
import com.scholarclaw.modules.schedule.service.ScheduleService;
import java.util.List;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Authenticated, owner-scoped import tasks. Source content is never cacheable. */
@RestController
@RequestMapping("/api/v1/schedule/imports")
public class ScheduleImportController {
    private final ScheduleImportService service;
    private final ScheduleImageImportService images;
    private final ScheduleFileImportService files;
    public ScheduleImportController(ScheduleImportService service, ScheduleImageImportService images,
                                    ScheduleFileImportService files) {
        this.service = service; this.images = images; this.files = files;
    }

    @PostMapping(value = "/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Result<ImportView>> createFile(@RequestParam("term_id") String termId,
            @RequestParam("client_request_id") String requestId, @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(Result.ok(files.create(user(), termId, requestId, file)));
    }
    @PostMapping(value = "/{importId}/file/layout", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Result<ImportView>> fileLayout(@PathVariable String importId,
            @RequestParam("version") long version, @RequestPart("layout") ScheduleFileImportService.Layout layout,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.ok(files.remap(user(), ScheduleService.parseId(importId), version, layout, file)));
    }
    @GetMapping("/file/model")
    public ResponseEntity<Result<ScheduleImageModelResolver.ModelSelection>> fileModel() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.ok(files.model(user())));
    }
    @PostMapping("/{importId}/file/assist")
    public ResponseEntity<Result<ImportView>> fileAssist(@PathVariable String importId,
                                                         @RequestBody ImageRetryRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.ok(files.assist(user(), ScheduleService.parseId(importId), request)));
    }
    @PostMapping("/{importId}/preview")
    public ResponseEntity<Result<ConflictPreview>> preview(@PathVariable String importId, @RequestBody PreviewRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.ok(service.preview(user(), ScheduleService.parseId(importId), request)));
    }

    @PostMapping("/text")
    public ResponseEntity<Result<ImportView>> createText(@RequestBody TextImportRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                .body(Result.ok("导入任务已接收", service.createText(user(), request)));
    }
    @GetMapping("/image/model")
    public ResponseEntity<Result<ScheduleImageModelResolver.ModelSelection>> imageModel() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.ok(images.model(user())));
    }
    @PostMapping(value = "/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Result<ImportView>> createImage(@RequestParam("term_id") String termId,
            @RequestParam("client_request_id") String requestId, @RequestParam("consent_token") String consentToken,
            @RequestParam("confirm_external_transfer") boolean confirmed, @RequestPart("image") MultipartFile image) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                .body(Result.ok("导入任务已接收", images.create(user(), termId, requestId, consentToken, confirmed, image)));
    }
    @GetMapping("/{importId}/image")
    public ResponseEntity<InputStreamResource> image(@PathVariable String importId) {
        var preview = images.preview(user(), ScheduleService.parseId(importId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(preview.mediaType())).body(new InputStreamResource(preview.stream()));
    }
    @PostMapping("/{importId}/image/retry")
    public ResponseEntity<Result<ImportView>> retryImage(@PathVariable String importId,
                                                         @RequestBody ImageRetryRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.ok("导入任务已重试",
                images.retry(user(), ScheduleService.parseId(importId), request)));
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
