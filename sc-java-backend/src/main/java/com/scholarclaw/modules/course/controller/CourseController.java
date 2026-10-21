package com.scholarclaw.modules.course.controller;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.response.Result;
import com.scholarclaw.common.security.SecurityContextUtils;
import com.scholarclaw.modules.course.dto.ChangeEmbeddingModelRequest;
import com.scholarclaw.modules.course.dto.ChangeEmbeddingModelVO;
import com.scholarclaw.modules.course.dto.CourseNamespaceVO;
import com.scholarclaw.modules.course.dto.CreateCourseRequest;
import com.scholarclaw.modules.course.dto.DeleteCourseVO;
import com.scholarclaw.modules.course.service.CourseService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 课程空间接口（{@code 04} §4.3：`API-COURSE-001~004`）。
 *
 * <p>身份只来自 JWT（{@link SecurityContextUtils#requireCurrentUserId()}）；路径中的
 * {@code course_id} 只作为资源标识，服务层始终用 {@code user_id + course_id} 联合过滤，
 * 跨用户访问返回 404（防 IDOR）。
 */
@RestController
@RequestMapping("/api/v1/courses")
public class CourseController {

    private final CourseService courseService;

    public CourseController(CourseService courseService) {
        this.courseService = courseService;
    }

    @GetMapping
    public Result<List<CourseNamespaceVO>> list() {
        return Result.ok(courseService.listCourses(SecurityContextUtils.requireCurrentUserId()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<CourseNamespaceVO> create(@Valid @RequestBody CreateCourseRequest request) {
        return Result.ok("课程空间创建成功",
                courseService.createCourse(SecurityContextUtils.requireCurrentUserId(), request));
    }

    @PutMapping("/{courseId}/embedding-model")
    public Result<ChangeEmbeddingModelVO> changeEmbeddingModel(
            @PathVariable("courseId") String courseId,
            @Valid @RequestBody ChangeEmbeddingModelRequest request) {
        Long parsed = parseCourseId(courseId);
        return Result.ok("课程向量模型已切换",
                courseService.changeEmbeddingModel(
                        SecurityContextUtils.requireCurrentUserId(), parsed, request));
    }

    @DeleteMapping("/{courseId}")
    public Result<DeleteCourseVO> delete(@PathVariable("courseId") String courseId) {
        Long parsed = parseCourseId(courseId);
        return Result.ok("课程知识库已删除，关联的 DDL 待办任务已自动解绑保留",
                courseService.deleteCourse(SecurityContextUtils.requireCurrentUserId(), parsed));
    }

    private static Long parseCourseId(String raw) {
        try {
            long value = Long.parseLong(raw);
            if (value <= 0) {
                throw new NumberFormatException("non-positive");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "course_id 必须为正的十进制字符串。");
        }
    }
}
