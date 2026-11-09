package com.scholarclaw.modules.schedule.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.client.PythonAiEngineClient;
import com.scholarclaw.modules.schedule.repository.ScheduleImportRepository;
import com.scholarclaw.modules.schedule.repository.ScheduleRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScheduleImportWorkerTest {
    @Test
    void scheduledScanWaitsUntilApplicationRunnersIncludingMigrationsHaveCompleted() {
        var imports = mock(ScheduleImportRepository.class);
        var worker = new ScheduleImportWorker(imports, mock(ScheduleRepository.class),
                mock(PythonAiEngineClient.class), new ObjectMapper(), true);
        worker.poll();
        verifyNoInteractions(imports);
        when(imports.candidates(any(), anyInt())).thenReturn(List.of());
        worker.applicationReady();
        worker.poll();
        verify(imports).expireTextTasks(any());
        verify(imports).failExhaustedLeases(any());
        worker.shutdown();
    }
}
