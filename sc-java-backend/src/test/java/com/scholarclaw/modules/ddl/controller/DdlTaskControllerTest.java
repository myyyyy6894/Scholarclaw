package com.scholarclaw.modules.ddl.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.config.JacksonConfig;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.exception.GlobalExceptionHandler;
import com.scholarclaw.common.security.ScUserPrincipal;
import com.scholarclaw.modules.ddl.dto.CompleteDdlTaskRequest;
import com.scholarclaw.modules.ddl.dto.CreateDdlTaskRequest;
import com.scholarclaw.modules.ddl.dto.DdlExtractOutcome;
import com.scholarclaw.modules.ddl.dto.DdlExtractVO;
import com.scholarclaw.modules.ddl.dto.DdlTaskBoardVO;
import com.scholarclaw.modules.ddl.dto.DdlTaskVO;
import com.scholarclaw.modules.ddl.dto.DeleteDdlTaskVO;
import com.scholarclaw.modules.ddl.dto.DraftDecisionRequest;
import com.scholarclaw.modules.ddl.dto.ExtractDdlTaskRequest;
import com.scholarclaw.modules.ddl.dto.TaskChangeDraftVO;
import com.scholarclaw.modules.ddl.dto.UpdateDdlTaskRequest;
import com.scholarclaw.modules.ddl.service.DdlExtractionService;
import com.scholarclaw.modules.ddl.service.DdlTaskService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** `API-DDL-002/003/004/006/007` 的控制器契约（TASK-703）。 */
class DdlTaskControllerTest {

    private static final long USER_ID = 10001L;
    private static final long TASK_ID = 103L;
    private static final ObjectMapper MAPPER = JacksonConfig
            .applyTo(new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder(),
                    ZoneId.of("Asia/Shanghai")).build();

