package com.scholarclaw.modules.ddl.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scholarclaw.common.event.CourseDeletingEvent;
import com.scholarclaw.modules.ddl.mapper.DdlTaskMapper;
import com.scholarclaw.modules.ddl.service.DdlDraftStore;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TASK-401 / PO-05：删除课程时 DDL 任务解绑保留，且清理关联变更草案。 */
class CourseDeletingListenerTest {

    @Test
    @DisplayName("课程删除事件触发 t_ddl_task.course_id 置空，并清理该课程任务的草案")
    void unbindsTasksAndClearsDraftsOnCourseDeleting() {
        DdlTaskMapper mapper = mock(DdlTaskMapper.class);
        DdlDraftStore draftStore = mock(DdlDraftStore.class);
        when(mapper.selectTaskIdsByCourse(10001L, 2001L)).thenReturn(List.of(103L, 104L));
        CourseDeletingListener listener = new CourseDeletingListener(mapper, draftStore);

        listener.onCourseDeleting(new CourseDeletingEvent(10001L, 2001L));

        verify(mapper).unbindCourse(10001L, 2001L);
        verify(draftStore).deleteDraftAfterCommit(10001L, 103L);
        verify(draftStore).deleteDraftAfterCommit(10001L, 104L);
        assertThat(List.of(103L, 104L)).isNotEmpty();
    }
}
