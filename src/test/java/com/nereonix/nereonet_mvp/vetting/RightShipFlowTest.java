package com.nereonix.nereonet_mvp.vetting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditEntry;
import com.nereonix.nereonet_mvp.core.audit.AuditRepository;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.ship.ShipType;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserService;
import com.nereonix.nereonet_mvp.core.vetting.GhgRating;
import com.nereonix.nereonet_mvp.core.vetting.InspectionOutcome;
import com.nereonix.nereonet_mvp.core.vetting.RightShipClient;
import com.nereonix.nereonet_mvp.core.vetting.RightShipReport;
import com.nereonix.nereonet_mvp.core.vetting.RightShipReportRepository;
import com.nereonix.nereonet_mvp.core.vetting.RightShipSource;
import com.nereonix.nereonet_mvp.core.vetting.RightShipSyncService;
import com.nereonix.nereonet_mvp.core.vetting.ShipRightShipStatus;
import com.nereonix.nereonet_mvp.core.vetting.ShipRightShipStatusRepository;
import com.nereonix.nereonet_mvp.core.vetting.Subscore;
import com.nereonix.nereonet_mvp.core.vetting.VettingService;

/**
 * P40: the office's Vetting section. The list, a vessel's Vetting and RightShip pages (rated, not rated, sanctioned),
 * Edit RightShip data, Upload report and its preview, the vessel page's badges, the Overview's items and the Vessels
 * list's Safety Score; the API sync with a fake RightShip (no request at all while it is not configured; the
 * nightly job updates only the changed vessels and keeps the manual subscores; a failure is shown, never a 500).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
@Import(RightShipFlowTest.FakeClientConfig.class)
class RightShipFlowTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 0};
    private static final byte[] PDF = "%PDF-1.7\n% a report\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    private static final LocalDate REPORT_DATE = LocalDate.of(2026, 10, 21);
    private static final Pattern ROW_NAME = Pattern.compile("(?s)<td class=\"cell-primary\">\\s*<a [^>]*>([^<]*)</a>");

    @Autowired MockMvc mockMvc;
    @Autowired ShipRepository shipRepository;
    @Autowired UserService userService;
    @Autowired VettingService vettingService;
    @Autowired RightShipSyncService syncService;
    @Autowired ShipRightShipStatusRepository statusRepository;
    @Autowired RightShipReportRepository reportRepository;
    @Autowired AuditRepository auditRepository;
    @Autowired FakeRightShipClient rightShip;

    LocalDate today;
    int imo;

    @BeforeEach
    void setUp() {
        today = VettingService.today();
        rightShip.reset();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The list
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void theListShowsEveryActiveVesselMostUrgentFirst() throws Exception {
        rated(vessel("VALID"), 4, today.plusMonths(6));
        Ship expiring = vessel("EXPIRING");
        rated(expiring, 5, today.plusDays(20));
        rated(vessel("EXPIRED"), 4, today.minusDays(2));
        rated(vessel("LOW"), 2, today.plusMonths(6));
        vessel("NOT RATED");
        Ship inactive = vessel("INACTIVE");
        inactive.setActive(false);

        String html = html(get("/office/vetting"));
        assertThat(names(html)).containsExactly("EXPIRED", "EXPIRING", "LOW", "VALID", "NOT RATED");
        assertThat(html).contains("Expired", "Expires in 20 days", "2/5", "N/A", "Safety Score", "GHG Rating",
                        "data-row-href=\"/office/vetting/" + expiring.getId() + "\"")
                .contains("SIRE 2.0");

        // Filters and sorting (P19).
        assertThat(names(html(get("/office/vetting").param("status", "attention"))))
                .containsExactly("EXPIRED", "EXPIRING", "LOW");
        assertThat(names(html(get("/office/vetting").param("status", "notrated")))).containsExactly("NOT RATED");
        assertThat(names(html(get("/office/vetting").param("status", "valid")))).containsExactly("VALID");
        assertThat(names(html(get("/office/vetting").param("sort", "name"))))
                .containsExactly("EXPIRED", "EXPIRING", "LOW", "NOT RATED", "VALID");
        assertThat(names(html(get("/office/vetting").param("sort", "score").param("dir", "desc"))))
                .containsExactly("EXPIRING", "EXPIRED", "VALID", "LOW", "NOT RATED");
        assertThat(names(html(get("/office/vetting").param("q", "low")))).containsExactly("LOW");
        assertThat(names(html(get("/office/vetting").param("type", "TANKER")))).isEmpty();

        // Greek office screens.
        assertThat(html(get("/office/vetting").param("lang", "el"))).contains("Χρειάζεται προσοχή", "Έληξε");
    }

    @Test
    void anEmptyFleetShowsTheEmptyState() throws Exception {
        assertThat(html(get("/office/vetting"))).contains("No active vessels", "href=\"/office/ships\"");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // A vessel's pages
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void aRatedVesselShowsItsScoreSubscoresGhgAndInspection() throws Exception {
        Ship alder = vessel("ALDER");
        alder(alder);

        String vessel = html(get("/office/vetting/{id}", alder.getId()));
        assertThat(vessel).contains("<a class=\"stretched-link\" href=\"/office/vetting/" + alder.getId() + "/rightship\">RightShip</a>",
                "3/5", "Expires in 38 days", "href=\"/office/ships/" + alder.getId() + "\"", "Open vessel");

        String page = html(get("/office/vetting/{id}/rightship", alder.getId()));
        assertThat(page).contains("RightShip Safety Score", "3/5", "Bulk carrier", "21 Oct 2026",
                        "Highest impact", "Medium impact", "Lowest impact", "Vessel incident performance",
                        "DOC company performance", "PSC deficiencies", "Detentions", "Flag performance", "Class performance",
                        "Unverified", "Expires in 38 days", "Edit RightShip data", "Open vessel", "Official document",
                        "No RightShip report yet")
                .containsSubsequence("<nav class=\"page-breadcrumbs\"", ">Vetting</a>", ">ALDER</a>", ">RightShip</li>")
                // Only the lowest subscore (DOC, 2/5) is marked, in words.
                .containsPattern("(?s)class=\"rs-subscore is-lowest\">.*?DOC company performance.*?2/5.*?>Lowest</span>")
                // Without the API configured, no Refresh and no Last sync line.
                .doesNotContain("Refresh from RightShip", "Last sync failed", "Add RightShip data");
        assertThat(count(page, "is-lowest")).isEqualTo(1);
        assertThat(count(page, ">Lowest</span>")).isEqualTo(1);
    }

    @Test
    void aVesselWithoutDataAsksForItAndShowsNotAvailable() throws Exception {
        Ship ship = vessel("MV Demo");

        assertThat(html(get("/office/vetting/{id}", ship.getId()))).contains("No RightShip data yet.");
        String page = html(get("/office/vetting/{id}/rightship", ship.getId()));
        assertThat(page).contains("N/A", "No RightShip data yet", "Add RightShip data",
                        "href=\"/office/vetting/" + ship.getId() + "/rightship/edit\"", "Upload report")
                .doesNotContain("Edit RightShip data", "Subscore summary");
        // One primary button: Add RightShip data; Upload report is secondary.
        assertThat(count(page, "btn btn-primary")).isEqualTo(1);
    }

    @Test
    void aSanctionedVesselHasNoSafetyScoreAndARestrictedVesselShowsTheComments() throws Exception {
        Ship ship = vessel("SANCTIONED");
        ShipRightShipStatus values = new ShipRightShipStatus();
        values.setSafetyScore(0);
        values.setRestricted(true);
        values.setRestrictionComments("Sanctioned owner.");
        vettingService.saveManual(ship, values, null);
        crew(ship);

        assertThat(html(get("/office/vetting/{id}/rightship", ship.getId())))
                .contains("0/5", "No safety score", "Restricted by RightShip", "Sanctioned owner.");
        // The Overview: a Safety Score of 2 or below needs attention.
        assertThat(html(get("/office"))).contains("Safety Score 0/5", "Low score", "Open RightShip",
                "href=\"/office/vetting/" + ship.getId() + "/rightship\"");
    }

    @Test
    void anInactiveOrUnknownVesselIsNotFound() throws Exception {
        Ship ship = vessel("OLD");
        ship.setActive(false);
        for (String url : List.of("/office/vetting/" + ship.getId(), "/office/vetting/" + ship.getId() + "/rightship",
                "/office/vetting/" + ship.getId() + "/rightship/edit", "/office/vetting/999999/rightship")) {
            mockMvc.perform(get(url)).andExpect(status().isNotFound());
        }
    }

    @Test
    void theCrewCannotOpenVetting() throws Exception {
        Ship ship = vessel("ALDER");
        User master = userService.createUser("master@example.com", "Demo Master", "secret123", Role.MASTER, ship, null);
        for (String url : List.of("/office/vetting", "/office/vetting/" + ship.getId() + "/rightship")) {
            mockMvc.perform(get(url).with(user(new CurrentUser(master)))).andExpect(status().isForbidden());
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Edit and upload
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void theOfficeEditsTheRightShipDataAndEachSaveIsKept() throws Exception {
        Ship ship = vessel("ROWAN");
        String edit = "/office/vetting/" + ship.getId() + "/rightship/edit";
        assertThat(html(get(edit))).contains("Edit RightShip data", "name=\"safetyScore\"", "name=\"vesselIncident\"",
                "name=\"classPerformance\"", "name=\"ghgRating\"", "name=\"inspectionValidUntil\"");

        mockMvc.perform(post(edit).with(csrf()).param("safetyScore", "4").param("calculatedAt", "2026-10-21")
                        .param("vesselIncident", "4").param("doc", "4").param("pscDeficiencies", "3")
                        .param("detentions", "5").param("flag", "4").param("classPerformance", "5")
                        .param("ghgRating", "B").param("ghgVerified", "true")
                        .param("inspectionValidUntil", today.plusMonths(6).toString())
                        .param("lastInspectionOutcome", "ACCEPTABLE").param("inspectionRequired", "true"))
                .andExpect(redirectedUrl("/office/vetting/" + ship.getId() + "/rightship"))
                .andExpect(flash().attribute("successMessage", "vetting.saved"));

        ShipRightShipStatus current = statusRepository.findByShipIdAndCurrentTrue(ship.getId()).orElseThrow();
        assertThat(current.getSafetyScore()).isEqualTo(4);
        assertThat(current.subscore(Subscore.PSC_DEFICIENCIES)).isEqualTo(3);
        assertThat(current.getGhgRating()).isEqualTo(GhgRating.B);
        assertThat(current.isGhgVerified()).isTrue();
        assertThat(current.getSource()).isEqualTo(RightShipSource.MANUAL);
        assertThat(current.getUpdatedBy().getEmail()).isEqualTo("office@example.com");
        assertThat(current.getCalculatedAt()).isEqualTo(REPORT_DATE);
        assertThat(actions()).contains("RIGHTSHIP_UPDATED");
        // Opened again, the form shows what was saved, dates as the date inputs read them.
        assertThat(html(get(edit))).containsPattern("id=\"calculatedAt\" name=\"calculatedAt\" value=\"2026-10-21\"")
                .containsPattern("<option value=\"3\" selected=\"selected\">3/5</option>");
        assertThat(html(get("/office/vetting/{id}/rightship", ship.getId())))
                .contains("4/5", "Verified", "Entered by", "PSC deficiencies");

        // A second save adds a row; only the newest is current.
        mockMvc.perform(post(edit).with(csrf()).param("safetyScore", "5"))
                .andExpect(redirectedUrl("/office/vetting/" + ship.getId() + "/rightship"));
        assertThat(statusRepository.findAll().stream().filter(s -> s.getShip().getId().equals(ship.getId())))
                .hasSize(2).filteredOn(ShipRightShipStatus::isCurrent).singleElement()
                .extracting(ShipRightShipStatus::getSafetyScore).isEqualTo(5);

        // Wrong values are refused with the field's message.
        assertThat(mockMvc.perform(post(edit).with(csrf()).param("safetyScore", "6").param("doc", "0")
                        .param("ghgRating", "B").param("ghgPlus", "true").param("platformLink", "http://rightship.example"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("Choose a score from 0 to 5.", "Choose a subscore from 1 to 5.",
                        "A plus is only given with an A rating.", "Enter an address that starts with https://");
    }

    @Test
    void theOfficeUploadsAReportThatIsShownBesideTheDashboard() throws Exception {
        Ship ship = vessel("ALDER");
        alder(ship);
        String upload = "/office/vetting/" + ship.getId() + "/rightship/reports/new";
        assertThat(html(get(upload))).contains("accept=\".pdf,.png,.jpg,.jpeg\"", "name=\"documentDate\"");

        // A file of another kind, or none, is refused.
        assertThat(mockMvc.perform(multipart(upload).file(new MockMultipartFile("file", "report.png", "image/png",
                        "not a png".getBytes(StandardCharsets.UTF_8))).param("documentDate", "2026-10-21").with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("Choose a PDF, PNG or JPEG file.");
        assertThat(mockMvc.perform(multipart(upload).param("documentDate", "2026-10-21").with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("Choose a file.");

        mockMvc.perform(multipart(upload).file(new MockMultipartFile("file", "rightship-2025.pdf", "application/pdf", PDF))
                        .param("documentDate", "2025-10-20").with(csrf()))
                .andExpect(redirectedUrl("/office/vetting/" + ship.getId() + "/rightship"));
        mockMvc.perform(multipart(upload).file(new MockMultipartFile("file", "alder.png", "image/png", PNG))
                        .param("documentDate", "2026-10-21").with(csrf()))
                .andExpect(redirectedUrl("/office/vetting/" + ship.getId() + "/rightship"))
                .andExpect(flash().attribute("successMessage", "vetting.report.uploaded"));
        assertThat(actions()).contains("RIGHTSHIP_REPORT_UPLOADED");

        List<RightShipReport> reports = vettingService.reports(ship.getId());
        assertThat(reports).extracting(RightShipReport::getFileName).containsExactly("alder.png", "rightship-2025.pdf");
        String file = "/office/vetting/" + ship.getId() + "/rightship/reports/" + reports.getFirst().getId() + "/file";
        String page = html(get("/office/vetting/{id}/rightship", ship.getId()));
        // The newest inline as an image; the earlier one listed under it; Upload report is now the header's primary.
        assertThat(page).contains("<img class=\"rs-preview-image\" src=\"" + file + "\"",
                        "alt=\"RightShip report of ALDER, 21 Oct 2026\"", "Earlier reports", "rightship-2025.pdf",
                        "20 Oct 2025", "href=\"" + file + "?download=true\"")
                .doesNotContain("No RightShip report yet");
        assertThat(count(page, "btn btn-primary")).isEqualTo(1);

        mockMvc.perform(get(file)).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("inline")));
        mockMvc.perform(get(file).param("download", "true"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")));
        // A PDF opens in the browser too: the earlier report as a link.
        String pdf = "/office/vetting/" + ship.getId() + "/rightship/reports/" + reports.getLast().getId() + "/file";
        mockMvc.perform(get(pdf)).andExpect(header().string("Content-Type", "application/pdf"));
        // Another vessel's report is not found under this vessel.
        Ship other = vessel("OTHER");
        mockMvc.perform(get("/office/vetting/" + other.getId() + "/rightship/reports/" + reports.getFirst().getId() + "/file"))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Elsewhere in the office
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void theVesselPageHeaderShowsTheBadgesAndHasNoVettingTab() throws Exception {
        Ship ship = vessel("ALDER");
        alder(ship);

        String page = html(get("/office/ships/{id}", ship.getId()));
        assertThat(page).containsPattern("(?s)<a class=\"vetting-badges\"\\s+href=\"/office/vetting/" + ship.getId() + "\">.*?"
                        + "3/5.*?ghg-c.*?Expires in 38 days.*?</a>")
                .doesNotContain("/office/ships/" + ship.getId() + "/vetting");
        // An inactive vessel has no Vetting page, so no badges.
        ship.setActive(false);
        assertThat(html(get("/office/ships/{id}", ship.getId()))).doesNotContain("vetting-badges");
    }

    @Test
    void theOverviewListsInspectionsDueAndTheVesselsListSortsByScore() throws Exception {
        Ship alder = vessel("ALDER");
        alder(alder);
        rated(vessel("LAUREL"), 5, today.plusMonths(9));
        crew(vessel("MV Demo"));

        String overview = html(get("/office"));
        assertThat(overview).contains("RightShip inspection", "Expires in 38 days", "Open RightShip",
                "href=\"/office/vetting/" + alder.getId() + "/rightship\"", "Vetting needs attention",
                "href=\"/office/vetting?status=attention\"");

        String ships = html(get("/office/ships").param("sort", "score"));
        assertThat(names(ships)).containsExactly("ALDER", "LAUREL", "MV Demo");
        assertThat(ships).contains("href=\"/office/vetting/" + alder.getId() + "\"", "3/5", "5/5", "N/A");
        assertThat(names(html(get("/office/ships").param("sort", "score").param("dir", "desc"))))
                .containsExactly("LAUREL", "ALDER", "MV Demo");
        assertThat(names(html(get("/office/ships")))).containsExactly("ALDER", "LAUREL", "MV Demo");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The API sync
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void withoutCredentialsTheAppMakesNoRequestToRightShip() throws Exception {
        Ship ship = vessel("ALDER");
        alder(ship);

        assertThat(syncService.run().ran()).isFalse();
        mockMvc.perform(post("/office/vetting/{id}/rightship/refresh", ship.getId()).with(csrf()))
                .andExpect(flash().attribute("errorMessage", "vetting.refresh.notConfigured"));
        assertThat(rightShip.calls).isEmpty();
        assertThat(html(get("/office/vetting/{id}/rightship", ship.getId()))).doesNotContain("Refresh from RightShip");
    }

    @Nested
    @TestPropertySource(properties = {"app.rightship.base-url=https://rightship.example.test",
            "app.rightship.client-id=test-client", "app.rightship.client-secret=test-secret"})
    class WithTheApiConfigured {

        @Test
        void theNightlyJobUpdatesOnlyTheChangedVesselsAndKeepsTheManualSubscores() throws Exception {
            Ship alder = vessel("ALDER");
            alder(alder);
            Ship laurel = vessel("LAUREL");
            rated(laurel, 5, today.plusMonths(9));
            Ship old = vessel("OLD");
            old.setActive(false);

            // The first run asks for every active vessel.
            rightShip.infos.put(alder.getImoNumber(), info(alder, 4, 3));
            rightShip.infos.put(laurel.getImoNumber(), info(laurel, 5, 5));
            RightShipSyncService.Result first = syncService.run();
            assertThat(first.ran()).isTrue();
            assertThat(first.failed()).isFalse();
            assertThat(first.asked()).isEqualTo(2);
            assertThat(rightShip.calls).containsExactly("info [" + alder.getImoNumber() + ", " + laurel.getImoNumber() + "]");

            ShipRightShipStatus fromApi = statusRepository.findByShipIdAndCurrentTrue(alder.getId()).orElseThrow();
            assertThat(fromApi.getSource()).isEqualTo(RightShipSource.API);
            assertThat(fromApi.getSafetyScore()).isEqualTo(4);
            // DOC from the API; the other five kept from the manual entry, with that report's date.
            assertThat(fromApi.subscore(Subscore.DOC)).isEqualTo(3);
            assertThat(fromApi.subscore(Subscore.VESSEL_INCIDENT)).isEqualTo(4);
            assertThat(fromApi.subscore(Subscore.CLASS)).isEqualTo(5);
            assertThat(fromApi.getSubscoresReportDate()).isEqualTo(REPORT_DATE);
            assertThat(actions()).contains("RIGHTSHIP_SYNCED");
            assertThat(html(get("/office/vetting/{id}/rightship", alder.getId())))
                    .contains("Updated from RightShip", "from RightShip", "from report, 21 Oct 2026",
                            "Refresh from RightShip");

            // The next run asks what changed since the last run, and only those (active) vessels.
            rightShip.calls.clear();
            rightShip.updated = List.of(alder.getImoNumber(), old.getImoNumber());
            rightShip.infos.put(alder.getImoNumber(), info(alder, 5, 4));
            long laurelRows = rows(laurel);
            RightShipSyncService.Result next = syncService.run();
            assertThat(next.asked()).isEqualTo(1);
            assertThat(next.updated()).isEqualTo(1);
            assertThat(rightShip.calls).containsExactly("updates " + today, "info [" + alder.getImoNumber() + "]");
            assertThat(rows(laurel)).isEqualTo(laurelRows);
            ShipRightShipStatus updated = statusRepository.findByShipIdAndCurrentTrue(alder.getId()).orElseThrow();
            assertThat(updated.getSafetyScore()).isEqualTo(5);
            assertThat(updated.subscore(Subscore.VESSEL_INCIDENT)).isEqualTo(4);

            // Nothing new: no row is written.
            long alderRows = rows(alder);
            syncService.run();
            assertThat(rows(alder)).isEqualTo(alderRows);
        }

        @Test
        void aFailedSyncIsShownAndNeverAnError() throws Exception {
            Ship alder = vessel("ALDER");
            alder(alder);
            rightShip.failure = new RightShipClient.RightShipException("RightShip answered 503");

            RightShipSyncService.Result result = syncService.run();
            assertThat(result.failed()).isTrue();
            assertThat(html(get("/office/vetting"))).contains("Last sync failed at", "may be out of date");
            assertThat(html(get("/office/vetting/{id}/rightship", alder.getId()))).contains("Last sync failed at");
            mockMvc.perform(post("/office/vetting/{id}/rightship/refresh", alder.getId()).with(csrf()))
                    .andExpect(redirectedUrl("/office/vetting/" + alder.getId() + "/rightship"))
                    .andExpect(flash().attribute("errorMessage", "vetting.refresh.failed"));

            // The next run that works clears it.
            rightShip.failure = null;
            assertThat(syncService.run().failed()).isFalse();
            assertThat(html(get("/office/vetting"))).doesNotContain("Last sync failed at");
        }

        @Test
        void refreshFromRightShipUpdatesOneVessel() throws Exception {
            Ship alder = vessel("ALDER");
            alder(alder);
            String refresh = "/office/vetting/" + alder.getId() + "/rightship/refresh";

            mockMvc.perform(post(refresh).with(csrf()))
                    .andExpect(flash().attribute("errorMessage", "vetting.refresh.notFound"));
            rightShip.infos.put(alder.getImoNumber(), info(alder, 4, 3));
            mockMvc.perform(post(refresh).with(csrf()))
                    .andExpect(flash().attribute("successMessage", "vetting.refresh.updated"));
            mockMvc.perform(post(refresh).with(csrf()))
                    .andExpect(flash().attribute("successMessage", "vetting.refresh.unchanged"));
            assertThat(actions()).contains("RIGHTSHIP_REFRESHED");
            assertThat(statusRepository.findByShipIdAndCurrentTrue(alder.getId()).orElseThrow().getUpdatedBy().getEmail())
                    .isEqualTo("office@example.com");
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** A RightShip that records each call and answers from what the test put in. */
    static class FakeRightShipClient implements RightShipClient {

        final List<String> calls = new ArrayList<>();
        final Map<String, VesselInfo> infos = new HashMap<>();
        List<String> updated = List.of();
        RuntimeException failure;

        void reset() {
            calls.clear();
            infos.clear();
            updated = List.of();
            failure = null;
        }

        @Override
        public List<String> updatedSince(LocalDate sinceDate) {
            calls.add("updates " + sinceDate);
            if (failure != null) {
                throw failure;
            }
            return updated;
        }

        @Override
        public List<VesselInfo> info(Collection<String> imoNumbers) {
            calls.add("info " + imoNumbers);
            if (failure != null) {
                throw failure;
            }
            return imoNumbers.stream().map(infos::get).filter(Objects::nonNull).toList();
        }
    }

    @TestConfiguration
    static class FakeClientConfig {

        @Bean
        @Primary
        FakeRightShipClient fakeRightShipClient() {
            return new FakeRightShipClient();
        }
    }

    private Ship vessel(String name) {
        Ship ship = new Ship();
        ship.setImoNumber(String.valueOf(9_800_000 + imo++));
        ship.setName(name);
        ship.setType(ShipType.BULK_CARRIER);
        ship.setCreatedAt(Instant.now());
        return shipRepository.save(ship);
    }

    // ALDER of the demo data: 3/5, subscores 4 2 3 4 4 5, GHG C unverified, inspection valid for 38 more days.
    // Someone on board: the Overview shows its work list once there is a vessel and crew (P25).
    private void crew(Ship ship) {
        userService.createUser("crew" + imo + "@example.com", "Crew Member", "secret123", Role.CREW_MEMBER, ship, null);
    }

    private void alder(Ship ship) {
        ShipRightShipStatus values = new ShipRightShipStatus();
        values.setSafetyScore(3);
        values.setCalculatedAt(REPORT_DATE);
        List<Integer> subscores = List.of(4, 2, 3, 4, 4, 5);
        for (int i = 0; i < Subscore.values().length; i++) {
            values.setSubscore(Subscore.values()[i], subscores.get(i));
        }
        values.setGhgRating(GhgRating.C);
        values.setGhgRatedAt(REPORT_DATE);
        values.setInspectionRequired(true);
        values.setLastInspectionOutcome(InspectionOutcome.ACCEPTABLE);
        values.setInspectionValidUntil(today.plusDays(38));
        vettingService.saveManual(ship, values, null);
    }

    private void rated(Ship ship, int score, LocalDate inspectionValidUntil) {
        ShipRightShipStatus values = new ShipRightShipStatus();
        values.setSafetyScore(score);
        values.setCalculatedAt(REPORT_DATE);
        values.setInspectionValidUntil(inspectionValidUntil);
        vettingService.saveManual(ship, values, null);
    }

    private static RightShipClient.VesselInfo info(Ship ship, int score, int doc) {
        return new RightShipClient.VesselInfo(ship.getImoNumber(), score, false, LocalDate.of(2026, 11, 1), doc,
                GhgRating.C, false, false, REPORT_DATE, true, InspectionOutcome.ACCEPTABLE,
                VettingService.today().plusDays(38), false, null, null);
    }

    private long rows(Ship ship) {
        return statusRepository.findAll().stream().filter(s -> s.getShip().getId().equals(ship.getId())).count();
    }

    private List<String> actions() {
        return auditRepository.findAll().stream().map(AuditEntry::getAction).toList();
    }

    private static List<String> names(String html) {
        return ROW_NAME.matcher(html).results().map(m -> m.group(1).strip()).toList();
    }

    private String html(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) {
            n++;
        }
        return n;
    }
}