    private DdlTaskService ddlTaskService;
    private DdlExtractionService ddlExtractionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ddlTaskService = mock(DdlTaskService.class);
        ddlExtractionService = mock(DdlExtractionService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new DdlTaskController(ddlTaskService, ddlExtractionService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(MAPPER))
                .setValidator(new LocalValidatorFactoryBean())
                .build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new ScUserPrincipal(USER_ID, "zhangsan_cs", "STUDENT", "jti",
                        Instant.now().plusSeconds(60)), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("手动建单返回 201，ID 为十进制字符串，字段为 snake_case")
    void createReturnsCreatedEnvelope() throws Exception {
        when(ddlTaskService.createManualTask(eq(USER_ID), any(CreateDdlTaskRequest.class)))
                .thenReturn(taskVO());

        mockMvc.perform(post("/api/v1/ddl/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"client_request_id":"req_manual_001","course_name":"操作系统原理",
                                 "task_title":"提交实验二报告","time_precision":"EXACT_DATETIME",
                                 "due_date":"2099-12-31","due_time":"22:00",
                                 "deliverable_format":"zip","submission_channel":"学习通"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.task_id").value("103"))
                .andExpect(jsonPath("$.data.display_code").value("#T103"))
                .andExpect(jsonPath("$.data.time_precision").value("EXACT_DATETIME"))
                .andExpect(jsonPath("$.data.is_relative_time").value(false))
                .andExpect(jsonPath("$.data.due_at").value("2099-12-31T22:00:00+08:00"))
                .andExpect(jsonPath("$.data.time_inferred").value(false))
                .andExpect(jsonPath("$.data.reminded_t24h").value(false))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        verify(ddlTaskService).createManualTask(eq(USER_ID), any(CreateDdlTaskRequest.class));
    }

    @Test
    @DisplayName("缺失 client_request_id 由 Service 映射为 IDEMPOTENCY_KEY_REQUIRED")
    void missingClientRequestIdMapsToIdempotencyError() throws Exception {
        when(ddlTaskService.createManualTask(eq(USER_ID), any(CreateDdlTaskRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED));

        mockMvc.perform(post("/api/v1/ddl/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"course_name\":\"操作系统原理\",\"task_title\":\"任务\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    @DisplayName("缺少必填 task_title → 400 VALIDATION_ERROR")
    void missingTaskTitleIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/ddl/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"client_request_id\":\"req_manual_001\",\"course_name\":\"操作系统原理\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("看板返回四个契约组字段，草案为空数组")
    void listReturnsBoardGroups() throws Exception {
        when(ddlTaskService.listTasks(USER_ID, null, null))
                .thenReturn(new DdlTaskBoardVO(List.of(taskVO()), List.of(), List.of(), List.of()));

        mockMvc.perform(get("/api/v1/ddl/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pending_inbox_tasks[0].task_id").value("103"))
                .andExpect(jsonPath("$.data.pending_change_drafts").isArray())
                .andExpect(jsonPath("$.data.pending_change_drafts").isEmpty())
                .andExpect(jsonPath("$.data.active_tasks").isArray())
                .andExpect(jsonPath("$.data.completed_tasks").isArray());
    }

    @Test
    @DisplayName("看板过滤参数原样转发给 Service")
    void listForwardsFilters() throws Exception {
        when(ddlTaskService.listTasks(USER_ID, "ACTIVE_BOARD", "2001"))
                .thenReturn(new DdlTaskBoardVO(List.of(), List.of(), List.of(taskVO()), List.of()));

        mockMvc.perform(get("/api/v1/ddl/tasks")
                        .param("status_group", "ACTIVE_BOARD")
                        .param("course_id", "2001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active_tasks[0].task_id").value("103"));

        verify(ddlTaskService).listTasks(USER_ID, "ACTIVE_BOARD", "2001");
    }

    @Test
    @DisplayName("编辑任务返回更新后的 DDL 任务")
    void updateReturnsUpdatedTask() throws Exception {
        when(ddlTaskService.updateTask(eq(USER_ID), eq(TASK_ID), any(UpdateDdlTaskRequest.class)))
                .thenReturn(taskVO());

        mockMvc.perform(put("/api/v1/ddl/tasks/103")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"course_name":"操作系统原理","task_title":"提交实验二报告",
                                 "time_precision":"DATE_ONLY","due_date":"2099-12-31"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task_id").value("103"));
    }

    @Test
    @DisplayName("非法 task_id 返回 400，不进入服务层")
    void invalidTaskIdIsRejected() throws Exception {
        mockMvc.perform(put("/api/v1/ddl/tasks/not-a-number")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"course_name":"操作系统原理","task_title":"任务",
                                 "time_precision":"DATE_ONLY","due_date":"2099-12-31"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("跨用户任务编辑 → 404 RESOURCE_NOT_FOUND")
    void crossUserUpdateIsNotFound() throws Exception {
        when(ddlTaskService.updateTask(eq(USER_ID), eq(TASK_ID), any(UpdateDdlTaskRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        mockMvc.perform(put("/api/v1/ddl/tasks/103")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"course_name":"操作系统原理","task_title":"任务",
                                 "time_precision":"DATE_ONLY","due_date":"2099-12-31"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("完成勾选转发 completed 布尔值")
    void completionForwardsBoolean() throws Exception {
        when(ddlTaskService.setCompletion(USER_ID, TASK_ID, true)).thenReturn(taskVO());

        mockMvc.perform(patch("/api/v1/ddl/tasks/103/completion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task_id").value("103"));

        verify(ddlTaskService).setCompletion(USER_ID, TASK_ID, true);
    }

    @Test
    @DisplayName("删除返回 deleted_task_id 与 CANCELED 状态")
    void deleteReturnsCanceledId() throws Exception {
        when(ddlTaskService.cancelTask(USER_ID, TASK_ID))
                .thenReturn(new DeleteDdlTaskVO("103", "CANCELED"));

        mockMvc.perform(delete("/api/v1/ddl/tasks/103"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("任务已删除"))
                .andExpect(jsonPath("$.data.deleted_task_id").value("103"))
                .andExpect(jsonPath("$.data.status").value("CANCELED"));
    }

    @Test
    @DisplayName("提取接口返回 extract_outcome/task/change_draft 契约字段")
    void extractReturnsContractEnvelope() throws Exception {
        when(ddlExtractionService.extract(eq(USER_ID), any(ExtractDdlTaskRequest.class)))
                .thenReturn(new DdlExtractVO(DdlExtractOutcome.CREATED, taskVO(), null));

        mockMvc.perform(post("/api/v1/ddl/tasks/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raw_text":"请于2099年12月31日22:00前提交实验报告",
                                 "client_request_id":"req_extract_001","force_create":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.extract_outcome").value("CREATED"))
                .andExpect(jsonPath("$.data.task.task_id").value("103"))
                .andExpect(jsonPath("$.data.change_draft").isEmpty());

        verify(ddlExtractionService).extract(eq(USER_ID), any(ExtractDdlTaskRequest.class));
    }

    @Test
    @DisplayName("变更草案确认接口返回任务，缺失 decision 返回 400")
    void draftDecisionReturnsTask() throws Exception {
        when(ddlExtractionService.decideDraft(eq(USER_ID), eq(TASK_ID),
                any(DraftDecisionRequest.class))).thenReturn(taskVO());

        mockMvc.perform(post("/api/v1/ddl/tasks/103/draft-decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"CONFIRM_UPDATE\",\"client_request_id\":\"req_decide_001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task_id").value("103"));

        mockMvc.perform(post("/api/v1/ddl/tasks/103/draft-decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"client_request_id\":\"req_decide_001\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("提取接口缺少 raw_text 返回 400")
    void extractRequiresRawText() throws Exception {
        mockMvc.perform(post("/api/v1/ddl/tasks/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"client_request_id\":\"req_extract_001\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("变更草案响应包含 change_draft 对比字段")
    void extractCanReturnChangeDraft() throws Exception {
        TaskChangeDraftVO draft = new TaskChangeDraftVO(
                TASK_ID, "提交实验二报告（延期）", "EXACT_DATETIME",
                "2099-12-31", "23:59:00",
                OffsetDateTime.parse("2099-12-31T23:59:00+08:00"), false,
                "zip", "学习通", "延期通知", "12月31日 23:59",
                OffsetDateTime.parse("2026-10-16T11:40:00+08:00"));
        when(ddlExtractionService.extract(eq(USER_ID), any(ExtractDdlTaskRequest.class)))
                .thenReturn(new DdlExtractVO(
                        DdlExtractOutcome.CHANGE_DRAFT_CONFIRM_REQUIRED, taskVO(), draft));

        mockMvc.perform(post("/api/v1/ddl/tasks/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raw_text":"实验二报告延期到12月31日23:59",
                                 "client_request_id":"req_extract_002"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.extract_outcome")
                        .value("CHANGE_DRAFT_CONFIRM_REQUIRED"))
                .andExpect(jsonPath("$.data.task.task_id").value("103"))
                .andExpect(jsonPath("$.data.change_draft.target_task_id").value("103"))
                .andExpect(jsonPath("$.data.change_draft.new_task_title")
                        .value("提交实验二报告（延期）"))
                .andExpect(jsonPath("$.data.change_draft.new_due_at")
                        .value("2099-12-31T23:59:00+08:00"));
    }

    private static DdlTaskVO taskVO() {
        return new DdlTaskVO(
                TASK_ID,
                "#T103",
                null,
                "操作系统原理",
                "提交实验二报告",
                "zip",
                "学习通",
                null,
                "2099-12-31 22:00 前",
                "EXACT_DATETIME",
                false,
                "2099-12-31",
                "22:00:00",
                OffsetDateTime.parse("2099-12-31T22:00:00+08:00"),
                false,
                "Asia/Shanghai",
                new BigDecimal("1.000"),
                "ACTIVE",
                null,
                0,
                false,
                false,
                false,
                "WEB",
                null,
                OffsetDateTime.parse("2026-10-15T11:40:00+08:00"),
                OffsetDateTime.parse("2026-10-15T11:40:00+08:00"));
    }
}
