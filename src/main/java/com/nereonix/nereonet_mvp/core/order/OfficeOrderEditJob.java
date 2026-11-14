package com.nereonix.nereonet_mvp.core.order;

import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// P50: every minute, tells the ship about the office's changes of an order once the office has not changed it for 10
// minutes (app.orders.office-edit-quiet) and never pressed "Done editing".
@Component
public class OfficeOrderEditJob {

    private final OfficeOrderEditService editService;

    public OfficeOrderEditJob(OfficeOrderEditService editService) {
        this.editService = editService;
    }

    @Scheduled(fixedDelayString = "${app.orders.office-edit-check:PT1M}", initialDelayString = "PT1M")
    public void notifyIdleOrders() {
        editService.notifyIdle(Instant.now());
    }
}
