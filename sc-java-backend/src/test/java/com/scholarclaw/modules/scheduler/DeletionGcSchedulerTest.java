package com.scholarclaw.modules.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.course.service.CourseService.StaleCourse;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.service.KnowledgeDeletionService;
import com.scholarclaw.modules.knowledge.service.KnowledgeDocService;
import com.scholarclaw.modules.knowledge.service.OrphanResourceScanner;
import com.scholarclaw.modules.knowledge.service.OrphanResourceScanner.OrphanScanResult;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import com.scholarclaw.modules.userconfig.service.UserDeactivationService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class DeletionGcSchedulerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-31T12:00:00Z"),
            ZoneId.of("Asia/Shanghai"));

    private UserConfigService userConfigService;
    private UserDeactivationService userDeactivationService;
    private KnowledgeDocService docService;
    private KnowledgeDeletionService deletionService;
    private CourseService courseService;
    private OrphanResourceScanner orphanScanner;
    private StringRedisTemplate redisTemplate;
    private DeletionGcScheduler scheduler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        userConfigService = mock(UserConfigService.class);
        userDeactivationService = mock(UserDeactivationService.class);
        docService = mock(KnowledgeDocService.class);
        deletionService = mock(KnowledgeDeletionService.class);
        courseService = mock(CourseService.class);
        orphanScanner = mock(OrphanResourceScanner.class);
        redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);
        when(orphanScanner.scanOrphanRawFiles(any(Duration.class), anyInt()))
                .thenReturn(new OrphanScanResult(0, 0, 0));
        scheduler = new DeletionGcScheduler(userConfigService, userDeactivationService,
                docService, deletionService, courseService, orphanScanner, redisTemplate,
                CLOCK, true, 10, 60, 100, 300);
    }

    @Test
    @DisplayName("滞留用户/文档/课程与孤儿文件全部重放")
    void replaysAllStaleResources() {
        when(userConfigService.findStaleDeactivating(any(), anyInt()))
                .thenReturn(List.of(11L));
        KnowledgeDocEntity doc = doc(501L, 11L);
        when(docService.listStaleDeleting(any(), anyInt())).thenReturn(List.of(doc));
        when(deletionService.finalizeDocumentDeletion(11L, doc, null)).thenReturn(true);
        when(courseService.listStaleDeleting(any(), anyInt()))
                .thenReturn(List.of(new StaleCourse(11L, 21L, 3)));
        when(deletionService.finalizeCourseDeletion(11L, 21L, 3)).thenReturn(true);
        when(orphanScanner.scanOrphanRawFiles(any(Duration.class), eq(100)))
                .thenReturn(new OrphanScanResult(5, 2, 0));

        DeletionGcScheduler.GcResult result = scheduler.runOnce();

        assertThat(result.users()).isEqualTo(1);
        assertThat(result.documents()).isEqualTo(1);
        assertThat(result.courses()).isEqualTo(1);
        assertThat(result.orphanDeleted()).isEqualTo(2);
        verify(userDeactivationService).retryCleanup(11L);
        verify(courseService).purgeSoftDeleted(11L, 21L);
    }

    @Test
    @DisplayName("单个对象失败不影响其余对象，下一轮继续重试")
    void isolatesPerItemFailures() {
        when(userConfigService.findStaleDeactivating(any(), anyInt()))
                .thenReturn(List.of(11L, 12L));
        org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
                .when(userDeactivationService).retryCleanup(11L);
        KnowledgeDocEntity doc = doc(501L, 12L);
        when(docService.listStaleDeleting(any(), anyInt())).thenReturn(List.of(doc));
        when(deletionService.finalizeDocumentDeletion(12L, doc, null))
                .thenReturn(false);

        DeletionGcScheduler.GcResult result = scheduler.runOnce();

        assertThat(result.users()).isEqualTo(1);
        assertThat(result.documents()).isZero();
        verify(userDeactivationService).retryCleanup(12L);
    }

    @Test
    @DisplayName("锁被其他实例持有：本轮跳过")
    @SuppressWarnings("unchecked")
    void skipsWhenLocked() {
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(false);

        assertThat(scheduler.runOnce()).isNull();
        verify(docService, never()).listStaleDeleting(any(), anyInt());
    }

    private static KnowledgeDocEntity doc(Long docId, Long userId) {
        KnowledgeDocEntity doc = new KnowledgeDocEntity();
        doc.setDocId(docId);
        doc.setUserId(userId);
        return doc;
    }
}
