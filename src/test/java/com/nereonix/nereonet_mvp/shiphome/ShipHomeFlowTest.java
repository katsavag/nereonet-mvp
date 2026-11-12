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
 * first; the Master's "Crew acknowledgements"; Sign out next to the name; the phone tab bar instead of the offcanvas
 * menu; no images and no script of its own. Seen as Master, Chief cook and Crew member.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ShipHomeFlowTest {

    private static final Pattern TO_READ = Pattern.compile("(?s)<section [^>]*aria-labelledby=\"toReadTitle\">.*?</section>");
    private static final Pattern CREW_ACKS =
            Pattern.compile("(?s)<section [^>]*aria-labelledby=\"crewAcknowledgementsTitle\">.*?</section>");
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
        // Header: name, rank, ship with IMO number; Sign out next to the name.
        assertThat(html).contains("<title>Home · Nereonet</title>", "<p class=\"eyebrow\">MV Demo · IMO 9074729</p>",
                ">Demo Cook</span>", "<p class=\"page-subtitle\">Chief Cook</p>")
                .containsPattern("(?s)<span class=\"home-sign-out-name\">Not Demo Cook\\?</span>\\s*"
                        + "<form action=\"/logout\" method=\"post\">.*?>Sign out</button>");

        String toRead = section(TO_READ, html);
        assertThat(titles(toRead)).containsExactly("Drug and alcohol policy");
        assertThat(toRead).contains("href=\"/ship/library/" + policy.getId() + "\"", "class=\"read-card-type\">Library<",
                        "Published", "href=\"/ship/library\"")
                .doesNotContain("You are up to date.");
        // Not the Master: no crew acknowledgements.
        assertThat(html).doesNotContain("Crew acknowledgements", "crewAcknowledgementsTitle");

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

    // P27: announcements not yet acknowledged join To read, in date order with the library items, until acknowledged.
    @Test
    @WithUserDetails("office@example.com") // sends the announcement; the pages are opened as the crew
    void unacknowledgedAnnouncementsAreToRead() throws Exception {
        Long id = announcementService.create("Safety drill", "Drill on Friday.", false, java.util.List.of(ship.getId()),
                "office@example.com")
                .announcement().getId();

        String toRead = section(TO_READ, home(cook));
        // The policy was published two days ago, the announcement sent now: oldest first.
        assertThat(titles(toRead)).containsExactly("Drug and alcohol policy", "Safety drill");
        assertThat(toRead).contains("href=\"/ship/announcements/" + id + "\"", "class=\"read-card-type\">Announcement<",
                        "<span>Sent</span>", "<span class=\"read-card-icon\" aria-hidden=\"true\">",
                        "href=\"/ship/announcements\">All announcements</a>");

        mockMvc.perform(post("/ship/announcements/{id}/acknowledge", id).with(user(new CurrentUser(cook))).with(csrf()))
                .andExpect(redirectedUrl("/ship/announcements/" + id));
        assertThat(titles(section(TO_READ, home(cook)))).containsExactly("Drug and alcohol policy");
        // Only the cook acknowledged it: still to read for the others.
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
        assertThat(html).doesNotContain("Crew acknowledgements");
    }

    @Test
    void masterSeesWhoOnBoardHasNotAcknowledged() throws Exception {
        LibraryItem drill = item(ship, "Fire drill procedure", true, Instant.now().minus(Duration.ofDays(10)));
        itemRepository.flush();
        // Everyone on board acknowledges the drill; only the cook the policy.
        for (User u : List.of(master, cook, crew)) {
            acknowledge(u, drill);
        }
        acknowledge(cook, policy);

        String html = home(master);
        assertThat(html).contains("<p class=\"page-subtitle\">Master</p>", "No certificates on record.");
        assertThat(titles(section(TO_READ, html))).containsExactly("Drug and alcohol policy");

        String acks = section(CREW_ACKS, html);
        assertThat(acks).contains("Drug and alcohol policy", "1 of 3 acknowledged",
                        "href=\"/ship/library/" + policy.getId() + "/acknowledgements\"")
                .doesNotContain("Fire drill procedure");

        acknowledge(master, policy);
        acknowledge(crew, policy);
        assertThat(section(CREW_ACKS, home(master)))
                .contains("Everyone on board has acknowledged every required document.")
                .doesNotContain("acknowledged</span>");
    }

    @Test
    void crewNavigateWithTheTabBarOnPhones() throws Exception {
        notificationService.notify(cook, NotificationType.LIBRARY, "MV Demo", "New document to read",
                "/ship/library/" + policy.getId());
        String html = home(cook);

        // The tab bar: Home (active), Announcements, Library, Notifications with the unread count, in words too.
        String tabBar = section(TAB_BAR, html);
        assertThat(Pattern.compile("class=\"tab-bar-link[^\"]*\" href=\"([^\"]+)\"").matcher(tabBar).results()
                .map(m -> m.group(1)).toList()).containsExactly("/ship", "/ship/announcements", "/ship/library",
                "/notifications");
        assertThat(tabBar).contains("aria-label=\"Main navigation\"",
                        "<a class=\"tab-bar-link active\" href=\"/ship\" aria-current=\"page\">",
                        "<span class=\"tab-bar-count num\" aria-hidden=\"true\">1</span>", "1 unread");
        assertThat(section(TAB_BAR, html(get("/ship/library").with(user(new CurrentUser(cook))))))
                .contains("<a class=\"tab-bar-link active\" href=\"/ship/library\" aria-current=\"page\">");
        // No offcanvas menu button and no bell for the crew: the tab bar has both.
        assertThat(html).containsPattern("<body[^>]* class=\"has-tab-bar\"")
                .doesNotContain("data-bs-target=\"#sidebar\"", "id=\"notificationBell\"");

        // No images, and no script beyond the shared ones of the layout.
        assertThat(html).doesNotContain("<img", "<picture", "url(", "<script>");
        assertThat(scripts(html)).isEqualTo(scripts(html(get("/ship/library").with(user(new CurrentUser(cook))))));
    }

    @Test
    @WithUserDetails("office@example.com") // seeded by BootstrapData
    void officeKeepsItsMenuAndHasNoTabBarNorCrewHome() throws Exception {
        String html = html(get("/office"));
        assertThat(html).contains("data-bs-target=\"#sidebar\"", "id=\"notificationBell\"")
                .doesNotContain("class=\"tab-bar", "class=\"has-tab-bar\"");
        mockMvc.perform(get("/ship")).andExpect(status().isForbidden());
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
