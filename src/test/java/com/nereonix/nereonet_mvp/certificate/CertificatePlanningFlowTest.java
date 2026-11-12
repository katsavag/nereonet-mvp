package com.nereonix.nereonet_mvp.certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.charset.StandardCharsets;
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
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.certificate.CertificateRepository;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;
import com.nereonix.nereonet_mvp.core.user.UserService;

/**
 * P20: certificates the office can plan with. The Status filter, the Overview's and the sidebar's counts linking to
 * exactly that many certificates, the type suggestions and the case-insensitive match on save, the ship's matrix,
 * the CSV exports and the forms going back to where they were opened.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class CertificatePlanningFlowTest {

    private static final Pattern TITLE_COUNT =
            Pattern.compile("<span class=\"title-count\"><span aria-hidden=\"true\">·</span> <span class=\"num\">(\\d+)</span>");
    private static final Pattern PRIMARY = Pattern.compile("(?s)<td class=\"cell-primary\">\\s*<a [^>]*>([^<]*)</a>");
    private static final Pattern DATALIST =
            Pattern.compile("(?s)<datalist id=\"certificateTypes\">(.*?)</datalist>");
    private static final Pattern OPTION = Pattern.compile("<option value=\"([^\"]*)\"");
    private static final Pattern MATRIX_HEAD = Pattern.compile("<th scope=\"col\" class=\"cert-matrix-type\">([^<]*)</th>");
    private static final Pattern MATRIX_ROW = Pattern.compile("(?s)<th scope=\"row\" class=\"cert-matrix-person\">\\s*<a [^>]*>([^<]*)</a>");

    @Autowired MockMvc mockMvc;
    @Autowired ShipRepository shipRepository;
    @Autowired UserRepository userRepository;
    @Autowired UserService userService;
    @Autowired CertificateRepository certificateRepository;

    private Ship alpha;
    private Ship beta;
    private Ship gamma;
    private User anna;
    private User bob;
    private User cook;
    private User cadet;
    private User elena;
    private User ivan;
    private Certificate annaStcw;

    // MV Alpha: Anna (Chief Officer), Bob (Bosun), Chris (Cook), Dana (Deck Cadet, nothing), Olga (inactive user).
    // MV Beta: Elena. MV Gamma, inactive: Ivan. Days left from today (UTC):
    //   Anna  STCW Basic Safety Training -5 (expired), Passport 400 (valid)
    //   Bob   STCW Basic Safety Training 10, Medical Fitness Certificate 45
    //   Chris Medical Fitness Certificate 80
    //   Elena Passport 20; Ivan Passport -1 (expired, inactive ship)
    @BeforeEach
    void fleet() {
        alpha = ship("MV Alpha", "1000001", true);
        beta = ship("MV Beta", "1000002", true);
        gamma = ship("MV Gamma", "1000003", false);
        anna = crew(alpha, "Anna Chief", Rank.CHIEF_OFFICER);
        bob = crew(alpha, "Bob Bosun", Rank.BOSUN);
        cook = crew(alpha, "Chris Cook", Rank.COOK);
        cadet = crew(alpha, "Dana Cadet", Rank.DECK_CADET);
        User olga = crew(alpha, "Olga Gone", Rank.ABLE_SEAMAN);
        olga.setEnabled(false);
        userRepository.save(olga);
        elena = crew(beta, "Elena Beta", Rank.MASTER);
        ivan = crew(gamma, "Ivan Idle", Rank.OILER);

        annaStcw = certificate(anna, "STCW Basic Safety Training", -5);
        certificate(anna, "Passport", 400);
        certificate(bob, "STCW Basic Safety Training", 10);
        certificate(bob, "Medical Fitness Certificate", 45);
        certificate(cook, "Medical Fitness Certificate", 80);
        certificate(elena, "Passport", 20);
        certificate(ivan, "Passport", -1);
    }

    // 1. The Status filter -------------------------------------------------------------------------------------------

    @Test
    void statusFilterShowsEachStateAndDefaultsToExpiredAndWithin90Days() throws Exception {
        assertThat(count(html(get("/office/certificates")))).isEqualTo(6);
        assertThat(count(html(get("/office/certificates").param("status", "expired")))).isEqualTo(2);
        assertThat(count(html(get("/office/certificates").param("status", "30")))).isEqualTo(2);
        assertThat(count(html(get("/office/certificates").param("status", "60")))).isEqualTo(3);
        assertThat(count(html(get("/office/certificates").param("status", "90")))).isEqualTo(4);
        assertThat(names(html(get("/office/certificates").param("status", "valid")))).containsExactly("Passport");
        assertThat(count(html(get("/office/certificates").param("status", "all")))).isEqualTo(7);
        assertThat(count(html(get("/office/certificates").param("status", "expired,30")))).isEqualTo(4);
        // Something it does not know: the default, so any URL shows a list.
        assertThat(count(html(get("/office/certificates").param("status", "soon")))).isEqualTo(6);

        String sixty = html(get("/office/certificates").param("status", "60"));
        assertThat(sixty).contains("<option value=\"60\" selected=\"selected\">Within 60 days</option>",
                "Status: Within 60 days");
        // A combination from a link keeps its own option, so the form sends it on.
        String combined = html(get("/office/certificates").param("status", "expired,30"));
        assertThat(combined).contains("<option value=\"expired,30\" selected=\"selected\">Expired or within 30 days</option>",
                "Status: Expired or within 30 days");
        assertThat(html(get("/office/certificates").param("status", "expired,30").param("lang", "el")))
                .contains("Έληξαν ή λήγουν εντός 30 ημερών");

        // Expires: the date and how far it is; the badge has an icon, not colour alone.
        String expired = html(get("/office/certificates").param("status", "expired"));
        assertThat(expired).contains("expired 5 days ago", "expired 1 day ago")
                .containsPattern("(?s)status-badge status-badge-icon status-danger\">\\s*<svg[^>]*>.*?</svg>\\s*<span>Expired</span>");
        assertThat(html(get("/office/certificates").param("status", "valid")))
                .containsPattern("(?s)status-badge-icon status-ok\">\\s*<svg.*?<span>Valid</span>");
    }

    @Test
    void activeShipsOnlyLeavesOutTheCrewOfInactiveShips() throws Exception {
        String active = html(get("/office/certificates").param("status", "expired").param("activeShips", "true"));
        assertThat(names(active)).containsExactly("STCW Basic Safety Training");
        assertThat(active).containsPattern("id=\"filterActiveShips\" name=\"activeShips\"\\s+value=\"true\" checked=\"checked\"")
                .contains("<span class=\"filter-chip-text\">Active ships only</span>");
    }

    // 2. The Overview's counts link to exactly that many -------------------------------------------------------------

    @Test
    void eachCertificateCountOnTheOverviewLinksToAListOfExactlyThatCount() throws Exception {
        String overview = html(get("/office"));

        // a. "Full lists": certificates expired or expiring within 30 days, crew of active ships.
        Matcher full = Pattern.compile("(?s)<a href=\"(/office/certificates[^\"]*)\">\\s*<span>Certificates expired or expiring</span>"
                + "\\s*<span class=\"title-count\"><span aria-hidden=\"true\">·</span> <span class=\"num\">(\\d+)</span>")
                .matcher(overview);
        assertThat(full.find()).isTrue();
        assertThat(full.group(2)).isEqualTo("3");
        assertThat(count(html(url(full.group(1))))).isEqualTo(3);

        // b. The fleet table: certificate issues of each active ship.
        Matcher fleet = Pattern.compile("class=\"count-link\" href=\"(/office/certificates[^\"]*)\">(\\d+)</a>").matcher(overview);
        int links = 0;
        while (fleet.find()) {
            links++;
            assertThat(count(html(url(fleet.group(1))))).as(fleet.group(1)).isEqualTo(Long.parseLong(fleet.group(2)));
        }
        assertThat(links).isEqualTo(2); // MV Alpha 2, MV Beta 1
        assertThat(overview).contains(">2</a>", ">1</a>");

        // c. The sidebar counter: every certificate expired or expiring within 30 days, any ship.
        Matcher nav = Pattern.compile("(?s)href=\"(/office/certificates[^\"]*)\">\\s*<svg.*?<span class=\"nav-count num\" aria-hidden=\"true\">(\\d+)</span>")
                .matcher(overview);
        assertThat(nav.find()).isTrue();
        assertThat(nav.group(2)).isEqualTo("4");
        assertThat(count(html(url(nav.group(1))))).isEqualTo(4);

        // d. The ship page's Certificates tab count: expired or within 90 days, as the tab's List view shows them.
        String tab = html(get("/office/ships/{id}/certificates", alpha.getId()));
        assertThat(tab).containsPattern("Certificates</span>\\s*<span class=\"ship-tab-count num\" aria-hidden=\"true\">4</span>");
        assertThat(names(html(get("/office/ships/{id}/certificates/list", alpha.getId())))).hasSize(4);

        // Open certificate on the Overview comes back to the Overview.
        assertThat(overview).contains("/office/crew/" + anna.getId() + "/certificates/" + annaStcw.getId()
                + "/edit?returnTo=/office\"");
    }

    // 3. Type suggestions and the match on save ----------------------------------------------------------------------

    @Test
    void typesAreSuggestedMostUsedFirstAndMatchedIgnoringCaseAndSpaces() throws Exception {
        String form = html(get("/office/crew/{id}/certificates/new", cadet.getId()));
        Matcher datalist = DATALIST.matcher(form);
        assertThat(datalist.find()).isTrue();
        List<String> suggestions = OPTION.matcher(datalist.group(1)).results().map(m -> HtmlUtils.htmlUnescape(m.group(1))).toList();
        // In use: Passport 3, then the two used twice by name; then the starter list without those.
        assertThat(suggestions).startsWith("Passport", "Medical Fitness Certificate", "STCW Basic Safety Training",
                "Certificate of Competency", "Flag State Endorsement");
        assertThat(suggestions).hasSize(CertificateTypes.STARTER.size()).contains("US Visa C1/D", "Seaman's Book")
                .doesNotHaveDuplicates();
        assertThat(form).contains("list=\"certificateTypes\"");

        addFor(cadet, "stcw basic safety training ");
        addFor(cadet, "  PASSPORT");
        addFor(cadet, "medical first aid");       // not in use yet: the starter list's spelling
        addFor(cadet, "Bridge Resource Management");
        assertThat(certificateRepository.findAllByUserIdOrderByExpiresOnAsc(cadet.getId()))
                .extracting(Certificate::getType)
                .containsExactlyInAnyOrder("STCW Basic Safety Training", "Passport", "Medical First Aid",
                        "Bridge Resource Management");
        assertThat(certificateRepository.countByType()).extracting(CertificateRepository.TypeCount::type)
                .doesNotContain("stcw basic safety training", "PASSPORT");

        // Editing the only certificate of a type can still correct its spelling.
        Certificate brm = certificateRepository.findAllByUserIdOrderByExpiresOnAsc(cadet.getId()).stream()
                .filter(c -> c.getType().equals("Bridge Resource Management")).findFirst().orElseThrow();
        mockMvc.perform(post("/office/crew/{u}/certificates/{id}/edit", cadet.getId(), brm.getId())
                        .param("type", "BRIDGE RESOURCE MANAGEMENT").param("expiresOn", "2027-06-01").with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(certificateRepository.findById(brm.getId()).orElseThrow().getType())
                .isEqualTo("BRIDGE RESOURCE MANAGEMENT");
    }

    // 4. The ship's matrix -------------------------------------------------------------------------------------------

    @Test
    void matrixShowsTheCrewByRankAndTheTypesTheyHold() throws Exception {
        // Renewed: the cell shows the certificate that expires last. Another spelling: the same column.
        Certificate renewed = certificate(anna, "STCW Basic Safety Training", 700);
        Certificate lowerCase = certificate(cook, "stcw basic safety training", 30);

        String html = html(get("/office/ships/{id}/certificates", alpha.getId()));

        // Rows: active crew on board by department and rank (Deck Cadet before Bosun in Rank's order).
        assertThat(MATRIX_ROW.matcher(html).results().map(m -> m.group(1)).toList())
                .containsExactly("Anna Chief", "Dana Cadet", "Bob Bosun", "Chris Cook");
        assertThat(html).doesNotContain("Olga Gone", "Elena Beta");
        // Columns: the most held first (STCW 3, Medical 2, Passport 1).
        assertThat(MATRIX_HEAD.matcher(html).results().map(m -> m.group(1)).toList())
                .containsExactly("STCW Basic Safety Training", "Medical Fitness Certificate", "Passport");

        String returnTo = "returnTo=/office/ships/" + alpha.getId() + "/certificates";
        assertThat(html).contains("/office/crew/" + anna.getId() + "/certificates/" + renewed.getId() + "/edit?" + returnTo)
                .doesNotContain("/certificates/" + annaStcw.getId() + "/edit")
                .contains("/office/crew/" + cook.getId() + "/certificates/" + lowerCase.getId() + "/edit?" + returnTo);
        // A cell: the date and the state; a dash adds one with the person and type filled in.
        assertThat(html).contains("<span class=\"visually-hidden\">STCW Basic Safety Training, Bob Bosun:</span>",
                "<span class=\"visually-hidden\">Add Passport for Dana Cadet</span>");
        String add = "/office/ships/" + alpha.getId() + "/certificates/new?userId=" + cadet.getId()
                + "&amp;type=Passport&amp;" + returnTo;
        assertThat(html).contains("href=\"" + add + "\"", "class=\"cert-matrix-person\"", "aria-current=\"page\"");

        String form = html(url(HtmlUtils.htmlUnescape(add)));
        assertThat(form).contains("<option value=\"" + cadet.getId() + "\" selected=\"selected\">",
                "id=\"type\" name=\"type\" value=\"Passport\"",
                "<input type=\"hidden\" name=\"returnTo\" value=\"/office/ships/" + alpha.getId() + "/certificates\">");

        mockMvc.perform(post("/office/ships/{id}/certificates/new", alpha.getId())
                        .param("userId", cadet.getId().toString()).param("type", "Passport")
                        .param("expiresOn", "2030-01-01")
                        .param("returnTo", "/office/ships/" + alpha.getId() + "/certificates").with(csrf()))
                .andExpect(redirectedUrl("/office/ships/" + alpha.getId() + "/certificates"));
        assertThat(html(get("/office/ships/{id}/certificates", alpha.getId())))
                .doesNotContain("Add Passport for Dana Cadet");

        // A ship without crew says so, and leads to its Crew tab instead of offering Add certificate (P25).
        Long emptyId = ship("MV Empty", "1000004", true).getId();
        assertThat(html(get("/office/ships/{id}/certificates", emptyId)))
                .contains("class=\"empty-state\"", "No active crew on board",
                        "Certificates belong to a crew member: add this ship&#39;s crew first.",
                        "href=\"/office/ships/" + emptyId + "/crew\"", "Go to Crew")
                .doesNotContain("/office/ships/" + emptyId + "/certificates/new", "Export CSV");
    }

    // 5. CSV exports -------------------------------------------------------------------------------------------------

    @Test
    void theListAndTheMatrixExportAsCsv() throws Exception {
        String list = html(get("/office/certificates").param("status", "expired"));
        assertThat(list).contains("href=\"/office/certificates/export?status=expired\"");

        String csv = csv(get("/office/certificates/export").param("status", "expired"),
                "certificates-" + today() + ".csv");
        assertThat(csv.split("\r\n")).containsExactly(
                "imo_number,ship_name,crew_member,rank,type,number,expires_on,days_left,status",
                "1000001,MV Alpha,Anna Chief,CHIEF_OFFICER,STCW Basic Safety Training,," + today().minusDays(5) + ",-5,expired",
                "1000003,MV Gamma,Ivan Idle,OILER,Passport,," + today().minusDays(1) + ",-1,expired");

        String tabList = csv(get("/office/ships/{id}/certificates/list/export", alpha.getId()).param("status", "60"),
                "certificates-1000001-" + today() + ".csv");
        assertThat(tabList.split("\r\n")).hasSize(3);
        assertThat(tabList).endsWith(",45,60_days\r\n");

        String matrix = csv(get("/office/ships/{id}/certificates/export", alpha.getId()),
                "certificate-matrix-1000001-" + today() + ".csv");
        // Medical and STCW are held by two each: by name.
        assertThat(matrix.split("\r\n")).containsExactly(
                "crew_member,rank,Medical Fitness Certificate,STCW Basic Safety Training,Passport",
                "Anna Chief,CHIEF_OFFICER,," + today().minusDays(5) + "," + today().plusDays(400),
                "Dana Cadet,DECK_CADET,,,",
                "Bob Bosun,BOSUN," + today().plusDays(45) + "," + today().plusDays(10) + ",",
                "Chris Cook,COOK," + today().plusDays(80) + ",,");
    }

    // 6. Add and edit go back to where they were opened ----------------------------------------------------------------

    @Test
    void addAndEditReturnToWhereTheyWereOpened() throws Exception {
        String list = html(get("/office/certificates").param("status", "expired"));
        String edit = "/office/crew/" + anna.getId() + "/certificates/" + annaStcw.getId()
                + "/edit?returnTo=/office/certificates?status%3Dexpired";
        assertThat(list).contains("href=\"" + edit + "\"");

        String form = html(url(edit));
        assertThat(form).contains("<input type=\"hidden\" name=\"returnTo\" value=\"/office/certificates?status=expired\">",
                "href=\"/office/certificates?status=expired\">Cancel</a>");
        mockMvc.perform(post("/office/crew/{u}/certificates/{id}/edit", anna.getId(), annaStcw.getId())
                        .param("type", "STCW Basic Safety Training").param("expiresOn", "2031-01-01")
                        .param("returnTo", "/office/certificates?status=expired").with(csrf()))
                .andExpect(redirectedUrl("/office/certificates?status=expired"));

        // From the crew member's page, or with a returnTo that is not one of these pages: their page.
        for (String returnTo : new String[] {null, "https://example.org/", "//example.org", "/office/users",
                "/office/certificates\r\nX: 1"}) {
            var request = post("/office/crew/{u}/certificates/new", bob.getId())
                    .param("type", "Passport").param("expiresOn", "2031-01-01").with(csrf());
            if (returnTo != null) {
                request.param("returnTo", returnTo);
            }
            mockMvc.perform(request).andExpect(redirectedUrl("/office/crew/" + bob.getId() + "/edit"));
        }
        assertThat(html(get("/office/crew/{u}/certificates/new", bob.getId()).param("returnTo", "//example.org")))
                .doesNotContain("example.org")
                .contains("href=\"/office/crew/" + bob.getId() + "/edit\">Cancel</a>");
    }

    // ---------------------------------------------------------------------------------------------------------------

    private void addFor(User user, String type) throws Exception {
        mockMvc.perform(post("/office/crew/{id}/certificates/new", user.getId())
                        .param("type", type).param("expiresOn", "2027-06-01").with(csrf()))
                .andExpect(redirectedUrl("/office/crew/" + user.getId() + "/edit"));
    }

    private String csv(RequestBuilder request, String filename) throws Exception {
        byte[] body = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"" + filename + "\""))
                .andReturn().getResponse().getContentAsByteArray();
        String text = new String(body, StandardCharsets.UTF_8);
        assertThat(text).startsWith("﻿");
        return text.substring(1);
    }

    private String html(RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // A link from a page (&amp; unescaped), sent as it is, without encoding it again.
    private static RequestBuilder url(String href) {
        return get(URI.create(HtmlUtils.htmlUnescape(href)));
    }

    // The count after the page title: "Certificates · 6".
    private static long count(String html) {
        Matcher matcher = TITLE_COUNT.matcher(html);
        assertThat(matcher.find()).as("count after the title").isTrue();
        return Long.parseLong(matcher.group(1));
    }

    private static List<String> names(String html) {
        return PRIMARY.matcher(html).results().map(m -> HtmlUtils.htmlUnescape(m.group(1))).toList();
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    private Ship ship(String name, String imo, boolean active) {
        Ship ship = new Ship();
        ship.setImoNumber(imo);
        ship.setName(name);
        ship.setActive(active);
        ship.setCreatedAt(Instant.now());
        return shipRepository.save(ship);
    }

    private User crew(Ship ship, String name, Rank rank) {
        String email = name.toLowerCase().replace(' ', '.') + "@example.com";
        return userService.createUser(email, name, "secret123", rank.role(), ship, rank);
    }

    private Certificate certificate(User user, String type, long daysLeft) {
        Certificate certificate = new Certificate();
        certificate.setUser(user);
        certificate.setType(type);
        certificate.setExpiresOn(today().plusDays(daysLeft));
        certificate.setCreatedAt(Instant.now());
        return certificateRepository.save(certificate);
    }
}
