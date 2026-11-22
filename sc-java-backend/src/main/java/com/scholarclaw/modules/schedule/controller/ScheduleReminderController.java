package com.scholarclaw.modules.schedule.controller;

import com.scholarclaw.common.response.Result;
import com.scholarclaw.common.security.SecurityContextUtils;
import com.scholarclaw.modules.schedule.service.ScheduleReminderService;
import com.scholarclaw.modules.schedule.service.ScheduleReminderService.PreferenceView;
import com.scholarclaw.modules.schedule.service.ScheduleReminderService.PreferenceWrite;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/schedule/reminder-preference")
public class ScheduleReminderController {
    private final ScheduleReminderService reminders;

    public ScheduleReminderController(ScheduleReminderService reminders) { this.reminders = reminders; }

    @GetMapping
    public Result<PreferenceView> get() {
        return Result.ok(reminders.preference(SecurityContextUtils.requireCurrentUserId()));
    }

    @PutMapping
    public Result<PreferenceView> update(@RequestBody PreferenceWrite write) {
        return Result.ok(reminders.setPreference(SecurityContextUtils.requireCurrentUserId(), write));
    }
}
