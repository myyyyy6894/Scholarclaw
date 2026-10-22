package com.scholarclaw.modules.ddl.listener;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.scholarclaw.common.event.CourseDeletingEvent;
import com.scholarclaw.modules.ddl.mapper.DdlTaskMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TASK-401：删除课程时 DDL 任务解绑保留（{@code API-COURSE-004}、PO-05）。 */
class CourseDeletingListenerTest {

    @Test
    @DisplayName("课程删除事件触发 t_ddl_task.course_id 置空")
    void unbindsTasksOnCourseDeleting() {
        DdlTaskMapper mapper = mock(DdlTaskMapper.class);
        CourseDeletingListener listener = new CourseDeletingListener(mapper);

        listener.onCourseDeleting(new CourseDeletingEvent(10001L, 2001L));

        verify(mapper).unbindCourse(10001L, 2001L);
    }
}
