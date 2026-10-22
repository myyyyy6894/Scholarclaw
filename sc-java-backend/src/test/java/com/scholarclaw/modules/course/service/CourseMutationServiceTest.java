package com.scholarclaw.modules.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scholarclaw.common.enums.IndexStatus;
import com.scholarclaw.modules.course.entity.CourseNamespaceEntity;
import com.scholarclaw.modules.course.mapper.CourseNamespaceMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

class CourseMutationServiceTest {

    private CourseNamespaceMapper mapper;
    private CourseDocumentStatsProvider provider;
    private CourseMutationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mapper = mock(CourseNamespaceMapper.class);
        provider = mock(CourseDocumentStatsProvider.class);
        ObjectProvider<CourseDocumentStatsProvider> providerHolder = mock(ObjectProvider.class);
        when(providerHolder.getIfAvailable()).thenReturn(provider);
        service = new CourseMutationService(mapper, mock(ApplicationEventPublisher.class),
                providerHolder);
    }

    @Test
    @DisplayName("仍有解析中文档时保持 REBUILDING")
    void keepsRebuildingWhileParsing() {
        when(mapper.selectOne(any())).thenReturn(course(2));
        when(provider.statsByCourse(10001L, Map.of(2001L, 2)))
                .thenReturn(Map.of(2001L, new CourseDocumentStatsProvider.CourseDocumentStats(
                        0, 2, 0, 2)));

        service.refreshIndexStatus(10001L, 2001L, 2);

        verify(mapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("全部 READY 时收敛为 ACTIVE")
    void activatesWhenAllReady() {
        when(mapper.selectOne(any())).thenReturn(course(2));
        when(provider.statsByCourse(10001L, Map.of(2001L, 2)))
                .thenReturn(Map.of(2001L, new CourseDocumentStatsProvider.CourseDocumentStats(
                        2, 0, 0, 2)));
        when(mapper.update(any(), any())).thenReturn(1);

        service.refreshIndexStatus(10001L, 2001L, 2);

        verify(mapper).update(any(), any());
    }

    @Test
    @DisplayName("存在失败文档时收敛为 REBUILD_FAILED")
    void marksRebuildFailed() {
        when(mapper.selectOne(any())).thenReturn(course(2));
        when(provider.statsByCourse(10001L, Map.of(2001L, 2)))
                .thenReturn(Map.of(2001L, new CourseDocumentStatsProvider.CourseDocumentStats(
                        1, 0, 1, 2)));
        when(mapper.update(any(), any())).thenReturn(1);

        service.refreshIndexStatus(10001L, 2001L, 2);

        verify(mapper).update(any(), any());
    }

    @Test
    @DisplayName("版本已变化时不再更新课程状态")
    void ignoresStaleVersion() {
        when(mapper.selectOne(any())).thenReturn(course(3));

        service.refreshIndexStatus(10001L, 2001L, 2);

        verify(provider, never()).statsByCourse(any(), any());
        verify(mapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("课程不在 REBUILDING 时不做处理")
    void ignoresActiveCourse() {
        CourseNamespaceEntity active = course(2);
        active.setIndexStatus(IndexStatus.ACTIVE.name());
        when(mapper.selectOne(any())).thenReturn(active);

        service.refreshIndexStatus(10001L, 2001L, 2);

        verify(mapper, never()).update(any(), any());
    }

    private static CourseNamespaceEntity course(int version) {
        CourseNamespaceEntity course = new CourseNamespaceEntity();
        course.setCourseId(2001L);
        course.setUserId(10001L);
        course.setIndexVersion(version);
        course.setIndexStatus(IndexStatus.REBUILDING.name());
        course.setIsDeleting(0);
        return course;
    }
}
