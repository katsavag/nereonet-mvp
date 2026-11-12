package com.nereonix.nereonet_mvp.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.certificate.CertificateRepository;
import com.nereonix.nereonet_mvp.core.mail.MailService;
import com.nereonix.nereonet_mvp.core.order.ShipOrder;
import com.nereonix.nereonet_mvp.core.order.ShipOrderRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrderStatus;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserService;

/**
 * P04 tables and row actions on the office lists, with rows in every table: one shared table style, the count next
 * to the card title, one bold primary cell per row, at most one visible row action plus a "more" menu named after
 * the record with destructive entries last, status badges, emails on one line, and stacked cards on phones (all
 * lists except the audit log).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class TablesFlowTest {

    private static final Pattern TBODY = Pattern.compile("(?s)<tbody>(.*?)</tbody>");
    private static final Pattern ROW = Pattern.compile("(?s)<tr\\b.*?</tr>");
    private static final Pattern ROW_MENU = Pattern.compile("(?s)<details class=\"row-menu dropdown\">.*?</details>");
    private static final Pattern VISIBLE_BUTTON = Pattern.compile("class=\"btn ");

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

    @MockitoBean
    MailService mailService;

    @Test
    void officeListsShareOneTableStyle() throws Exception {
        Ship ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);
        User crew = userService.createUser("a.very.long.address.for.a.crew.member@example.com", "Crew Member",
                "secret123", Role.CREW_MEMBER, ship, Rank.DECK_CADET);
        Certificate certificate = new Certificate();
        certificate.setUser(crew);
        certificate.setType("STCW Basic Safety");
        certificate.setExpiresOn(LocalDate.now(ZoneOffset.UTC).plusDays(20));
        certificate.setCreatedAt(Instant.now());
        certificateRepository.save(certificate);
        mockMvc.perform(post("/office/ships/{id}/items/new", ship.getId())
                .param("name", "Basmati rice").param("unit", "kg")
                .param("category", "PROVISIONS").with(csrf()));
        mockMvc.perform(post("/office/invitations")
                .param("fullName", "New Office").param("email", "new.office@example.com").with(csrf()));
        mockMvc.perform(post("/office/announcements/send")
                .param("target", "ships").param("shipIds", ship.getId().toString()).param("title", "Safety drill").param("body", "Friday 10:00").with(csrf()));

        ShipOrder order = new ShipOrder();
        order.setShip(ship);
        order.setStatus(ShipOrderStatus.SENT);
        order.setSentBy(crew);
        order.setSentAt(Instant.now());
        order.setRequestedPort("Rotterdam");
        order.setNeededBy(LocalDate.now(ZoneOffset.UTC).plusDays(7));
        order.setCreatedAt(Instant.now());
        orderRepository.save(order);

        // Page -> card title as rendered before the count.
        Map<String, String> pages = Map.of(
                "/office/ships", "Ships",
                "/office/crew", "Crew",
                "/office/users", "Office users",
                "/office/certificates", "Certificates",
                "/office/announcements", "Announcements",
                "/office/ships/" + ship.getId() + "/items", "Galley items",
                "/office/orders", "Orders",
                "/office/audit", "Audit log");
        for (Map.Entry<String, String> page : pages.entrySet()) {
            String html = html(page.getKey());
            String on = " on " + page.getKey();
            assertThat(html).as("shared table style" + on).contains("class=\"table data-table");
            // The audit log keeps its columns and scrolls sideways on phones; the other lists stack.
            assertThat(html.contains("data-table-stack")).as("stacked on phones" + on)
                    .isEqualTo(!page.getKey().equals("/office/audit"));
            // A tab of the ship page has its count on the tab (P08), the other lists after the page title.
            assertThat(html).as("count next to the title" + on)
                    .contains(page.getKey().startsWith("/office/ships/") ? "class=\"ship-tab-count" : "class=\"title-count\"")
                    .doesNotContain("font-monospace small text-body-secondary");

            Matcher body = TBODY.matcher(html);
            assertThat(body.find()).as("table rows" + on).isTrue();
            List<String> rows = ROW.matcher(body.group(1)).results().map(java.util.regex.MatchResult::group).toList();
            assertThat(rows).as("rows" + on).isNotEmpty();
            for (String row : rows) {
                assertThat(row).as("bold cells" + on).doesNotContain("fw-semibold", "fw-bold");
                if (!page.getKey().equals("/office/audit")) {
                    assertThat(count(row, "class=\"cell-primary")).as("one primary cell per row" + on).isEqualTo(1);
                }
                String outsideMenu = ROW_MENU.matcher(row).replaceAll("");
                assertThat(count(outsideMenu, VISIBLE_BUTTON)).as("visible row actions" + on).isLessThanOrEqualTo(1);
                assertThat(outsideMenu).as("destructive action outside the menu" + on)
                        .doesNotContain("dropdown-item-danger", "btn-outline-danger");
                for (String menu : ROW_MENU.matcher(row).results().map(java.util.regex.MatchResult::group).toList()) {
                    assertThat(menu).contains("data-bs-toggle=\"dropdown\"", "More actions: ");
                    int danger = menu.indexOf("dropdown-item-danger");
                    if (danger >= 0) {
                        assertThat(menu.indexOf("dropdown-divider")).as("divider before destructive entries" + on)
                                .isBetween(0, danger);
                        assertThat(menu.substring(danger)).as("destructive entries last" + on)
                                .doesNotContain("class=\"dropdown-item\"");
                    }
                }
            }
        }

        // The primary column links to the record's page.
        // A ship's name opens the ship page (P08).
        assertThat(html("/office/ships")).contains("href=\"/office/ships/" + ship.getId() + "\">MV Demo</a>",
                "status-badge status-ok", "data-label=\"Ship code\"");
        String crewList = html("/office/crew");
        assertThat(crewList).contains("href=\"/office/crew/" + crew.getId() + "/edit\">Crew Member</a>",
                "More actions: Crew Member", "data-label=\"Ship\"");
        // Emails stay on one line, cut with an ellipsis, with the full value in the title.
        assertThat(crewList).contains("class=\"truncate\" title=\"a.very.long.address.for.a.crew.member@example.com\"");
        Matcher crewBody = TBODY.matcher(crewList);
        assertThat(crewBody.find()).isTrue();
        assertThat(crewBody.group(1)).as("emails broken mid-word").doesNotContain("text-break");
        assertThat(html("/office/users")).contains("More actions: New Office", "status-badge status-primary");
        assertThat(html("/office/certificates")).contains(
                "href=\"/office/crew/" + crew.getId() + "/certificates/" + certificate.getId() + "/edit?returnTo=/office/certificates\">STCW Basic Safety</a>",
                "status-badge-icon status-danger");
        assertThat(html("/office/ships/" + ship.getId() + "/items")).contains(">Basmati rice</a>",
                "More actions: Basmati rice");
        assertThat(html("/office/orders")).contains(
                "href=\"/office/orders/" + order.getId() + "\">Order #" + order.getId() + "</a>",
                "class=\"cell-select\"", "data-label=\"Requested port\">Rotterdam</td>");
        assertThat(html("/office/ships")).containsPattern("Ships</span>\\s*<span class=\"title-count\">"
                + "<span aria-hidden=\"true\">·</span> <span class=\"num\">1</span>");
    }

    @Test
    void appCssDefinesTheTableStyle() throws Exception {
        String css = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/static/css/app.css"));
        assertThat(css).contains(".data-table > thead > tr > th {", "letter-spacing: .04em", "text-transform: uppercase",
                "--nn-row-height: 52px", "--nn-cell-padding-y: var(--nn-space-3)", ".data-table .num { text-align: right; }",
                ".data-table > tbody > tr:hover", ".status-badge::before", ".truncate {", "text-overflow: ellipsis",
                "@media (max-width: 767.98px)", ".data-table-stack", "content: attr(data-label)");
    }

    private String html(String page) throws Exception {
        return mockMvc.perform(get(page)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static int count(String html, String needle) {
        return count(html, Pattern.compile(Pattern.quote(needle)));
    }

    private static int count(String html, Pattern pattern) {
        List<Integer> found = new ArrayList<>();
        Matcher m = pattern.matcher(html);
        while (m.find()) {
            found.add(m.start());
        }
        return found.size();
    }
}
