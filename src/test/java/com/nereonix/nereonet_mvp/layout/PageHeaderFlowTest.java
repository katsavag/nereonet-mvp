package com.nereonix.nereonet_mvp.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.announcement.AnnouncementRepository;
import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.certificate.CertificateRepository;
import com.nereonix.nereonet_mvp.core.library.LibraryCategory;
import com.nereonix.nereonet_mvp.core.library.LibraryItem;
import com.nereonix.nereonet_mvp.core.library.LibraryItemRepository;
import com.nereonix.nereonet_mvp.core.library.LibraryItemVersion;
import com.nereonix.nereonet_mvp.core.library.LibraryItemVersionRepository;
import com.nereonix.nereonet_mvp.core.mail.MailService;
import com.nereonix.nereonet_mvp.core.request.ItemRequest;
import com.nereonix.nereonet_mvp.core.request.ItemRequestRepository;
import com.nereonix.nereonet_mvp.core.request.RequestStatus;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserService;

/**
 * P06 page header on every page: one h1, breadcrumbs on every page below the top level (section, record, page; each
 * a link except the last), the eyebrow only for the ship's name and IMO number, the record count next to the page
 * title instead of in a card header, "Page name · Nereonet" tab titles and the page name in the phone top bar.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class PageHeaderFlowTest {

    private static final String SHIP_CONTEXT = "MV Demo · IMO 9074729";
    private static final Pattern TAB_TITLE = Pattern.compile("<title>(.*?)</title>");
    private static final Pattern TOP_BAR_TITLE = Pattern.compile("<span class=\"topbar-title fw-bold\">(.*?)</span>");
    private static final Pattern BREADCRUMBS = Pattern.compile(
            "(?s)<nav class=\"page-breadcrumbs\" aria-label=\"([^\"]*)\">\\s*<ol class=\"breadcrumb\">(.*?)</ol>");
    private static final Pattern CRUMB = Pattern.compile("(?s)<li class=\"breadcrumb-item[^\"]*\"[^>]*>(.*?)</li>");
    private static final Pattern CRUMB_LINK = Pattern.compile("<a href=\"[^\"]+\">(.*?)</a>");
    private static final Pattern H1 = Pattern.compile("(?s)<h1\\b.*?</h1>");
    private static final Pattern EYEBROW = Pattern.compile("(?s)<p class=\"eyebrow\">(.*?)</p>");
    private static final Pattern EYEBROW_SHIP_LINK = Pattern.compile("<a href=\"/office/ships/\\d+\">(.*?)</a>");
    // The ship page's header adds a class of its own (ship-header).
    private static final Pattern MAIN_STARTS_WITH_HEADER = Pattern.compile("<main\\b[^>]*>\\s*<header class=\"page-header[^\"]*\">");
    private static final Pattern PAGE_HEADER = Pattern.compile("<header class=\"page-header[^\"]*\">");
    private static final Pattern CARD_HEADER = Pattern.compile("(?s)<div class=\"card-header.*?</div>");

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

    @Autowired
    AnnouncementRepository announcementRepository;

    @Autowired
    LibraryItemRepository libraryItemRepository;

    @Autowired
    LibraryItemVersionRepository versionRepository;

    @MockitoBean
    MailService mailService;

    /** One page: its URL, who opens it, the expected tab title, breadcrumb labels and eyebrow. */
    private record Page(String url, User as, String tabTitle, List<String> crumbs, String eyebrow) {
    }

    @Test
    void everyPageStartsWithThePageHeader() throws Exception {
        Ship ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);
        long id = ship.getId();
        User crew = userService.createUser("crew@example.com", "Crew Member", "secret123", Role.CREW_MEMBER, ship,
                Rank.DECK_CADET);
        User master = userService.createUser("master@example.com", "Ship Master", "secret123", Role.MASTER, ship, null);
        Certificate certificate = new Certificate();
        certificate.setUser(crew);
        certificate.setType("STCW Basic Safety");
        certificate.setExpiresOn(LocalDate.now(ZoneOffset.UTC).plusDays(20));
        certificate.setCreatedAt(Instant.now());
        certificateRepository.save(certificate);
        mockMvc.perform(post("/office/ships/{id}/items/new", id)
                .param("name", "Basmati rice").param("unit", "kg").with(csrf()));
        mockMvc.perform(post("/office/announcements/send")
                .param("target", Long.toString(id)).param("title", "Safety drill").param("body", "Friday 10:00").with(csrf()));
        long announcementId = announcementRepository.findAll().get(0).getId();
        ItemRequest request = new ItemRequest();
        request.setShip(ship);
        request.setRequestedBy(crew);
        request.setStatus(RequestStatus.APPROVED);
        request.setDecidedBy(master);
        request.setDecidedAt(Instant.now());
        request.setCreatedAt(Instant.now());
        requestRepository.save(request);
        LibraryItem item = libraryItem(ship, "Drug and alcohol policy");

        Map<String, Page> pages = new LinkedHashMap<>();
        // Top level: the sidebar entries. No breadcrumbs.
        add(pages, "/office", null, "Overview", List.of(), null);
        add(pages, "/office/ships", null, "Ships", List.of(), null);
        add(pages, "/office/crew", null, "Crew", List.of(), null);
        add(pages, "/office/users", null, "Office users", List.of(), null);
        add(pages, "/office/audit", null, "Audit log", List.of(), null);
        add(pages, "/office/certificates", null, "Expiring certificates", List.of(), null);
        add(pages, "/office/announcements", null, "Announcements", List.of(), null);
        add(pages, "/office/requests", null, "Approved requests", List.of(), null);
        add(pages, "/notifications", null, "Notifications", List.of(), null);
        add(pages, "/ship", crew, "Overview", List.of(), null);
        add(pages, "/ship/library", crew, "Library", List.of(), SHIP_CONTEXT);
        // Below the top level: section > record > page.
        add(pages, "/office/announcements/new", null, "New announcement", List.of("Announcements", "New announcement"), null);
        add(pages, "/office/announcements/" + announcementId, null, "Safety drill",
                List.of("Announcements", "Safety drill"), SHIP_CONTEXT);
        add(pages, "/office/requests/" + request.getId(), null, "Request #" + request.getId(),
                List.of("Requests", "Request #" + request.getId()), SHIP_CONTEXT);
        add(pages, "/office/crew/" + crew.getId() + "/edit", null, "Edit crew member",
                List.of("Crew", "Edit crew member"), null);
        add(pages, "/office/crew/" + crew.getId() + "/certificates/new", null, "Add certificate",
                List.of("Crew", "Crew Member", "Add certificate"), null);
        add(pages, "/office/crew/" + crew.getId() + "/certificates/" + certificate.getId() + "/edit", null,
                "Edit certificate", List.of("Crew", "Crew Member", "Edit certificate"), null);
        add(pages, "/office/crew/import", null, "Import crew", List.of("Crew", "Import crew from a file"), null);
        // The ship page (P08): every tab has the ship's name as its heading, under Ships; the tab title and the phone
        // top bar name the ship and the tab. No eyebrow: the ship is the heading.
        for (String[] tab : new String[][] {{"", "Overview"}, {"/crew", "Crew"}, {"/certificates", "Certificates"},
                {"/library", "Library"}, {"/items", "Items"}, {"/announcements", "Announcements"},
                {"/requests", "Requests"}}) {
            add(pages, "/office/ships/" + id + tab[0], null, "MV Demo · " + tab[1], List.of("Ships", "MV Demo"), null);
        }
        // Below the ship page: Ships > the ship > its tab > the page, with the ship as the eyebrow.
        add(pages, "/office/ships/" + id + "/edit", null, "Edit ship", List.of("Ships", "MV Demo", "Edit ship"),
                SHIP_CONTEXT);
        add(pages, "/office/ships/" + id + "/certificates/new", null, "Add certificate",
                List.of("Ships", "MV Demo", "Certificates", "Add certificate"), SHIP_CONTEXT);
        add(pages, "/office/announcements/new?shipId=" + id, null, "New announcement",
                List.of("Ships", "MV Demo", "Announcements", "New announcement"), null);
        add(pages, "/office/ships/" + id + "/items/new", null, "Add item",
                List.of("Ships", "MV Demo", "Items", "Add item"), SHIP_CONTEXT);
        add(pages, "/office/ships/" + id + "/items/import", null, "Import items",
                List.of("Ships", "MV Demo", "Items", "Import items from a file"), SHIP_CONTEXT);
        add(pages, "/office/ships/" + id + "/library/new", null, "Add an item",
                List.of("Ships", "MV Demo", "Library", "Add an item"), SHIP_CONTEXT);
        add(pages, "/office/ships/" + id + "/library/particulars", null, "Edit ship particulars",
                List.of("Ships", "MV Demo", "Library", "Edit ship particulars"), SHIP_CONTEXT);
        add(pages, "/office/ships/" + id + "/library/" + item.getId() + "/edit", null, "Publish new version",
                List.of("Ships", "MV Demo", "Library", "Publish new version"), SHIP_CONTEXT);
        add(pages, "/office/ships/" + id + "/library/" + item.getId() + "/acknowledgements", null, "Acknowledgements",
                List.of("Ships", "MV Demo", "Library", "Acknowledgements"), SHIP_CONTEXT);
        add(pages, "/ship/library/" + item.getId(), crew, "Drug and alcohol policy",
                List.of("Library", "Drug and alcohol policy"), SHIP_CONTEXT);
        add(pages, "/ship/library/new", master, "Add an item", List.of("Library", "Add an item"), SHIP_CONTEXT);
        add(pages, "/ship/library/particulars", master, "Edit ship particulars",
                List.of("Library", "Edit ship particulars"), SHIP_CONTEXT);
        add(pages, "/ship/library/" + item.getId() + "/edit", master, "Publish new version",
                List.of("Library", "Drug and alcohol policy", "Publish new version"), SHIP_CONTEXT);
        add(pages, "/ship/library/" + item.getId() + "/acknowledgements", master, "Acknowledgements",
                List.of("Library", "Drug and alcohol policy", "Acknowledgements"), SHIP_CONTEXT);

        for (Page page : pages.values()) {
            MockHttpServletRequestBuilder get = get(page.url());
            if (page.as() != null) {
                get = get.with(user(new CurrentUser(page.as())));
            }
            assertPageHeader(page, html(get), true);
        }

        // The import reviews: a file uploaded first, the review kept in the session.
        MockHttpSession crewImport = new MockHttpSession();
        mockMvc.perform(multipart("/office/crew/import").file(csv("Full name,Email,Rank,Ship IMO\n"
                + "Anna Testa,anna.testa@example.com,Chief Officer,9074729\n")).session(crewImport).with(csrf()));
        assertPageHeader(new Page("/office/crew/import/review", null, "Review crew import",
                List.of("Crew", "Import crew", "Review crew import"), null),
                html(get("/office/crew/import/review").session(crewImport)), true);
        MockHttpSession itemImport = new MockHttpSession();
        mockMvc.perform(multipart("/office/ships/{id}/items/import", id).file(csv("Name,Unit,Category\nOlive oil,lt,Oils\n"))
                .session(itemImport).with(csrf()));
        assertPageHeader(new Page("/office/ships/" + id + "/items/import/review", null, "Review item import",
                List.of("Ships", "MV Demo", "Items", "Import items", "Review item import"), SHIP_CONTEXT),
                html(get("/office/ships/{id}/items/import/review", id).session(itemImport)), true);

        // Signed out: the same header, without breadcrumbs or the app frame.
        assertPageHeader(new Page("/login", null, "Sign in", List.of(), null), html(get("/login").with(anonymous())), false);
        assertPageHeader(new Page("/register", null, "Link invalid or expired", List.of(), null),
                html(get("/register").param("token", "not-a-token").with(anonymous())), false);
    }

    @Test
    void listPagesShowTheCountNextToThePageTitle() throws Exception {
        Ship ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);
        User crew = userService.createUser("crew@example.com", "Crew Member", "secret123", Role.CREW_MEMBER, ship, null);
        ItemRequest request = new ItemRequest();
        request.setShip(ship);
        request.setRequestedBy(crew);
        request.setDecidedBy(crew);
        request.setStatus(RequestStatus.APPROVED);
        request.setDecidedAt(Instant.now());
        request.setCreatedAt(Instant.now());
        requestRepository.save(request);

        // The tabs of the ship page show their count on the tab instead (ShipPageFlowTest).
        for (String url : List.of("/office/ships", "/office/crew", "/office/users",
                "/office/audit", "/office/certificates", "/office/announcements", "/office/requests")) {
            String html = html(get(url));
            Matcher h1 = H1.matcher(html);
            assertThat(h1.find()).as("h1 on " + url).isTrue();
            assertThat(h1.group()).as("count in the page title on " + url).contains("<span class=\"title-count\">");
            assertThat(html.indexOf("title-count")).as("count only in the page title on " + url)
                    .isEqualTo(html.lastIndexOf("title-count"));
            for (String cardHeader : CARD_HEADER.matcher(html).results().map(java.util.regex.MatchResult::group).toList()) {
                assertThat(cardHeader).as("card header repeating the title on " + url).doesNotContain("<h2");
            }
        }
        assertThat(html(get("/office/ships"))).containsPattern("<h1 class=\"page-title\">\\s*<span>Ships</span>\\s*"
                + "<span class=\"title-count\"><span aria-hidden=\"true\">·</span> <span class=\"num\">1</span></span>");

        // Export CSV moved from the card header to the page header; it still submits the list's form without JavaScript.
        String requests = html(get("/office/requests"));
        // (data-download, data-busy-label: P15.)
        assertThat(requests).contains("<form id=\"exportForm\" action=\"/office/requests/export\" method=\"post\" data-download>")
                .containsPattern("<div class=\"page-actions\">\\s*<button type=\"submit\" form=\"exportForm\" class=\"btn btn-primary\"");
    }

    @Test
    void greekOfficePagesHaveGreekBreadcrumbs() throws Exception {
        Ship ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);

        String html = html(get("/office/ships/{id}/items", ship.getId()).param("lang", "el"));
        Matcher breadcrumbs = BREADCRUMBS.matcher(html);
        assertThat(breadcrumbs.find()).isTrue();
        assertThat(breadcrumbs.group(1)).isEqualTo("Διαδρομή πλοήγησης");
        assertThat(breadcrumbs.group(2)).contains(">Πλοία</a>");
    }

    @Test
    void appCssDefinesTheLayoutFrame() throws Exception {
        String css = Files.readString(Path.of("src/main/resources/static/css/app.css"));
        assertThat(css).contains(".app-main { min-width: 0; }", "--nn-content-width: 80rem;",
                "max-width: calc(var(--nn-content-width) + 2 * var(--nn-gutter));", "--nn-section-gap: var(--nn-space-5);",
                ".page-header { margin-bottom: var(--nn-section-gap); }", ".page-breadcrumbs .breadcrumb {",
                "min-height: var(--nn-touch-target);", ".topbar-title {");
        String layout = Files.readString(Path.of("src/main/resources/templates/layout/layout.html"));
        assertThat(layout).contains("class=\"app-main flex-grow-1\"", "class=\"app-content\"");
    }

    private void assertPageHeader(Page page, String html, boolean signedIn) {
        String on = " on " + page.url();
        Matcher tab = TAB_TITLE.matcher(html);
        assertThat(tab.find()).as("tab title" + on).isTrue();
        assertThat(tab.group(1)).as("tab title" + on).isEqualTo(page.tabTitle() + " · Nereonet");
        if (signedIn) {
            Matcher topBar = TOP_BAR_TITLE.matcher(html);
            assertThat(topBar.find()).as("phone top bar" + on).isTrue();
            assertThat(topBar.group(1)).as("phone top bar title" + on).isEqualTo(page.tabTitle());
            assertThat(html).as("page header first in main" + on).containsPattern(MAIN_STARTS_WITH_HEADER);
        }

        assertThat(H1.matcher(html).results().count()).as("one h1" + on).isEqualTo(1);
        assertThat(PAGE_HEADER.matcher(html).results().count()).as("one page header" + on).isEqualTo(1);
        assertThat(html).as("old back links" + on).doesNotContain("←", "Back to ships");

        // On office pages the ship eyebrow links to the ship page (P08); compare its text.
        List<String> eyebrows = EYEBROW.matcher(html).results()
                .map(m -> EYEBROW_SHIP_LINK.matcher(m.group(1)).replaceAll("$1")).toList();
        if (page.eyebrow() == null) {
            assertThat(eyebrows).as("eyebrow" + on).isEmpty();
        } else {
            assertThat(eyebrows).as("eyebrow" + on).containsExactly(page.eyebrow());
        }

        Matcher breadcrumbs = BREADCRUMBS.matcher(html);
        if (page.crumbs().isEmpty()) {
            assertThat(breadcrumbs.find()).as("no breadcrumbs on a top-level page" + on).isFalse();
            return;
        }
        assertThat(breadcrumbs.find()).as("breadcrumbs" + on).isTrue();
        assertThat(breadcrumbs.group(1)).isEqualTo("Breadcrumbs");
        List<String> items = CRUMB.matcher(breadcrumbs.group(2)).results().map(m -> m.group(1).trim()).toList();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Matcher link = CRUMB_LINK.matcher(items.get(i));
            boolean last = i == items.size() - 1;
            assertThat(link.matches()).as("crumb " + (i + 1) + (last ? " is plain text" : " is a link") + on)
                    .isEqualTo(!last);
            labels.add(last ? items.get(i) : link.group(1));
        }
        assertThat(labels).as("breadcrumbs" + on).isEqualTo(page.crumbs());
        assertThat(breadcrumbs.group(2)).as("current page" + on)
                .contains("<li class=\"breadcrumb-item active\" aria-current=\"page\">");
    }

    private void add(Map<String, Page> pages, String url, User as, String tabTitle, List<String> crumbs, String eyebrow) {
        pages.put(url, new Page(url, as, tabTitle, crumbs, eyebrow));
    }

    private String html(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private static MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "import.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    private LibraryItem libraryItem(Ship ship, String title) {
        LibraryItem item = new LibraryItem();
        item.setShip(ship);
        item.setTitle(title);
        item.setCategory(LibraryCategory.COMPANY_POLICIES);
        item.setRequired(true);
        item.setCreatedAt(Instant.now());
        libraryItemRepository.save(item);
        LibraryItemVersion version = new LibraryItemVersion();
        version.setItem(item);
        version.setVersionLabel("1");
        version.setText("No alcohol on board.");
        version.setPublishedAt(Instant.now());
        item.setCurrentVersion(versionRepository.save(version));
        libraryItemRepository.flush();
        return item;
    }
}
