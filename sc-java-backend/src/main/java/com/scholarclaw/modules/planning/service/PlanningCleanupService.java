package com.scholarclaw.modules.planning.service;

import com.scholarclaw.modules.planning.repository.PlanningInputRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanningCleanupService {
    private final PlanningInputRepository repository;
    public PlanningCleanupService(PlanningInputRepository repository) { this.repository = repository; }
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void purge(long userId) { repository.deleteUserData(userId); }
}
