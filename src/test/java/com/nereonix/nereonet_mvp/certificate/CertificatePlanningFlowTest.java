package com.nereonix.nereonet_mvp.certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import com.nereonix.nereonet_mvp.XlsSheet;
import com.nereonix.nereonet_mvp.core.audit.AuditEntry;
import com.nereonix.nereonet_mvp.core.audit.AuditRepository;
import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.certificate.CertificateRepository;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
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
 * the exports (Excel since P39) and the forms going back to where they were opened.
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

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    @Autowired MockMvc mockMvc;
    @Autowired ShipRepository shipRepository;
    @Autowired UserRepository userRepository;
    @Autowired UserService userService;
    @Autowired CertificateRepository certificateRepository;
    @Autowired AuditRepository auditRepository;

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
                .contains("<span class=\"filter-chip-text\">Active vessels only</span>");
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
                        "Certificates belong to a crew member: add this vessel&#39;s crew first.",
                        "href=\"/office/ships/" + emptyId + "/crew\"", "Go to Crew")
                .doesNotContain("/office/ships/" + emptyId + "/certificates/new", "/certificates/export");
    }

    // 5. Excel exports (CSV until P39; .xls since P40) ---------------------------------------------------------------

    @Test
    void theListAndTheMatrixExportAsExcel() throws Exception {
        String list = html(get("/office/certificates").param("status", "expired"));
        assertThat(list).contains("href=\"/office/certificates/export?status=expired\"");

        // Dates are date cells, days left a number, the IMO number text; a missing number an empty cell.
        XlsSheet sheet = xls(get("/office/certificates/export").param("status", "expired"),
                "certificates-" + today() + ".xls");
        assertThat(sheet.name()).isEqualTo("Certificates");
        assertThat(sheet.rows()).containsExactly(
                List.of("imo_number", "ship_name", "crew_member", "rank", "type", "number", "expires_on", "days_left",
                        "status"),
                Arrays.asList("1000001", "MV Alpha", "Anna Chief", "CHIEF_OFFICER", "STCW Basic Safety Training", null,
                        today().minusDays(5), -5.0, "expired"),
                Arrays.asList("1000003", "MV Gamma", "Ivan Idle", "OILER", "Passport", null, today().minusDays(1), -1.0,
                        "expired"));

        XlsSheet tabList = xls(get("/office/ships/{id}/certificates/list/export", alpha.getId()).param("status", "60"),
                "certificates-1000001-" + today() + ".xls");
        assertThat(tabList.body()).hasSize(2);
        assertThat(tabList.body().getLast()).endsWith(today().plusDays(45), 45.0, "60_days");

        XlsSheet matrix = xls(get("/office/ships/{id}/certificates/export", alpha.getId()),
                "certificate-matrix-1000001-" + today() + ".xls");
        assertThat(matrix.name()).isEqualTo("Certificate matrix");
        // Medical and STCW are held by two each: by name.
        assertThat(matrix.rows()).containsExactly(
                List.of("crew_member", "rank", "Medical Fitness Certificate", "STCW Basic Safety Training", "Passport"),
                Arrays.asList("Anna Chief", "CHIEF_OFFICER", null, today().minusDays(5), today().plusDays(400)),
                Arrays.asList("Dana Cadet", "DECK_CADET", null, null, null),
                Arrays.asList("Bob Bosun", "BOSUN", today().plusDays(45), today().plusDays(10), null),
                Arrays.asList("Chris Cook", "COOK", today().plusDays(80), null, null));

        // The button says Export, without a format.
        assertThat(list).contains("<span>Export</span>").doesNotContain("Export CSV");
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

    // 7. Sensible dates (P39) -------------------------------------------------------------------------------------------

    @Test
    void anExpiryOutsideThirtyYearsEitherSideIsRefusedWithTheLimits() throws Exception {
        LocalDate earliest = today().minusYears(30);
        LocalDate latest = today().plusYears(30);
        String range = "Enter a date between " + DAY.format(earliest) + " and " + DAY.format(latest) + ".";

        // The picker offers the same days.
        assertThat(html(get("/office/crew/{id}/certificates/new", cadet.getId())))
                .contains("min=\"" + earliest + "\"", "max=\"" + latest + "\"");

        // 22 Nov 0333 (a slip for 2033): refused, nothing saved, the form again with the limits.
        for (String typed : new String[] {"0333-11-22", earliest.minusDays(1).toString(), latest.plusDays(1).toString()}) {
            String form = mockMvc.perform(post("/office/crew/{id}/certificates/new", cadet.getId())
                            .param("type", "Passport").param("expiresOn", typed).with(csrf()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(form).as(typed).contains(range, "is-invalid");
        }
        // In Greek, the same limits with Greek month names.
        assertThat(mockMvc.perform(post("/office/crew/{id}/certificates/new", cadet.getId()).param("lang", "el")
                        .param("type", "Passport").param("expiresOn", "0333-11-22").with(csrf()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("Εισαγάγετε ημερομηνία από " + DateTimeFormatter.ofPattern("d MMM yyyy", Locale.of("el")).format(earliest));
        assertThat(certificateRepository.findAllByUserIdOrderByExpiresOnAsc(cadet.getId())).isEmpty();

        // The limits themselves are accepted; so is editing a certificate to an expiry within them.
        addFor(cadet, "Passport", earliest);
        addFor(cadet, "Seaman's Book", latest);
        assertThat(certificateRepository.findAllByUserIdOrderByExpiresOnAsc(cadet.getId())).hasSize(2);
        mockMvc.perform(post("/office/crew/{u}/certificates/{id}/edit", anna.getId(), annaStcw.getId())
                        .param("type", "STCW Basic Safety Training").param("expiresOn", "0333-11-22").with(csrf()))
                .andExpect(status().isOk());
        assertThat(certificateRepository.findById(annaStcw.getId()).orElseThrow().getExpiresOn())
                .isEqualTo(today().minusDays(5));
    }

    // 8. The certificate's file, and My certificates (P39) --------------------------------------------------------------

    private static final byte[] PDF = "%PDF-1.7\n% a scan\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 0};

    @Test
    void theOfficeAttachesAFileThatOnlyTheCrewMemberAndTheOfficeCanOpen() throws Exception {
        String edit = "/office/crew/" + anna.getId() + "/certificates/" + annaStcw.getId() + "/edit";
        String officeUrl = "/office/crew/" + anna.getId() + "/certificates/" + annaStcw.getId() + "/file";
        String crewUrl = "/ship/profile/certificates/" + annaStcw.getId() + "/file";

        // The form takes an optional file: PDF, JPEG or PNG, at most 10 MB.
        assertThat(html(get(edit))).contains("enctype=\"multipart/form-data\"", "type=\"file\" id=\"file\" name=\"file\"",
                "accept=\".pdf,.jpg,.jpeg,.png\"", "data-max-size=\"10485760\"",
                "A scan of the certificate: PDF, JPEG or PNG, at most 10 MB.").doesNotContain("Remove the file");

        // Anything else is refused, also a file renamed to .pdf; nothing is saved.
        for (MockMultipartFile wrong : new MockMultipartFile[] {
                new MockMultipartFile("file", "stcw.docx", "application/pdf", PDF),
                new MockMultipartFile("file", "stcw.pdf", "application/pdf", "not a pdf".getBytes(StandardCharsets.UTF_8))}) {
            assertThat(mockMvc.perform(multipart(edit).file(wrong).param("type", "STCW Basic Safety Training")
                            .param("expiresOn", annaStcw.getExpiresOn().toString()).with(csrf()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .contains("Only PDF, JPEG or PNG files can be attached.");
        }
        assertThat(annaStcw.hasFile()).isFalse();

        mockMvc.perform(multipart(edit).file(new MockMultipartFile("file", "stcw.pdf", "application/pdf", PDF))
                        .param("type", "STCW Basic Safety Training").param("expiresOn", annaStcw.getExpiresOn().toString())
                        .with(csrf()))
                .andExpect(redirectedUrl("/office/crew/" + anna.getId() + "/edit"));
        assertThat(annaStcw.getFileName()).isEqualTo("stcw.pdf");
        assertThat(annaStcw.getFileContentType()).isEqualTo("application/pdf");
        assertThat(audit("CERTIFICATE_FILE_ADDED")).singleElement()
                .isEqualTo("anna.chief@example.com: STCW Basic Safety Training, file stcw.pdf");

        // The lists show a paperclip with Open; the office opens it in the browser, never cached.
        String open = "href=\"" + officeUrl + "\" target=\"_blank\" rel=\"noopener\" title=\"stcw.pdf\"";
        assertThat(html(get("/office/certificates").param("status", "expired"))).contains(open, "<span>Open</span>",
                "the file of STCW Basic Safety Training (opens in a new tab)");
        assertThat(html(get("/office/crew/{id}/edit", anna.getId()))).contains(open);
        assertThat(html(get(edit))).contains("Current file:", "<strong>stcw.pdf</strong>", open, "Remove the file",
                "Choose a file to replace the current one");
        assertThat(file(get(officeUrl), "application/pdf", "stcw.pdf")).isEqualTo(PDF);
        // A certificate without a file has none to open.
        Certificate passport = certificateRepository.findAllByUserIdOrderByExpiresOnAsc(anna.getId()).getLast();
        mockMvc.perform(get("/office/crew/{u}/certificates/{id}/file", anna.getId(), passport.getId()))
                .andExpect(status().isNotFound());

        // Anna: My certificates, expired first, read only, with Open on the one with a file.
        String profile = html(get("/ship/profile").with(user(new CurrentUser(anna))));
        assertThat(profile).contains("My certificates", "Only you and the office see your certificates.")
                .containsSubsequence("STCW Basic Safety Training", "Expired", "href=\"" + crewUrl + "\"", "Passport", "Valid")
                .doesNotContain(officeUrl, "/ship/profile/certificates/" + passport.getId() + "/file");
        assertThat(file(get(crewUrl).with(user(new CurrentUser(anna))), "application/pdf", "stcw.pdf")).isEqualTo(PDF);
        // Bob, on the same vessel, never sees it: not on his profile, and its URL is not found.
        assertThat(html(get("/ship/profile").with(user(new CurrentUser(bob))))).doesNotContain(crewUrl)
                .contains("Medical Fitness Certificate");
        mockMvc.perform(get(crewUrl).with(user(new CurrentUser(bob)))).andExpect(status().isNotFound());
        // Dana has none yet.
        assertThat(html(get("/ship/profile").with(user(new CurrentUser(cadet))))).contains("No certificates on record.");

        // Replaced by a PNG, then removed: each audited; the file URLs are gone after the removal.
        mockMvc.perform(multipart(edit).file(new MockMultipartFile("file", "stcw-scan.PNG", "image/png", PNG))
                        .param("type", "STCW Basic Safety Training").param("expiresOn", annaStcw.getExpiresOn().toString())
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(file(get(officeUrl), "image/png", "stcw-scan.PNG")).isEqualTo(PNG);
        assertThat(audit("CERTIFICATE_FILE_REPLACED")).singleElement()
                .isEqualTo("anna.chief@example.com: STCW Basic Safety Training, file stcw-scan.PNG, replaces stcw.pdf");

        mockMvc.perform(multipart(edit).param("type", "STCW Basic Safety Training")
                        .param("expiresOn", annaStcw.getExpiresOn().toString()).param("removeFile", "true").with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(annaStcw.hasFile()).isFalse();
        assertThat(audit("CERTIFICATE_FILE_REMOVED")).singleElement()
                .isEqualTo("anna.chief@example.com: STCW Basic Safety Training, file stcw-scan.PNG");
        mockMvc.perform(get(officeUrl)).andExpect(status().isNotFound());
        mockMvc.perform(get(crewUrl).with(user(new CurrentUser(anna)))).andExpect(status().isNotFound());

        // A new certificate can come with its file, from the crew member's page or the vessel's tab.
        mockMvc.perform(multipart("/office/ships/{id}/certificates/new", alpha.getId())
                        .file(new MockMultipartFile("file", "passport.jpg", "image/jpeg",
                                new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0}))
                        .param("userId", cadet.getId().toString()).param("type", "Passport")
                        .param("expiresOn", today().plusYears(5).toString()).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(certificateRepository.findAllByUserIdOrderByExpiresOnAsc(cadet.getId())).singleElement()
                .extracting(Certificate::getFileContentType).isEqualTo("image/jpeg");
    }

    // The file's content, checking it opens in the browser under its name and is never cached.
    private byte[] file(RequestBuilder request, String contentType, String fileName) throws Exception {
        return mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", contentType))
                .andExpect(header().string("Content-Disposition", "inline; filename=\"" + fileName + "\"; filename*=UTF-8''" + fileName))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsByteArray();
    }

    private List<String> audit(String action) {
        return auditRepository.findAll().stream().filter(e -> e.getAction().equals(action))
                .map(AuditEntry::getDetails).toList();
    }

    // ---------------------------------------------------------------------------------------------------------------

    private void addFor(User user, String type, LocalDate expiresOn) throws Exception {
        mockMvc.perform(post("/office/crew/{id}/certificates/new", user.getId())
                        .param("type", type).param("expiresOn", expiresOn.toString()).with(csrf()))
                .andExpect(redirectedUrl("/office/crew/" + user.getId() + "/edit"));
    }

    private void addFor(User user, String type) throws Exception {
        mockMvc.perform(post("/office/crew/{id}/certificates/new", user.getId())
                        .param("type", type).param("expiresOn", "2027-06-01").with(csrf()))
                .andExpect(redirectedUrl("/office/crew/" + user.getId() + "/edit"));
    }

    private XlsSheet xls(RequestBuilder request, String filename) throws Exception {
        return XlsSheet.of(mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/vnd.ms-excel"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"" + filename + "\""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsByteArray());
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
