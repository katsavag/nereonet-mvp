package com.nereonix.nereonet_mvp.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.announcement.Announcement;
import com.nereonix.nereonet_mvp.core.announcement.AnnouncementRecipient;
import com.nereonix.nereonet_mvp.core.announcement.AnnouncementRecipientRepository;
import com.nereonix.nereonet_mvp.core.announcement.AnnouncementRepository;
import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.certificate.CertificateRepository;
import com.nereonix.nereonet_mvp.core.config.DevData;
import com.nereonix.nereonet_mvp.core.invitation.Invitation;
import com.nereonix.nereonet_mvp.core.invitation.InvitationRepository;
import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemCategory;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.library.LibraryAcknowledgement;
import com.nereonix.nereonet_mvp.core.library.LibraryAcknowledgementRepository;
import com.nereonix.nereonet_mvp.core.library.LibraryCategory;
import com.nereonix.nereonet_mvp.core.library.LibraryItem;
import com.nereonix.nereonet_mvp.core.library.LibraryItemRepository;
import com.nereonix.nereonet_mvp.core.library.LibraryItemVersion;
import com.nereonix.nereonet_mvp.core.library.LibraryItemVersionRepository;
import com.nereonix.nereonet_mvp.core.profile.CrewProfileRepository;
import com.nereonix.nereonet_mvp.core.mail.MailService;
import com.nereonix.nereonet_mvp.core.order.ShipOrder;
import com.nereonix.nereonet_mvp.core.order.ShipOrderLine;
import com.nereonix.nereonet_mvp.core.order.ShipOrderLineRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrderRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrderStatus;
import com.nereonix.nereonet_mvp.core.request.ItemRequestRepository;
import com.nereonix.nereonet_mvp.core.request.RequestService;
import com.nereonix.nereonet_mvp.core.vetting.SireService;
import com.nereonix.nereonet_mvp.core.vetting.VettingService;
import com.nereonix.nereonet_mvp.core.request.RequestStatus;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;
import com.nereonix.nereonet_mvp.core.user.UserService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

