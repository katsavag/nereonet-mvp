package com.nereonix.nereonet_mvp.dashboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ends the snoozes that no longer apply (P43) once a day, at the certificate reminders' time, so a snooze ends by
 * itself when its date passes, when its item is resolved or when the item gets worse, even on a day nobody opens the
 * Overview (which syncs them too, DashboardService.load). The reminder job runs after, so a certificate whose snooze
 * just ended gets its reminder the same morning.
 */
@Component
public class SnoozeSyncJob {

    private static final Logger log = LoggerFactory.getLogger(SnoozeSyncJob.class);

    private final DashboardService dashboardService;

    public SnoozeSyncJob(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @Scheduled(cron = "${app.certificates.reminder-cron}", zone = "UTC")
    public void sync() {
        try {
            dashboardService.load();
        } catch (RuntimeException e) {
            log.error("Snooze sync failed", e);
        }
    }
}
