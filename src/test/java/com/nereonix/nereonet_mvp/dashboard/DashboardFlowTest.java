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
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.library.LibraryAcknowledgement;
import com.nereonix.nereonet_mvp.core.library.LibraryAcknowledgementRepository;
import com.nereonix.nereonet_mvp.core.library.LibraryCategory;
import com.nereonix.nereonet_mvp.core.library.LibraryItem;
import com.nereonix.nereonet_mvp.core.library.LibraryItemRepository;
import com.nereonix.nereonet_mvp.core.library.LibraryItemVersion;
import com.nereonix.nereonet_mvp.core.library.LibraryItemVersionRepository;
import com.nereonix.nereonet_mvp.core.mail.MailService;
import com.nereonix.nereonet_mvp.core.request.ItemRequest;
import com.nereonix.nereonet_mvp.core.request.ItemRequestRepository;
import com.nereonix.nereonet_mvp.core.request.RequestLine;
import com.nereonix.nereonet_mvp.core.request.RequestLineRepository;
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

// The office Overview (P09): "Needs attention" work list, fleet table, summary line.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class DashboardFlowTest {

    private static final Pattern ATTENTION_LIST =
            Pattern.compile("(?s)<ul class=\"list-group list-group-flush attention-list\">(.*?)</ul>");
    private static final Pattern ATTENTION_ROW = Pattern.compile("(?s)<li class=\"list-group-item attention-item\">");
    private static final Pattern FLEET_TABLE = Pattern.compile("(?s)<table class=\"table data-table[^\"]*\">(.*?)</table>");
    private static final Pattern MAIN = Pattern.compile("(?s)<main>(.*)</main>");
    private static final Pattern HREF = Pattern.compile("href=\"([^\"#]*)");

    @Autowired MockMvc mockMvc;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired DashboardService dashboardService;
    @Autowired ShipRepository shipRepository;
    @Autowired UserRepository userRepository;
    @Autowired UserService userService;
    @Autowired CertificateRepository certificateRepository;
    @Autowired ItemRequestRepository requestRepository;
    @Autowired RequestLineRepository lineRepository;
    @Autowired ItemRepository itemRepository;
    @Autowired InvitationRepository invitationRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired AnnouncementRecipientRepository recipientRepository;
    @Autowired LibraryItemRepository libraryItemRepository;
    @Autowired LibraryItemVersionRepository versionRepository;
    @Autowired LibraryAcknowledgementRepository acknowledgementRepository;

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
                .contains("1 ship · 2 crew · 0 open items")
                .doesNotContain("attention-list", "attention-footer");
        assertThat(html).contains("<span class=\"visually-hidden\">Open items:</span>")
                .containsPattern("(?s)attention-total.*?<span class=\"num\">0</span>");
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
        // c. requests
        ItemRequest request = request(ship, bosun, days(-1), null);
        request(ship, bosun, days(-2), days(-1)); // exported
        // a. and b. certificates
        Certificate soon = certificate(cadet, "STCW Basic Safety", 12);
        Certificate gone = certificate(cook, "Medical fitness", -3);
        certificate(cook, "Food hygiene", 60);

        String html = html(get("/office"));
        String list = attentionList(html);

        assertThat(ATTENTION_ROW.matcher(list).results().count()).isEqualTo(7);
        assertThat(list).containsSubsequence("Medical fitness", "STCW Basic Safety", "Request #" + request.getId(),
                "Eve Expired", "Wes Waiting", "Fire drill on Friday", "Drug and alcohol policy");
        assertThat(list).doesNotContain("Food hygiene", "Fay Fresh", "All read", "Too new", "Optional reading");
        assertThat(html).contains("1 ship · 3 crew · 7 open items")
                .containsPattern("(?s)attention-total.*?<span class=\"num\">7</span>");

        // a. expired certificate
        String certificateUrl = "/office/crew/" + cook.getId() + "/certificates/" + gone.getId() + "/edit";
        assertThat(list).contains(">Expired</span>", "href=\"" + certificateUrl + "?returnTo=/office\"", ">Open certificate</a>",
                "Chris Cook, Cook", ">" + date(-3) + "</time>", "expired 3 days ago");
        // b. expiring certificate
        assertThat(list).contains(">Expires in 12 days</span>", "Dana Cadet, Deck Cadet", ">" + date(12) + "</time>",
                "</span> in 12 days</span>",
                "href=\"/office/crew/" + cadet.getId() + "/certificates/" + soon.getId() + "/edit?returnTo=/office\"");
        // c. request: Export in place
        assertThat(list).contains(">Not exported</span>", "href=\"/office/requests/" + request.getId() + "\"",
                "Request #" + request.getId() + " · 1 item", "Bob Bosun, Bosun",
                "action=\"/office/requests/export\"", "name=\"ids\" value=\"" + request.getId() + "\"", ">Export</button>");
        // d. invitations: Resend, back to the Overview
        assertThat(list).contains(">Not accepted</span>", "Oiler", "expired 1 day ago", "expires in 4 days",
                "action=\"/office/invitations/" + expired.getId() + "/resend\"",
                "action=\"/office/invitations/" + waiting.getId() + "/resend\"",
                "name=\"returnTo\" value=\"overview\"", ">Resend invitation</button>");
        // e. announcement and library item
        assertThat(list).contains("href=\"/office/announcements/" + drill.getId() + "#pending\"", "All ships",
                "1 of 2 not acknowledged",
                "href=\"/office/ships/" + ship.getId() + "/library/" + policy.getId() + "/acknowledgements\"",
                "2 of 3 not acknowledged", "version 1 published", ">View who has not acknowledged</a>");
        // Every row: the badge has an icon; the action is a secondary button named by the row's title.
        assertThat(list).contains("status-badge-icon", "<svg viewBox=\"0 0 24 24\"")
                .contains("aria-describedby=\"attention0\"", "id=\"attention0\"")
                .doesNotContain("btn-primary");
        // The full list of each kind.
        assertThat(html).contains("href=\"/office/certificates?status=expired,30&amp;activeShips=true\"", "href=\"/office/requests?exported=no\"",
                "href=\"/office?type=invitations\"", "href=\"/office?type=acknowledgements\"");
    }

    @Test
    void theSeededApprovedRequestsAreTwoRowsUntilExported() throws Exception {
        devData().run(null);
        Ship ship = shipRepository.findAllByOrderByNameAsc().getFirst();
        List<ItemRequest> seeded = requestRepository.findDecided(RequestStatus.APPROVED, ship.getId(), true);
        assertThat(seeded).hasSize(2);

        String list = attentionList(html(get("/office")));
        assertThat(ATTENTION_ROW.matcher(list).results().count()).isEqualTo(2);
        assertThat(count(list, ">Export</button>")).isEqualTo(2);

        for (ItemRequest request : seeded) {
            mockMvc.perform(post("/office/requests/export").param("ids", request.getId().toString())
                            .param("returnTo", "overview").with(csrf()))
                    .andExpect(redirectedUrl("/office"));
        }

        String html = html(get("/office"));
        assertThat(html).contains("Nothing needs your attention").doesNotContain(">Export</button>");
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

    @Test
    void theCardShowsTheFirst50AndTheFilteredListShowsAll() throws Exception {
        Ship ship = ship("MV Alpha");
        User bosun = crew(ship, "Bob Bosun", Rank.BOSUN);
        for (int i = 0; i < 51; i++) {
            certificate(bosun, "Certificate " + i, -1 - i);
        }
        invitation(ship, "Eve Expired", null, days(-1));

        String html = html(get("/office"));
        assertThat(ATTENTION_ROW.matcher(attentionList(html)).results().count()).isEqualTo(50);
        assertThat(html).contains("Showing the first 50 of 52.", "1 ship · 1 crew · 52 open items")
                .containsPattern("(?s)attention-total.*?<span class=\"num\">52</span>")
                .doesNotContain("Eve Expired"); // the least urgent, past the first 50
        // The most overdue first.
        assertThat(attentionList(html)).containsSubsequence("Certificate 50", "Certificate 49", "Certificate 1<")
                .doesNotContain("Certificate 0<");

        String certificates = html(get("/office").param("type", "certificates"));
        assertThat(ATTENTION_ROW.matcher(attentionList(certificates)).results().count()).isEqualTo(51);
        assertThat(certificates).contains("Showing only:", "Certificates expired or expiring", "href=\"/office\"")
                .doesNotContain("Eve Expired", "Showing the first");

        String invitations = html(get("/office").param("type", "invitations"));
        assertThat(ATTENTION_ROW.matcher(attentionList(invitations)).results().count()).isEqualTo(1);
        assertThat(invitations).contains("Eve Expired", "Crew invitations not accepted");
    }

    @Test
    void theFleetTableListsShipsWithOpenItemsFirstAndLinksOnlyNonZeroCounts() throws Exception {
        Ship calm = ship("MV Aaa Calm");
        crew(calm, "Anna Able", Rank.ABLE_SEAMAN);
        Ship busy = ship("MV Zzz Busy");
        User bosun = crew(busy, "Bob Bosun", Rank.BOSUN);
        certificate(bosun, "Medical fitness", -3);
        request(busy, bosun, days(-1), null);
        announcement("Fire drill", busy, days(-8), bosun);
        Ship inactive = ship("MV Laid Up");
        inactive.setActive(false);
        certificate(crew(inactive, "Ivan Idle", Rank.OILER), "Passport", -10);

        String html = html(get("/office"));
        String fleet = group(FLEET_TABLE, html);

        assertThat(fleet).containsSubsequence("MV Zzz Busy", "MV Aaa Calm").doesNotContain("MV Laid Up");
        assertThat(html).doesNotContain("Ivan Idle", "Passport").contains("2 ships · 2 crew · 3 open items");
        long busyId = busy.getId();
        assertThat(fleet).contains("href=\"/office/ships/" + busyId + "\"",
                "href=\"/office/crew?shipId=" + busyId + "\"",
                "href=\"/office/certificates?shipId=" + busyId + "&amp;status=expired,30\"",
                "href=\"/office?type=acknowledgements&amp;shipId=" + busyId + "\"",
                "href=\"/office/requests?shipId=" + busyId + "&amp;exported=no\"");
        // The calm ship: crew linked, every other count a muted dash and no link.
        long calmId = calm.getId();
        assertThat(fleet).contains("href=\"/office/crew?shipId=" + calmId + "\"")
                .doesNotContain("certificates?shipId=" + calmId, "type=acknowledgements&amp;shipId=" + calmId,
                        "requests?shipId=" + calmId);
        assertThat(count(fleet, "class=\"count-zero\"")).isEqualTo(3);

        // The ship and condition filter of a count: only that ship's items of that kind.
        String acknowledgements = html(get("/office").param("type", "acknowledgements").param("shipId", "" + busyId));
        assertThat(attentionList(acknowledgements)).contains("Fire drill").doesNotContain("Medical fitness");
        assertThat(acknowledgements).contains("Showing only:", "Not acknowledged", "<strong>MV Zzz Busy</strong>");
    }

    @Test
    void noLinkOnThePageLeadsToAnUnfilteredList() throws Exception {
        Ship ship = ship("MV Alpha");
        User bosun = crew(ship, "Bob Bosun", Rank.BOSUN);
        certificate(bosun, "Medical fitness", -3);
        certificate(bosun, "STCW Basic Safety", 5);
        request(ship, bosun, days(-1), null);
        invitation(ship, "Eve Expired", null, days(-1));
        announcement("Fire drill", ship, days(-8), bosun);
        libraryItem(ship, "Drug and alcohol policy", true, days(-10));

        String main = group(MAIN, html(get("/office")));
        List<String> lists = List.of("/office", "/office/crew", "/office/certificates", "/office/announcements",
                "/office/requests", "/office/users");
        Matcher hrefs = HREF.matcher(main);
        List<String> found = new ArrayList<>();
        while (hrefs.find()) {
            found.add(hrefs.group(1));
        }
        assertThat(found).isNotEmpty().noneMatch(lists::contains);
    }

    @Test
    void thePageHasOnePrimaryActionAndASummaryLineInsteadOfTiles() throws Exception {
        completeSetup();
        String html = html(get("/office"));
        assertThat(count(html, "btn btn-primary")).isEqualTo(1);
        assertThat(html).contains("href=\"/office/announcements/new\"", "New announcement")
                .contains("<p class=\"page-subtitle\">1 ship · 1 crew · 0 open items</p>")
                .doesNotContain("fs-2 fw-bold", "Active crew", "Open items needing attention", "Set up Nereonet");
        assertThat(html(get("/office").param("lang", "el")))
                .contains("1 πλοίο · 1 μέλος πληρώματος · 0 ανοιχτά θέματα", "Τίποτα δεν χρειάζεται την προσοχή σας");
    }

    // First run (P25) ------------------------------------------------------------------------------------------------

    @Test
    void onAFreshInstallationTheSetupChecklistTakesThePlaceOfTheWorkList() throws Exception {
        String html = html(get("/office"));

        assertThat(html).contains("Set up Nereonet", "0 of 4 done", "<ol class=\"setup-steps\">")
                .contains("Add a ship", "Add or import its crew", "Add the ship&#39;s first library document",
                        "Send a first announcement")
                .contains("<p class=\"page-subtitle\">0 ships · 0 crew · 0 open items</p>")
                .doesNotContain("Needs attention", "id=\"fleetTitle\"", "New announcement", "Done</span>");
        // The next step is the page's one primary button; the others link to their pages.
        assertThat(count(html, "btn btn-primary")).isEqualTo(1);
        assertThat(html).contains("aria-current=\"step\"", "<a class=\"btn btn-primary setup-step-action\" href=\"/office/ships\">Go to Ships</a>")
                .contains("href=\"/office/crew\">Add or import its crew</a>",
                        "href=\"/office/ships\">Add the ship&#39;s first library document</a>",
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
    void loadRunsTheSameSevenQueriesWhateverTheFleetSize() {
        addShipWithOpenItems();
        long small = queriesOfLoad();
        for (int i = 0; i < 4; i++) {
            addShipWithOpenItems();
        }
        long large = queriesOfLoad();

        assertThat(dashboardService.load().openItems()).isEqualTo(5 * 6);
        assertThat(small).isEqualTo(7);
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
        request(ship, master, days(-1), null);
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

    private ItemRequest request(Ship ship, User requestedBy, Instant approvedAt, Instant exportedAt) {
        ItemRequest request = new ItemRequest();
        request.setShip(ship);
        request.setRequestedBy(requestedBy);
        request.setStatus(RequestStatus.APPROVED);
        request.setDecidedAt(approvedAt);
        request.setCreatedAt(approvedAt);
        request.setExportedAt(exportedAt);
        requestRepository.save(request);
        Item item = new Item();
        item.setShip(ship);
        item.setName("Rice " + UUID.randomUUID());
        item.setUnit("kg");
        item.setCreatedAt(Instant.now());
        itemRepository.save(item);
        RequestLine line = new RequestLine();
        line.setRequest(request);
        line.setItem(item);
        line.setQuantity(BigDecimal.TEN);
        lineRepository.save(line);
        return request;
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
        announcement.setShip(target);
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
                lineRepository, libraryItemRepository, versionRepository);
    }
}
