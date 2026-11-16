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
import java.util.EnumSet;
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
import com.nereonix.nereonet_mvp.core.requirement.DocumentRequirement;
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
 * Required documents per rank and per vessel, each vessel starting with its type's defaults (a new vessel, the
 * filter of vessels only, Changed, Reset, a type change, the migration from rules by vessel type). The grid (starter template, ticking, adding a type, a note too
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
    // An apostrophe as Thymeleaf writes it in text and attributes.
    private static final String APOS = "&#39;";
    private static final Pattern CHIP_OR_NAME = Pattern.compile("(?s)<td class=\"cell-primary\">\\s*<a [^>]*>([^<]*)</a>");

    @Autowired MockMvc mockMvc;
    @Autowired ShipRepository shipRepository;
    @Autowired UserService userService;
    @Autowired CertificateRepository certificateRepository;
    @Autowired AuditRepository auditRepository;
    @Autowired DocumentRequirementRepository requirementRepository;
    @Autowired StarterRequirements starterRequirements;
    @Autowired RequirementService requirementService;
    @Autowired VesselRequirementsMigration migration;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;

    private Ship tanker;
    private Ship bulk;
    private User olga;
    private User bob;
    private User carl;
    private User nora;
    private User rex;

    // The two vessels, then the starter template (each vessel gets its type's defaults):
    //   MV Tanker (TANKER): Olga Chief, Chief Officer: all 10 of her documents valid but the Tanker Endorsement (10 of 11).
    //                       Carl Cook, Cook: Passport valid, Medical expiring in 10 days, STCW expired (2 of 6).
    //                       Rex Norank: no rank (none apply; Rank needs review).
    //   MV Bulk (BULK_CARRIER): Bob Bosun, Bosun: all five valid (5 of 5). Nora New, Able Seaman: none (0 of 5).
    @BeforeEach
    void fleet() {
        tanker = ship("MV Tanker", "9100001", ShipType.TANKER);
        bulk = ship("MV Bulk", "9100002", ShipType.BULK_CARRIER);
        assertThat(starterRequirements.seed()).isTrue();
        assertThat(requirementRepository.findAll()).hasSize(11 + 10).allMatch(DocumentRequirement::isSuggestion);
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
        // "Change what is required" opens her vessel's grid.
        assertThat(page).contains("/office/crew/" + olga.getId() + "/certificates/new?type=Tanker%20Endorsement&amp;returnTo=/office/crew/"
                + olga.getId() + "/edit", "aria-label=\"Add certificate: Tanker Endorsement\"",
                "href=\"/office/requirements?vessel=" + tanker.getId() + "\"");

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

        // Untick on MV Tanker: Tanker Endorsement no longer required of anyone on board.
        mockMvc.perform(post("/office/requirements").param("vessel", tanker.getId().toString())
                        .param("rows[0].type", "Tanker Endorsement").with(csrf()))
                .andExpect(redirectedUrl("/office/requirements?vessel=" + tanker.getId()))
                .andExpect(flash().attribute("successMessage", "requirement.saved"));
        assertThat(audit("REQUIREMENTS_UPDATED")).containsExactly("MV Tanker: Tanker Endorsement removed");

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
    void theGridShowsTheVesselsDefaultsAndSavesWhatIsTicked() throws Exception {
        // Without a choice: the first vessel by name, MV Bulk, with the Bulk carrier defaults as suggestions.
        String grid = html(get("/office/requirements"));
        assertThat(grid).contains("Starter template: a suggestion to review", "<span class=\"badge status-badge status-info\">Suggestion</span>",
                "It does not say what any regulation requires", "<form action=\"/office/requirements\" method=\"post\"",
                "Required on MV Bulk", "Based on the defaults for Bulk carrier.", "Save for MV Bulk",
                "<input type=\"hidden\" name=\"vessel\" value=\"" + bulk.getId() + "\">")
                .doesNotContain("status-neutral\">Changed</span>");
        // Passport for every rank; GMDSS for the Master and the deck officers only; no Tanker Endorsement on a bulker.
        assertThat(checked(grid, "Passport", Rank.COOK)).isTrue();
        assertThat(checked(grid, "GMDSS Operator", Rank.SECOND_OFFICER)).isTrue();
        assertThat(checked(grid, "GMDSS Operator", Rank.SECOND_ENGINEER)).isFalse();
        assertThat(checked(grid, "Tanker Endorsement", Rank.COOK)).isFalse();

        // The tanker's grid: its own defaults, Tanker Endorsement included.
        String tankers = html(get("/office/requirements").param("vessel", tanker.getId().toString()));
        assertThat(tankers).contains("Required on MV Tanker", "Based on the defaults for Tanker.", "Save for MV Tanker");
        assertThat(checked(tankers, "Tanker Endorsement", Rank.COOK)).isTrue();

        // Add a document type on MV Tanker: it shows first, marked New; tick, note, save.
        String added = html(get("/office/requirements").param("vessel", tanker.getId().toString())
                .param("add", "Yellow Fever Certificate"));
        assertThat(added).containsSubsequence("<tbody>", "Yellow Fever Certificate", "New", "Passport");
        mockMvc.perform(post("/office/requirements").param("vessel", tanker.getId().toString())
                        .param("rows[0].type", "Yellow Fever Certificate").param("rows[0].ranks", "COOK", "BOSUN")
                        .param("rows[0].note", "Needed for West African ports").with(csrf()))
                .andExpect(redirectedUrl("/office/requirements?vessel=" + tanker.getId()));
        assertThat(audit("REQUIREMENTS_UPDATED")).singleElement().asString()
                .startsWith("MV Tanker: Yellow Fever Certificate added").contains("+COOK", "+BOSUN");
        tankers = html(get("/office/requirements").param("vessel", tanker.getId().toString()));
        assertThat(checked(tankers, "Yellow Fever Certificate", Rank.COOK)).isTrue();
        assertThat(checked(tankers, "Yellow Fever Certificate", Rank.MASTER)).isFalse();
        // Beyond the defaults: marked Changed; the tanker's rows are no longer suggestions, the bulker's still are.
        assertThat(typeCell(tankers, "Yellow Fever Certificate")).contains("status-neutral\">Changed</span>");
        assertThat(typeCell(tankers, "Passport")).doesNotContain("Changed");
        assertThat(requirementRepository.findAll()).filteredOn(DocumentRequirement::isSuggestion)
                .hasSize(10).allMatch(r -> r.isFor(bulk));
        assertThat(checked(html(get("/office/requirements")), "Yellow Fever Certificate", Rank.COOK)).isFalse();
        // The cook now needs it, with the office's note.
        assertThat(html(get("/office/crew/{id}/edit", carl.getId())))
                .contains("Yellow Fever Certificate", "Needed for West African ports");
        assertThat(summary(html(get("/office/crew/{id}/edit", carl.getId())))).isEqualTo("2 of 7");

        // A note too long: the grid again, with the error, and nothing saved.
        String tooLong = html(post("/office/requirements").param("vessel", bulk.getId().toString())
                .param("rows[0].type", "Passport").param("rows[0].ranks", "MASTER")
                .param("rows[0].note", "x".repeat(201)).with(csrf()));
        assertThat(tooLong).contains("Not saved: check the highlighted notes.", "Up to 200 characters.", "is-invalid",
                "Required on MV Bulk");
        assertThat(audit("REQUIREMENTS_UPDATED")).hasSize(1);

        // Saving without a change only confirms: still recorded while a suggestion is reviewed, then not at all.
        mockMvc.perform(post("/office/requirements").param("vessel", bulk.getId().toString())
                        .param("rows[0].type", "Passport").param("rows[0].ranks", Arrays.stream(Rank.values())
                                .map(Rank::name).toArray(String[]::new)).with(csrf()))
                .andExpect(redirectedUrl("/office/requirements?vessel=" + bulk.getId()));
        assertThat(audit("REQUIREMENTS_UPDATED")).contains("MV Bulk: reviewed, no changes");
        assertThat(html(get("/office/requirements"))).doesNotContain("Starter template");
        // A post for an unknown vessel changes nothing.
        mockMvc.perform(post("/office/requirements").param("vessel", "999999")
                        .param("rows[0].type", "Passport").with(csrf()))
                .andExpect(redirectedUrl("/office/requirements"))
                .andExpect(flash().attribute("errorMessage", "requirement.error.noVessel"));
    }

    // The row toggle: it acts on the shown vessel only.
    @Test
    void theRowToggleTicksEveryRankOfThisVesselAlsoWithoutJavaScript() throws Exception {
        String grid = html(get("/office/requirements"));
        // Each row starts with "Select all ranks for <type>", a checkbox the server applies too; no placeholder on
        // the note field.
        assertThat(grid).contains("aria-label=\"Select all ranks for Passport\"", "name=\"rows[0].all\"", "data-row-all",
                        "data-row-rank", "data-requirement-row")
                .doesNotContain("placeholder=", "For example: needed before joining", "req-cell-inherited");
        assertThat(checked(grid, "GMDSS Operator", Rank.COOK)).isFalse();

        mockMvc.perform(post("/office/requirements").param("vessel", bulk.getId().toString())
                        .param("rows[0].type", "GMDSS Operator").param("rows[0].all", "true").with(csrf()))
                .andExpect(redirectedUrl("/office/requirements?vessel=" + bulk.getId()));
        grid = html(get("/office/requirements"));
        for (Rank rank : Rank.values()) {
            assertThat(checked(grid, "GMDSS Operator", rank)).as(rank.name()).isTrue();
        }
        assertThat(audit("REQUIREMENTS_UPDATED")).singleElement().asString().startsWith("MV Bulk: GMDSS Operator +")
                .contains("+COOK", "+WIPER").doesNotContain("+MASTER");
        // The tanker keeps its own.
        String tankers = html(get("/office/requirements").param("vessel", tanker.getId().toString()));
        assertThat(checked(tankers, "GMDSS Operator", Rank.COOK)).isFalse();
        assertThat(checked(tankers, "GMDSS Operator", Rank.MASTER)).isTrue();
    }

    @Test
    void theFilterListsOnlyTheVessels() throws Exception {
        ship("MV Old", "9100009", ShipType.TANKER).setActive(false);
        String grid = html(get("/office/requirements"));
        // Without JavaScript: a select of the active vessels, by name, each with its type for the combobox; no vessel
        // types and no All vessels. The first is shown when none is chosen.
        assertThat(grid).contains("<label for=\"vessel\" class=\"form-label\">Vessel</label>",
                        "<select id=\"vessel\" name=\"vessel\" class=\"form-select\"",
                        "<option value=\"" + tanker.getId() + "\" data-kind=\"Tanker\">MV Tanker</option>",
                        "data-requirement-filter", "data-no-results=\"No vessel matches.\"")
                .containsPattern("<option value=\"" + bulk.getId() + "\" data-kind=\"Bulk carrier\"\\s+selected=\"selected\">MV Bulk</option>")
                .containsSubsequence(">MV Bulk</option>", ">MV Tanker</option>")
                .doesNotContain("<optgroup", "All vessels", "data-kind=\"vessel type\"", "value=\"TANKER\"", "MV Old");

        // A vessel shows its own grid; an unknown or inactive one the first vessel.
        assertThat(html(get("/office/requirements").param("vessel", tanker.getId().toString())))
                .containsPattern("<option value=\"" + tanker.getId() + "\" data-kind=\"Tanker\"\\s+selected=\"selected\">")
                .contains("<input type=\"hidden\" name=\"vessel\" value=\"" + tanker.getId() + "\">");
        assertThat(html(get("/office/requirements").param("vessel", "999999"))).contains("Required on MV Bulk");
        assertThat(html(get("/office/requirements").param("vessel", "TANKER"))).contains("Required on MV Bulk");
        // A vessel without a type: the general defaults.
        Ship untyped = ship("MV Plain", "9100010", null);
        requirementService.startWithDefaults(untyped);
        assertThat(html(get("/office/requirements").param("vessel", untyped.getId().toString())))
                .contains("data-kind=\"type not set\"", "Based on the general defaults: this vessel has no type.",
                        "Reset to the general defaults");
        // In Greek.
        assertThat(html(get("/office/requirements").param("lang", "el")))
                .contains(">Πλοίο</label>", "Απαιτούνται στο MV Bulk", "Με βάση τις προεπιλογές για");
        // No active vessel at all: an empty state that leads to Add vessel.
        tanker.setActive(false);
        bulk.setActive(false);
        untyped.setActive(false);
        assertThat(html(get("/office/requirements"))).contains("No vessels yet", "href=\"/office/ships/new\"")
                .doesNotContain("<table");
    }

    @Test
    void deleteRowAsksFirstAndRemovesTheTypeFromTheShownVesselOnly() throws Exception {
        String tankers = html(get("/office/requirements").param("vessel", tanker.getId().toString()));
        // The icon button at the row's end names the row and the vessel; its confirmation form sits after the grid form.
        int row = tankers.indexOf("<span class=\"req-grid-type-name\">Tanker Endorsement</span>");
        String tr = tankers.substring(row, tankers.indexOf("</tr>", row));
        assertThat(tr).contains("class=\"btn btn-ghost btn-sm req-row-delete\"",
                "href=\"/office/requirements/delete?type=Tanker%20Endorsement&amp;vessel=" + tanker.getId() + "\"",
                "data-confirm-open", "data-confirm-form=\"reqDelete",
                "<span class=\"visually-hidden\">Remove Tanker Endorsement from MV Tanker" + APOS + "s requirements</span>");
        assertThat(tankers).contains("data-confirm-title=\"Remove Tanker Endorsement?\"",
                "data-confirm-body=\"Removes Tanker Endorsement from MV Tanker" + APOS + "s requirements. The other vessels keep theirs.\"",
                "data-confirm-tone=\"danger\"", "<input type=\"hidden\" name=\"type\" value=\"Tanker Endorsement\">");
        // Without the script: the plain confirmation page.
        assertThat(html(get("/office/requirements/delete").param("type", "Tanker Endorsement")
                        .param("vessel", tanker.getId().toString())))
                .contains("Remove Tanker Endorsement?", "from MV Tanker" + APOS + "s requirements", ">Remove</button>",
                        "href=\"/office/requirements?vessel=" + tanker.getId() + "\"");

        mockMvc.perform(post("/office/requirements/delete").param("type", "Passport")
                        .param("vessel", tanker.getId().toString()).with(csrf()))
                .andExpect(redirectedUrl("/office/requirements?vessel=" + tanker.getId()))
                .andExpect(flash().attribute("successMessage", "requirement.row.removed"));
        assertThat(audit("REQUIREMENTS_UPDATED")).containsExactly("MV Tanker: Passport removed");
        // Olga no longer needs a passport; on MV Bulk Bob still does.
        assertThat(summary(html(get("/office/crew/{id}/edit", olga.getId())))).isEqualTo("9 of 10");
        assertThat(summary(html(get("/office/crew/{id}/edit", bob.getId())))).isEqualTo("5 of 5");
        assertThat(checked(html(get("/office/requirements")), "Passport", Rank.COOK)).isTrue();

        // Removing a type the vessel does not require changes nothing.
        mockMvc.perform(post("/office/requirements/delete").param("type", "Tanker Endorsement")
                        .param("vessel", bulk.getId().toString()).with(csrf()))
                .andExpect(redirectedUrl("/office/requirements?vessel=" + bulk.getId()))
                .andExpect(flash().attribute("errorMessage", "requirement.row.nothing"));
        assertThat(audit("REQUIREMENTS_UPDATED")).hasSize(1);
    }

    // A new vessel starts with its type's defaults; its changes are its own; Reset brings the defaults back.
    @Test
    void eachVesselHasItsOwnRequirementsStartingWithItsTypesDefaults() throws Exception {
        Ship alder = newVessel("9300001", "ALDER");
        Ship laurel = newVessel("9400007", "LAUREL");
        String grid = html(get("/office/requirements").param("vessel", alder.getId().toString()));
        assertThat(grid).contains("Required on ALDER", "Based on the defaults for Bulk carrier.",
                        "Reset to Bulk carrier defaults", "status-info\">Suggestion</span>")
                .doesNotContain("status-neutral\">Changed</span>");
        assertThat(checked(grid, "Passport", Rank.COOK)).isTrue();
        assertThat(checked(grid, "Tanker Endorsement", Rank.COOK)).isFalse();
        // Nothing to reset yet: the confirmation says so.
        assertThat(grid).contains("data-confirm-body=\"ALDER already has these defaults. Nothing will change.\"");

        // Unticking the cook's passport on ALDER leaves LAUREL as it was.
        mockMvc.perform(post("/office/requirements").param("vessel", alder.getId().toString())
                        .param("rows[0].type", "Passport").param("rows[0].ranks", Arrays.stream(Rank.values())
                                .filter(r -> r != Rank.COOK).map(Rank::name).toArray(String[]::new)).with(csrf()))
                .andExpect(redirectedUrl("/office/requirements?vessel=" + alder.getId()));
        assertThat(audit("REQUIREMENTS_UPDATED")).containsExactly("ALDER: Passport -COOK");
        grid = html(get("/office/requirements").param("vessel", alder.getId().toString()));
        assertThat(checked(grid, "Passport", Rank.COOK)).isFalse();
        assertThat(typeCell(grid, "Passport")).contains("status-neutral\">Changed</span>");
        assertThat(typeCell(grid, "Seaman's Book")).doesNotContain("Changed");
        assertThat(checked(html(get("/office/requirements").param("vessel", laurel.getId().toString())), "Passport",
                Rank.COOK)).isTrue();

        // Reset: the dialog lists what will change, on ALDER only (the plain page too).
        String form = grid.substring(grid.indexOf("action=\"/office/requirements/reset\""));
        assertThat(form.substring(0, form.indexOf("</form>"))).contains(
                "data-confirm-title=\"Reset ALDER to the Bulk carrier defaults?\"",
                "data-confirm-body=\"These changes apply to ALDER only. The other vessels keep theirs.\"",
                "<input type=\"hidden\" name=\"vessel\" value=\"" + alder.getId() + "\">", "data-confirm-items",
                "<li>Passport goes back to the default ranks, not optional and without a note</li>");
        assertThat(grid).contains("data-confirm-form=\"reqReset\"",
                "href=\"/office/requirements/reset?vessel=" + alder.getId() + "\"");
        assertThat(html(get("/office/requirements/reset").param("vessel", alder.getId().toString())))
                .contains("Reset ALDER to the Bulk carrier defaults?", "class=\"confirm-page-items\"",
                        "<li>Passport goes back to the default ranks, not optional and without a note</li>", ">Reset</button>");
        mockMvc.perform(post("/office/requirements/reset").param("vessel", alder.getId().toString()).with(csrf()))
                .andExpect(redirectedUrl("/office/requirements?vessel=" + alder.getId()))
                .andExpect(flash().attribute("successMessage", "requirement.reset.done"));
        assertThat(audit("REQUIREMENTS_RESET")).containsExactly(
                "ALDER: reset to the BULK_CARRIER defaults: Passport restored");
        grid = html(get("/office/requirements").param("vessel", alder.getId().toString()));
        assertThat(checked(grid, "Passport", Rank.COOK)).isTrue();
        assertThat(grid).doesNotContain("status-neutral\">Changed</span>");
    }

    // When a vessel's type changes, the office chooses to keep its requirements (the default) or reset them.
    @Test
    void aTypeChangeKeepsTheRequirementsUnlessResetIsChosen() throws Exception {
        Ship alder = newVessel("9300001", "ALDER");
        Ship laurel = newVessel("9400007", "LAUREL");
        // The edit page asks, Keep first and chosen; shown by the script only once the type changes.
        String edit = html(get("/office/ships/{id}/edit", alder.getId()));
        assertThat(edit).containsPattern("data-changed-from=\"editType\"\\s+data-initial-value=\"BULK_CARRIER\"")
                .contains("Required documents if the type changes", "Keep this vessel" + APOS + "s required documents",
                        "Reset them to the new type" + APOS + "s defaults")
                .containsPattern("id=\"editRequirementsKeep\" name=\"resetRequirements\" value=\"false\"\\s+checked=\"checked\"");

        mockMvc.perform(post("/office/ships/{id}/edit", alder.getId()).param("name", "ALDER").param("type", "TANKER")
                        .param("resetRequirements", "false").with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(checked(html(get("/office/requirements").param("vessel", alder.getId().toString())),
                "Tanker Endorsement", Rank.COOK)).isFalse();
        assertThat(audit("REQUIREMENTS_RESET")).isEmpty();

        mockMvc.perform(post("/office/ships/{id}/edit", laurel.getId()).param("name", "LAUREL").param("type", "TANKER")
                        .param("resetRequirements", "true").with(csrf()))
                .andExpect(status().is3xxRedirection());
        String grid = html(get("/office/requirements").param("vessel", laurel.getId().toString()));
        assertThat(checked(grid, "Tanker Endorsement", Rank.COOK)).isTrue();
        assertThat(grid).contains("Based on the defaults for Tanker.").doesNotContain("status-neutral\">Changed</span>");
        assertThat(audit("REQUIREMENTS_RESET")).containsExactly(
                "LAUREL: reset to the TANKER defaults: Tanker Endorsement added");
        // Reset chosen while the type stays the same: nothing happens.
        mockMvc.perform(post("/office/ships/{id}/edit", alder.getId()).param("name", "ALDER").param("type", "TANKER")
                        .param("resetRequirements", "true").with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(audit("REQUIREMENTS_RESET")).hasSize(1);
    }

    // The rules by vessel type become each vessel's own, and no seafarer's documents change.
    @Test
    void theMigrationGivesEveryVesselTheRulesThatAppliedToIt() throws Exception {
        requirementRepository.deleteAll();
        // Rows saved before P58: all vessels (null) and per vessel type rows, no vessel. Counted before: Carl (Cook, tanker):
        // Passport and Tanker Endorsement required, Yellow Fever optional for him; Olga (Chief Officer, tanker): Tanker
        // Endorsement and Yellow Fever; Bob (Bosun, bulker): Passport; Nora (Able Seaman, bulker): nothing.
        legacy("Passport", null, false, null, Rank.COOK, Rank.BOSUN);
        legacy("Yellow Fever Certificate", null, true, "For West Africa", Rank.COOK);
        legacy("Tanker Endorsement", ShipType.TANKER, false, "Before joining", Rank.COOK, Rank.CHIEF_OFFICER);
        legacy("Yellow Fever Certificate", ShipType.TANKER, false, null, Rank.CHIEF_OFFICER);
        legacy("Ship Security Officer", ShipType.CONTAINER, false, null, Rank.CHIEF_OFFICER);

        assertThat(migration.migrate()).isEqualTo(5);
        assertThat(requirementRepository.findAll()).allMatch(r -> r.getShip() != null && r.getShipType() == null);
        assertThat(audit("REQUIREMENTS_MIGRATED")).containsExactly("5 rules by vessel type copied to 2 vessels as 5 rows");
        // Each seafarer's count is as before.
        String list = html(get("/office/crew"));
        assertThat(listSummary(list, "Carl Cook")).isEqualTo("1 of 2");
        assertThat(listSummary(list, "Olga Chief")).isEqualTo("0 of 2");
        assertThat(listSummary(list, "Bob Bosun")).isEqualTo("1 of 1");
        assertThat(listSummary(list, "Nora New")).isNull();
        // The tanker's note is kept; the bulker has only the all-vessels rows, Yellow Fever optional.
        assertThat(html(get("/office/crew/{id}/edit", carl.getId()))).contains("Before joining");
        String bulkGrid = html(get("/office/requirements").param("vessel", bulk.getId().toString()));
        assertThat(checked(bulkGrid, "Passport", Rank.BOSUN)).isTrue();
        assertThat(checked(bulkGrid, "Yellow Fever Certificate", Rank.COOK)).isTrue();
        assertThat(checked(bulkGrid, "Tanker Endorsement", Rank.COOK)).isFalse();
        assertThat(checked(bulkGrid, "Ship Security Officer", Rank.CHIEF_OFFICER)).isFalse();
        assertThat(requirementRepository.findAll()).filteredOn(r -> r.isFor(bulk)
                        && r.getCertificateType().equals("Yellow Fever Certificate"))
                .singleElement().satisfies(r -> assertThat(r.isOptional()).isTrue());
        // It runs once.
        assertThat(migration.migrate()).isZero();
        assertThat(audit("REQUIREMENTS_MIGRATED")).hasSize(1);
    }

    @Test
    void aSeafarerWithAllSomeOrNoneOfTheirDocuments() throws Exception {
        String list = html(get("/office/crew"));
        assertThat(listSummary(list, "Bob Bosun")).isEqualTo("5 of 5");
        assertThat(listSummary(list, "Carl Cook")).isEqualTo("2 of 6");
        assertThat(listSummary(list, "Nora New")).isEqualTo("0 of 5");
        assertThat(listSummary(list, "Rex Norank")).isNull();
        assertThat(list).contains("all held and valid", "4 missing or expired", "5 missing or expired");

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
                        ">Medical Fitness Certificate</a>")
                .contains("/office/crew/" + nora.getId() + "/edit#requiredDocuments\">Nora New</a>","4 required documents missing", "5 required documents missing")
                .doesNotContain("/office/crew/" + bob.getId() + "/edit#requiredDocuments");
        // The Required documents chip selected, the full list of the crew with documents missing.
        assertThat(html(get("/office").param("type", "documents")))
                .contains("href=\"/office/crew?status=active&amp;documents=missing\">Open the full list</a>");
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
        // The whole page, layout included; two of them: the requirements and the certificates of the page's people.
        assertThat(small).isEqualTo(13);
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
        assertThat(m.find()).as(type + ", " + rank).isTrue();
        return m.group(1).contains("checked");
    }

    // The first cell of a type's row: its name and badges.
    private static String typeCell(String grid, String type) {
        int row = grid.indexOf("<span class=\"req-grid-type-name\">" + HtmlUtils.htmlEscape(type) + "</span>");
        assertThat(row).as(type + " row").isPositive();
        return grid.substring(row, grid.indexOf("</th>", row));
    }

    // A Bulk carrier added through Add vessel (it starts with the Bulk carrier defaults).
    private Ship newVessel(String imo, String name) throws Exception {
        mockMvc.perform(post("/office/ships/new").param("imoNumber", imo).param("name", name)
                        .param("type", "BULK_CARRIER").with(csrf()))
                .andExpect(redirectedUrl("/office/ships"));
        return shipRepository.findByImoNumber(imo).orElseThrow();
    }

    // A requirement saved before P58: by vessel type (null: all vessels), on no vessel.
    private void legacy(String type, ShipType shipType, boolean optional, String note, Rank... ranks) {
        DocumentRequirement r = new DocumentRequirement();
        r.setCertificateType(type);
        r.setShipType(shipType);
        r.setRanks(EnumSet.of(ranks[0], ranks));
        r.setOptional(optional);
        r.setNote(note);
        requirementRepository.save(r);
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
