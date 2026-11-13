package com.nereonix.nereonet_mvp.requirement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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

import com.nereonix.nereonet_mvp.XlsSheet;
import com.nereonix.nereonet_mvp.core.audit.AuditEntry;
import com.nereonix.nereonet_mvp.core.audit.AuditRepository;
import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.certificate.CertificateRepository;
import com.nereonix.nereonet_mvp.core.requirement.DocumentRequirementRepository;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.ship.ShipType;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

/**
 * P42: required documents per rank and vessel type. The grid (starter template, ticking, adding a type, a note too
 * long, the audit entry), a seafarer with all, some and none of their documents, the Chief Officer on a tanker without
 * a Tanker Endorsement shown Missing on the office's page, the Crew list, the vessel's matrix, the Overview and their
 * own My profile and Home (and no longer once it is unticked), the Missing documents filter, the export holding the
 * rows of the page, and the Crew list's fixed number of queries.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class RequirementsFlowTest {

    private static final Pattern SUMMARY =
            Pattern.compile("(?s)class=\"documents-summary\">.*?</svg>\\s*<span>([^<]*)</span>");
    private static final Pattern CARD_ROW = Pattern.compile(
            "(?s)<li class=\"list-group-item requirement-row\">.*?<span>([^<]*)</span>.*?status-badge-icon[^>]*>.*?</svg>\\s*<span>([^<]*)</span>");
    private static final Pattern CHIP_OR_NAME = Pattern.compile("(?s)<td class=\"cell-primary\">\\s*<a [^>]*>([^<]*)</a>");

    @Autowired MockMvc mockMvc;
    @Autowired ShipRepository shipRepository;
    @Autowired UserService userService;
    @Autowired CertificateRepository certificateRepository;
    @Autowired AuditRepository auditRepository;
    @Autowired DocumentRequirementRepository requirementRepository;
    @Autowired StarterRequirements starterRequirements;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;

    private Ship tanker;
    private Ship bulk;
    private User olga;
    private User bob;
    private User carl;
    private User nora;
    private User rex;

    // The starter template, then:
    //   MV Tanker (TANKER): Olga Chief, Chief Officer: all 10 of her documents valid but the Tanker Endorsement (10 of 11).
    //                       Carl Cook, Cook: Passport valid, Medical expiring in 10 days, STCW expired (2 of 6).
    //                       Rex Norank: no rank (none apply; Rank needs review).
    //   MV Bulk (BULK_CARRIER): Bob Bosun, Bosun: all five valid (5 of 5). Nora New, Able Seaman: none (0 of 5).
    @BeforeEach
    void fleet() {
        assertThat(starterRequirements.seed()).isTrue();
        tanker = ship("MV Tanker", "9100001", ShipType.TANKER);
        bulk = ship("MV Bulk", "9100002", ShipType.BULK_CARRIER);
        olga = crew(tanker, "Olga Chief", Rank.CHIEF_OFFICER);
        carl = crew(tanker, "Carl Cook", Rank.COOK);
        rex = userService.createUser("rex.norank@example.com", "Rex Norank", "secret123", Role.CREW_MEMBER, tanker, null);
        bob = crew(bulk, "Bob Bosun", Rank.BOSUN);
        nora = crew(bulk, "Nora New", Rank.ABLE_SEAMAN);
        for (String type : List.of("STCW Basic Safety Training", "Medical Fitness Certificate", "Seaman's Book",
                "Passport", "Security Awareness", "Certificate of Competency", "Flag State Endorsement",
                "GMDSS Operator", "Advanced Fire Fighting", "Medical First Aid")) {
            certificate(olga, type, 400);
        }
        for (String type : List.of("STCW Basic Safety Training", "Medical Fitness Certificate", "Seaman's Book",
                "Passport", "Security Awareness")) {
            certificate(bob, type, 300);
        }
        certificate(carl, "Passport", 500);
        certificate(carl, "Medical Fitness Certificate", 10);
        certificate(carl, "STCW Basic Safety Training", -3);
    }

    @Test
    void aChiefOfficerOnATankerWithoutTankerEndorsementIsMissingEverywhereUntilItIsUnticked() throws Exception {
        // The office's crew member page: the card, missing first, with Add certificate filled in and coming back.
        String page = html(get("/office/crew/{id}/edit", olga.getId()));
        assertThat(page).contains("id=\"requiredDocuments\"", "For Chief Officer on MV Tanker (vessel type: Tanker).");
        List<String[]> rows = cardRows(page);
        assertThat(rows).hasSize(11);
        assertThat(rows.getFirst()).containsExactly("Tanker Endorsement", "Missing");
        assertThat(rows.subList(1, 11)).allSatisfy(r -> assertThat(r[1]).isEqualTo("Valid"));
        assertThat(summary(page)).isEqualTo("10 of 11");
        assertThat(page).contains("/office/crew/" + olga.getId() + "/certificates/new?type=Tanker%20Endorsement&amp;returnTo=/office/crew/"
                + olga.getId() + "/edit", "aria-label=\"Add certificate: Tanker Endorsement\"",
                "href=\"/office/requirements?vesselType=TANKER\"");

        // The Crew list, the vessel's matrix, the Overview.
        assertThat(listSummary(html(get("/office/crew")), "Olga Chief")).isEqualTo("10 of 11");
        String matrix = html(get("/office/ships/{id}/certificates", tanker.getId()));
        assertThat(matrix).contains("Tanker Endorsement</th>");
        assertThat(matrixCell(matrix, "Olga Chief", "Tanker Endorsement")).contains("cert-matrix-missing", "<span>Missing</span>");
        String overview = html(get("/office"));
        assertThat(overview).contains("/office/crew/" + olga.getId() + "/edit#requiredDocuments", "1 required document missing");

        // Her own My profile (first in My certificates, with the office to contact) and her Home.
        String profile = html(get("/ship/profile").with(user(new CurrentUser(olga))));
        assertThat(profile).containsSubsequence("cert-row-required", "Tanker Endorsement", "Contact the office to add it.",
                "<span>Missing</span>", "Advanced Fire Fighting");
        String home = html(get("/ship").with(user(new CurrentUser(olga))));
        assertThat(home).contains("href=\"/ship/profile#myCertificatesTitle\"", "1 required document missing");

        // Untick: Tanker Endorsement no longer required of anyone on tankers.
        mockMvc.perform(post("/office/requirements").param("vesselType", "TANKER")
                        .param("rows[0].type", "Tanker Endorsement").with(csrf()))
                .andExpect(redirectedUrl("/office/requirements?vesselType=TANKER"))
                .andExpect(flash().attribute("successMessage", "requirement.saved"));
        assertThat(audit("REQUIREMENTS_UPDATED")).containsExactly("TANKER: Tanker Endorsement removed");

        page = html(get("/office/crew/{id}/edit", olga.getId()));
        assertThat(summary(page)).isEqualTo("10 of 10");
        assertThat(page).doesNotContain("Tanker Endorsement", "<span>Missing</span>");
        assertThat(listSummary(html(get("/office/crew")), "Olga Chief")).isEqualTo("10 of 10");
        assertThat(html(get("/office/ships/{id}/certificates", tanker.getId()))).doesNotContain("Tanker Endorsement");
        assertThat(html(get("/office"))).doesNotContain("/office/crew/" + olga.getId() + "/edit#requiredDocuments");
        assertThat(html(get("/ship/profile").with(user(new CurrentUser(olga))))).doesNotContain("Tanker Endorsement",
                "cert-row-required", "Contact the office to add it.");
        assertThat(html(get("/ship").with(user(new CurrentUser(olga))))).doesNotContain("documents-missing-link");
    }

    @Test
    void theGridShowsTheStarterTemplateAndSavesWhatIsTicked() throws Exception {
        String grid = html(get("/office/requirements"));
        assertThat(grid).contains("Starter template: a suggestion to review", "<span class=\"badge status-badge status-info\">Suggestion</span>",
                "It does not say what any regulation requires", "<form action=\"/office/requirements\" method=\"post\"");
        // Passport for every rank; GMDSS for the Master and the deck officers only.
        assertThat(checked(grid, "Passport", Rank.COOK)).isTrue();
        assertThat(checked(grid, "GMDSS Operator", Rank.SECOND_OFFICER)).isTrue();
        assertThat(checked(grid, "GMDSS Operator", Rank.SECOND_ENGINEER)).isFalse();

        // A vessel type's grid: All vessels' ticks are grey ticks, said in words; its own are checkboxes.
        String tankers = html(get("/office/requirements").param("vesselType", "TANKER"));
        assertThat(tankers).contains("Required on Tanker vessels", "Passport, Cook: required on all vessels", "Save for Tanker");
        assertThat(checked(tankers, "Tanker Endorsement", Rank.COOK)).isTrue();

        // Add a document type: it shows first, marked New; tick, note, save.
        String added = html(get("/office/requirements").param("add", "Yellow Fever Certificate"));
        assertThat(added).containsSubsequence("<tbody>", "Yellow Fever Certificate", "New", "Passport");
        mockMvc.perform(post("/office/requirements").param("vesselType", "all")
                        .param("rows[0].type", "Yellow Fever Certificate").param("rows[0].ranks", "COOK", "BOSUN")
                        .param("rows[0].note", "Needed for West African ports").with(csrf()))
                .andExpect(redirectedUrl("/office/requirements?vesselType=all"));
        assertThat(audit("REQUIREMENTS_UPDATED")).singleElement().asString()
                .startsWith("All vessels: Yellow Fever Certificate added").contains("+COOK", "+BOSUN");
        grid = html(get("/office/requirements"));
        assertThat(checked(grid, "Yellow Fever Certificate", Rank.COOK)).isTrue();
        assertThat(checked(grid, "Yellow Fever Certificate", Rank.MASTER)).isFalse();
        // Saving All vessels confirms its suggestions; the tanker row stays one until Tanker is saved.
        assertThat(requirementRepository.findAll()).filteredOn(r -> r.isSuggestion())
                .extracting(r -> r.getCertificateType()).containsExactly("Tanker Endorsement");
        // The cook now needs it, with the office's note.
        assertThat(html(get("/office/crew/{id}/edit", carl.getId())))
                .contains("Yellow Fever Certificate", "Needed for West African ports");
        assertThat(summary(html(get("/office/crew/{id}/edit", carl.getId())))).isEqualTo("2 of 7");

        // A note too long: the grid again, with the error, and nothing saved.
        String tooLong = html(post("/office/requirements").param("vesselType", "all")
                .param("rows[0].type", "Passport").param("rows[0].ranks", "MASTER")
                .param("rows[0].note", "x".repeat(201)).with(csrf()));
        assertThat(tooLong).contains("Not saved: check the highlighted notes.", "Up to 200 characters.", "is-invalid");
        assertThat(audit("REQUIREMENTS_UPDATED")).hasSize(1);

        // Saving without a change only confirms: still recorded while a suggestion is reviewed, then not at all.
        mockMvc.perform(post("/office/requirements").param("vesselType", "TANKER")
                        .param("rows[0].type", "Tanker Endorsement").param("rows[0].ranks", Arrays.stream(Rank.values())
                                .map(Rank::name).toArray(String[]::new)).with(csrf()))
                .andExpect(redirectedUrl("/office/requirements?vesselType=TANKER"));
        assertThat(audit("REQUIREMENTS_UPDATED")).contains("TANKER: reviewed, no changes");
        assertThat(html(get("/office/requirements"))).doesNotContain("Starter template");
    }

    @Test
    void aSeafarerWithAllSomeOrNoneOfTheirDocuments() throws Exception {
        String list = html(get("/office/crew"));
        assertThat(listSummary(list, "Bob Bosun")).isEqualTo("5 of 5");
        assertThat(listSummary(list, "Carl Cook")).isEqualTo("2 of 6");
        assertThat(listSummary(list, "Nora New")).isEqualTo("0 of 5");
        assertThat(listSummary(list, "Rex Norank")).isNull();
        assertThat(list).contains("all held and valid", "4 missing or expired", "5 missing or expired",
                "Without a rank and a vessel, no required documents apply to them.");

        // Carl: missing and expired first, then expiring, then valid.
        List<String[]> carlRows = cardRows(html(get("/office/crew/{id}/edit", carl.getId())));
        assertThat(carlRows).extracting(r -> r[0] + ": " + r[1]).containsExactly(
                "Seaman's Book: Missing", "Security Awareness: Missing", "Tanker Endorsement: Missing",
                "STCW Basic Safety Training: Expired",
                "Medical Fitness Certificate: Expires in 10 days",
                "Passport: Valid");
        assertThat(cardRows(html(get("/office/crew/{id}/edit", bob.getId())))).hasSize(5)
                .allSatisfy(r -> assertThat(r[1]).isEqualTo("Valid"));
        assertThat(cardRows(html(get("/office/crew/{id}/edit", nora.getId())))).hasSize(5)
                .allSatisfy(r -> assertThat(r[1]).isEqualTo("Missing"));
        assertThat(html(get("/office/crew/{id}/edit", rex.getId())))
                .contains("No required documents apply: set a rank and a vessel first.");

        // Bob's home and profile say nothing; Nora's home counts five.
        assertThat(html(get("/ship").with(user(new CurrentUser(bob))))).doesNotContain("documents-missing-link");
        assertThat(html(get("/ship/profile").with(user(new CurrentUser(bob))))).doesNotContain("cert-row-required");
        assertThat(html(get("/ship").with(user(new CurrentUser(nora))))).contains("5 required documents missing");

        // The Overview: one row per person on board with something missing, after the expired certificates.
        String overview = html(get("/office"));
        assertThat(overview).containsSubsequence(">STCW Basic Safety Training</a>",
                        "/office/crew/" + carl.getId() + "/edit#requiredDocuments\">Carl Cook</a>",
                        ">Medical Fitness Certificate</a>", "<span>Required documents missing</span>")
                .contains("/office/crew/" + nora.getId() + "/edit#requiredDocuments\">Nora New</a>","4 required documents missing", "5 required documents missing",
                        "href=\"/office/crew?status=active&amp;documents=missing\"")
                .doesNotContain("/office/crew/" + bob.getId() + "/edit#requiredDocuments");
    }

    @Test
    void theMissingDocumentsFilterAndTheDocumentsSort() throws Exception {
        String missing = html(get("/office/crew").param("documents", "missing"));
        assertThat(names(missing)).containsExactlyInAnyOrder("Olga Chief", "Carl Cook", "Nora New");
        assertThat(missing).containsPattern("id=\"filterDocuments\"[^>]*checked=\"checked\"").contains(
                "Missing documents");
        assertThat(names(html(get("/office/ships/{id}/crew", bulk.getId()).param("documents", "missing"))))
                .containsExactly("Nora New");
        // Most missing first, then complete, then no requirements.
        assertThat(names(html(get("/office/crew").param("sort", "documents").param("dir", "desc"))))
                .startsWith("Nora New", "Carl Cook", "Olga Chief", "Bob Bosun").endsWith("Rex Norank");
    }

    @Test
    void theExportHoldsTheRowsOfThePage() throws Exception {
        String list = html(get("/office/crew").param("documents", "missing"));
        assertThat(list).contains("href=\"/office/crew/documents/export?documents=missing\"", "Export required documents");
        XlsSheet sheet = XlsSheet.of(mockMvc.perform(get("/office/crew/documents/export").param("documents", "missing"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/vnd.ms-excel"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"required-documents-" + today() + ".xls\""))
                .andReturn().getResponse().getContentAsByteArray());
        assertThat(sheet.name()).isEqualTo("Required documents");
        assertThat(sheet.header()).containsExactly("imo_number", "ship_name", "crew_member", "rank", "requirement",
                "required", "state", "expires_on");
        assertThat(sheet.body()).hasSize(11 + 6 + 5);
        // Olga's rows are her card's, in its order.
        List<String[]> card = cardRows(html(get("/office/crew/{id}/edit", olga.getId())));
        List<List<Object>> olgas = sheet.body().stream().filter(r -> "Olga Chief".equals(r.get(2))).toList();
        assertThat(olgas).extracting(r -> r.get(4) + ": " + r.get(6))
                .containsExactlyElementsOf(card.stream().map(r -> r[0] + ": " + r[1].toLowerCase()).toList());
        assertThat(olgas.getFirst()).isEqualTo(Arrays.asList("9100001", "MV Tanker", "Olga Chief", "CHIEF_OFFICER",
                "Tanker Endorsement", "yes", "missing", null));
        assertThat(olgas.get(1)).endsWith("yes", "valid", today().plusDays(400));
        assertThat(sheet.body()).filteredOn(r -> "Carl Cook".equals(r.get(2)))
                .anySatisfy(r -> assertThat(r).endsWith("Medical Fitness Certificate", "yes", "expiring", today().plusDays(10)));

        // The vessel's Crew tab: its own file.
        XlsSheet tab = XlsSheet.of(mockMvc.perform(get("/office/ships/{id}/crew/documents/export", bulk.getId()))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"required-documents-9100002-" + today() + ".xls\""))
                .andReturn().getResponse().getContentAsByteArray());
        assertThat(tab.body()).extracting(r -> r.get(2)).containsOnly("Bob Bosun", "Nora New").hasSize(10);
    }

    @Test
    void theCrewListRunsTheSameQueriesWhateverItsSize() throws Exception {
        long small = queriesOfCrewList();
        for (int i = 0; i < 6; i++) {
            User extra = crew(i % 2 == 0 ? tanker : bulk, "Extra Hand" + (char) ('A' + i), Rank.ORDINARY_SEAMAN);
            certificate(extra, "Passport", 100);
        }
        long large = queriesOfCrewList();
        // The whole page, layout included; P42 adds two: the requirements and the certificates of the page's people.
        assertThat(small).isEqualTo(15);
        assertThat(large).isEqualTo(small);
    }

    @Test
    void withoutAnyRequirementTheCrewListHasNoDocumentsColumn() throws Exception {
        requirementRepository.deleteAll();
        String list = html(get("/office/crew"));
        assertThat(list).doesNotContain("filterDocuments", "Export required documents", "documents-summary");
        assertThat(html(get("/office/crew/{id}/edit", olga.getId())))
                .contains("No documents are required for this rank on this vessel.");
        // The template is never added again once it was.
        assertThat(starterRequirements.seed()).isFalse();
    }

    // ---------------------------------------------------------------------------------------------------------------

    private long queriesOfCrewList() throws Exception {
        entityManager.flush();
        entityManager.clear();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        html(get("/office/crew"));
        long queries = statistics.getPrepareStatementCount();
        statistics.setStatisticsEnabled(false);
        return queries;
    }

    // Each row of the Required documents card: the type and the state's words.
    private static List<String[]> cardRows(String page) {
        return CARD_ROW.matcher(page).results()
                .map(m -> new String[] {HtmlUtils.htmlUnescape(m.group(1)), HtmlUtils.htmlUnescape(m.group(2))}).toList();
    }

    // The "5 of 7" of the first summary on the page.
    private static String summary(String page) {
        Matcher m = SUMMARY.matcher(page);
        return m.find() ? m.group(1) : null;
    }

    // The Documents cell of a person's row on the Crew list; null for the dash.
    private static String listSummary(String list, String name) {
        int start = list.indexOf(">" + name + "</a>");
        assertThat(start).as(name + " on the list").isPositive();
        return summary(list.substring(start, list.indexOf("</tr>", start)));
    }

    // A matrix cell, by the person's row and the type's column.
    private static String matrixCell(String matrix, String name, String type) {
        List<String> types = Pattern.compile("<th scope=\"col\" class=\"cert-matrix-type\">([^<]*)</th>").matcher(matrix)
                .results().map(m -> HtmlUtils.htmlUnescape(m.group(1))).toList();
        int column = types.indexOf(type);
        assertThat(column).as(type + " column").isNotNegative();
        int start = matrix.indexOf(">" + name + "</a>");
        String row = matrix.substring(start, matrix.indexOf("</tr>", start));
        String[] cells = row.split("<td ");
        return cells[column + 1];
    }

    private static boolean checked(String grid, String type, Rank rank) {
        int row = grid.indexOf("<span class=\"req-grid-type-name\">" + HtmlUtils.htmlEscape(type) + "</span>");
        assertThat(row).as(type + " row").isPositive();
        String tr = grid.substring(row, grid.indexOf("</tr>", row));
        Matcher m = Pattern.compile("<input [^>]*name=\"rows\\[\\d+\\]\\.ranks\" value=\"" + rank.name() + "\"([^>]*)>")
                .matcher(tr);
        if (!m.find()) {
            // No checkbox: the grey tick of a rank already required on all vessels.
            return tr.contains("req-cell-inherited");
        }
        return m.group(1).contains("checked");
    }

    private static List<String> names(String html) {
        return CHIP_OR_NAME.matcher(html).results().map(m -> HtmlUtils.htmlUnescape(m.group(1))).toList();
    }

    private List<String> audit(String action) {
        return auditRepository.findAll().stream().filter(e -> e.getAction().equals(action))
                .map(AuditEntry::getDetails).toList();
    }

    private String html(RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    private Ship ship(String name, String imo, ShipType type) {
        Ship ship = new Ship();
        ship.setImoNumber(imo);
        ship.setName(name);
        ship.setType(type);
        ship.setActive(true);
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
