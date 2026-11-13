package com.nereonix.nereonet_mvp.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.alert.AlertSnooze;
import com.nereonix.nereonet_mvp.core.alert.AlertSnoozeRepository;
import com.nereonix.nereonet_mvp.core.alert.SnoozeEndReason;
import com.nereonix.nereonet_mvp.core.alert.SnoozeReason;
import com.nereonix.nereonet_mvp.core.audit.AuditEntry;
import com.nereonix.nereonet_mvp.core.audit.AuditRepository;
import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.certificate.CertificateReminderService;
import com.nereonix.nereonet_mvp.core.certificate.CertificateRepository;
import com.nereonix.nereonet_mvp.core.mail.MailService;
import com.nereonix.nereonet_mvp.core.notification.NotificationRepository;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.ship.ShipType;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserService;
import com.nereonix.nereonet_mvp.core.vetting.InspectionOutcome;
import com.nereonix.nereonet_mvp.core.vetting.ShipRightShipStatus;
import com.nereonix.nereonet_mvp.core.vetting.ShipRightShipStatusRepository;
import com.nereonix.nereonet_mvp.core.vetting.VettingService;
import com.nereonix.nereonet_mvp.dashboard.DashboardService;

/**
 * P43: snoozing alerts. Snooze from the Certificates list, the Vetting list and the Overview (one row, or several
 * reviewed on the snooze page); the counters, the Overview, the lists and the reminders while a snooze holds; Undo;
 * a snooze ending by itself when its date passes, when the item is resolved and when it gets worse; the limits
 * (reason, Other's note, an expired item for 30 days at most, until it expires). Crew pages are never touched.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class SnoozeFlowTest {

    private static final Pattern NAV_CERTIFICATES = Pattern.compile(
            "(?s)>Certificates</span>\\s*<span class=\"nav-count num\" aria-hidden=\"true\">(\\d+)</span>");
    private static final Pattern NAV_VETTING = Pattern.compile(
            "(?s)>Vetting</span>\\s*<span class=\"nav-count num\" aria-hidden=\"true\">(\\d+)</span>");
    private static final Pattern ATTENTION_ROW = Pattern.compile("<li class=\"list-group-item attention-item\">");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    @Autowired MockMvc mockMvc;
    @Autowired ShipRepository shipRepository;
    @Autowired UserService userService;
    @Autowired CertificateRepository certificateRepository;
    @Autowired AlertSnoozeRepository snoozeRepository;
    @Autowired AuditRepository auditRepository;
    @Autowired VettingService vettingService;
    @Autowired ShipRightShipStatusRepository statusRepository;
    @Autowired DashboardService dashboardService;
    @Autowired CertificateReminderService reminderService;
    @Autowired NotificationRepository notificationRepository;

    @MockitoBean
    MailService mailService;

    private LocalDate today;
    private Ship alder;
    private User jane;
    private Certificate stcw;      // expiring in 20 days (level 1)
    private Certificate medical;   // expiring in 45 days (level 2)
    private Certificate passport;  // expired 3 days ago

    @BeforeEach
    void fleet() {
        today = LocalDate.now(ZoneOffset.UTC);
        alder = ship("ALDER", "9300001");
        jane = userService.createUser("jane@example.com", "Jane Doe", "secret123", Rank.CHIEF_OFFICER.role(), alder,
                Rank.CHIEF_OFFICER);
        stcw = certificate(jane, "STCW Basic Safety Training", 20);
        medical = certificate(jane, "Medical Fitness Certificate", 45);
        passport = certificate(jane, "Passport", -3);
        rightShip(alder, 3, today.plusDays(38));
    }

    @Test
    void snoozingACertificateLowersTheCounterAndRemovesItFromTheOverviewUntilUndone() throws Exception {
        String key = "CERTIFICATE_EXPIRING:" + stcw.getId();
        String list = html(get("/office/certificates"));
        // The row has its checkbox and Snooze; the sidebar counts both certificates within 30 days.
        assertThat(list).contains("name=\"items\" value=\"" + key + "\" form=\"snoozeForm\"",
                "href=\"/office/alerts/snooze?items=" + key + "&amp;returnTo=/office/certificates\"", ">Snooze selected</span>",
                "href=\"/office/crew/" + jane.getId() + "/edit\">Jane Doe</a>");
        assertThat(navCertificates(list)).isEqualTo("2");
        assertThat(attentionRows(html(get("/office")))).isEqualTo(3); // the expired one, the two within 30 days... no: passport, stcw, and ALDER's inspection

        // The snooze page reviews the item; Other without a note is refused, as a missing reason.
        String form = html(get("/office/alerts/snooze").param("items", key).param("returnTo", "/office/certificates"));
        assertThat(form).contains("STCW Basic Safety Training · Jane Doe", "<span class=\"attention-ship\">ALDER</span>",
                        "in 20 days", "name=\"items\" value=\"" + key + "\"", "value=\"UNTIL_EXPIRY\"", ">Renewal in progress<",
                        ">Crew member signs off before expiry<", ">Not needed on this vessel<", ">Other<", "7 days", "30 days")
                .contains("<input type=\"hidden\" name=\"returnTo\" value=\"/office/certificates\">");
        String refused = html(post("/office/alerts/snooze").param("items", key).param("returnTo", "/office/certificates")
                .param("reason", "OTHER").param("duration", "DAYS_30").with(csrf()));
        assertThat(refused).contains("Say what the reason is", "is-invalid");
        assertThat(snoozeRepository.findAll()).isEmpty();
        assertThat(html(post("/office/alerts/snooze").param("items", key).param("returnTo", "/office/certificates")
                .param("duration", "DAYS_30").with(csrf()))).contains("Choose a reason.");

        mockMvc.perform(post("/office/alerts/snooze").param("items", key).param("returnTo", "/office/certificates")
                        .param("reason", "RENEWAL_IN_PROGRESS").param("duration", "DAYS_30").with(csrf()))
                .andExpect(redirectedUrl("/office/certificates"))
                .andExpect(flash().attribute("successMessage", "snooze.done"));
        AlertSnooze snooze = snoozeRepository.findAll().getFirst();
        assertThat(snooze.getUntil()).isEqualTo(today.plusDays(30));
        assertThat(snooze.getLevel()).isEqualTo(1);
        assertThat(snooze.getReason()).isEqualTo(SnoozeReason.RENEWAL_IN_PROGRESS);
        assertThat(snooze.getShip().getId()).isEqualTo(alder.getId());
        assertThat(snooze.getSnoozedBy().getEmail()).isEqualTo("office@example.com");
        assertThat(audit("ALERT_SNOOZED")).singleElement().asString()
                .contains("certificate expiring: STCW Basic Safety Training of jane@example.com on ALDER", "until " + today.plusDays(30),
                        "renewal in progress");

        // Left out of the counter, the Overview (tiles, Needs attention, By category, Next 30 days) and the default list.
        String overview = html(get("/office"));
        assertThat(navCertificates(overview)).isEqualTo("1");
        assertThat(overview).doesNotContain("STCW Basic Safety Training").contains("1 snoozed");
        assertThat(attentionRows(overview)).isEqualTo(2);
        String defaultList = html(get("/office/certificates"));
        assertThat(defaultList).doesNotContain("STCW Basic Safety Training");
        // Status "Snoozed" lists it, muted, with the snooze and Undo; "All" has it among the others.
        String snoozed = html(get("/office/certificates").param("status", "snoozed"));
        assertThat(snoozed).contains("class=\"is-snoozed\"", "STCW Basic Safety Training",
                        "Snoozed by John Doe until " + DAY.format(today.plusDays(30)), "Renewal in progress",
                        "action=\"/office/alerts/snooze/" + snooze.getId() + "/undo\"", ">Undo</button>",
                        "<option value=\"snoozed\" selected=\"selected\">Snoozed</option>")
                .doesNotContain("Medical Fitness Certificate", "href=\"/office/alerts/snooze?items=" + key);
        assertThat(html(get("/office/certificates").param("status", "all"))).contains("STCW Basic Safety Training",
                "Medical Fitness Certificate", "class=\"is-snoozed\"");
        // The crew member's own page is as before.
        assertThat(html(get("/ship/profile").with(user(new CurrentUser(jane))))).contains("STCW Basic Safety Training")
                .doesNotContain("Snoozed", "snooze");

        // The daily reminder skips it; Medical (45 days) gets its reminder.
        reminderService.sendDueReminders();
        assertThat(notificationRepository.findAllByUserEmailOrderByCreatedAtDescIdDesc(jane.getEmail()))
                .extracting(n -> n.getText()).allMatch(t -> t.contains("Medical Fitness"));

        // Undo brings it back at once.
        mockMvc.perform(post("/office/alerts/snooze/{id}/undo", snooze.getId()).param("returnTo", "/office/certificates?status=snoozed")
                        .with(csrf()))
                .andExpect(redirectedUrl("/office/certificates?status=snoozed"))
                .andExpect(flash().attribute("successMessage", "snooze.undone"));
        assertThat(snoozeRepository.findById(snooze.getId()).orElseThrow().getEndReason()).isEqualTo(SnoozeEndReason.UNDONE);
        assertThat(audit("ALERT_SNOOZE_UNDONE")).hasSize(1);
        assertThat(navCertificates(html(get("/office/certificates")))).isEqualTo("2");
        assertThat(html(get("/office"))).contains("STCW Basic Safety Training");
        // Undoing it again is not found.
        mockMvc.perform(post("/office/alerts/snooze/{id}/undo", snooze.getId()).with(csrf())).andExpect(status().isNotFound());
    }

    @Test
    void severalRowsAreReviewedOnTheSnoozePageAndSnoozedTogether() throws Exception {
        String stcwKey = "CERTIFICATE_EXPIRING:" + stcw.getId();
        String passportKey = "CERTIFICATE_EXPIRED:" + passport.getId();
        Certificate far = certificate(jane, "Yellow Fever", 400); // not an alert: left out
        String page = html(get("/office/alerts/snooze").param("items", stcwKey).param("items", passportKey)
                .param("items", "CERTIFICATE_EXPIRING:" + far.getId()).param("items", "nonsense"));
        assertThat(page).contains("2 items", "type=\"checkbox\" id=\"item0\"", "type=\"checkbox\" id=\"item1\"",
                        "STCW Basic Safety Training · Jane Doe", "Passport · Jane Doe", "expired 3 days ago",
                        "30 days (the most for an expired item)")
                .doesNotContain("Yellow Fever", "value=\"UNTIL_EXPIRY\"");

        // With an expired item, 30 days at most: a later date is refused; so is Until it expires.
        assertThat(html(post("/office/alerts/snooze").param("items", stcwKey).param("items", passportKey)
                .param("reason", "NOT_NEEDED_ON_VESSEL").param("duration", "DATE")
                .param("untilDate", today.plusDays(40).toString()).with(csrf())))
                .contains("An expired item can be snoozed for 30 days at most.");
        assertThat(html(post("/office/alerts/snooze").param("items", stcwKey).param("items", passportKey)
                .param("reason", "NOT_NEEDED_ON_VESSEL").param("duration", "UNTIL_EXPIRY").with(csrf())))
                .contains("An expired item can be snoozed for 30 days at most.");
        assertThat(html(post("/office/alerts/snooze").param("items", stcwKey)
                .param("reason", "NOT_NEEDED_ON_VESSEL").param("duration", "DATE")
                .param("untilDate", today.toString()).with(csrf()))).contains("Enter a date after today.");
        assertThat(snoozeRepository.findAll()).isEmpty();

        mockMvc.perform(post("/office/alerts/snooze").param("items", stcwKey).param("items", passportKey)
                        .param("reason", "OTHER").param("note", "Waiting for the agent").param("duration", "DATE")
                        .param("untilDate", today.plusDays(10).toString()).param("returnTo", "/office").with(csrf()))
                .andExpect(redirectedUrl("/office"))
                .andExpect(flash().attribute("successArgs", new Object[] {2}));
        assertThat(snoozeRepository.findAll()).hasSize(2)
                .allSatisfy(s -> {
                    assertThat(s.getUntil()).isEqualTo(today.plusDays(10));
                    assertThat(s.getNote()).isEqualTo("Waiting for the agent");
                });
        assertThat(html(get("/office/certificates").param("status", "snoozed"))).contains("Other: Waiting for the agent");

        // Until it expires: each item until its own date.
        snoozeRepository.deleteAll();
        String medicalKey = "CERTIFICATE_EXPIRING:" + medical.getId();
        mockMvc.perform(post("/office/alerts/snooze").param("items", stcwKey).param("items", medicalKey)
                        .param("reason", "SIGNS_OFF_BEFORE_EXPIRY").param("duration", "UNTIL_EXPIRY").with(csrf()))
                .andExpect(redirectedUrl("/office"));
        assertThat(snoozeRepository.findAll()).extracting(AlertSnooze::getUntil)
                .containsExactlyInAnyOrder(today.plusDays(20), today.plusDays(45));
        // A link to nowhere: the Overview, never a URL outside the app.
        mockMvc.perform(get("/office/alerts/snooze").param("items", "CERTIFICATE_EXPIRING:999999")
                        .param("returnTo", "https://example.org/"))
                .andExpect(redirectedUrl("/office"))
                .andExpect(flash().attribute("errorMessage", "snooze.error.nothing"));
    }

    @Test
    void snoozingAldersInspectionLowersTheVettingCounterAndTheSnoozeEndsWhenItExpires() throws Exception {
        String key = "VETTING_INSPECTION:" + alder.getId();
        String vetting = html(get("/office/vetting"));
        assertThat(navVetting(vetting)).isEqualTo("1");
        assertThat(vetting).contains("name=\"items\" value=\"" + key + "\" form=\"snoozeForm\"",
                "href=\"/office/alerts/snooze?items=" + key + "&amp;returnTo=/office/vetting\"", "Expires in 38 days");
        assertThat(html(get("/office"))).contains("RightShip inspection", "Expires in 38 days");

        mockMvc.perform(post("/office/alerts/snooze").param("items", key).param("returnTo", "/office/vetting")
                        .param("reason", "RENEWAL_IN_PROGRESS").param("duration", "DAYS_30").with(csrf()))
                .andExpect(redirectedUrl("/office/vetting"));
        AlertSnooze snooze = snoozeRepository.findAll().getFirst();
        assertThat(snooze.getLevel()).isEqualTo(2); // 38 days: within 60

        String overview = html(get("/office"));
        assertThat(navVetting(overview)).isNull();
        assertThat(overview).doesNotContain("RightShip inspection");
        String list = html(get("/office/vetting"));
        assertThat(list).contains("class=\"is-snoozed\"", "Snoozed by John Doe until " + DAY.format(today.plusDays(30)),
                        "Renewal in progress", "action=\"/office/alerts/snooze/" + snooze.getId() + "/undo\"", ">Undo</button>")
                .doesNotContain("href=\"/office/alerts/snooze?items=" + key);
        assertThat(names(html(get("/office/vetting").param("status", "snoozed")))).containsExactly("ALDER");
        assertThat(names(html(get("/office/vetting").param("status", "attention")))).isEmpty();
        assertThat(html(get("/office/vetting"))).contains("<option value=\"snoozed\">Snoozed</option>");

        // Undo brings it back at once.
        mockMvc.perform(post("/office/alerts/snooze/{id}/undo", snooze.getId()).param("returnTo", "/office/vetting").with(csrf()))
                .andExpect(redirectedUrl("/office/vetting"));
        assertThat(navVetting(html(get("/office/vetting")))).isEqualTo("1");
        assertThat(html(get("/office"))).contains("RightShip inspection");

        // Snoozed until it expires: once the valid-until date has passed the item is expired, worse than when it was
        // snoozed, so the snooze ends by itself and the item returns as expired.
        mockMvc.perform(post("/office/alerts/snooze").param("items", key).param("returnTo", "/office/vetting")
                        .param("reason", "RENEWAL_IN_PROGRESS").param("duration", "UNTIL_EXPIRY").with(csrf()))
                .andExpect(redirectedUrl("/office/vetting"));
        AlertSnooze untilExpiry = snoozeRepository.findAll().stream().filter(AlertSnooze::active).findFirst().orElseThrow();
        assertThat(untilExpiry.getUntil()).isEqualTo(today.plusDays(38));
        assertThat(navVetting(html(get("/office/vetting")))).isNull();
        ShipRightShipStatus current = statusRepository.findByShipIdAndCurrentTrue(alder.getId()).orElseThrow();
        current.setInspectionValidUntil(today.minusDays(1));
        statusRepository.saveAndFlush(current);
        assertThat(navVetting(html(get("/office/vetting")))).isEqualTo("1"); // the counter follows at once
        String expired = html(get("/office"));
        assertThat(expired).contains("RightShip inspection", ">Expired</span>");
        assertThat(snoozeRepository.findById(untilExpiry.getId()).orElseThrow().getEndReason()).isEqualTo(SnoozeEndReason.WORSENED);
        assertThat(audit("ALERT_SNOOZE_ENDED")).singleElement().asString().contains("worsened");
    }

    @Test
    void aSnoozeEndsByItselfWhenItsDatePassesWhenResolvedOrWhenItGetsWorse() throws Exception {
        String medicalKey = "CERTIFICATE_EXPIRING:" + medical.getId();
        String stcwKey = "CERTIFICATE_EXPIRING:" + stcw.getId();
        String passportKey = "CERTIFICATE_EXPIRED:" + passport.getId();
        mockMvc.perform(post("/office/alerts/snooze").param("items", medicalKey).param("items", stcwKey).param("items", passportKey)
                        .param("reason", "RENEWAL_IN_PROGRESS").param("duration", "DAYS_30").with(csrf()))
                .andExpect(redirectedUrl("/office"));
        List<AlertSnooze> snoozes = snoozeRepository.findAll();
        assertThat(snoozes).hasSize(3);
        AlertSnooze ofMedical = snoozes.stream().filter(s -> s.getSubjectId() == medical.getId()).findFirst().orElseThrow();
        AlertSnooze ofStcw = snoozes.stream().filter(s -> s.getSubjectId() == stcw.getId()).findFirst().orElseThrow();
        AlertSnooze ofPassport = snoozes.stream().filter(s -> s.getSubjectId() == passport.getId()).findFirst().orElseThrow();
        assertThat(ofMedical.getLevel()).isEqualTo(2);
        assertThat(ofPassport.getLevel()).isEqualTo(0);
        assertThat(navCertificates(html(get("/office")))).isNull();

        // Worse: Medical crosses the 30-day threshold (the office corrected its date).
        medical.setExpiresOn(today.plusDays(25));
        // Resolved: a new STCW, well ahead.
        stcw.setExpiresOn(today.plusDays(500));
        // The date passes: the passport's snooze was until yesterday.
        ofPassport.setUntil(today.minusDays(1));
        certificateRepository.flush();
        snoozeRepository.flush();

        String overview = html(get("/office"));
        assertThat(overview).contains("Medical Fitness Certificate", "Passport").doesNotContain("STCW Basic Safety Training");
        assertThat(navCertificates(overview)).isEqualTo("2");
        assertThat(snoozeRepository.findById(ofMedical.getId()).orElseThrow().getEndReason()).isEqualTo(SnoozeEndReason.WORSENED);
        assertThat(snoozeRepository.findById(ofStcw.getId()).orElseThrow().getEndReason()).isEqualTo(SnoozeEndReason.RESOLVED);
        assertThat(snoozeRepository.findById(ofPassport.getId()).orElseThrow().getEndReason()).isEqualTo(SnoozeEndReason.DATE_PASSED);
        assertThat(audit("ALERT_SNOOZE_ENDED")).hasSize(3)
                .anySatisfy(d -> assertThat(d).contains("worsened (level 2 to 1)"))
                .anySatisfy(d -> assertThat(d).contains("resolved"))
                .anySatisfy(d -> assertThat(d).contains("date passed"));
        // Ended snoozes stay ended: nothing changes on the next load.
        html(get("/office"));
        assertThat(audit("ALERT_SNOOZE_ENDED")).hasSize(3);
    }

    @Test
    void theOverviewOffersSnoozeOnItsRowsAndSnoozeSelected() throws Exception {
        String overview = html(get("/office"));
        String stcwKey = "CERTIFICATE_EXPIRING:" + stcw.getId();
        assertThat(overview).contains("name=\"items\" value=\"" + stcwKey + "\" form=\"snoozeForm\"",
                        "name=\"items\" value=\"VETTING_INSPECTION:" + alder.getId() + "\" form=\"snoozeForm\"",
                        "href=\"/office/alerts/snooze?items=" + stcwKey + "&amp;returnTo=/office\"", ">Snooze selected</span>",
                        "<form id=\"snoozeForm\" action=\"/office/alerts/snooze\" method=\"get\" data-selection");
        // In Greek too.
        assertThat(html(get("/office").param("lang", "el"))).contains("Αναβολή επιλεγμένων");
        assertThat(html(get("/office/alerts/snooze").param("items", stcwKey).param("lang", "el")))
                .contains("Ανανέωση σε εξέλιξη", "Έως τη λήξη του", ">Αναβολή</button>");
        // The audit log names the actions.
        mockMvc.perform(post("/office/alerts/snooze").param("items", stcwKey).param("reason", "RENEWAL_IN_PROGRESS")
                .param("duration", "DAYS_7").with(csrf())).andExpect(status().is3xxRedirection());
        assertThat(html(get("/office/audit"))).contains("Alert snoozed");
    }

    // ---------------------------------------------------------------------------------------------------------------

    private String html(RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String navCertificates(String html) {
        Matcher m = NAV_CERTIFICATES.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private static String navVetting(String html) {
        Matcher m = NAV_VETTING.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private static long attentionRows(String html) {
        return ATTENTION_ROW.matcher(html).results().count();
    }

    private static List<String> names(String html) {
        return Pattern.compile("(?s)<td class=\"cell-primary\">\\s*<a [^>]*>([^<]*)</a>").matcher(html).results()
                .map(m -> m.group(1)).toList();
    }

    private List<String> audit(String action) {
        return auditRepository.findAll().stream().filter(e -> e.getAction().equals(action))
                .map(AuditEntry::getDetails).toList();
    }

    private Ship ship(String name, String imo) {
        Ship ship = new Ship();
        ship.setImoNumber(imo);
        ship.setName(name);
        ship.setType(ShipType.BULK_CARRIER);
        ship.setCreatedAt(Instant.now());
        return shipRepository.save(ship);
    }

    private Certificate certificate(User user, String type, long daysLeft) {
        Certificate c = new Certificate();
        c.setUser(user);
        c.setType(type);
        c.setExpiresOn(today.plusDays(daysLeft));
        c.setCreatedAt(Instant.now());
        return certificateRepository.save(c);
    }

    private void rightShip(Ship ship, int score, LocalDate validUntil) {
        ShipRightShipStatus values = new ShipRightShipStatus();
        values.setSafetyScore(score);
        values.setCalculatedAt(LocalDate.of(2026, 10, 21));
        values.setInspectionRequired(true);
        values.setLastInspectionOutcome(InspectionOutcome.ACCEPTABLE);
        values.setInspectionValidUntil(validUntil);
        vettingService.saveManual(ship, values, null);
        Mockito.reset(mailService);
    }
}
