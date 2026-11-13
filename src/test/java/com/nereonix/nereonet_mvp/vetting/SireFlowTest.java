package com.nereonix.nereonet_mvp.vetting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditEntry;
import com.nereonix.nereonet_mvp.core.audit.AuditRepository;
import com.nereonix.nereonet_mvp.core.config.DevData;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.library.LibraryItemRepository;
import com.nereonix.nereonet_mvp.core.library.LibraryItemVersionRepository;
import com.nereonix.nereonet_mvp.core.profile.CrewProfileRepository;
import com.nereonix.nereonet_mvp.core.request.ItemRequestRepository;
import com.nereonix.nereonet_mvp.core.request.RequestService;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.ship.ShipType;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;
import com.nereonix.nereonet_mvp.core.user.UserService;
import com.nereonix.nereonet_mvp.core.vetting.ObservationType;
import com.nereonix.nereonet_mvp.core.vetting.ShipRightShipStatus;
import com.nereonix.nereonet_mvp.core.vetting.SireInspection;
import com.nereonix.nereonet_mvp.core.vetting.SireInspectionRepository;
import com.nereonix.nereonet_mvp.core.vetting.SireObservation;
import com.nereonix.nereonet_mvp.core.vetting.SireObservationRepository;
import com.nereonix.nereonet_mvp.core.vetting.SireReportState;
import com.nereonix.nereonet_mvp.core.vetting.SireService;
import com.nereonix.nereonet_mvp.core.vetting.SireSettings;
import com.nereonix.nereonet_mvp.core.vetting.SireStatus;
import com.nereonix.nereonet_mvp.core.vetting.VettingService;

