package com.scholarclaw.modules.planning.service;

import com.scholarclaw.modules.planning.repository.PlanningInputRepository;
import com.scholarclaw.modules.planning.repository.StudyPlanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanningCleanupService {
    private final PlanningInputRepository repository;
    private final StudyPlanRepository plans;
    public PlanningCleanupService(PlanningInputRepository repository, StudyPlanRepository plans) {
        this.repository = repository; this.plans = plans;
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void purge(long userId) { plans.deleteUserData(userId); repository.deleteUserData(userId); }
}
