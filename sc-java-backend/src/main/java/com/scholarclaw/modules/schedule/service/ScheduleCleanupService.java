package com.scholarclaw.modules.schedule.service;

import com.scholarclaw.modules.schedule.repository.ScheduleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScheduleCleanupService {
    private final ScheduleRepository repository;
    private final ScheduleImageStorage imageStorage;
    public ScheduleCleanupService(ScheduleRepository repository, ScheduleImageStorage imageStorage) {
        this.repository = repository; this.imageStorage = imageStorage;
    }

    /** 独立事务提交后，监听器才可登记注销清理完成；失败由账号 GC 重放。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void purge(long userId) {
        // Keep DB task references until physical deletion succeeds; failed account GC is retried.
        imageStorage.deleteUserImages(userId);
        repository.deleteUserData(userId);
    }
}
