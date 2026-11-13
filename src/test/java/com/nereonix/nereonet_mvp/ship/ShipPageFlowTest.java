package com.nereonix.nereonet_mvp.ship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.certificate.CertificateRepository;
import com.nereonix.nereonet_mvp.core.invitation.InvitationRepository;
import com.nereonix.nereonet_mvp.core.mail.MailService;
import com.nereonix.nereonet_mvp.core.order.ShipOrder;
import com.nereonix.nereonet_mvp.core.order.ShipOrderRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrderStatus;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.ship.ShipType;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserService;
import com.nereonix.nereonet_mvp.dashboard.DashboardService;

/**
 * P08 ship page: /office/ships/{id} and its tabs. Every tab, for an active and an inactive ship, shows the same ship
 * header (name, IMO number, type, flag, status, Edit, the "more" menu) and tab bar, with the ship fixed; actions started
 * on a tab keep the ship and return to the tab; unknown ids give 404; every ship name in the office links to the page.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class ShipPageFlowTest {

    private static final List<String> TABS = List.of("", "/crew", "/certificates", "/library", "/items",
            "/announcements");
    private static final Pattern H1 = Pattern.compile("(?s)<h1 class=\"page-title\">\\s*<span>(.*?)</span>");
    private static final Pattern TAB_BAR = Pattern.compile("(?s)<nav class=\"ship-tabs\"[^>]*>(.*?)</nav>");
    private static final Pattern TAB = Pattern.compile(
            "(?s)<a class=\"ship-tab( active)?\" href=\"([^\"]+)\"[^>]*>\\s*<span>([^<]+)</span>\\s*"
                    + "<span class=\"ship-tab-count num[^\"]*\" aria-hidden=\"true\">(\\d+)</span>");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ShipRepository shipRepository;

    @Autowired
    UserService userService;

    @Autowired
    CertificateRepository certificateRepository;

    @Autowired
    ShipOrderRepository orderRepository;

    @Autowired
    InvitationRepository invitationRepository;

    @Autowired
    DashboardService dashboardService;

    @MockitoBean
    MailService mailService;

    Ship demo;
    Ship other;
    User cadet;
    User cook;
    User elsewhere;

    @BeforeEach
    void ships() {
        demo = ship("9074729", "MV Demo", true);
        demo.setType(ShipType.BULK_CARRIER);
        demo.setFlag("GR");
        other = ship("9176187", "MV Other", true);
        cadet = userService.createUser("cadet@example.com", "Anna Cadet", "secret123", Role.CREW_MEMBER, demo,
                Rank.DECK_CADET);
        cook = userService.createUser("cook@example.com", "Chris Cook", "secret123", Role.CHIEF_COOK, demo,
                Rank.CHIEF_COOK);
        userService.createUser("norank@example.com", "Nico Norank", "secret123", Role.CREW_MEMBER, demo, null);
        elsewhere = userService.createUser("elsewhere@example.com", "Elsa Where", "secret123", Role.CREW_MEMBER, other,
                null);
    }

    @Test
    void everyTabShowsTheShipHeaderAndTabBarForAnActiveShip() throws Exception {
        for (String tab : TABS) {
            String url = "/office/ships/" + demo.getId() + tab;
            String html = html(get(url));
            assertFrame(html, url);
            assertThat(html).as("status on " + url).contains(">Active</span>")
                    .doesNotContain("ship-inactive-banner", "This vessel is inactive.");
            // The "more" menu: Deactivate, sending the tab back.
            assertThat(html).as("more menu on " + url)
                    .contains("action=\"/office/ships/" + demo.getId() + "/deactivate\"")
                    .doesNotContain("action=\"/office/ships/" + demo.getId() + "/activate\"")
                    .contains("<input type=\"hidden\" name=\"tab\" value=\"" + key(tab) + "\">");
        }
        assertThat(html(get("/office/ships/{id}/crew", demo.getId())))
                .contains("data-bs-target=\"#addCrewModal\"",
                        "action=\"/office/ships/" + demo.getId() + "/crew/new\"");
        assertThat(html(get("/office/ships/{id}/announcements", demo.getId())))
                .contains("href=\"/office/announcements/new?shipId=" + demo.getId() + "\"");
    }

    @Test
    void everyTabExplainsWhatIsBlockedOnAnInactiveShip() throws Exception {
        mockMvc.perform(post("/office/ships/{id}/deactivate", demo.getId()).param("tab", "items").with(csrf()))
                .andExpect(redirectedUrl("/office/ships/" + demo.getId() + "/items"));

        for (String tab : TABS) {
            String url = "/office/ships/" + demo.getId() + tab;
            String html = html(get(url));
            assertFrame(html, url);
            assertThat(html).as("inactive banner on " + url)
                    .contains("class=\"alert alert-warning ship-inactive-banner\" role=\"note\"", "This vessel is inactive.",
                            "You cannot invite crew to it, move crew to it or send it announcements", ">Inactive</span>")
                    .contains("action=\"/office/ships/" + demo.getId() + "/activate\"")
                    .doesNotContain("action=\"/office/ships/" + demo.getId() + "/deactivate\"");
        }
        // Inviting crew and announcing are blocked, so the tabs do not offer them.
        assertThat(html(get("/office/ships/{id}/crew", demo.getId())))
                .doesNotContain("addCrewModal", "/office/ships/" + demo.getId() + "/crew/new");
        assertThat(html(get("/office/ships/{id}/announcements", demo.getId())))
                .doesNotContain("/office/announcements/new?shipId=");

        mockMvc.perform(post("/office/ships/{id}/activate", demo.getId()).param("tab", "crew").with(csrf()))
                .andExpect(redirectedUrl("/office/ships/" + demo.getId() + "/crew"));
        assertThat(shipRepository.findById(demo.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void unknownShipGives404OnEveryTab() throws Exception {
        for (String tab : TABS) {
            mockMvc.perform(get("/office/ships/{id}" + tab, 999_999)).andExpect(status().isNotFound());
        }
        mockMvc.perform(get("/office/ships/{id}/edit", 999_999)).andExpect(status().isNotFound());
        mockMvc.perform(get("/office/ships/{id}/certificates/new", 999_999)).andExpect(status().isNotFound());
        mockMvc.perform(get("/office/announcements/new").param("shipId", "999999")).andExpect(status().isNotFound());
    }

    @Test
    void tabsShowTheirCountsAndOverviewListsTheOpenItems() throws Exception {
        certificate(cadet, "STCW Basic Safety", 20);   // open item: within 30 days
        certificate(cook, "Food hygiene", 80);         // on the Certificates tab only
        certificate(cook, "Medical fitness", -3);      // open item: expired
        ShipOrder sent = order(demo, null);            // open item: sent, not exported
        order(demo, Instant.now());                    // exported: not an open item
        mockMvc.perform(post("/office/ships/{id}/crew/new", demo.getId())
                .param("fullName", "New Member").param("email", "new.member@example.com")
                .param("rank", "ABLE_SEAMAN").param("shipId", demo.getId().toString()).with(csrf()));
        mockMvc.perform(post("/office/ships/{id}/items/new", demo.getId())
                .param("name", "Basmati rice").param("unit", "kg")
                .param("category", "PROVISIONS").with(csrf()));
        mockMvc.perform(post("/office/announcements/send")
                .param("target", "fleet").param("title", "Fleet notice").param("body", "Hello").with(csrf()));

        String html = html(get("/office/ships/{id}", demo.getId()));
        assertThat(tabs(html)).containsExactly(
                "Overview 3 active", "Crew 4", "Certificates 3", "Library 0", "Items 1", "Announcements 1");
        assertThat(html).contains("ship-tab-count num ship-tab-count-alert", "(3 open items)");

        // The same rule as the fleet Overview's certificate and order columns.
        DashboardService.FleetRow fleetRow = dashboardService.load().fleet().stream()
                .filter(row -> row.ship().id().equals(demo.getId())).findFirst().orElseThrow();
        assertThat(fleetRow.certificateIssues() + fleetRow.ordersNotExported()).isEqualTo(3);
        assertThat(html).contains("Medical fitness", "STCW Basic Safety", "Not exported").doesNotContain("Food hygiene")
                .contains("href=\"/office/orders/" + sent.getId() + "\">Order #" + sent.getId() + "</a>");

        // Crew on board by department; the pending invitation is not on board.
        assertThat(html).containsSubsequence(">Deck<", "Anna Cadet", ">Galley<", "Chris Cook", ">Rank not set<",
                "Nico Norank").doesNotContain("New Member", "Elsa Where");
        // Particulars summary.
        assertThat(html).contains("Bulk carrier", "Greece (GR)", "9074729");
    }

    @Test
    void tabsListOnlyThisShipWithoutShipFilterOrColumn() throws Exception {
        certificate(cadet, "STCW Basic Safety", 20);
        certificate(elsewhere, "Elsewhere cert", 20);
        for (String tab : List.of("/crew", "/certificates", "/announcements")) {
            String url = "/office/ships/" + demo.getId() + tab;
            String html = html(get(url));
            assertThat(html).as("no ship filter on " + url).doesNotContain("id=\"filterShipId\"");
            if (!tab.equals("/crew")) {
                // The Crew tab's Add crew member modal has the ship field, preselected (P17).
                assertThat(html).as("no ship field on " + url).doesNotContain("name=\"shipId\"");
            }
            assertThat(html).as("no ship column on " + url).doesNotContain("<th scope=\"col\">Ship</th>", "sort=ship");
            assertThat(html).as("only this ship on " + url).doesNotContain("Elsa Where", "Elsewhere cert");
            // Other ships only as choices of the Crew tab's Add crew member modal (P17).
            assertThat(html.replaceAll("<option [^>]*>[^<]*</option>", "")).as("only this ship on " + url)
                    .doesNotContain("MV Other");
        }
        // The fleet-wide pages keep their ship filter and column.
        for (String url : List.of("/office/crew", "/office/certificates", "/office/announcements", "/office/orders")) {
            assertThat(html(get(url))).as("ship filter on " + url).contains("id=\"filterShipId\"");
        }
        assertThat(html(get("/office/crew"))).contains("href=\"/office/crew?sort=ship&amp;dir=asc\"", "Elsa Where");
    }

    // P17: the Crew tab has the Crew page's Add crew member modal with the ship preselected; the former invitation
    // page redirects to the tab with the modal open.
    @Test
    void addingCrewFromTheCrewTabPreselectsTheShip() throws Exception {
        mockMvc.perform(get("/office/ships/{id}/invitations/new", demo.getId()))
                .andExpect(redirectedUrl("/office/ships/" + demo.getId() + "/crew?add=true"));
        String tab = html(get("/office/ships/{id}/crew", demo.getId()).param("add", "true"));
        assertThat(tab).contains("id=\"addCrewModal\"",
                        "action=\"/office/ships/" + demo.getId() + "/crew/new\"",
                        "<option value=\"" + demo.getId() + "\" selected=\"selected\">MV Demo · IMO 9074729</option>",
                        "bootstrap.Modal.getOrCreateInstance('#addCrewModal').show()");
        // Opened only when asked for.
        assertThat(html(get("/office/ships/{id}/crew", demo.getId())))
                .doesNotContain("bootstrap.Modal.getOrCreateInstance('#addCrewModal').show()");

        // Errors: the tab again, with the modal open and the errors.
        String errors = html(post("/office/ships/{id}/crew/new", demo.getId())
                .param("fullName", "").param("email", "new.member@example.com")
                .param("rank", "ABLE_SEAMAN").param("shipId", demo.getId().toString()).with(csrf()));
        assertThat(errors).contains("MV Demo · Crew", "bootstrap.Modal.getOrCreateInstance('#addCrewModal').show()",
                "is-invalid");
        assertThat(invitationRepository.findAll()).isEmpty();

        mockMvc.perform(post("/office/ships/{id}/crew/new", demo.getId())
                        .param("fullName", "New Member").param("email", "new.member@example.com")
                        .param("rank", "ABLE_SEAMAN").param("shipId", demo.getId().toString()).with(csrf()))
                .andExpect(redirectedUrl("/office/ships/" + demo.getId() + "/crew"));
        assertThat(invitationRepository.findAll()).singleElement()
                .satisfies(i -> assertThat(i.getShip().getId()).isEqualTo(demo.getId()));
        assertThat(html(get("/office/ships/{id}/crew", demo.getId()))).contains("New Member", "Invited");

        // Another ship picked in the modal: the invitation is for that ship, and the user lands on its Crew tab.
        mockMvc.perform(post("/office/ships/{id}/crew/new", demo.getId())
                        .param("fullName", "Other Member").param("email", "other.member@example.com")
                        .param("rank", "ABLE_SEAMAN").param("shipId", other.getId().toString()).with(csrf()))
                .andExpect(redirectedUrl("/office/ships/" + other.getId() + "/crew"));

        // A user's "more" menu on the tab returns to the tab.
        mockMvc.perform(post("/office/users/{id}/deactivate", cadet.getId()).param("tab", "crew").with(csrf()))
                .andExpect(redirectedUrl("/office/ships/" + demo.getId() + "/crew"));
        mockMvc.perform(post("/office/users/{id}/activate", cadet.getId()).with(csrf()))
                .andExpect(redirectedUrl("/office/crew"));
    }

    @Test
    void addingACertificateFromTheTabPicksFromThisShipsCrew() throws Exception {
        String form = html(get("/office/ships/{id}/certificates/new", demo.getId()));
        assertThat(form).contains("name=\"userId\"", "Anna Cadet · Deck Cadet", "Chris Cook")
                .doesNotContain("Elsa Where")
                .contains("action=\"/office/ships/" + demo.getId() + "/certificates/new\"");

        mockMvc.perform(post("/office/ships/{id}/certificates/new", demo.getId())
                        .param("userId", elsewhere.getId().toString()).param("type", "STCW")
                        .param("expiresOn", "2030-01-01").with(csrf()))
                .andExpect(status().isOk());
        assertThat(certificateRepository.findAll()).isEmpty();

        mockMvc.perform(post("/office/ships/{id}/certificates/new", demo.getId())
                        .param("userId", cadet.getId().toString()).param("type", "STCW Basic Safety")
                        .param("expiresOn", LocalDate.now(ZoneOffset.UTC).plusDays(10).toString()).with(csrf()))
                .andExpect(redirectedUrl("/office/ships/" + demo.getId() + "/certificates"));
        assertThat(certificateRepository.findAll()).singleElement()
                .satisfies(c -> assertThat(c.getUser().getId()).isEqualTo(cadet.getId()));
        assertThat(html(get("/office/ships/{id}/certificates", demo.getId()))).contains("STCW Basic Safety");
    }

    @Test
    void newAnnouncementFromTheTabHasTheShipPreselectedAndReturnsThere() throws Exception {
        String form = html(get("/office/announcements/new").param("shipId", demo.getId().toString()));
        // Selected ships, with this ship ticked (P28).
        assertThat(form).containsPattern("id=\"targetShips\" name=\"target\"[^>]*checked=\"checked\"")
                .containsPattern("name=\"shipIds\"[^>]*value=\"" + demo.getId() + "\"[^>]*checked=\"checked\"")
                .contains("<span class=\"ship-pick-name\">MV Demo</span>")
                .contains("<input type=\"hidden\" name=\"returnShipId\" value=\"" + demo.getId() + "\">");
        // Opened elsewhere, nothing is preselected.
        assertThat(html(get("/office/announcements/new"))).doesNotContainPattern("<input[^>]*checked")
                .doesNotContain("returnShipId");

        mockMvc.perform(post("/office/announcements/send").param("target", "ships").param("shipIds", demo.getId().toString())
                        .param("title", "Drill").param("body", "Friday").param("returnShipId", demo.getId().toString())
                        .with(csrf()))
                .andExpect(redirectedUrl("/office/ships/" + demo.getId() + "/announcements"));
        assertThat(html(get("/office/ships/{id}/announcements", demo.getId()))).contains("Drill");
    }

    @Test
    void editFromTheShipHeaderReturnsToTheTab() throws Exception {
        String crewTab = html(get("/office/ships/{id}/crew", demo.getId()));
        // The pop-up is filled by the server; without JavaScript Edit is a link to the same form as a page.
        assertThat(crewTab).contains("href=\"/office/ships/" + demo.getId() + "/edit?tab=crew\"",
                "id=\"editShipModal\"", "action=\"/office/ships/" + demo.getId() + "/edit\"",
                "value=\"MV Demo\"", "value=\"Greece (GR)\"");

        mockMvc.perform(post("/office/ships/{id}/edit", demo.getId()).param("name", "MV Demo II")
                        .param("type", "TANKER").param("flag", "GR").param("tab", "crew").with(csrf()))
                .andExpect(redirectedUrl("/office/ships/" + demo.getId() + "/crew"));
        assertThat(shipRepository.findById(demo.getId()).orElseThrow().getName()).isEqualTo("MV Demo II");

        // A failed save from the ship page shows its errors on the Edit page, which still returns to the tab.
        String errors = mockMvc.perform(post("/office/ships/{id}/edit", demo.getId()).param("name", " ")
                        .param("tab", "crew").with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(errors).contains("Enter the vessel name.", "<input type=\"hidden\" name=\"tab\" value=\"crew\">",
                "href=\"/office/ships/" + demo.getId() + "/crew\">Cancel</a>");

        // An unknown tab value never becomes a redirect target.
        mockMvc.perform(post("/office/ships/{id}/edit", demo.getId()).param("name", "MV Demo")
                        .param("tab", "//evil.example.com").with(csrf()))
                .andExpect(redirectedUrl("/office/ships"));
        assertThat(html(get("/office/ships/{id}/edit", demo.getId()))).contains("name=\"page\" value=\"true\"");
    }

    @Test
    void shipsListLinksTheNameAndKeepsOnlyTheMoreMenu() throws Exception {
        String html = html(get("/office/ships"));
        assertThat(html).contains("<a class=\"ship-link\" href=\"/office/ships/" + demo.getId() + "\">MV Demo</a>")
                .doesNotContain("/office/ships/" + demo.getId() + "/library\"", "/office/ships/" + demo.getId() + "/items\"");
        Matcher row = Pattern.compile("(?s)<tr[^>]*>\\s*<td class=\"cell-primary\">\\s*<a class=\"ship-link\" href=\"/office/ships/"
                + demo.getId() + "\">.*?</tr>").matcher(html);
        assertThat(row.find()).isTrue();
        String outsideMenu = row.group().replaceAll("(?s)<details class=\"row-menu dropdown\">.*?</details>", "");
        assertThat(outsideMenu).doesNotContain("<button", "btn-ghost");
        assertThat(row.group()).contains("class=\"dropdown-item\" href=\"/office/ships/" + demo.getId() + "/edit\"",
                "data-bs-target=\"#editShipModal\"", "/office/ships/" + demo.getId() + "/deactivate");
    }

    @Test
    void everyShipNameInTheOfficeLinksToTheShipPage() throws Exception {
        certificate(cadet, "STCW Basic Safety", 20);
        order(demo, null);
        mockMvc.perform(post("/office/announcements/send").param("target", "ships").param("shipIds", demo.getId().toString())
                .param("title", "Drill").param("body", "Friday").with(csrf()));
        String link = "<a class=\"ship-link\" href=\"/office/ships/" + demo.getId() + "\">MV Demo</a>";
        for (String url : List.of("/office", "/office/ships", "/office/crew", "/office/certificates",
                "/office/announcements", "/office/orders", "/office/audit",
                "/office/ships/" + demo.getId() + "/library")) {
            assertThat(html(get(url))).as("ship link on " + url).contains(link);
        }
        // Ship-scoped pages below a tab: the eyebrow links to the ship page, the crumbs go back through the tab.
        String addItem = html(get("/office/ships/{id}/items/new", demo.getId()));
        assertThat(addItem).contains("<p class=\"eyebrow\"><a href=\"/office/ships/" + demo.getId() + "\">MV Demo · IMO 9074729</a></p>",
                "<a href=\"/office/ships/" + demo.getId() + "/items\">Items</a>");
    }

    @Test
    void greekTabs() throws Exception {
        String html = html(get("/office/ships/{id}", demo.getId()).param("lang", "el"));
        assertThat(tabs(html)).extracting(t -> t.substring(0, t.indexOf(' ')))
                .containsExactly("Επισκόπηση", "Πλήρωμα", "Πιστοποιητικά", "Βιβλιοθήκη", "Είδη", "Ανακοινώσεις");
    }

    @Test
    void tabBarScrollsOnPhonesWithTheActiveTabInView() throws Exception {
        String css = Files.readString(Path.of("src/main/resources/static/css/app.css"));
        assertThat(css).contains(".ship-tabs-list {", "overflow-x: auto;", ".ship-tab.active::after {",
                "min-height: var(--nn-touch-target);");
        String js = Files.readString(Path.of("src/main/resources/static/js/ship-tabs.js"));
        assertThat(js).contains("[aria-current=\"page\"]", "scrollLeft");
        assertThat(html(get("/office/ships/{id}/announcements", demo.getId()))).containsPattern("/js/ship-tabs(-[0-9a-f]+)?\\.js");
    }

    /** The ship header and the tab bar, with exactly the tab of url active. */
    private void assertFrame(String html, String url) {
        String on = " on " + url;
        Ship ship = shipRepository.findById(demo.getId()).orElseThrow();
        Matcher h1 = H1.matcher(html);
        assertThat(h1.find()).as("h1" + on).isTrue();
        assertThat(h1.group(1)).as("ship name as the heading" + on).isEqualTo(ship.getName());
        assertThat(html).as("ship facts" + on).contains("<span class=\"num\">IMO 9074729</span>", "Bulk carrier",
                "Flag Greece (GR)", "class=\"page-header ship-header\"");
        assertThat(html).as("Edit" + on).contains("href=\"/office/ships/" + ship.getId() + "/edit?tab=");
        assertThat(html).as("more menu" + on).contains("More actions: " + ship.getName());
        List<String> tabs = tabs(html);
        assertThat(tabs).as("tabs" + on).hasSize(6);
        assertThat(tabs.stream().filter(t -> t.endsWith(" active")).toList()).as("one active tab" + on).hasSize(1);
        Matcher active = Pattern.compile("<a class=\"ship-tab active\" href=\"([^\"]+)\" aria-current=\"page\">").matcher(html);
        assertThat(active.find()).as("active tab" + on).isTrue();
        assertThat(active.group(1)).as("active tab" + on).isEqualTo(url);
    }

    /** "Label count", plus " active" on the active tab. */
    private static List<String> tabs(String html) {
        Matcher bar = TAB_BAR.matcher(html);
        assertThat(bar.find()).as("tab bar").isTrue();
        return TAB.matcher(bar.group(1)).results()
                .map(m -> m.group(3).trim() + " " + m.group(4) + (m.group(1) != null ? " active" : "")).toList();
    }

    private static String key(String tab) {
        return tab.isEmpty() ? "overview" : tab.substring(1);
    }

    private Ship ship(String imo, String name, boolean active) {
        Ship ship = new Ship();
        ship.setImoNumber(imo);
        ship.setName(name);
        ship.setActive(active);
        ship.setCreatedAt(Instant.now());
        return shipRepository.save(ship);
    }

    private void certificate(User user, String type, int daysFromNow) {
        Certificate certificate = new Certificate();
        certificate.setUser(user);
        certificate.setType(type);
        certificate.setExpiresOn(LocalDate.now(ZoneOffset.UTC).plusDays(daysFromNow));
        certificate.setCreatedAt(Instant.now());
        certificateRepository.save(certificate);
    }

    // An order the ship has sent (without lines).
    private ShipOrder order(Ship ship, Instant exportedAt) {
        ShipOrder order = new ShipOrder();
        order.setShip(ship);
        order.setStatus(ShipOrderStatus.SENT);
        order.setSentAt(Instant.now());
        order.setRequestedPort("Rotterdam");
        order.setNeededBy(LocalDate.now(ZoneOffset.UTC).plusDays(7));
        order.setCreatedAt(Instant.now());
        order.setExportedAt(exportedAt);
        return orderRepository.save(order);
    }

    private String html(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
