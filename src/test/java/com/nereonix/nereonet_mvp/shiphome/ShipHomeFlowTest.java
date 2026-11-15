package com.nereonix.nereonet_mvp.shiphome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.announcement.AnnouncementService;
import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.certificate.CertificateRepository;
import com.nereonix.nereonet_mvp.core.library.LibraryCategory;
import com.nereonix.nereonet_mvp.core.library.LibraryItem;
import com.nereonix.nereonet_mvp.core.library.LibraryItemRepository;
import com.nereonix.nereonet_mvp.core.library.LibraryItemVersion;
import com.nereonix.nereonet_mvp.core.library.LibraryItemVersionRepository;
import com.nereonix.nereonet_mvp.core.notification.NotificationService;
import com.nereonix.nereonet_mvp.core.notification.NotificationType;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserService;

/**
 * P26: the crew home page (/ship). Header with name, rank and ship; "To read" (Required items not acknowledged in the
 * current version, oldest first; "You are up to date." once done); the person's own certificates, expired and expiring
 * first; Sign out next to the name; the phone tab bar instead of the offcanvas menu; no images and no script of its
 * own. Seen as Chief cook and Crew member; the Master has their own home page (MasterHomeFlowTest).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ShipHomeFlowTest {

    private static final Pattern TO_READ = Pattern.compile("(?s)<section [^>]*aria-labelledby=\"toReadTitle\">.*?</section>");
    private static final Pattern CERTIFICATES =
            Pattern.compile("(?s)<section [^>]*aria-labelledby=\"certificatesTitle\">.*?</section>");
    private static final Pattern TAB_BAR = Pattern.compile("(?s)<nav class=\"tab-bar d-md-none\".*?</nav>");
    private static final Pattern READ_CARD_TITLE = Pattern.compile("<span class=\"read-card-title\">([^<]+)</span>");
    private static final Pattern SCRIPT = Pattern.compile("<script src=\"/([^\"]+?)(-[0-9a-f]{32})?\\.js\"");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ShipRepository shipRepository;

    @Autowired
    UserService userService;

    @Autowired
    LibraryItemRepository itemRepository;

    @Autowired
    LibraryItemVersionRepository versionRepository;

    @Autowired
    CertificateRepository certificateRepository;

    @Autowired
    NotificationService notificationService;

    @Autowired
    AnnouncementService announcementService;

    Ship ship;
    User master;
    User cook;
    User crew;
    LibraryItem policy;

    @BeforeEach
    void setUp() {
        ship = ship("9074729", "MV Demo");
        master = userService.createUser("master@example.com", "Ship Master", "secret123", Role.MASTER, ship, Rank.MASTER);
        cook = userService.createUser("cook@example.com", "Demo Cook", "secret123", Role.CHIEF_COOK, ship, Rank.CHIEF_COOK);
        crew = userService.createUser("crew@example.com", "Anna Testa", "secret123", Role.CREW_MEMBER, ship,
                Rank.ABLE_SEAMAN);
        // As the dev data: one Required item and one that is not.
        policy = item(ship, "Drug and alcohol policy", true, Instant.now().minus(Duration.ofDays(2)));
        item(ship, "Galley hygiene rules", false, Instant.now().minus(Duration.ofDays(3)));
    }

    @Test
    void chiefCookSeesWhatToReadUntilAcknowledged() throws Exception {
        String html = home(cook);
        // Header: name, rank, ship with IMO number. P58: no Sign out in the page at any width (no header actions);
        // the sidebar's user block (from 768px) still signs out, and More does on phones (ProfileFlowTest).
        assertThat(html).contains("<title>Home · Nereonet</title>", "<p class=\"eyebrow\">MV Demo · IMO 9074729</p>",
                ">Demo Cook</span>", "<p class=\"page-subtitle\">Chief Cook</p>")
                .doesNotContain("home-sign-out", "from-md", "Not Demo Cook", "class=\"page-actions\"");
        String content = html.substring(html.indexOf("<main"), html.indexOf("</main>"));
        assertThat(content).doesNotContain("action=\"/logout\"");
        assertThat(html).containsPattern("(?s)<form action=\"/logout\" method=\"post\">.{0,300}?"
                + "<button type=\"submit\" class=\"btn btn-outline-light btn-sm text-nowrap sidebar-sign-out\"");

        String toRead = section(TO_READ, html);
        assertThat(titles(toRead)).containsExactly("Drug and alcohol policy");
        assertThat(toRead).contains("href=\"/ship/library/" + policy.getId() + "\"", "class=\"read-card-type\">Library<",
                        "Published", "href=\"/ship/library\"")
                .doesNotContain("You are up to date.");

        mockMvc.perform(post("/ship/library/{id}/acknowledge", policy.getId()).with(user(new CurrentUser(cook))).with(csrf()))
                .andExpect(redirectedUrl("/ship/library/" + policy.getId()));
        String after = section(TO_READ, home(cook));
        assertThat(titles(after)).isEmpty();
        assertThat(after).contains("You are up to date.");

        // A new version is to read again.
        LibraryItemVersion second = version(policy, "2", Instant.now());
        policy.setCurrentVersion(second);
        itemRepository.flush();
        assertThat(titles(section(TO_READ, home(cook)))).containsExactly("Drug and alcohol policy");
    }

    // P27: announcements not yet read join To read, in date order with the library items, until opened (P39: News,
    // read by opening it).
    @Test
    @WithUserDetails("office@example.com") // sends the announcement; the pages are opened as the crew
    void unacknowledgedAnnouncementsAreToRead() throws Exception {
        Long id = announcementService.create("Safety drill", "Drill on Friday.", false, java.util.List.of(ship.getId()),
                "office@example.com")
                .announcement().getId();

        String toRead = section(TO_READ, home(cook));
        // The policy was published two days ago, the announcement sent now: oldest first.
        assertThat(titles(toRead)).containsExactly("Drug and alcohol policy", "Safety drill");
        assertThat(toRead).contains("href=\"/ship/news/" + id + "\"", "class=\"read-card-type\">News item<",
                        "<span>Sent</span>", "<span class=\"read-card-icon\" aria-hidden=\"true\">",
                        "href=\"/ship/news\">All news</a>");

        mockMvc.perform(get("/ship/news/{id}", id).with(user(new CurrentUser(cook)))).andExpect(status().isOk());
        assertThat(titles(section(TO_READ, home(cook)))).containsExactly("Drug and alcohol policy");
        // Only the cook opened it: still to read for the others.
        assertThat(titles(section(TO_READ, home(crew)))).containsExactly("Drug and alcohol policy", "Safety drill");
    }

    @Test
    void crewMemberSeesOldestFirstAndOnlyTheirOwnShipAndCertificates() throws Exception {
        LibraryItem older = item(ship, "Fire drill procedure", true, Instant.now().minus(Duration.ofDays(10)));
        LibraryItem retired = item(ship, "Old garbage plan", true, Instant.now().minus(Duration.ofDays(20)));
        retired.setRetired(true);
        Ship other = ship("9321483", "MV Other");
        item(other, "Other ship policy", true, Instant.now().minus(Duration.ofDays(30)));

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        certificate(crew, "Medical fitness", today.plusDays(200));
        certificate(crew, "STCW Basic Safety", today.minusDays(5));
        certificate(crew, "GMDSS", today.plusDays(20));
        certificate(cook, "Food hygiene", today.minusDays(1));
        itemRepository.flush();

        String html = home(crew);
        assertThat(html).contains("<p class=\"page-subtitle\">Able Seaman</p>", ">Anna Testa</span>");
        assertThat(titles(section(TO_READ, html))).containsExactly("Fire drill procedure", "Drug and alcohol policy");
        assertThat(older.getId()).isNotNull();

        String certificates = section(CERTIFICATES, html);
        // Expired first, then expiring soon, then valid; each with its state in words. Nobody else's.
        assertThat(certificates).containsSubsequence("STCW Basic Safety", "expired 5 days ago", "Expired",
                        "GMDSS", "in 20 days", "30 days", "Medical fitness", "Valid")
                .contains("Contact the office to update a certificate.")
                .doesNotContain("Food hygiene", "<form", "<input");
    }

    @Test
    void crewNavigateWithTheTabBarOnPhones() throws Exception {
        notificationService.notify(crew, NotificationType.LIBRARY, "MV Demo", "New document to read",
                "/ship/library/" + policy.getId());
        String html = home(crew);

        // The tab bar (P47): for crew members Home (active), News, Library and More, four tabs; Notifications is on
        // the More page, and its unread count is on the More tab, in words too.
        String tabBar = section(TAB_BAR, html);
        assertThat(tabs(tabBar)).containsExactly("/ship", "/ship/news", "/ship/library", "/ship/more");
        assertThat(tabBar).contains("aria-label=\"Main navigation\"",
                        "<a class=\"tab-bar-link active\" href=\"/ship\" aria-current=\"page\">")
                .containsPattern("(?s)href=\"/ship/more\".*?<span class=\"tab-bar-count num\" aria-hidden=\"true\">1</span>"
                        + ".*?<span class=\"visually-hidden\">1 new or waiting</span>")
                .doesNotContain("/notifications");
        // The More page: Notifications first, with its count; on the notifications page More is the active tab.
        String more = html(get("/ship/more").with(user(new CurrentUser(crew))));
        assertThat(more).containsSubsequence("<ul class=\"profile-rows\">", "href=\"/notifications\"", "Notifications",
                "<span class=\"more-count num\" aria-hidden=\"true\">1</span>", "1 unread", "href=\"/ship/profile\"");
        assertThat(section(TAB_BAR, html(get("/notifications").with(user(new CurrentUser(crew))))))
                .contains("<a class=\"tab-bar-link active\" href=\"/ship/more\" aria-current=\"page\">");
        assertThat(section(TAB_BAR, html(get("/ship/library").with(user(new CurrentUser(crew))))))
                .contains("<a class=\"tab-bar-link active\" href=\"/ship/library\" aria-current=\"page\">");
        // No offcanvas menu button and no bell for the crew: the tab bar and its More page have both.
        assertThat(html).containsPattern("<body[^>]* class=\"has-tab-bar[ \"]")
                .doesNotContain("data-bs-toggle=\"offcanvas\"", "id=\"notificationBell\"");

        // No images, and no script beyond the shared ones of the layout. The one exception is the Nereonix mark of the
        // sidebar (P29): lazy, so a phone, where the sidebar is hidden, never loads it.
        assertThat(html).containsPattern("<img class=\"brand-mark\" src=\"/img/brand/nereonix-mark-192-[0-9a-f]{32}\\.png\""
                + " width=\"32\" height=\"32\" alt=\"\" loading=\"lazy\">");
        assertThat(html.replaceAll("<img class=\"brand-mark\"[^>]*>", ""))
                .doesNotContain("<img", "<picture", "url(", "<script>");
        assertThat(scripts(html)).isEqualTo(scripts(html(get("/ship/library").with(user(new CurrentUser(crew))))));
    }

    // P65: four tabs per role, Home first and More last; whatever left the bar is on the More page under the sidebar's
    // headings, with its counter, and the More tab adds up what is under it.
    @Test
    @WithUserDetails("office@example.com") // sends the announcement; the pages are opened as the crew
    void theTabsFitTheRole() throws Exception {
        announcementService.create("Safety drill", "Drill on Friday.", false, List.of(ship.getId()),
                "office@example.com");

        // The Master: Approvals and Vessel order are tabs (active on their pages); News and Library are under More,
        // and so is the news not opened yet, on the More tab.
        String masterBar = section(TAB_BAR, home(master));
        assertThat(tabs(masterBar)).containsExactly("/ship", "/ship/approvals", "/ship/order", "/ship/more");
        assertThat(masterBar).contains("<span class=\"tab-bar-label\">Approvals</span>",
                        "<span class=\"tab-bar-label\">Vessel order</span>", "data-more-base=\"1\"")
                // The news not opened yet plus its notification.
                .containsPattern("(?s)href=\"/ship/more\".*?<span class=\"tab-bar-count num\" aria-hidden=\"true\">2</span>");
        assertThat(section(TAB_BAR, html(get("/ship/approvals").with(user(new CurrentUser(master))))))
                .contains("<a class=\"tab-bar-link active\" href=\"/ship/approvals\" aria-current=\"page\">");
        assertThat(section(TAB_BAR, html(get("/ship/news").with(user(new CurrentUser(master))))))
                .contains("<a class=\"tab-bar-link active\" href=\"/ship/more\" aria-current=\"page\">");
        String masterMore = html(get("/ship/more").with(user(new CurrentUser(master))));
        assertThat(moreLinks(masterMore)).containsExactly("/notifications", "/ship/profile", "/ship/news",
                "/ship/library", "/ship/crew");
        assertThat(masterMore).contains("<h2 class=\"more-group-title\" id=\"moreOnBoard\">On board</h2>")
                .doesNotContain("id=\"moreSupplies\"")
                .containsPattern("(?s)href=\"/ship/news\">.*?<span class=\"more-count num\" aria-hidden=\"true\">1</span>"
                        + "\\s*<span class=\"visually-hidden\">1 unread</span>");

        // The Chief cook: Stores and Requests; News, Library and Vessel order under More.
        String cookBar = section(TAB_BAR, home(cook));
        assertThat(tabs(cookBar)).containsExactly("/ship", "/ship/stores", "/ship/requests", "/ship/more");
        assertThat(section(TAB_BAR, html(get("/ship/library").with(user(new CurrentUser(cook))))))
                .contains("<a class=\"tab-bar-link active\" href=\"/ship/more\" aria-current=\"page\">");
        String cookMore = html(get("/ship/more").with(user(new CurrentUser(cook))));
        assertThat(moreLinks(cookMore)).containsExactly("/notifications", "/ship/profile", "/ship/news",
                "/ship/library", "/ship/crew", "/ship/order");
        assertThat(cookMore).containsSubsequence("id=\"moreOnBoard\">On board</h2>",
                "<h2 class=\"more-group-title\" id=\"moreSupplies\">Supplies</h2>",
                "<ul class=\"profile-rows\" aria-labelledby=\"moreSupplies\">", "href=\"/ship/order\"");

        // Crew members: as before, Requests and My crew under More.
        assertThat(moreLinks(html(get("/ship/more").with(user(new CurrentUser(crew))))))
                .containsExactly("/notifications", "/ship/profile", "/ship/crew", "/ship/requests");
    }

    @Test
    @WithUserDetails("office@example.com") // seeded by BootstrapData
    void officeKeepsItsMenuAndHasNoTabBarNorCrewHome() throws Exception {
        String html = html(get("/office"));
        assertThat(html).contains("data-bs-target=\"#sidebar\"", "id=\"notificationBell\"")
                .doesNotContain("class=\"tab-bar", "class=\"has-tab-bar\"");
        mockMvc.perform(get("/ship")).andExpect(status().isForbidden());
    }

    private static List<String> tabs(String tabBar) {
        return Pattern.compile("class=\"tab-bar-link[^\"]*\" href=\"([^\"]+)\"").matcher(tabBar).results()
                .map(m -> m.group(1)).toList();
    }

    /** The rows of the More page, in order. */
    private static List<String> moreLinks(String html) {
        String nav = html.substring(html.indexOf("<nav class=\"more-links\""));
        nav = nav.substring(0, nav.indexOf("</nav>"));
        return Pattern.compile("<a class=\"profile-row\" href=\"([^\"]+)\"").matcher(nav).results()
                .map(m -> m.group(1)).toList();
    }

    private String home(User u) throws Exception {
        return html(get("/ship").with(user(new CurrentUser(u))));
    }

    private String html(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private static String section(Pattern pattern, String html) {
        Matcher m = pattern.matcher(html);
        assertThat(m.find()).as(pattern.pattern()).isTrue();
        return m.group();
    }

    private static List<String> titles(String toRead) {
        return READ_CARD_TITLE.matcher(toRead).results().map(m -> m.group(1)).toList();
    }

    private static List<String> scripts(String html) {
        return SCRIPT.matcher(html).results().map(m -> m.group(1)).toList();
    }

    private void acknowledge(User u, LibraryItem item) throws Exception {
        mockMvc.perform(post("/ship/library/{id}/acknowledge", item.getId()).with(user(new CurrentUser(u))).with(csrf()))
                .andExpect(redirectedUrl("/ship/library/" + item.getId()));
    }

    private Ship ship(String imo, String name) {
        Ship s = new Ship();
        s.setImoNumber(imo);
        s.setName(name);
        s.setCreatedAt(Instant.now());
        return shipRepository.save(s);
    }

    private LibraryItem item(Ship s, String title, boolean required, Instant publishedAt) {
        LibraryItem item = new LibraryItem();
        item.setShip(s);
        item.setTitle(title);
        item.setCategory(LibraryCategory.COMPANY_POLICIES);
        item.setRequired(required);
        item.setCreatedAt(publishedAt);
        itemRepository.save(item);
        item.setCurrentVersion(version(item, "1", publishedAt));
        return item;
    }

    private LibraryItemVersion version(LibraryItem item, String label, Instant publishedAt) {
        LibraryItemVersion version = new LibraryItemVersion();
        version.setItem(item);
        version.setVersionLabel(label);
        version.setText("Read this.");
        version.setPublishedAt(publishedAt);
        return versionRepository.save(version);
    }

    private void certificate(User u, String type, LocalDate expiresOn) {
        Certificate c = new Certificate();
        c.setUser(u);
        c.setType(type);
        c.setExpiresOn(expiresOn);
        c.setCreatedAt(Instant.now());
        certificateRepository.save(c);
    }
}
