package com.nereonix.nereonet_mvp.dashboard;

import java.util.List;

import org.springframework.stereotype.Component;

import com.nereonix.nereonet_mvp.dashboard.DashboardService.Setup;
import com.nereonix.nereonet_mvp.dashboard.DashboardService.SetupStep;

/**
 * The Overview's first-run checklist. A step is done when its data exists: any ship; any user with a ship role or
 * pending crew invitation; any library item not retired; any announcement. The library step opens the first ship's
 * Library tab (Vessels while there is none). One query.
 */
@Component
class SetupChecklist {

    private final DashboardQueries queries;

    SetupChecklist(DashboardQueries queries) {
        this.queries = queries;
    }

    Setup load() {
        DashboardQueries.SetupRow row = queries.setup();
        String library = row.firstShipId() == null ? "/office/ships" : "/office/ships/" + row.firstShipId() + "/library";
        return new Setup(List.of(
                new SetupStep("ship", row.ships() > 0, "/office/ships"),
                new SetupStep("crew", row.crewUsers() + row.crewInvitations() > 0, "/office/crew"),
                new SetupStep("library", row.libraryItems() > 0, library),
                new SetupStep("announcement", row.announcements() > 0, "/office/announcements")));
    }
}
