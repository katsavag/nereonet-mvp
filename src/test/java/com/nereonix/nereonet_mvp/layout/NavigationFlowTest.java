package com.nereonix.nereonet_mvp.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
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
import org.springframework.test.web.servlet.MvcResult;
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

import jakarta.servlet.http.Cookie;

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
                "/office/ships/" + id + "/crew", "/notifications");
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
        // The language switch moved to the top bar (P29).
        assertThat(nav).containsSubsequence(">Operations</p>", ">Administration</p>", ">Account</p>").doesNotContain("lang=el")
                .contains("aria-labelledby=\"navGroupOperations\"", "aria-labelledby=\"navGroupAdministration\"",
                        "aria-labelledby=\"navGroupAccount\"")
                .doesNotContain("Office invitations");
    }

    @Test
    void crewSidebarHasHomeAnnouncementsLibraryAndNotifications() throws Exception {
        String nav = sidebar(get("/ship/library").with(user(new CurrentUser(crew))));
        assertThat(LINK.matcher(nav).results().map(m -> m.group(1)).toList())
                .containsExactly("/ship", "/ship/announcements", "/ship/library", "/notifications");
        assertThat(nav).contains(">On board</p>", ">Account</p>").doesNotContain("/office", "lang=el");
        // The ship home page is Home (P26).
        assertActive(get("/ship").with(user(new CurrentUser(crew))), "/ship", "Home");
        // The crew's announcements (P27).
        assertActive(get("/ship/announcements").with(user(new CurrentUser(crew))), "/ship/announcements", "Announcements");
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
        // Certificates opens what its counter counts (P20); without a count, the list as it is.
        assertThat(empty).contains("href=\"/office/certificates\">");
        assertThat(nav).containsPattern("(?s)href=\"/office/certificates\\?status=expired,30\">.*?>Certificates</span>\\s*"
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
        assertThat(NavSection.of("/ship")).isEqualTo(NavSection.HOME);
        assertThat(NavSection.of("/ship/library/3")).isEqualTo(NavSection.LIBRARY);
        assertThat(NavSection.of("/ship/announcements/7")).isEqualTo(NavSection.ANNOUNCEMENTS);
        assertThat(NavSection.of("/shipping")).isNull();
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

    // P29: from 768px the sidebar is a 64px rail until this browser chooses the full sidebar; the choice is a cookie the
    // server reads, so the page is rendered in that state (no flash).
    @Test
    void theSidebarIsARailUntilTheBrowserChoosesTheFullOne() throws Exception {
        String first = html(get(URI.create("/office/crew?shipId=" + ship.getId())));
        assertThat(first).containsPattern("<body [^>]*class=\"sidebar-collapsed\"")
                .containsPattern("(?s)<form class=\"sidebar-toggle-form\" action=\"/preferences/sidebar\"\\s+method=\"post\">.*?"
                        + "<input type=\"hidden\" name=\"state\" value=\"expanded\" data-sidebar-state>\\s*"
                        + "<input type=\"hidden\" name=\"returnTo\" value=\"/office/crew\\?shipId=" + ship.getId() + "\">\\s*"
                        + "<button type=\"submit\" class=\"sidebar-toggle\" aria-controls=\"sidebar\" data-sidebar-toggle"
                        + "[^>]*aria-expanded=\"false\">.*?<span class=\"sidebar-label\">Expand menu</span>");
        // The toggle is the last thing in the sidebar: the Tab order of the entries is unchanged.
        assertThat(first).containsSubsequence("class=\"sidebar-nav\"", "class=\"sidebar-user\"", "data-sidebar-toggle", "</aside>");

        String expanded = html(get("/office/crew").cookie(new Cookie("sidebar", "expanded")));
        assertThat(expanded).containsPattern("<body [^>]*class=\"sidebar-expanded\"")
                .contains("name=\"state\" value=\"collapsed\"", "aria-expanded=\"true\"",
                        "<span class=\"sidebar-label\">Collapse menu</span>");
        // Any other value is the default.
        assertThat(html(get("/office/crew").cookie(new Cookie("sidebar", "wide"))))
                .containsPattern("<body [^>]*class=\"sidebar-collapsed\"");
        // Crew have the same sidebar from 768px; Greek office screens say it in Greek.
        assertThat(html(get("/ship/library").with(user(new CurrentUser(crew)))))
                .containsPattern("<body [^>]*class=\"has-tab-bar sidebar-collapsed\"").contains("data-sidebar-toggle");
        assertThat(html(get("/office").param("lang", "el"))).contains("<span class=\"sidebar-label\">Ανάπτυξη μενού</span>");
    }

    // Without JavaScript the toggle is a form: the answer sets the cookie and goes back to the same page.
    @Test
    void withoutJavaScriptTheToggleSetsTheCookieAndReturnsToThePage() throws Exception {
        String returnTo = "/office/crew?shipId=" + ship.getId();
        MvcResult result = mockMvc.perform(post("/preferences/sidebar").param("state", "expanded")
                        .param("returnTo", returnTo).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(returnTo))
                .andReturn();
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).startsWith("sidebar=expanded;").contains("Path=/", "Max-Age=31536000", "SameSite=Lax");
        assertThat(html(get(returnTo).cookie(new Cookie("sidebar", "expanded"))))
                .containsPattern("<body [^>]*class=\"sidebar-expanded\"");

        mockMvc.perform(post("/preferences/sidebar").param("state", "collapsed").param("returnTo", "/office").with(csrf()))
                .andExpect(redirectedUrl("/office"))
                .andExpect(cookie().value("sidebar", "collapsed"));
        // Only a page of this app: never another host.
        for (String elsewhere : List.of("//evil.example/x", "https://evil.example", "/\\evil.example", "office")) {
            mockMvc.perform(post("/preferences/sidebar").param("state", "expanded").param("returnTo", elsewhere).with(csrf()))
                    .andExpect(redirectedUrl("/"));
        }
        // A POST like every other: without the CSRF token it is refused (sent to sign in, P12) and sets nothing.
        mockMvc.perform(post("/preferences/sidebar").param("state", "expanded"))
                .andExpect(redirectedUrl("/login?expired"))
                .andExpect(cookie().doesNotExist("sidebar"));
    }

    // Phones (P29): the office menu has a visible close button next to the brand; Bootstrap's offcanvas keeps its
    // backdrop (a tap outside closes it), Esc and the scroll lock; js/sidebar.js closes it when an entry is chosen.
    @Test
    void thePhoneMenuHasACloseButtonAndClosesOnATapOutside() throws Exception {
        assertThat(html(get("/office/crew"))).containsPattern("(?s)<div class=\"sidebar-head\">\\s*<a class=\"sidebar-brand\" href=\"/office\">"
                + ".*?</a>\\s*<button type=\"button\" class=\"sidebar-close\" data-bs-dismiss=\"offcanvas\"\\s+data-bs-target=\"#sidebar\" aria-label=\"Close menu\">");
        String layout = Files.readString(Path.of("src/main/resources/templates/layout/layout.html"));
        assertThat(layout).doesNotContain("data-bs-backdrop", "data-bs-scroll", "data-bs-keyboard").contains("/js/sidebar.js");
        String script = Files.readString(Path.of("src/main/resources/static/js/sidebar.js"));
        assertThat(script).contains("bootstrap.Offcanvas.getOrCreateInstance(sidebar).hide()", "document.cookie = 'sidebar='",
                "aria-expanded", "event.preventDefault()");
        String css = Files.readString(Path.of("src/main/resources/static/css/app.css"));
        assertThat(css).contains(".sidebar-collapsed .sidebar { width: var(--nn-rail-width); }", "--nn-rail-width: 4rem;",
                ".sidebar-collapsed .nav-count {", ".sidebar-collapsed .sidebar-link:focus-visible .sidebar-link-label,",
                ".sidebar-close {", "width: 44px;",
                // The panel leaves part of the dimmed page visible on a phone, for the tap outside.
                ".sidebar .offcanvas-md { --bs-offcanvas-width: min(20rem, 85vw); }");
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
