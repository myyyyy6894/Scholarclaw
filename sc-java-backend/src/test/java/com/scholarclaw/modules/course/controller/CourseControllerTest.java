package com.scholarclaw.modules.course.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.config.JacksonConfig;
import com.scholarclaw.common.exception.GlobalExceptionHandler;
import com.scholarclaw.common.security.ScUserPrincipal;
import com.scholarclaw.modules.course.dto.ChangeEmbeddingModelVO;
import com.scholarclaw.modules.course.dto.CourseNamespaceVO;
import com.scholarclaw.modules.course.dto.CreateCourseRequest;
import com.scholarclaw.modules.course.dto.DeleteCourseVO;
import com.scholarclaw.modules.course.service.CourseService;
import java.time.Instant;
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

/** `API-COURSE-001~004` 的控制器契约。 */
class CourseControllerTest {

    private static final long USER_ID = 10001L;
    private static final long COURSE_ID = 2001L;
    private static final ObjectMapper MAPPER = JacksonConfig
            .applyTo(new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder(),
                    ZoneId.of("Asia/Shanghai")).build();

    private CourseService courseService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        courseService = mock(CourseService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new CourseController(courseService))
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
    @DisplayName("列表返回十进制字符串 course_id 与 snake_case 字段")
    void listUsesStringIdAndSnakeCase() throws Exception {
        when(courseService.listCourses(USER_ID)).thenReturn(List.of(course()));

        mockMvc.perform(get("/api/v1/courses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data[0].course_id").value("2001"))
                .andExpect(jsonPath("$.data[0].course_name").value("操作系统原理"))
                .andExpect(jsonPath("$.data[0].embedding_dim").value(1024))
                .andExpect(jsonPath("$.data[0].index_status").value("ACTIVE"));
    }

    @Test
    @DisplayName("创建课程返回 201，身份来自 JWT")
    void createReturnsCreated() throws Exception {
        when(courseService.createCourse(eq(USER_ID), any(CreateCourseRequest.class)))
                .thenReturn(course());

        mockMvc.perform(post("/api/v1/courses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"course_name\":\"操作系统原理\",\"description\":\"2026 秋\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.course_id").value("2001"));

        verify(courseService).createCourse(eq(USER_ID), any(CreateCourseRequest.class));
    }

    @Test
    @DisplayName("非法 course_id 返回 400，不进入服务层")
    void rejectsInvalidCourseId() throws Exception {
        mockMvc.perform(put("/api/v1/courses/not-a-number/embedding-model")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"use_user_default\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("切换模型转发解析后的 course_id")
    void changeEmbeddingForwardsParsedId() throws Exception {
        when(courseService.changeEmbeddingModel(eq(USER_ID), eq(COURSE_ID), any()))
                .thenReturn(new ChangeEmbeddingModelVO(COURSE_ID, 1, 2, "ACTIVE",
                        "OPENAI_COMPATIBLE", "text-embedding-3-small", 1536, 0));

        mockMvc.perform(put("/api/v1/courses/2001/embedding-model")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"use_user_default\":false,\"embedding_provider\":\"OPENAI_COMPATIBLE\","
                                + "\"embedding_model\":\"text-embedding-3-small\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.course_id").value("2001"))
                .andExpect(jsonPath("$.data.current_index_version").value(2));
    }

    @Test
    @DisplayName("删除课程返回 deleted_course_id")
    void deleteReturnsDeletedId() throws Exception {
        when(courseService.deleteCourse(USER_ID, COURSE_ID)).thenReturn(new DeleteCourseVO(COURSE_ID));

        mockMvc.perform(delete("/api/v1/courses/2001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted_course_id").value("2001"));
    }

    private static CourseNamespaceVO course() {
        return new CourseNamespaceVO(COURSE_ID, "操作系统原理", "2026 秋", false,
                "OPENAI_COMPATIBLE", "BAAI/bge-m3", 1024, 1, "ACTIVE",
                0, 0, 0, "2026-10-21T10:00:00.000+08:00", "2026-10-21T10:00:00.000+08:00");
    }
}
