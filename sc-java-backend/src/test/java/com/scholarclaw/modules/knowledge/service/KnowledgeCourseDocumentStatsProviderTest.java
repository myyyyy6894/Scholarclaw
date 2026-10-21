package com.scholarclaw.modules.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.scholarclaw.modules.course.service.CourseDocumentStatsProvider.CourseDocumentStats;
import com.scholarclaw.modules.knowledge.mapper.CourseDocStatsRow;
import com.scholarclaw.modules.knowledge.mapper.KnowledgeDocMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KnowledgeCourseDocumentStatsProviderTest {

    @Test
    @DisplayName("按课程聚合 READY / UPLOADED+PARSING / FAILED / 总数")
    void aggregatesByCourse() {
        KnowledgeDocMapper mapper = mock(KnowledgeDocMapper.class);
        CourseDocStatsRow row = new CourseDocStatsRow();
        row.setCourseId(2001L);
        row.setReadyCount(4L);
        row.setParsingCount(2L);
        row.setFailedCount(1L);
        row.setTotalCount(7L);
        when(mapper.selectCourseStats(10001L, List.of(2001L))).thenReturn(List.of(row));

        Map<Long, CourseDocumentStats> stats = new KnowledgeCourseDocumentStatsProvider(mapper)
                .statsByCourse(10001L, List.of(2001L));

        assertThat(stats.get(2001L).ready()).isEqualTo(4);
        assertThat(stats.get(2001L).parsing()).isEqualTo(2);
        assertThat(stats.get(2001L).failed()).isEqualTo(1);
        assertThat(stats.get(2001L).total()).isEqualTo(7);
    }
}
