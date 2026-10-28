package com.scholarclaw.modules.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.scholarclaw.common.enums.IndexStatus;
import com.scholarclaw.modules.course.entity.CourseNamespaceEntity;
import com.scholarclaw.modules.course.mapper.CourseNamespaceMapper;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

class CourseMutationServiceTest {

    private CourseNamespaceMapper mapper;
    private CourseDocumentStatsProvider provider;
    private CourseMutationService service;

    @BeforeAll
    static void initTableInfo() {
        // Lambda 条件依赖 MyBatis-Plus 的实体元数据缓存；纯单测没有 Spring 上下文，需显式初始化
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                CourseNamespaceEntity.class);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mapper = mock(CourseNamespaceMapper.class);
        provider = mock(CourseDocumentStatsProvider.class);
        ObjectProvider<CourseDocumentStatsProvider> providerHolder = mock(ObjectProvider.class);
        when(providerHolder.getIfAvailable()).thenReturn(provider);
        service = new CourseMutationService(mapper, mock(ApplicationEventPublisher.class),
                providerHolder, mock(com.scholarclaw.modules.userconfig.service.UserConfigService.class));
    }

    @Test
    @DisplayName("仍有解析中文档时保持/回到 REBUILDING")
    void keepsRebuildingWhileParsing() {
        when(mapper.selectOne(any())).thenReturn(course(2));
        when(mapper.update(any(), any())).thenReturn(1);
        when(provider.statsByCourse(10001L, Map.of(2001L, 2)))
                .thenReturn(Map.of(2001L, new CourseDocumentStatsProvider.CourseDocumentStats(
                        0, 2, 0, 2)));

        assertThat(service.refreshIndexStatus(10001L, 2001L, 2)).isFalse();
        verify(mapper).update(any(), any());
    }

    @Test
    @DisplayName("全部 READY 时收敛为 ACTIVE 并返回 true（供重建完成通知）")
    void activatesWhenAllReady() {
        when(mapper.selectOne(any())).thenReturn(course(2));
        when(provider.statsByCourse(10001L, Map.of(2001L, 2)))
                .thenReturn(Map.of(2001L, new CourseDocumentStatsProvider.CourseDocumentStats(
                        2, 0, 0, 2)));
        when(mapper.update(any(), any())).thenReturn(1);

        assertThat(service.refreshIndexStatus(10001L, 2001L, 2)).isTrue();
        verify(mapper).update(any(), any());
    }

    @Test
    @DisplayName("存在失败文档时收敛为 REBUILD_FAILED，不返回 true")
    void marksRebuildFailed() {
        when(mapper.selectOne(any())).thenReturn(course(2));
        when(provider.statsByCourse(10001L, Map.of(2001L, 2)))
                .thenReturn(Map.of(2001L, new CourseDocumentStatsProvider.CourseDocumentStats(
                        1, 0, 1, 2)));
        when(mapper.update(any(), any())).thenReturn(1);

        assertThat(service.refreshIndexStatus(10001L, 2001L, 2)).isFalse();
        verify(mapper).update(any(), any());
    }

    @Test
    @DisplayName("REBUILD_FAILED 在失败文档全部重试成功后收敛 ACTIVE 并返回 true")
    void recoversFromRebuildFailed() {
        CourseNamespaceEntity failed = course(2);
        failed.setIndexStatus(IndexStatus.REBUILD_FAILED.name());
        when(mapper.selectOne(any())).thenReturn(failed);
        when(provider.statsByCourse(10001L, Map.of(2001L, 2)))
                .thenReturn(Map.of(2001L, new CourseDocumentStatsProvider.CourseDocumentStats(
                        2, 0, 0, 2)));
        when(mapper.update(any(), any())).thenReturn(1);

        assertThat(service.refreshIndexStatus(10001L, 2001L, 2)).isTrue();
        verify(mapper).update(any(), any());
    }

    @Test
    @DisplayName("并发下条件更新影响 0 行时不报告重建完成")
    void doesNotReportRebuildDoneWhenUpdateLosesRace() {
        when(mapper.selectOne(any())).thenReturn(course(2));
        when(provider.statsByCourse(10001L, Map.of(2001L, 2)))
                .thenReturn(Map.of(2001L, new CourseDocumentStatsProvider.CourseDocumentStats(
                        2, 0, 0, 2)));
        when(mapper.update(any(), any())).thenReturn(0);

        assertThat(service.refreshIndexStatus(10001L, 2001L, 2)).isFalse();
    }
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

    @Test
    @DisplayName("软删除先锁定课程行，再更新；课程不存在时 404 且不执行 UPDATE")
    void softDeleteLocksCourseRowBeforeUpdate() {
        when(mapper.selectActiveCourseForUpdate(10001L, 2001L)).thenReturn(course(1));
        when(mapper.update(any(), any())).thenReturn(1);

        service.softDelete(10001L, 2001L);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(mapper);
        order.verify(mapper).selectActiveCourseForUpdate(10001L, 2001L);
        order.verify(mapper).update(any(), any());

        when(mapper.selectActiveCourseForUpdate(10001L, 2001L)).thenReturn(null);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.softDelete(10001L, 2001L))
                .isInstanceOf(com.scholarclaw.common.exception.BusinessException.class)
                .satisfies(ex -> assertThat(
                        ((com.scholarclaw.common.exception.BusinessException) ex).getErrorCode())
                        .isEqualTo(com.scholarclaw.common.exception.ErrorCode.RESOURCE_NOT_FOUND));
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
