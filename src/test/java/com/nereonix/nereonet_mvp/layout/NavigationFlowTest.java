package com.nereonix.nereonet_mvp.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.certificate.CertificateRepository;
import com.nereonix.nereonet_mvp.core.request.ItemRequest;
import com.nereonix.nereonet_mvp.core.request.ItemRequestRepository;
import com.nereonix.nereonet_mvp.core.request.RequestStatus;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserService;
import com.nereonix.nereonet_mvp.navigation.NavSection;

/**
 * P07 sidebar: the navigation groups, Overview one click away, exactly one active entry (its section, also on the
 * pages below it), the counters, and the brand linking to the user's home page.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class NavigationFlowTest {

    private static final Pattern SIDEBAR_NAV = Pattern.compile("(?s)<nav class=\"sidebar-nav\"[^>]*>.*?</nav>");
    private static final Pattern ACTIVE = Pattern.compile(
            "(?s)<a class=\"nav-link sidebar-link active\" href=\"([^\"]+)\" aria-current=\"page\">\\s*<svg.*?</svg>\\s*"
                    + "<span class=\"sidebar-link-label\">([^<]+)</span>");
    private static final Pattern LINK = Pattern.compile("<a class=\"nav-link sidebar-link[^\"]*\" href=\"([^\"]+)\"");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ShipRepository shipRepository;

    @Autowired
    UserService userService;

    @Autowired
    CertificateRepository certificateRepository;

    @Autowired
    ItemRequestRepository requestRepository;

    Ship ship;
    User crew;
    User master;

    @BeforeEach
    void setUp() {
        ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);
        crew = userService.createUser("crew@example.com", "Crew Member", "secret123", Role.CREW_MEMBER, ship, null);
        master = userService.createUser("master@example.com", "Ship Master", "secret123", Role.MASTER, ship, null);
    }

    @Test
    void theEntryOfTheCurrentSectionIsActive() throws Exception {
        // Three representative URLs: the office home, a page below Crew, a page below Ships.
        assertActive(get("/office"), "/office", "Overview");
        assertActive(get("/office/crew/{id}/edit", crew.getId()), "/office/crew", "Crew");
        assertActive(get("/office/ships/{id}/items", ship.getId()), "/office/ships", "Ships");
        // And for the crew: a page below Library.
        assertActive(get("/ship/library/new").with(user(new CurrentUser(master))), "/ship/library", "Library");
        assertActive(get("/notifications"), "/notifications", "Notifications");
    }

    @Test
    void everyOfficePageHasExactlyOneActiveEntryAndOverviewOneClickAway() throws Exception {
        long id = ship.getId();
        List<String> pages = List.of("/office", "/office/ships", "/office/crew", "/office/crew/import",
                "/office/crew/" + crew.getId() + "/edit", "/office/crew/" + crew.getId() + "/certificates/new",
                "/office/certificates", "/office/announcements", "/office/announcements/new", "/office/requests",
                "/office/users", "/office/audit", "/office/ships/" + id + "/items", "/office/ships/" + id + "/library",
                "/office/ships/" + id + "/invitations/new", "/notifications");
        for (String page : pages) {
            String nav = sidebar(get(page));
            assertThat(count(nav, "aria-current=\"page\"")).as("active entries on " + page).isEqualTo(1);
            assertThat(count(nav, "sidebar-link active")).as("active entries on " + page).isEqualTo(1);
            assertThat(nav).as("Overview on " + page).contains("href=\"/office\"");
        }
        // The office groups, in order, each a labelled list. Office invitations are part of Office users.
        String nav = sidebar(get("/office/ships"));
        assertThat(LINK.matcher(nav).results().map(m -> m.group(1)).toList()).containsExactly(
                "/office", "/office/ships", "/office/crew", "/office/certificates", "/office/announcements",
                "/office/requests", "/office/users", "/office/audit", "/notifications");
        assertThat(nav).containsSubsequence(">Operations</p>", ">Administration</p>", ">Account</p>", "lang=el")
                .contains("aria-labelledby=\"navGroupOperations\"", "aria-labelledby=\"navGroupAdministration\"",
                        "aria-labelledby=\"navGroupAccount\"")
                .doesNotContain("Office invitations");
    }

    @Test
    void crewSidebarHasLibraryAndNotifications() throws Exception {
        String nav = sidebar(get("/ship/library").with(user(new CurrentUser(crew))));
        assertThat(LINK.matcher(nav).results().map(m -> m.group(1)).toList())
                .containsExactly("/ship/library", "/notifications");
        assertThat(nav).contains(">On board</p>", ">Account</p>").doesNotContain("/office", "lang=el");
        // The ship home page has no entry yet (P26): nothing is active there.
        assertThat(sidebar(get("/ship").with(user(new CurrentUser(crew))))).doesNotContain("aria-current");
    }

    @Test
    void theBrandLinksToTheUsersHomePage() throws Exception {
        assertThat(html(get("/office/crew"))).containsPattern("<a class=\"sidebar-brand\" href=\"/office\">");
        assertThat(html(get("/ship/library").with(user(new CurrentUser(crew)))))
                .containsPattern("<a class=\"sidebar-brand\" href=\"/ship\">");
    }

    @Test
    void countersShowWhatIsWaitingAndHideWhenZero() throws Exception {
        String empty = sidebar(get("/office"));
        assertThat(empty).doesNotContain("nav-count");

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        certificate(today.minusDays(3));   // expired
        certificate(today.plusDays(30));   // expiring within 30 days
        certificate(today.plusDays(31));   // not yet
        request(null);                      // approved, not exported
        request(Instant.now());             // exported

        String nav = sidebar(get("/office"));
        assertThat(nav).containsPattern("(?s)href=\"/office/certificates\">.*?>Certificates</span>\\s*"
                        + "<span class=\"nav-count num\" aria-hidden=\"true\">2</span>\\s*"
                        + "<span class=\"visually-hidden\">2 expired or expiring within 30 days</span>")
                .containsPattern("(?s)href=\"/office/requests\">.*?>Requests</span>\\s*"
                        + "<span class=\"nav-count num\" aria-hidden=\"true\">1</span>\\s*"
                        + "<span class=\"visually-hidden\">1 approved, not exported</span>");
        assertThat(count(nav, "nav-count")).as("no unread notifications, no pill").isEqualTo(2);

        // Office counters only: the crew sidebar has none of them.
        assertThat(sidebar(get("/ship/library").with(user(new CurrentUser(crew))))).doesNotContain("nav-count");

        // Greek office screens.
        assertThat(sidebar(get("/office").param("lang", "el")))
                .contains(">Λειτουργίες</p>", ">Επισκόπηση</span>", "2 ληγμένα ή λήγουν εντός 30 ημερών");
    }

    @Test
    void sectionsFollowTheUrlSegments() {
        assertThat(NavSection.of("/office")).isEqualTo(NavSection.OVERVIEW);
        assertThat(NavSection.of("/office/")).isEqualTo(NavSection.OVERVIEW);
        assertThat(NavSection.of("/office/crew/12/edit")).isEqualTo(NavSection.CREW);
        assertThat(NavSection.of("/office/invitations")).isEqualTo(NavSection.USERS);
        assertThat(NavSection.of("/office/crewing")).isNull();
        assertThat(NavSection.of("/ship")).isNull();
        assertThat(NavSection.of("/error")).isNull();
    }

    @Test
    void appCssLetsTheNavigationScrollAndPinsTheUserBlock() throws Exception {
        String css = Files.readString(Path.of("src/main/resources/static/css/app.css"));
        assertThat(css).contains(".sidebar .sidebar-body { overflow: hidden; }", "overflow-y: auto;",
                ".sidebar-link.active::before {", "width: 3px;", ".nav-count {", ".sidebar-user {");
        String layout = Files.readString(Path.of("src/main/resources/templates/layout/layout.html"));
        // The phone menu is a Bootstrap offcanvas: Esc closes it (keyboard: true is its default, never switched off).
        assertThat(layout).contains("class=\"offcanvas-md offcanvas-start").doesNotContain("data-bs-keyboard=\"false\"");
    }

    private void assertActive(MockHttpServletRequestBuilder request, String href, String label) throws Exception {
        String nav = sidebar(request);
        Matcher active = ACTIVE.matcher(nav);
        assertThat(active.find()).as("active entry").isTrue();
        assertThat(active.group(1)).isEqualTo(href);
        assertThat(active.group(2)).isEqualTo(label);
        assertThat(active.find()).as("a second active entry").isFalse();
    }

    private String sidebar(MockHttpServletRequestBuilder request) throws Exception {
        Matcher nav = SIDEBAR_NAV.matcher(html(request));
        assertThat(nav.find()).as("sidebar navigation").isTrue();
        return nav.group();
    }

    private String html(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private void certificate(LocalDate expiresOn) {
        Certificate certificate = new Certificate();
        certificate.setUser(crew);
        certificate.setType("STCW Basic Safety");
        certificate.setExpiresOn(expiresOn);
        certificate.setCreatedAt(Instant.now());
        certificateRepository.save(certificate);
    }

    private void request(Instant exportedAt) {
        ItemRequest request = new ItemRequest();
        request.setShip(ship);
        request.setRequestedBy(crew);
        request.setStatus(RequestStatus.APPROVED);
        request.setDecidedBy(master);
        request.setDecidedAt(Instant.now());
        request.setCreatedAt(Instant.now());
        request.setExportedAt(exportedAt);
        requestRepository.save(request);
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) {
            n++;
        }
        return n;
    }
}