/**
 * P41: SIRE 2.0 in Vetting. The Vetting list's column and filters, a vessel's card (order by vessel type, the
 * collapsed line), the SIRE page (report current until, the operator comment deadline, observations by chapter, Open
 * and All, the report), Add / Edit inspection, Add observation and closing one, the sidebar counter and the
 * Overview, 404s for another vessel's records, and the dev data's BIRCH.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class SireFlowTest {

    private static final byte[] PDF = "%PDF-1.7\n% a SIRE report\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    private static final Pattern ROW_NAME = Pattern.compile("(?s)<td class=\"cell-primary\">\\s*<a [^>]*>([^<]*)</a>");
    private static final Pattern VETTING_COUNT =
            Pattern.compile("(?s)>Vetting</span>\\s*<span class=\"nav-count num\" aria-hidden=\"true\">(\\d+)</span>");

    @Autowired MockMvc mockMvc;
    @Autowired ShipRepository shipRepository;
    @Autowired UserRepository userRepository;
    @Autowired UserService userService;
    @Autowired VettingService vettingService;
    @Autowired SireService sireService;
    @Autowired SireInspectionRepository inspectionRepository;
    @Autowired SireObservationRepository observationRepository;
    @Autowired AuditRepository auditRepository;
    // For the dev data.
    @Autowired ItemRepository itemRepository;
    @Autowired ItemRequestRepository requestRepository;
    @Autowired LibraryItemRepository libraryItemRepository;
    @Autowired LibraryItemVersionRepository versionRepository;
    @Autowired CrewProfileRepository profileRepository;
    @Autowired RequestService requestService;

    LocalDate today;
    int imo;

    @BeforeEach
    void setUp() {
        today = VettingService.today();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The list
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void theListShowsTheSireColumnAndFiltersByRegime() throws Exception {
        Ship birch = vessel("BIRCH", ShipType.TANKER);
        SireInspection inspection = inspection(birch, LocalDate.of(2026, 6, 12), SireStatus.PUBLISHED, null);
        observation(inspection, "9.1.2", ObservationType.HARDWARE, today.minusDays(3), null);   // overdue
        observation(inspection, "7.3.1", ObservationType.HUMAN, today.plusDays(30), null);      // open
        observation(inspection, "5.4.1", ObservationType.HARDWARE, today.minusDays(60), today.minusDays(70)); // closed
        Ship cedar = vessel("CEDAR", ShipType.TANKER);
        inspection(cedar, today.minusMonths(2), SireStatus.COMMENTS_SENT, null);
        vessel("ALDER", ShipType.BULK_CARRIER);
        vessel("ELM", ShipType.TANKER); // a tanker without an inspection

        String html = html(get("/office/vetting"));
        assertThat(html).contains(">SIRE 2.0<", "Published · 12 Jun 2026 · 2 open", "1 overdue",
                "Operator comments sent · " + date(today.minusMonths(2)));
        // Most urgent first: BIRCH's overdue observation; the others by name.
        assertThat(names(html)).containsExactly("BIRCH", "ALDER", "CEDAR", "ELM");

        // Needs attention includes the SIRE items.
        assertThat(names(html(get("/office/vetting").param("status", "attention")))).containsExactly("BIRCH");
        // Regime: SIRE 2.0 keeps the tankers; its Status filter looks at SIRE only.
        assertThat(names(html(get("/office/vetting").param("regime", "sire")))).containsExactly("BIRCH", "CEDAR", "ELM");
        assertThat(names(html(get("/office/vetting").param("regime", "sire").param("status", "valid"))))
                .containsExactly("CEDAR");
        assertThat(names(html(get("/office/vetting").param("regime", "sire").param("status", "notrated"))))
                .containsExactly("ELM");
        // Regime: RightShip looks at RightShip only: nobody needs attention there.
        assertThat(names(html(get("/office/vetting").param("regime", "rightship").param("status", "attention")))).isEmpty();
        assertThat(html(get("/office/vetting").param("regime", "sire"))).contains("name=\"regime\"", "Regime");
        // Sorted by the SIRE column: the latest inspection first, none last.
        assertThat(names(html(get("/office/vetting").param("sort", "sire").param("dir", "desc"))))
                .containsExactly("CEDAR", "BIRCH", "ALDER", "ELM");

        // Greek office screens: the regime filter is "Σύστημα αξιολόγησης" (P43; "Καθεστώς" before), the vessel
        // types keep their English names.
        String greek = html(get("/office/vetting").param("lang", "el"));
        assertThat(greek).contains("Δημοσιεύθηκε · ", "Σύστημα αξιολόγησης", ">Tanker<", ">Bulk carrier<")
                .doesNotContain("Καθεστώς", "Δεξαμενόπλοιο");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // A vessel's Vetting page
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void aTankerShowsSireFirstAndOtherTypesACollapsedLine() throws Exception {
        Ship birch = vessel("BIRCH", ShipType.TANKER);
        SireInspection inspection = inspection(birch, LocalDate.of(2026, 6, 12), SireStatus.PUBLISHED, null);
        for (int i = 1; i <= 5; i++) {
            observation(inspection, "4.1." + i, ObservationType.PROCESS, today.plusDays(10), i <= 3 ? today : null);
        }
        String tanker = html(get("/office/vetting/{id}", birch.getId()));
        assertThat(tanker).containsSubsequence("id=\"regimeSire\"", "id=\"regimeRightShip\"")
                .contains("href=\"/office/vetting/" + birch.getId() + "/sire\">SIRE 2.0</a>", "12 Jun 2026", "Published",
                        "5 observations · 2 open", "Report current until " + date(LocalDate.of(2027, 6, 12)));

        Ship alder = vessel("ALDER", ShipType.BULK_CARRIER);
        String bulk = html(get("/office/vetting/{id}", alder.getId()));
        assertThat(bulk).containsSubsequence("id=\"regimeRightShip\"", "SIRE 2.0 applies to tankers.",
                        "href=\"/office/vetting/" + alder.getId() + "/sire/inspections/new\"", "Add inspection")
                .doesNotContain("href=\"/office/vetting/" + alder.getId() + "/sire\">SIRE 2.0</a>");

        // Once a bulk carrier has an inspection, its card is a full card (still after RightShip).
        inspection(alder, today.minusMonths(1), SireStatus.DRAFT_RECEIVED, null);
        assertThat(html(get("/office/vetting/{id}", alder.getId())))
                .containsSubsequence("id=\"regimeRightShip\"", "id=\"regimeSire\"").contains("Draft received")
                .doesNotContain("applies to tankers");

        // The vessel page header's badge row: a SIRE badge for tankers only; P43: its own link to the SIRE page.
        assertThat(html(get("/office/ships/{id}", birch.getId())))
                .containsPattern("(?s)class=\"vetting-badges\".*?<a class=\"vetting-badge\" href=\"/office/vetting/" + birch.getId()
                        + "/sire\">.*?SIRE 2.0.*?Report current until.*?\\(opens SIRE 2.0\\)</span>\\s*</a>");
        assertThat(html(get("/office/ships/{id}", alder.getId()))).doesNotContain("vetting-badge-label\">SIRE 2.0");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The SIRE page
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void theSirePageShowsTheInspectionObservationsAndPolicy() throws Exception {
        Ship birch = vessel("BIRCH", ShipType.TANKER);
        SireInspection inspection = inspection(birch, LocalDate.of(2026, 6, 12), SireStatus.PUBLISHED,
                LocalDate.of(2026, 6, 26));
        SireObservation overdue = observation(inspection, "9.1.2", ObservationType.HARDWARE, today.minusDays(3), null);
        observation(inspection, "7.3.1", ObservationType.HUMAN, today.plusDays(30), null);
        observation(inspection, "8.2.3", ObservationType.PROCESS, today.minusDays(60), today.minusDays(61));
        String url = "/office/vetting/" + birch.getId() + "/sire";

        String page = html(get(url));
        assertThat(page)
                .containsSubsequence("<nav class=\"page-breadcrumbs\"", ">Vetting</a>", ">BIRCH</a>", ">SIRE 2.0</li>")
                .contains("SIRE 2.0 inspection", "12 Jun 2026", "Piraeus, Greece", "Demo Energy Ltd", "DEMO-SIRE-0001",
                        "Published", "26 Jun 2026", "Report current until 12 Jun 2027", "Company policy:",
                        "12 months after the inspection", "14 days after the report is published", "not OCIMF rules",
                        "3 observations · 2 open", "Hardware", "Process", "Human",
                        "Chapter 9 · Mooring and Anchoring", "Chapter 7 · Maritime Security", "Overdue", "Open",
                        "href=\"" + url + "/inspections/" + inspection.getId() + "/observations/" + overdue.getId() + "/edit\"",
                        "Add inspection", "Upload report", "Add observation", "Open vessel", "No report yet")
                // Open is the default view: the closed observation (chapter 8) shows under All only.
                .doesNotContain("Chapter 8 · Cargo and Ballast Systems", "Operator comments due");
        assertThat(count(page, "btn btn-primary")).isEqualTo(1);
        assertThat(html(get(url).param("show", "all"))).contains("Chapter 8 · Cargo and Ballast Systems", "Closed");

        // Report current until: ending within 60 days (warning), then no longer current (danger).
        SireInspection ending = inspection(vessel("ENDING", ShipType.TANKER), today.minusMonths(12).plusDays(20),
                SireStatus.PUBLISHED, null);
        assertThat(sireService.view(ending).reportState()).isEqualTo(SireReportState.ENDING);
        assertThat(html(get("/office/vetting/{id}/sire", ending.getShip().getId())))
                .containsPattern("(?s)status-warning\">\\s*<svg[^>]*>.*?</svg>\\s*<span>Report current until "
                        + Pattern.quote(date(today.plusDays(20))));
        SireInspection old = inspection(vessel("OLD", ShipType.TANKER), today.minusMonths(13), SireStatus.PUBLISHED, null);
        assertThat(html(get("/office/vetting/{id}/sire", old.getShip().getId())))
                .containsPattern("(?s)status-danger\">\\s*<svg[^>]*>.*?</svg>\\s*<span>Report no longer current since "
                        + Pattern.quote(date(today.minusMonths(1))));

        // A vessel without an inspection: the empty state holds Add inspection, the only primary button.
        Ship elm = vessel("ELM", ShipType.TANKER);
        String empty = html(get("/office/vetting/{id}/sire", elm.getId()));
        assertThat(empty).contains("No SIRE 2.0 inspection yet", "Add inspection").doesNotContain("Upload report");
        assertThat(count(empty, "btn btn-primary")).isEqualTo(1);
    }

    @Test
    void theOperatorCommentDeadlineIsShownWhileTheDraftIsReceived() throws Exception {
        Ship birch = vessel("BIRCH", ShipType.TANKER);
        crew(birch);
        // Published 12 days ago: with 14 days for the comments, due in 2 days.
        SireInspection draft = inspection(birch, today.minusDays(20), SireStatus.DRAFT_RECEIVED, today.minusDays(12));
        String url = "/office/vetting/" + birch.getId() + "/sire";

        assertThat(html(get(url))).contains("Operator comments due " + date(today.plusDays(2)), "in 2 days", "alert-warning")
                .contains("Company policy: 14 days from the published date");
        // The Overview, the list and the counter.
        assertThat(attention(html(get("/office")))).contains("Operator comments", "BIRCH", "Due in 2 days",
                "Open SIRE 2.0", "href=\"" + url + "\"");
        assertThat(html(get("/office/vetting"))).contains("Comments due in 2 days");
        assertThat(vettingService.vesselsNeedingAttention()).isEqualTo(1);

        // Without a published date, counted from the inspection date: 6 days overdue.
        draft.setPublishedOn(null);
        assertThat(html(get(url))).contains("Operator comments due " + date(today.minusDays(6)), "6 days overdue",
                "alert-danger");

        // Due in 10 days: shown on the page, but not yet an item anywhere else.
        draft.setPublishedOn(today.minusDays(4));
        assertThat(html(get(url))).contains("Operator comments due " + date(today.plusDays(10)), "alert-info");
        assertThat(vettingService.vesselsNeedingAttention()).isZero();
        assertThat(attention(html(get("/office")))).doesNotContain("Operator comments");

        // Once the comments are sent, no deadline.
        draft.setStatus(SireStatus.COMMENTS_SENT);
        assertThat(html(get(url))).doesNotContain("Operator comments due");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Add and edit
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void theOfficeAddsAndEditsAnInspection() throws Exception {
        Ship birch = vessel("BIRCH", ShipType.TANKER);
        String add = "/office/vetting/" + birch.getId() + "/sire/inspections/new";
        assertThat(html(get(add))).contains("Add inspection", "name=\"inspectionDate\"", "name=\"port\"",
                        "name=\"submittingCompany\"", "name=\"inspectorName\"", "name=\"reportReference\"",
                        "name=\"status\"", "name=\"publishedOn\"", "Operator comments sent")
                .containsSubsequence(">Vetting</a>", ">BIRCH</a>", ">SIRE 2.0</a>", ">Add inspection</li>");

        // Required fields, the plausible-date rule (P39) and no publication before the inspection.
        assertThat(postForm(post(add).with(csrf()).param("inspectionDate", today.plusDays(3).toString())
                        .param("publishedOn", today.minusDays(400).toString())))
                .contains("Enter the port.", "Enter the submitting company.", "Choose the report status.",
                        "The report cannot be published before the inspection.");
        assertThat(postForm(post(add).with(csrf()).param("port", "Piraeus"))).contains("Enter the inspection date.");
        assertThat(inspectionRepository.findAll()).isEmpty();

        mockMvc.perform(post(add).with(csrf()).param("inspectionDate", today.minusDays(5).toString())
                        .param("port", " Piraeus, Greece ").param("submittingCompany", "Demo Energy Ltd")
                        .param("status", "DRAFT_RECEIVED").param("inspectorName", ""))
                .andExpect(redirectedUrl("/office/vetting/" + birch.getId() + "/sire"))
                .andExpect(flash().attribute("successMessage", "sire.inspection.added"));
        SireInspection added = inspectionRepository.findAllByShipIdOrderByInspectionDateDescIdDesc(birch.getId()).getFirst();
        assertThat(added.getPort()).isEqualTo("Piraeus, Greece");
        assertThat(added.getInspectorName()).isNull();
        assertThat(added.getCreatedBy().getEmail()).isEqualTo("office@example.com");
        assertThat(actions()).contains("SIRE_INSPECTION_ADDED");

        String edit = "/office/vetting/" + birch.getId() + "/sire/inspections/" + added.getId() + "/edit";
        assertThat(html(get(edit))).contains("Edit inspection", "value=\"Piraeus, Greece\"");
        mockMvc.perform(post(edit).with(csrf()).param("inspectionDate", today.minusDays(5).toString())
                        .param("port", "Piraeus, Greece").param("submittingCompany", "Demo Energy Ltd")
                        .param("status", "PUBLISHED").param("publishedOn", today.toString())
                        .param("reportReference", "DEMO-SIRE-0002"))
                .andExpect(redirectedUrl("/office/vetting/" + birch.getId() + "/sire"))
                .andExpect(flash().attribute("successMessage", "sire.inspection.saved"));
        assertThat(added.getStatus()).isEqualTo(SireStatus.PUBLISHED);
        assertThat(added.getReportReference()).isEqualTo("DEMO-SIRE-0002");
        assertThat(actions()).contains("SIRE_INSPECTION_UPDATED");

        // An earlier inspection opens on the same page and says so.
        SireInspection earlier = inspection(birch, today.minusYears(1), SireStatus.PUBLISHED, null);
        String page = html(get("/office/vetting/{id}/sire", birch.getId()));
        assertThat(page).contains("Earlier inspections", "Inspection of " + date(today.minusYears(1)),
                "href=\"/office/vetting/" + birch.getId() + "/sire?inspection=" + earlier.getId() + "\"");
        assertThat(html(get("/office/vetting/{id}/sire", birch.getId()).param("inspection", earlier.getId().toString())))
                .contains("This is an earlier inspection", "Show the latest", "Other inspections");
    }

    @Test
    void theOfficeAddsAnObservationAndClosesIt() throws Exception {
        Ship birch = vessel("BIRCH", ShipType.TANKER);
        SireInspection inspection = inspection(birch, today.minusMonths(1), SireStatus.PUBLISHED, null);
        User owner = userRepository.findByEmail("office@example.com").orElseThrow();
        String base = "/office/vetting/" + birch.getId() + "/sire/inspections/" + inspection.getId();
        String add = base + "/observations/new";
        assertThat(html(get(add))).contains("Add observation", "name=\"chapter\"", "9 · Mooring and Anchoring",
                "name=\"questionNumber\"", "name=\"type\"", "name=\"description\"", "name=\"operatorComment\"",
                "name=\"correctiveAction\"", "name=\"ownerId\"", "name=\"targetDate\"", "name=\"closedOn\"",
                owner.getFullName());

        // Required fields; a question number as in the report; an owner must be an office user.
        User master = userService.createUser("master@example.com", "Demo Master", "secret123", Role.MASTER, birch, null);
        assertThat(postForm(post(add).with(csrf()).param("chapter", "13").param("questionNumber", "nine")
                        .param("ownerId", master.getId().toString()).param("closedOn", today.plusDays(1).toString())))
                .contains("Choose a VIQ chapter.", "Enter the question number as in the report", "Choose the type.",
                        "Enter the observation.", "Choose an office user.");
        assertThat(observationRepository.findAll()).isEmpty();

        mockMvc.perform(post(add).with(csrf()).param("chapter", "9").param("questionNumber", "9.1.2")
                        .param("type", "HARDWARE").param("description", "Fire main isolating valve hard to operate.")
                        .param("ownerId", owner.getId().toString()).param("targetDate", today.plusDays(14).toString()))
                .andExpect(redirectedUrl("/office/vetting/" + birch.getId() + "/sire#observation-"
                        + observationRepository.findAll().getFirst().getId()))
                .andExpect(flash().attribute("successMessage", "sire.observation.added"));
        SireObservation added = observationRepository.findAll().getFirst();
        assertThat(added.getOwner().getId()).isEqualTo(owner.getId());
        assertThat(actions()).contains("SIRE_OBSERVATION_ADDED");
        assertThat(html(get("/office/vetting/{id}/sire", birch.getId())))
                .contains("1 observation · 1 open", "Fire main isolating valve", "Chapter 9 · Mooring and Anchoring");

        // Its edit form: the comment, corrective action and the closed date close it.
        String edit = base + "/observations/" + added.getId() + "/edit";
        assertThat(html(get(edit))).contains("Observation 9.1.2", "Fire main isolating valve hard to operate.");
        mockMvc.perform(post(edit).with(csrf()).param("chapter", "9").param("questionNumber", "9.1.2")
                        .param("type", "HARDWARE").param("description", "Fire main isolating valve hard to operate.")
                        .param("operatorComment", "Valve overhauled.").param("correctiveAction", "Added to the PMS.")
                        .param("ownerId", owner.getId().toString()).param("targetDate", today.plusDays(14).toString())
                        .param("closedOn", today.toString()))
                .andExpect(redirectedUrl("/office/vetting/" + birch.getId() + "/sire#observation-" + added.getId()))
                .andExpect(flash().attribute("successMessage", "sire.observation.saved"));
        assertThat(added.getClosedOn()).isEqualTo(today);
        assertThat(added.getOperatorComment()).isEqualTo("Valve overhauled.");
        assertThat(actions()).contains("SIRE_OBSERVATION_UPDATED");
        String page = html(get("/office/vetting/{id}/sire", birch.getId()));
        assertThat(page).contains("1 observation · 0 open", "No open observations.");
        assertThat(html(get("/office/vetting/{id}/sire", birch.getId()).param("show", "all"))).contains("Closed", "closed");
    }

    @Test
    void theOfficeUploadsTheReportPdf() throws Exception {
        Ship birch = vessel("BIRCH", ShipType.TANKER);
        SireInspection inspection = inspection(birch, today.minusMonths(1), SireStatus.PUBLISHED, null);
        String upload = "/office/vetting/" + birch.getId() + "/sire/inspections/" + inspection.getId() + "/report";
        assertThat(html(get(upload))).contains("accept=\".pdf,application/pdf\"", "Upload report");
        String file = upload + "/file";
        mockMvc.perform(get(file)).andExpect(status().isNotFound());

        // Not a PDF, or nothing: refused.
        assertThat(postForm(multipart(upload).file(new MockMultipartFile("file", "report.png", "image/png",
                new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'})).with(csrf())))
                .contains("Choose a PDF file.");
        assertThat(postForm(multipart(upload).with(csrf()))).contains("Choose a file.");

        mockMvc.perform(multipart(upload).file(new MockMultipartFile("file", "birch-sire.pdf", "application/pdf", PDF))
                        .with(csrf()))
                .andExpect(redirectedUrl("/office/vetting/" + birch.getId() + "/sire"))
                .andExpect(flash().attribute("successMessage", "sire.report.uploaded"));
        assertThat(actions()).contains("SIRE_REPORT_UPLOADED");
        assertThat(html(get("/office/vetting/{id}/sire", birch.getId())))
                .contains("Open the SIRE 2.0 report (PDF)", "href=\"" + file + "\"", "href=\"" + file + "?download=true\"",
                        "birch-sire.pdf")
                .doesNotContain("No report yet");
        mockMvc.perform(get(file)).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("inline")));
        mockMvc.perform(get(file).param("download", "true"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Access
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void anotherVesselsInspectionIsNotFound() throws Exception {
        Ship birch = vessel("BIRCH", ShipType.TANKER);
        SireInspection inspection = inspection(birch, today.minusMonths(1), SireStatus.PUBLISHED, null);
        SireObservation observation = observation(inspection, "9.1.2", ObservationType.HARDWARE, null, null);
        Ship cedar = vessel("CEDAR", ShipType.TANKER);
        SireInspection cedars = inspection(cedar, today.minusMonths(2), SireStatus.PUBLISHED, null);

        String wrong = "/office/vetting/" + cedar.getId() + "/sire/inspections/" + inspection.getId();
        for (String url : List.of(wrong + "/edit", wrong + "/report", wrong + "/report/file", wrong + "/observations/new",
                wrong + "/observations/" + observation.getId() + "/edit",
                // An observation under another inspection, even of the same vessel's fleet.
                "/office/vetting/" + cedar.getId() + "/sire/inspections/" + cedars.getId() + "/observations/"
                        + observation.getId() + "/edit")) {
            mockMvc.perform(get(url)).andExpect(status().isNotFound());
        }
        mockMvc.perform(get("/office/vetting/{id}/sire", cedar.getId()).param("inspection", inspection.getId().toString()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(wrong + "/observations/" + observation.getId() + "/edit").with(csrf())
                        .param("chapter", "9").param("questionNumber", "9.1.2").param("type", "HARDWARE")
                        .param("description", "Changed").param("closedOn", today.toString()))
                .andExpect(status().isNotFound());
        assertThat(observation.getClosedOn()).isNull();

        // An inactive vessel has no SIRE page; the crew cannot open one.
        birch.setActive(false);
        mockMvc.perform(get("/office/vetting/{id}/sire", birch.getId())).andExpect(status().isNotFound());
        User master = userService.createUser("master@example.com", "Demo Master", "secret123", Role.MASTER, cedar, null);
        mockMvc.perform(get("/office/vetting/{id}/sire", cedar.getId()).with(user(new CurrentUser(master))))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The sidebar counter and the Overview
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void theSidebarCountsEachVesselOnceAndTheOverviewListsTheSireItems() throws Exception {
        assertThat(vettingService.vesselsNeedingAttention()).isZero();

        // Report no longer current; report ending within 60 days; still current (not counted).
        Ship old = vessel("OLD", ShipType.TANKER);
        inspection(old, today.minusMonths(13), SireStatus.PUBLISHED, null);
        Ship ending = vessel("ENDING", ShipType.TANKER);
        inspection(ending, today.minusMonths(12).plusDays(60), SireStatus.PUBLISHED, null);
        Ship fine = vessel("FINE", ShipType.TANKER);
        SireInspection fineInspection = inspection(fine, today.minusMonths(12).plusDays(61), SireStatus.PUBLISHED, null);
        assertThat(vettingService.vesselsNeedingAttention()).isEqualTo(2);

        // An observation past its target date counts its vessel; one closed or due today does not.
        observation(fineInspection, "4.1.1", ObservationType.PROCESS, today, null);
        observation(fineInspection, "4.1.2", ObservationType.PROCESS, today.minusDays(5), today.minusDays(1));
        assertThat(vettingService.vesselsNeedingAttention()).isEqualTo(2);
        SireObservation overdue = observation(fineInspection, "9.1.2", ObservationType.HARDWARE, today.minusDays(1), null);
        assertThat(vettingService.vesselsNeedingAttention()).isEqualTo(3);

        // A vessel with both a RightShip inspection expiring and a SIRE item counts once.
        ShipRightShipStatus rightShip = new ShipRightShipStatus();
        rightShip.setInspectionValidUntil(today.plusDays(10));
        vettingService.saveManual(old, rightShip, null);
        assertThat(vettingService.vesselsNeedingAttention()).isEqualTo(3);

        // Only the latest inspection counts: a newer one replaces the old report.
        inspection(old, today.minusMonths(1), SireStatus.PUBLISHED, null);
        assertThat(vettingService.vesselsNeedingAttention()).isEqualTo(3); // OLD still counts for RightShip

        crew(fine);
        String overview = html(get("/office"));
        assertThat(VETTING_COUNT.matcher(overview).results().map(m -> m.group(1)).toList()).containsExactly("3");
        assertThat(attention(overview)).contains("SIRE 2.0 report", "ENDING", "Report ends in",
                        "Observation 9.1.2", "FINE", "Overdue",
                        "href=\"/office/vetting/" + fine.getId() + "/sire#observation-" + overdue.getId() + "\"")
                .doesNotContain("Observation 4.1.1", "Observation 4.1.2");
        // Ranked with RightShip: the expiring RightShip inspection before the SIRE report.
        assertThat(attention(overview)).containsSubsequence("RightShip inspection", "SIRE 2.0 report", "Observation 9.1.2");
    }

    @Test
    void theReportCurrentDatesFollowThePolicy() {
        SireSettings settings = new SireSettings(12, 14);
        assertThat(settings.currentUntil(LocalDate.of(2026, 6, 12))).isEqualTo(LocalDate.of(2027, 6, 12));
        // Months are uneven: the latest inspection whose report ends by a date, checked against currentUntil.
        for (LocalDate until = LocalDate.of(2027, 1, 1); until.isBefore(LocalDate.of(2029, 1, 1)); until = until.plusDays(1)) {
            LocalDate latest = settings.latestInspectionCurrentUntil(until);
            assertThat(settings.currentUntil(latest)).isBeforeOrEqualTo(until);
            assertThat(settings.currentUntil(latest.plusDays(1))).isAfter(until);
        }
        assertThat(new SireSettings(0, -1).reportCurrentMonths()).isEqualTo(12);
        assertThat(new SireSettings(6, 7).commentDays()).isEqualTo(7);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The dev data
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void theDevDataSeedsBirchWithItsSireInspection() throws Exception {
        devData().run(null);
        devData().run(null);

        Ship birch = shipRepository.findByImoNumber("9600009").orElseThrow();
        assertThat(birch.getType()).isEqualTo(ShipType.TANKER);
        List<SireInspection> inspections = sireService.inspections(birch.getId());
        assertThat(inspections).singleElement().satisfies(i -> {
            assertThat(i.getPort()).isEqualTo("Piraeus, Greece");
            assertThat(i.getStatus()).isEqualTo(SireStatus.PUBLISHED);
            assertThat(i.getReportFileName()).isEqualTo("birch-sire-sample.pdf");
        });
        assertThat(sireService.latest(birch).total()).isEqualTo(5);
        assertThat(sireService.latest(birch).open()).isEqualTo(2);
        assertThat(sireService.latest(birch).overdue()).isEqualTo(1);

        String list = html(get("/office/vetting"));
        assertThat(list).contains("Published · 12 Jun 2026 · 2 open");
        // ALDER's RightShip inspection and BIRCH's overdue observation.
        assertThat(VETTING_COUNT.matcher(list).results().map(m -> m.group(1)).toList()).containsExactly("2");

        assertThat(html(get("/office/vetting/{id}", birch.getId())))
                .containsSubsequence("id=\"regimeSire\"", "id=\"regimeRightShip\"").contains("5 observations · 2 open");
        Ship alder = shipRepository.findByImoNumber("9300001").orElseThrow();
        assertThat(html(get("/office/vetting/{id}", alder.getId())))
                .containsSubsequence("id=\"regimeRightShip\"", "SIRE 2.0 applies to tankers.");
        assertThat(attention(html(get("/office")))).contains("Observation 9.1.2", "BIRCH", "Overdue");
        assertThat(html(get("/office/vetting/{id}/sire", birch.getId())))
                .contains("Open the SIRE 2.0 report (PDF)", "Fire main isolating valve hard to operate on deck.");
    }

    // ---------------------------------------------------------------------------------------------------------------

    private DevData devData() {
        return new DevData(shipRepository, userRepository, userService, itemRepository, requestRepository,
                libraryItemRepository, versionRepository, profileRepository, requestService, vettingService, sireService);
    }

    private Ship vessel(String name, ShipType type) {
        Ship ship = new Ship();
        ship.setImoNumber(String.valueOf(9_700_000 + imo++));
        ship.setName(name);
        ship.setType(type);
        ship.setCreatedAt(Instant.now());
        return shipRepository.save(ship);
    }

    // Someone on board: the Overview shows its work list once there is a vessel and crew (P25).
    private void crew(Ship ship) {
        userService.createUser("crew" + imo++ + "@example.com", "Crew Member", "secret123", Role.CREW_MEMBER, ship, null);
    }

    private SireInspection inspection(Ship ship, LocalDate date, SireStatus status, LocalDate publishedOn) {
        SireInspection values = new SireInspection();
        values.setInspectionDate(date);
        values.setPort("Piraeus, Greece");
        values.setSubmittingCompany("Demo Energy Ltd");
        values.setReportReference("DEMO-SIRE-0001");
        values.setStatus(status);
        values.setPublishedOn(publishedOn);
        return sireService.addInspection(ship, values, null);
    }

    private SireObservation observation(SireInspection inspection, String question, ObservationType type,
                                        LocalDate target, LocalDate closedOn) {
        SireObservation values = new SireObservation();
        values.setChapter(Integer.parseInt(question.substring(0, question.indexOf('.'))));
        values.setQuestionNumber(question);
        values.setType(type);
        values.setDescription("Observation " + question + " as in the report.");
        values.setTargetDate(target);
        values.setClosedOn(closedOn);
        return sireService.addObservation(inspection, values, null);
    }

    private static String date(LocalDate date) {
        return date.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH));
    }

    private List<String> actions() {
        return auditRepository.findAll().stream().map(AuditEntry::getAction).toList();
    }

    private static List<String> names(String html) {
        return ROW_NAME.matcher(html).results().map(m -> m.group(1).strip()).toList();
    }

    // The Overview's "Needs attention" list.
    private static String attention(String html) {
        int start = html.indexOf("attention-list");
        return start < 0 ? "" : html.substring(start);
    }

    private String html(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private String postForm(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return html(request);
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) {
            n++;
        }
        return n;
    }
}