// The office Overview (P09, P43): the stat tiles, the "Needs attention" work list (paged, filter chips, sortable by
// date), the By category, Next 30 days, Fleet and Recent activity cards, and a fixed number of queries.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class DashboardFlowTest {

    private static final Pattern ATTENTION_LIST =
            Pattern.compile("(?s)<ul class=\"list-group list-group-flush attention-list\">(.*?)</ul>\\s*(?:<nav|</section>)");
    private static final Pattern ATTENTION_ROW = Pattern.compile("(?s)<li class=\"list-group-item attention-item\">");
    private static final Pattern FLEET_TABLE = Pattern.compile("(?s)<table class=\"table data-table fleet-table[^\"]*\">(.*?)</table>");
    private static final Pattern MAIN = Pattern.compile("(?s)<main class=\"overview\">(.*)</main>");
    private static final Pattern HREF = Pattern.compile("href=\"([^\"#]*)");
    private static final Pattern TILE = Pattern.compile("(?s)<li class=\"overview-tile\">.*?<span class=\"overview-tile-number num\">(\\d+)</span>"
            + "\\s*<span class=\"overview-tile-label\">([^<]*)</span>\\s*<span class=\"overview-tile-detail\">([^<]*)</span>");

    @Autowired MockMvc mockMvc;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired DashboardService dashboardService;
    @Autowired ShipRepository shipRepository;
    @Autowired UserRepository userRepository;
    @Autowired UserService userService;
    @Autowired CertificateRepository certificateRepository;
    @Autowired ItemRequestRepository requestRepository;
    @Autowired ShipOrderRepository orderRepository;
    @Autowired ShipOrderLineRepository orderLineRepository;
    @Autowired ItemRepository itemRepository;
    @Autowired InvitationRepository invitationRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired AnnouncementRecipientRepository recipientRepository;
    @Autowired LibraryItemRepository libraryItemRepository;
    @Autowired LibraryItemVersionRepository versionRepository;
    @Autowired LibraryAcknowledgementRepository acknowledgementRepository;
    @Autowired CrewProfileRepository profileRepository;
    @Autowired RequestService requestService;
    @Autowired VettingService vettingService;
    @Autowired SireService sireService;

    @MockitoBean
    MailService mailService;

    private int shipCount;

    @Test
    void withNothingOpenTheCardSaysSoWithTheTimeOfTheLastCheck() throws Exception {
        Ship ship = ship("MV Calm");
        crew(ship, "Anna Able", Rank.ABLE_SEAMAN);
        certificate(crew(ship, "Bob Bosun", Rank.BOSUN), "Medical fitness", 45); // not within 30 days

        String html = html(get("/office"));

        assertThat(html).contains("Nothing needs your attention", "Last checked", "class=\"local-time")
                .doesNotContain("attention-list", "attention-footer", "Showing the first");
        assertThat(html).contains("<span class=\"visually-hidden\">Open items:</span>")
                .containsPattern("(?s)attention-total.*?<span class=\"num\">0</span>");
        // P43: the four tiles instead of a summary line; each a link with a detail line.
        assertThat(tiles(html)).containsExactly(
                "1 Active vessels none with open items",
                "2 Crew on board no invitations waiting",
                "0 Items needing attention none snoozed",
                "0 Orders waiting for the office none to export");
        assertThat(html).contains("<a class=\"overview-tile-link\" href=\"/office/ships\">",
                "<a class=\"overview-tile-link\" href=\"/office/crew?status=active\">",
                "<a class=\"overview-tile-link\" href=\"#attention\">",
                "<a class=\"overview-tile-link\" href=\"/office/orders?status=sent\">");
        // Every card has its empty state (P25); By category says all clear.
        assertThat(html).contains("Nothing due in the next 30 days", "Nothing recorded yet", "All clear",
                "id=\"fleetTitle\"", "MV Calm");
    }

    @Test
    void everyKindOfOpenItemIsListedMostUrgentFirstWithItsAction() throws Exception {
        Ship ship = ship("MV Alpha");
        User bosun = crew(ship, "Bob Bosun", Rank.BOSUN);
        User cook = crew(ship, "Chris Cook", Rank.COOK);
        User cadet = crew(ship, "Dana Cadet", Rank.DECK_CADET);

        // e. acknowledgements (created first: the order on the card must not follow creation order)
        Announcement drill = announcement("Fire drill on Friday", null, days(-8), bosun, cook);
        acknowledge(drill, bosun);
        Announcement allRead = announcement("All read", ship, days(-9), bosun);
        acknowledge(allRead, bosun);
        announcement("Too new to chase", ship, days(-2), bosun);
        LibraryItem policy = libraryItem(ship, "Drug and alcohol policy", true, days(-10));
        acknowledge(policy, bosun);
        libraryItem(ship, "Too new policy", true, days(-1));
        libraryItem(ship, "Optional reading", false, days(-30));
        // d. invitations
        Invitation expired = invitation(ship, "Eve Expired", Rank.OILER, days(-1));
        Invitation waiting = invitation(ship, "Wes Waiting", null, days(4));     // sent 3 days ago (valid 7 days)
        invitation(ship, "Fay Fresh", null, days(7).minus(Duration.ofHours(1))); // sent an hour ago
        // c. orders (P36)
        ShipOrder order = order(ship, bosun, days(-1), null);
        order(ship, bosun, days(-2), days(-1)); // exported
        // a. and b. certificates
        Certificate soon = certificate(cadet, "STCW Basic Safety", 12);
        Certificate gone = certificate(cook, "Medical fitness", -3);
        certificate(cook, "Food hygiene", 60);

        String html = html(get("/office"));
        String list = attentionList(html);

        assertThat(ATTENTION_ROW.matcher(list).results().count()).isEqualTo(7);
        assertThat(list).containsSubsequence("Medical fitness", "STCW Basic Safety", "Order #" + order.getId(),
                "Eve Expired", "Wes Waiting", "Fire drill on Friday", "Drug and alcohol policy");
        assertThat(list).doesNotContain("Food hygiene", "Fay Fresh", "All read", "Too new", "Optional reading");
        assertThat(tiles(html)).containsExactly(
                "1 Active vessels 1 with open items",
                "3 Crew on board 2 invitations waiting",
                "7 Items needing attention none snoozed",
                "2 Orders waiting for the office 1 not exported yet");
        assertThat(html).containsPattern("(?s)attention-total.*?<span class=\"num\">7</span>");
        // The filter chips (P43), each with its count, links that work without JavaScript.
        assertThat(html).contains("<a class=\"chip is-current\" href=\"/office\" aria-current=\"true\">",
                        "href=\"/office?type=certificates\"", "href=\"/office?type=documents\"",
                        "href=\"/office?type=vetting\"", "href=\"/office?type=orders\"",
                        "href=\"/office?type=invitations\"", "href=\"/office?type=acknowledgements\"")
                .containsPattern("(?s)<span>Certificates</span>\\s*<span class=\"chip-count num\">2</span>")
                .containsPattern("(?s)<span>Invitations</span>\\s*<span class=\"chip-count num\">2</span>")
                .containsPattern("(?s)<span>Announcements</span>\\s*<span class=\"chip-count num\">2</span>")
                .containsPattern("(?s)<span>Vetting</span>\\s*<span class=\"chip-count num\">0</span>");
        // By category: each kind with its open count and the link to its list; Next 30 days: the expiring one.
        assertThat(html).containsPattern("(?s)<a class=\"category-tile\" href=\"/office/orders\\?exported=no\">.*?<span>1 open</span>")
                .containsPattern("(?s)<a class=\"category-tile\" href=\"/office/vetting\\?status=attention\">.*?<span>All clear</span>")
                .contains("STCW Basic Safety expires", "href=\"/office?sort=date\"");
        // Recent activity: the newest audit entries with the time.
        assertThat(html).contains("id=\"recentTitle\"", "href=\"/office/audit\"", "Nothing recorded yet"); // nothing here went through the audit log

        // a. expired certificate
        String certificateUrl = "/office/crew/" + cook.getId() + "/certificates/" + gone.getId() + "/edit";
        assertThat(list).contains(">Expired</span>", "href=\"" + certificateUrl + "?returnTo=/office\"", ">Open certificate</a>",
                "Chris Cook, Cook", ">" + date(-3) + "</time>", "expired 3 days ago");
        // b. expiring certificate
        assertThat(list).contains(">Expires in 12 days</span>", "Dana Cadet, Deck Cadet", ">" + date(12) + "</time>",
                "</span> in 12 days</span>",
                "href=\"/office/crew/" + cadet.getId() + "/certificates/" + soon.getId() + "/edit?returnTo=/office\"");
        // c. order sent, not exported: Open order, where it is confirmed and exported
        assertThat(list).contains(">Not exported</span>", ">Vessel order</span>",
                "href=\"/office/orders/" + order.getId() + "\">Order #" + order.getId() + " · 1 item</a>",
                "Bob Bosun, Bosun", ">Open order</a>");
        // d. invitations: Resend, back to the Overview
        assertThat(list).contains(">Not accepted</span>", "Oiler", "expired 1 day ago", "expires in 4 days",
                "action=\"/office/invitations/" + expired.getId() + "/resend\"",
                "action=\"/office/invitations/" + waiting.getId() + "/resend\"",
                "name=\"returnTo\" value=\"overview\"", ">Resend invitation</button>");
        // e. announcement and library item
        assertThat(list).contains("href=\"/office/announcements/" + drill.getId() + "#pending\"", "All vessels",
                "1 of 2 not acknowledged",
                "href=\"/office/ships/" + ship.getId() + "/library/" + policy.getId() + "/acknowledgements\"",
                "2 of 3 not acknowledged", "version 1 published", ">View who has not acknowledged</a>");
        // Every row: the badge has an icon; the action is a secondary button named by the row's title.
        assertThat(list).contains("status-badge-icon", "<svg viewBox=\"0 0 24 24\"")
                .contains("aria-describedby=\"attention0\"", "id=\"attention0\"")
                .doesNotContain("btn-primary");
        // The full list of each kind (the By category card and the chips).
        assertThat(html).contains("href=\"/office/certificates?status=expired,30&amp;activeShips=true\"", "href=\"/office/orders?exported=no\"",
                "href=\"/office?type=invitations\"", "href=\"/office?type=acknowledgements\"");
    }

    // P28: an announcement to several ships is one item as before (counted per recipient); its context names the
    // target as "2 ships" (one ship by its name).
    @Test
    void anAnnouncementToSelectedShipsIsOneItem() throws Exception {
        Ship alpha = ship("MV Alpha");
        Ship beta = ship("MV Beta");
        User bosun = crew(alpha, "Bob Bosun", Rank.BOSUN);
        User cook = crew(beta, "Chris Cook", Rank.COOK);
        Announcement both = announcement("Port state control", alpha, days(-8), bosun, cook);
        both.getTargetShips().add(beta);
        announcementRepository.saveAndFlush(both);
        Announcement one = announcement("Bunkering", beta, days(-9), cook);

        String list = attentionList(html(get("/office")));
        assertThat(ATTENTION_ROW.matcher(list).results().count()).isEqualTo(2);
        assertThat(list).containsPattern("(?s)href=\"/office/announcements/" + both.getId() + "\">Port state control</a>"
                        + "\\s*<p class=\"attention-context\">\\s*<span class=\"attention-ship\">2 vessels</span>"
                        + "\\s*· <span>2 of 2 not acknowledged</span>")
                .containsPattern("(?s)href=\"/office/announcements/" + one.getId() + "\">Bunkering</a>"
                        + "\\s*<p class=\"attention-context\">\\s*<span class=\"attention-ship\">MV Beta</span>");
    }

    // P40: the dev data adds, once, three bulk carriers with RightShip data and each its sample report; ALDER's
    // inspection expires in 38 days, so the sidebar counts it and the Overview lists it. P41: and the tanker BIRCH
    // with its SIRE 2.0 inspection, whose overdue observation makes the sidebar show Vetting 2 (see SireFlowTest).
    @Test
    void theDevDataSeedsThreeRightShipVesselsOnce() throws Exception {
        devData().run(null);
        devData().run(null);

        assertThat(shipRepository.findAllByOrderByNameAsc()).extracting(Ship::getName)
                .containsExactly("ALDER", "BIRCH", "LAUREL", "MV Demo", "ROWAN");
        Ship alder = shipRepository.findByImoNumber("9300001").orElseThrow();
        assertThat(vettingService.view(alder).score()).isEqualTo(3);
        assertThat(vettingService.view(alder).inspectionDaysLeft()).isEqualTo(38);
        assertThat(vettingService.view(shipRepository.findByImoNumber("9400007").orElseThrow()).ghgLetter()).isEqualTo("A+");
        assertThat(vettingService.reports(alder.getId())).singleElement()
                .satisfies(r -> assertThat(r.getFileName()).isEqualTo("alder-3-of-5.png"));
        assertThat(vettingService.view(shipRepository.findByImoNumber("9074729").orElseThrow()).hasData()).isFalse();

        String html = html(get("/office"));
        assertThat(html).containsPattern("(?s)>Vetting</span>\\s*<span class=\"nav-count num\" aria-hidden=\"true\">2</span>");
        assertThat(attentionList(html)).contains("RightShip inspection", "ALDER", "Expires in 38 days");
    }

    // P36: the office works on the orders the ships send, no longer on single approved requests.
    @Test
    void aSentOrderIsOpenUntilExportedAndAnOpenOrApprovedRequestNever() throws Exception {
        // The dev data seeds only requests waiting for the Master: nothing for the office yet.
        devData().run(null);
        Ship seeded = shipRepository.findByImoNumber("9074729").orElseThrow();
        assertThat(requestRepository.countByStatusAndShipId(RequestStatus.APPROVED, seeded.getId())).isZero();
        assertThat(requestRepository.countByStatusAndShipId(RequestStatus.PENDING, seeded.getId())).isEqualTo(2);
        assertThat(html(get("/office"))).doesNotContain(">Vessel order</span>");

        Ship ship = ship("MV Alpha");
        User master = crew(ship, "Mia Master", Rank.MASTER);
        ShipOrder open = order(ship, master, null, null); // still on board
        ShipOrder sent = order(ship, master, days(-1), null);

        String html = html(get("/office"));
        assertThat(count(attentionList(html), ">Vessel order</span>")).isEqualTo(1);
        assertThat(attentionList(html)).contains("Order #" + sent.getId()).doesNotContain("Order #" + open.getId());
        // The Fleet card (P43): the ship's open items link to the Overview filtered to it; the Orders tile counts the
        // order sent and not confirmed.
        assertThat(group(FLEET_TABLE, html)).contains("class=\"count-link\" href=\"/office?shipId=" + ship.getId() + "\">1</a>");
        assertThat(tiles(html)).contains("1 Orders waiting for the office 1 not exported yet");

        mockMvc.perform(post("/office/orders/export").param("ids", sent.getId().toString()).with(csrf()))
                .andExpect(status().isOk());

        String after = html(get("/office"));
        assertThat(after).doesNotContain(">Vessel order</span>");
        assertThat(tiles(after)).contains("1 Orders waiting for the office none to export");
    }

    @Test
    void resendingAnInvitationReturnsToTheOverviewAndClearsTheRow() throws Exception {
        Ship ship = ship("MV Alpha");
        Invitation invitation = invitation(ship, "Eve Expired", Rank.OILER, days(-1));
        assertThat(attentionList(html(get("/office")))).contains("Eve Expired");

        mockMvc.perform(post("/office/invitations/{id}/resend", invitation.getId())
                        .param("returnTo", "overview").with(csrf()))
                .andExpect(redirectedUrl("/office"))
                .andExpect(flash().attribute("successMessage", "invitation.resent"));

        assertThat(html(get("/office"))).contains("Nothing needs your attention").doesNotContain("Eve Expired");
        // Without returnTo it still goes back to Office users.
        mockMvc.perform(post("/office/invitations/{id}/resend", invitation.getId()).with(csrf()))
                .andExpect(redirectedUrl("/office/users"));
    }

    // P43: 10 a page with the P19 pager; a chip filters to one kind, the vessel filter to one ship.
    @Test
    void theListIsPagedTenAtATimeAndTheChipsFilterIt() throws Exception {
        Ship ship = ship("MV Alpha");
        User bosun = crew(ship, "Bob Bosun", Rank.BOSUN);
        for (int i = 0; i < 51; i++) {
            certificate(bosun, "Certificate " + i, -1 - i);
        }
        invitation(ship, "Eve Expired", null, days(-1));

        String html = html(get("/office"));
        assertThat(ATTENTION_ROW.matcher(attentionList(html)).results().count()).isEqualTo(10);
        assertThat(html).contains("1 to 10 of 52", "href=\"/office?page=2\"", "href=\"/office?size=20\"")
                .containsPattern("(?s)attention-total.*?<span class=\"num\">52</span>")
                .doesNotContain("Eve Expired", "Showing the first"); // the least urgent, on the last page
        assertThat(tiles(html)).contains("52 Items needing attention none snoozed");
        // The most overdue first.
        assertThat(attentionList(html)).containsSubsequence("Certificate 50", "Certificate 49", "Certificate 41<")
                .doesNotContain("Certificate 40<");
        String last = html(get("/office").param("page", "6"));
        assertThat(ATTENTION_ROW.matcher(attentionList(last)).results().count()).isEqualTo(2);
        assertThat(last).contains("51 to 52 of 52", "Eve Expired", "Certificate 0<");
        // The actions and the certificate form come back to the same page.
        assertThat(last).contains("returnTo=/office?page%3D6", "name=\"returnTo\" value=\"/office?page=6\"");

        String certificates = html(get("/office").param("type", "certificates"));
        assertThat(certificates).contains("1 to 10 of 51", "<a class=\"chip is-current\" href=\"/office?type=certificates\" aria-current=\"true\">",
                        "href=\"/office?type=certificates&amp;page=2\"")
                .doesNotContain("Eve Expired", "Showing only:");

        String invitations = html(get("/office").param("type", "invitations"));
        assertThat(ATTENTION_ROW.matcher(attentionList(invitations)).results().count()).isEqualTo(1);
        assertThat(invitations).contains("Eve Expired").doesNotContain("pager-range");
        // A chip of a kind without items: the list says so.
        assertThat(html(get("/office").param("type", "vetting"))).contains("Nothing needs your attention here");
        // By date: the earliest date first (Certificate 50 expired 52 days ago; the invitation yesterday).
        assertThat(attentionList(html(get("/office").param("sort", "date")))).containsSubsequence("Certificate 50", "Certificate 49");
        assertThat(html(get("/office").param("sort", "date").param("page", "6"))).contains("Eve Expired", "Certificate 0<");
    }

    @Test
    void theFleetCardListsShipsWithOpenItemsFirstAndLinksOnlyNonZeroCounts() throws Exception {
        Ship calm = ship("MV Aaa Calm");
        crew(calm, "Anna Able", Rank.ABLE_SEAMAN);
        Ship busy = ship("MV Zzz Busy");
        User bosun = crew(busy, "Bob Bosun", Rank.BOSUN);
        certificate(bosun, "Medical fitness", -3);
        order(busy, bosun, days(-1), null);
        announcement("Fire drill", busy, days(-8), bosun);
        Ship inactive = ship("MV Laid Up");
        inactive.setActive(false);
        certificate(crew(inactive, "Ivan Idle", Rank.OILER), "Passport", -10);

        String html = html(get("/office"));
        String fleet = group(FLEET_TABLE, html);

        assertThat(fleet).containsSubsequence("MV Zzz Busy", "MV Aaa Calm").doesNotContain("MV Laid Up");
        assertThat(html).doesNotContain("Ivan Idle", "Passport");
        assertThat(tiles(html)).containsExactly(
                "2 Active vessels 1 with open items",
                "2 Crew on board no invitations waiting",
                "3 Items needing attention none snoozed",
                "1 Orders waiting for the office 1 not exported yet");
        long busyId = busy.getId();
        // Each row: the name (to the ship page), the Safety Score (to its Vetting page) and the open items (to the
        // Overview filtered to the ship); a zero is a muted dash.
        assertThat(fleet).contains("href=\"/office/ships/" + busyId + "\"", "href=\"/office/vetting/" + busyId + "\"",
                "class=\"count-link\" href=\"/office?shipId=" + busyId + "\">3</a>", "N/A");
        long calmId = calm.getId();
        assertThat(fleet).contains("href=\"/office/ships/" + calmId + "\"").doesNotContain("href=\"/office?shipId=" + calmId + "\"");
        assertThat(count(fleet, "class=\"count-zero\"")).isEqualTo(1);

        // The ship filter: only that ship's items, with a chip to narrow to one kind.
        String ofBusy = html(get("/office").param("shipId", "" + busyId));
        assertThat(ATTENTION_ROW.matcher(attentionList(ofBusy)).results().count()).isEqualTo(3);
        assertThat(ofBusy).contains("Showing only:", "<strong>MV Zzz Busy</strong>", "href=\"/office\"",
                "href=\"/office?type=acknowledgements&amp;shipId=" + busyId + "\"");
        String acknowledgements = html(get("/office").param("type", "acknowledgements").param("shipId", "" + busyId));
        assertThat(attentionList(acknowledgements)).contains("Fire drill").doesNotContain("Medical fitness");
    }

    @Test
    void noLinkOnThePageLeadsToAnUnfilteredList() throws Exception {
        Ship ship = ship("MV Alpha");
        User bosun = crew(ship, "Bob Bosun", Rank.BOSUN);
        certificate(bosun, "Medical fitness", -3);
        certificate(bosun, "STCW Basic Safety", 5);
        order(ship, bosun, days(-1), null);
        invitation(ship, "Eve Expired", null, days(-1));
        announcement("Fire drill", ship, days(-8), bosun);
        libraryItem(ship, "Drug and alcohol policy", true, days(-10));

        String main = group(MAIN, html(get("/office")));
        // This page itself (the "All" chip, the page anchor) is the one unfiltered link.
        List<String> lists = List.of("/office/crew", "/office/certificates", "/office/announcements",
                "/office/orders", "/office/users");
        Matcher hrefs = HREF.matcher(main);
        List<String> found = new ArrayList<>();
        while (hrefs.find()) {
            found.add(hrefs.group(1));
        }
        assertThat(found).isNotEmpty().noneMatch(lists::contains);
    }

    @Test
    void thePageHasOnePrimaryActionAndTilesInsteadOfASummaryLine() throws Exception {
        completeSetup();
        String html = html(get("/office"));
        assertThat(count(html, "btn btn-primary")).isEqualTo(1);
        assertThat(html).contains("href=\"/office/announcements/new\"", "New announcement", "class=\"overview-tiles\"")
                .doesNotContain("fs-2 fw-bold", "Active crew", "page-subtitle", "Set up Nereonet");
        assertThat(html(get("/office").param("lang", "el")))
                .contains("Ενεργά πλοία", "Πλήρωμα επί των πλοίων", "Τίποτα δεν χρειάζεται την προσοχή σας",
                        "Ανά κατηγορία", "Επόμενες 30 ημέρες", "Πρόσφατη δραστηριότητα");
    }

    // First run (P25) ------------------------------------------------------------------------------------------------

    @Test
    void onAFreshInstallationTheSetupChecklistTakesThePlaceOfTheWorkList() throws Exception {
        String html = html(get("/office"));

        assertThat(html).contains("Set up Nereonet", "0 of 4 done", "<ol class=\"setup-steps\">")
                .contains("Add a vessel", "Add or import its crew", "Add the vessel&#39;s first library document",
                        "Send a first announcement")
                .doesNotContain("Needs attention", "id=\"fleetTitle\"", "New announcement", "Done</span>",
                        "overview-tiles");
        // The next step is the page's one primary button; the others link to their pages.
        assertThat(count(html, "btn btn-primary")).isEqualTo(1);
        assertThat(html).contains("aria-current=\"step\"", "<a class=\"btn btn-primary setup-step-action\" href=\"/office/ships\">Go to Vessels</a>")
                .contains("href=\"/office/crew\">Add or import its crew</a>",
                        "href=\"/office/ships\">Add the vessel&#39;s first library document</a>",
                        "href=\"/office/announcements\">Send a first announcement</a>");
        assertThat(html(get("/office").param("lang", "el")))
                .contains("Ρύθμιση του Nereonet", "0 από 4 ολοκληρώθηκαν", "Μετάβαση στα Πλοία");
    }

    @Test
    void eachStepShowsAsDoneWhenItsDataExistsAndTheChecklistGoesOnceAllAre() throws Exception {
        Ship ship = ship("MV First");
        String html = html(get("/office"));
        assertThat(html).contains("1 of 4 done", "class=\"setup-step is-done\"", "Done</span>",
                        "<a class=\"btn btn-primary setup-step-action\" href=\"/office/crew\">Go to Crew</a>")
                .doesNotContain("Needs attention"); // a ship but no crew: still nothing to work on

        // A pending crew invitation counts as crew: the work list comes back, under the checklist.
        invitation(ship, "Ivy Invited", Rank.OILER, days(6));
        html = html(get("/office"));
        assertThat(html).contains("2 of 4 done", "Needs attention", "id=\"fleetTitle\"",
                "<a class=\"btn btn-primary setup-step-action\" href=\"/office/ships/" + ship.getId() + "/library\">Go to Library</a>");
        assertThat(html.indexOf("Set up Nereonet")).isLessThan(html.indexOf("Needs attention"));
        assertThat(count(html, "btn btn-primary")).isEqualTo(1);

        libraryItem(ship, "House rules", false, days(-1));
        assertThat(html(get("/office"))).contains("3 of 4 done", "href=\"/office/announcements\">Go to Announcements</a>");

        announcement("Welcome", ship, days(-1));
        html = html(get("/office"));
        assertThat(html).doesNotContain("Set up Nereonet", "setup-step").contains("Needs attention", "New announcement");
        assertThat(dashboardService.setup().complete()).isTrue();
    }

    @Test
    void aRetiredLibraryItemOrAnOfficeUserDoesNotCountAsSetUp() throws Exception {
        Ship ship = ship("MV First");
        LibraryItem retired = libraryItem(ship, "Old rules", false, days(-1));
        retired.setRetired(true);
        libraryItemRepository.flush();

        DashboardService.Setup setup = dashboardService.setup();
        assertThat(setup.steps()).extracting(DashboardService.SetupStep::key, DashboardService.SetupStep::done)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("ship", true),
                        org.assertj.core.groups.Tuple.tuple("crew", false), // office@example.com is not crew
                        org.assertj.core.groups.Tuple.tuple("library", false),
                        org.assertj.core.groups.Tuple.tuple("announcement", false));
        assertThat(setup.workListShown()).isFalse();
        assertThat(setup.next().key()).isEqualTo("crew");
    }

    /** Every setup step done: a ship, a crew member, a library item and an announcement. */
    private void completeSetup() {
        Ship ship = ship("MV Ready");
        User crew = crew(ship, "Anna Able", Rank.ABLE_SEAMAN);
        libraryItem(ship, "House rules", false, days(-1));
        Announcement welcome = announcement("Welcome", ship, days(-1), crew);
        acknowledge(welcome, crew);
    }

    @Test
    void loadRunsTheSameTwelveQueriesWhateverTheFleetSize() {
        addShipWithOpenItems();
        long small = queriesOfLoad();
        for (int i = 0; i < 4; i++) {
            addShipWithOpenItems();
        }
        long large = queriesOfLoad();

        assertThat(dashboardService.load().openItems()).isEqualTo(5 * 6);
        // P43: the ships with their crew (one), the certificates, the required documents' rules (none set here:
        // nobody is then checked, so no queries for them), the RightShip rows, SIRE 2.0 (two), the orders, the
        // invitations, the announcements, the library items, the snoozes and the newest audit entries: 12.
        assertThat(small).isEqualTo(12);
        assertThat(large).isEqualTo(small);
    }

    // ---------------------------------------------------------------------------------------------------------------

    private long queriesOfLoad() {
        entityManager.flush();
        entityManager.clear();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        dashboardService.load();
        long queries = statistics.getPrepareStatementCount();
        statistics.setStatisticsEnabled(false);
        return queries;
    }

    private void addShipWithOpenItems() {
        Ship ship = ship("MV Ship " + shipCount);
        User master = crew(ship, "Master " + shipCount, Rank.MASTER);
        User cook = crew(ship, "Cook " + shipCount, Rank.COOK);
        certificate(master, "Medical fitness", -3);
        certificate(cook, "STCW Basic Safety", 5);
        order(ship, master, days(-1), null);
        invitation(ship, "Invitee " + shipCount, Rank.OILER, days(-1));
        announcement("Notice " + shipCount, ship, days(-8), master, cook);
        libraryItem(ship, "Policy " + shipCount, true, days(-10));
    }

    private String html(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static String attentionList(String html) {
        return group(ATTENTION_LIST, html);
    }

    // The four tiles as "number label detail".
    private static List<String> tiles(String html) {
        return TILE.matcher(html).results().map(m -> m.group(1) + " " + m.group(2) + " " + m.group(3)).toList();
    }

    private static String group(Pattern pattern, String html) {
        Matcher matcher = pattern.matcher(html);
        assertThat(matcher.find()).as("pattern " + pattern).isTrue();
        return matcher.group(1);
    }

    private static int count(String text, String part) {
        return text.split(Pattern.quote(part), -1).length - 1;
    }

    private static Instant days(long days) {
        return Instant.now().plus(Duration.ofDays(days));
    }

    private static String date(long days) {
        LocalDate date = LocalDate.now(ZoneOffset.UTC).plusDays(days);
        return date.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH));
    }

    private Ship ship(String name) {
        Ship ship = new Ship();
        ship.setImoNumber(String.format("%07d", 1000000 + shipCount++));
        ship.setName(name);
        ship.setCreatedAt(Instant.now());
        return shipRepository.save(ship);
    }

    private User crew(Ship ship, String name, Rank rank) {
        String email = name.toLowerCase().replace(' ', '.') + "@example.com";
        return userService.createUser(email, name, "secret123", Role.CREW_MEMBER, ship, rank);
    }

    private Certificate certificate(User user, String type, long daysLeft) {
        Certificate certificate = new Certificate();
        certificate.setUser(user);
        certificate.setType(type);
        certificate.setExpiresOn(LocalDate.now(ZoneOffset.UTC).plusDays(daysLeft));
        certificate.setCreatedAt(Instant.now());
        return certificateRepository.save(certificate);
    }

    // An order with one line: sent at sentAt (null: still OPEN on board), exported at exportedAt (null: never).
    private ShipOrder order(Ship ship, User sentBy, Instant sentAt, Instant exportedAt) {
        ShipOrder order = new ShipOrder();
        order.setShip(ship);
        order.setStatus(sentAt == null ? ShipOrderStatus.OPEN : ShipOrderStatus.SENT);
        if (sentAt != null) {
            order.setSentBy(sentBy);
            order.setSentAt(sentAt);
            order.setRequestedPort("Rotterdam");
            order.setNeededBy(LocalDate.now(ZoneOffset.UTC).plusDays(7));
        }
        order.setExportedAt(exportedAt);
        order.setCreatedAt(Instant.now());
        orderRepository.save(order);
        Item item = new Item();
        item.setShip(ship);
        item.setName("Rice " + UUID.randomUUID());
        item.setUnit("kg");
        item.setCategory(ItemCategory.PROVISIONS);
        item.setCreatedAt(Instant.now());
        itemRepository.save(item);
        ShipOrderLine line = new ShipOrderLine();
        line.setOrder(order);
        line.setItem(item);
        line.setQuantity(BigDecimal.TEN);
        order.getLines().add(orderLineRepository.save(line));
        return order;
    }

    private Invitation invitation(Ship ship, String fullName, Rank rank, Instant expiresAt) {
        Invitation invitation = new Invitation();
        invitation.setEmail(fullName.toLowerCase().replace(' ', '.') + "@example.com");
        invitation.setFullName(fullName);
        invitation.setRole(Role.CREW_MEMBER);
        invitation.setShip(ship);
        invitation.setRank(rank);
        invitation.setTokenId(UUID.randomUUID());
        invitation.setStatus(Invitation.Status.PENDING);
        invitation.setExpiresAt(expiresAt);
        invitation.setCreatedAt(expiresAt.minus(Duration.ofDays(7)));
        return invitationRepository.save(invitation);
    }

    private Announcement announcement(String title, Ship target, Instant sentAt, User... recipients) {
        Announcement announcement = new Announcement();
        announcement.setTitle(title);
        announcement.setBody("Please read.");
        if (target == null) {
            announcement.setFleetWide(true);
        } else {
            announcement.getTargetShips().add(target);
        }
        announcement.setCreatedAt(sentAt);
        announcementRepository.save(announcement);
        for (User user : recipients) {
            AnnouncementRecipient recipient = new AnnouncementRecipient();
            recipient.setAnnouncement(announcement);
            recipient.setUser(user);
            recipient.setShip(user.getShip());
            recipientRepository.save(recipient);
        }
        return announcement;
    }

    private void acknowledge(Announcement announcement, User user) {
        recipientRepository.findAllByAnnouncementOrderByUserFullNameAsc(announcement).stream()
                .filter(r -> r.getUser().getId().equals(user.getId()))
                .forEach(r -> r.setAcknowledgedAt(Instant.now()));
    }

    private LibraryItem libraryItem(Ship ship, String title, boolean required, Instant publishedAt) {
        LibraryItem item = new LibraryItem();
        item.setShip(ship);
        item.setTitle(title);
        item.setCategory(LibraryCategory.COMPANY_POLICIES);
        item.setRequired(required);
        item.setCreatedAt(publishedAt);
        libraryItemRepository.save(item);
        LibraryItemVersion version = new LibraryItemVersion();
        version.setItem(item);
        version.setVersionLabel("1");
        version.setText("Read me.");
        version.setPublishedAt(publishedAt);
        item.setCurrentVersion(versionRepository.save(version));
        libraryItemRepository.flush();
        return item;
    }

    private void acknowledge(LibraryItem item, User user) {
        LibraryAcknowledgement acknowledgement = new LibraryAcknowledgement();
        acknowledgement.setVersion(item.getCurrentVersion());
        acknowledgement.setUser(user);
        acknowledgement.setAcknowledgedAt(Instant.now());
        acknowledgementRepository.save(acknowledgement);
    }

    private DevData devData() {
        return new DevData(shipRepository, userRepository, userService, itemRepository, requestRepository,
                libraryItemRepository, versionRepository, profileRepository, requestService, vettingService, sireService);
    }
}
