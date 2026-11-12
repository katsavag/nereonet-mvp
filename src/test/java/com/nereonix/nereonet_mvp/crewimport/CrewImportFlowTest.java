package com.nereonix.nereonet_mvp.crewimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hamcrest.Matchers;
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

import com.nereonix.nereonet_mvp.core.importfile.ImportMappingRepository;
import com.nereonix.nereonet_mvp.core.invitation.Invitation;
import com.nereonix.nereonet_mvp.core.invitation.InvitationRepository;
import com.nereonix.nereonet_mvp.core.mail.MailService;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.UserRepository;
import com.nereonix.nereonet_mvp.core.user.UserService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class CrewImportFlowTest {

    private static final String BASE = "/office/crew/import";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ShipRepository shipRepository;

    @Autowired
    InvitationRepository invitationRepository;

    @Autowired
    ImportMappingRepository importMappingRepository;

    @Autowired
    UserService userService;

    @Autowired
    UserRepository userRepository;

    @MockitoBean
    MailService mailService;

    // A file with exactly the template's columns needs no mapping: upload, review, done.
    @Test
    void officeUserImportsCrewListAndInvitesEveryone() throws Exception {
        ship();
        // P18: no Role column; the rank sets the role.
        MockHttpSession session = new MockHttpSession();
        upload(session, "crew.csv", """
                Full name,Email,Rank,Ship IMO
                Anna Testa,anna.testa@example.com,Chief Officer,IMO 9074729
                Bruno Exemplo,bruno.exemplo@example.com,master,9074729
                Carla Motore,carla.motore@example.com,Chief Engineer,9074729
                """, BASE + "/review");

        String review = review(session);
        assertThat(review).contains("value=\"Anna Testa\"", "value=\"anna.testa@example.com\"",
                        "value=\"Bruno Exemplo\"", "value=\"bruno.exemplo@example.com\"")
                // The access each rank gives, under the rank; no role picker, no "Role column ignored" note.
                .contains(">Access: Crew member</p>", ">Access: Master</p>",
                        "data-access-hint=\"rankAccess1\"", "<optgroup label=\"Catering\">")
                .doesNotContain("rows[0].role", ">Role<", "roleColumnIgnored")
                // The steps, with Map columns done; every row says it is new.
                .containsPattern("<li class=\"import-step is-current\"\\s+aria-current=\"step\">")
                .containsPattern("<li class=\"import-step is-done\">\\s*<span class=\"import-step-mark\"")
                .contains("Will import: 3 new, 0 updates, 0 without changes (skipped).")
                .containsPattern("(?s)<td class=\"import-col-row\">\\s*<span class=\"import-row-number num\">2</span>"
                        + "\\s*<span class=\"badge status-badge status-primary\">New</span>");
        assertThat(invitationRepository.findAll()).isEmpty();

        mockMvc.perform(post(BASE + "/submit").session(session).with(csrf()))
                .andExpect(redirectedUrl(BASE + "/done"));

        List<Invitation> invitations = invitationRepository.findAll();
        assertThat(invitations).extracting(Invitation::getEmail, Invitation::getRank, Invitation::getRole)
                .containsExactlyInAnyOrder(
                        tuple("anna.testa@example.com", Rank.CHIEF_OFFICER, Role.CREW_MEMBER),
                        tuple("bruno.exemplo@example.com", Rank.MASTER, Role.MASTER),
                        // Chief Engineer has no role of its own.
                        tuple("carla.motore@example.com", Rank.CHIEF_ENGINEER, Role.CREW_MEMBER));
        assertThat(invitations).allMatch(invitation -> invitation.getStatus() == Invitation.Status.PENDING);
        verify(mailService, times(3)).send(anyString(), anyString(), contains("/register?token="));
        verify(mailService).send(eq("anna.testa@example.com"), anyString(), anyString());
        verify(mailService).send(eq("bruno.exemplo@example.com"), anyString(), anyString());
        // A template file needs no saved mapping.
        assertThat(importMappingRepository.findAll()).isEmpty();
    }

    // Acceptance (P24): samples/crew-sample.xlsx with its columns renamed and reordered imports without editing it.
    @Test
    void theSampleWithRenamedAndReorderedColumnsImportsWithoutEditing() throws Exception {
        ship();
        byte[] file = renamedAndReorderedSample();
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(multipart(BASE).file(new MockMultipartFile("file", "crew-from-system.xlsx",
                        "application/octet-stream", file)).session(session).with(csrf()))
                .andExpect(redirectedUrl(BASE + "/sheet"));

        // Two sheets: the crew sheet is chosen already, the Instructions sheet is the other choice.
        String sheets = html(get(BASE + "/sheet").session(session));
        assertThat(sheets).contains("crew-from-system.xlsx has 2 sheets. Which one holds the rows to import?",
                        ">Crew</span>", ">Instructions</span>", "Position · Vessel IMO No. · Seafarer · E-mail address")
                .containsPattern("<input type=\"radio\" class=\"form-check-input\" name=\"sheet\" value=\"0\" checked");
        mockMvc.perform(post(BASE + "/sheet").param("sheet", "0").session(session).with(csrf()))
                .andExpect(redirectedUrl(BASE + "/map"));

        // Every field found under its new name, in its new place.
        String map = html(get(BASE + "/map").session(session));
        assertThat(map).contains("File: crew-from-system.xlsx", "Sheet: Crew", "First 5 of 10 rows",
                "To: Full name", "To: Email", "To: Rank", "To: Ship (IMO number or exact name)");
        assertSelected(map, "fullName", 2);
        assertSelected(map, "email", 3);
        assertSelected(map, "rank", 0);
        assertSelected(map, "shipImo", 1);
        assertThat(map).doesNotContainPattern("name=\"column\\.fullName2\"(?:(?!</select>).)*?<option value=\"\\d+\" selected");

        // The ranks are written as in Nereonet, so there is nothing to match: straight to the review.
        mockMvc.perform(post(BASE + "/map").session(session).with(csrf()))
                .andExpect(redirectedUrl(BASE + "/review"));
        assertThat(review(session)).contains("value=\"Stavros Pelagidis\"", "value=\"stavros.pelagidis@example.com\"")
                .doesNotContain("is-invalid");
        mockMvc.perform(post(BASE + "/submit").session(session).with(csrf()))
                .andExpect(redirectedUrl(BASE + "/done"));
        assertThat(invitationRepository.findAll()).hasSize(10)
                .extracting(Invitation::getEmail, Invitation::getRank)
                .contains(tuple("stavros.pelagidis@example.com", Rank.MASTER),
                        tuple("rodel.tagaytay@example.com", Rank.STEWARD));
    }

    // Acceptance (P24): "C/E" in the rank column is asked about once, and the answer goes to every such row.
    @Test
    void aShortRankIsAskedAboutOnceForEveryRowWithIt() throws Exception {
        ship();
        MockHttpSession session = new MockHttpSession();
        upload(session, "crew.csv", """
                Full name,Email,Rank,Ship IMO
                Anna Testa,anna.testa@example.com,C/E,9074729
                Bruno Exemplo,bruno.exemplo@example.com,2/O,9074729
                Carla Motore,carla.motore@example.com,c/e,9074729
                Dino Motore,dino.motore@example.com,C/E,9074729
                Ezio Ponte,ezio.ponte@example.com,Bosun,9074729
                """, BASE + "/values");

        // One question per distinct value (C/E and c/e are the same), each with its suggestion chosen.
        String values = html(get(BASE + "/values").session(session));
        assertThat(values).contains("2 values to match", ">C/E</span>", "Rank · 3 rows", ">2/O</span>", "Rank · 1 row",
                        "Suggested. Check it, or choose another.", "Match the values")
                .doesNotContain(">Bosun</span>")
                .containsPattern("(?s)name=\"value\\.0\"(?:(?!</select>).)*?<option value=\"CHIEF_ENGINEER\"\\s+selected=\"selected\">")
                .containsPattern("(?s)name=\"value\\.1\"(?:(?!</select>).)*?<option value=\"SECOND_OFFICER\"\\s+selected=\"selected\">");
        // Confirm C/E, change 2/O.
        mockMvc.perform(post(BASE + "/values").param("value.0", "CHIEF_ENGINEER").param("value.1", "THIRD_OFFICER")
                        .session(session).with(csrf()))
                .andExpect(redirectedUrl(BASE + "/review"));

        String review = review(session);
        for (int row : List.of(0, 2, 3)) {
            assertThat(review).as("row " + row).containsPattern("(?s)name=\"rows\\[" + row + "\\]\\.rank\""
                    + "[^>]*>(?:(?!</select>).)*?<option value=\"CHIEF_ENGINEER\"[^>]*selected=\"selected\">");
        }
        mockMvc.perform(post(BASE + "/submit").session(session).with(csrf()))
                .andExpect(redirectedUrl(BASE + "/done"));
        assertThat(invitationRepository.findAll()).extracting(Invitation::getEmail, Invitation::getRank)
                .containsExactlyInAnyOrder(tuple("anna.testa@example.com", Rank.CHIEF_ENGINEER),
                        tuple("bruno.exemplo@example.com", Rank.THIRD_OFFICER),
                        tuple("carla.motore@example.com", Rank.CHIEF_ENGINEER),
                        tuple("dino.motore@example.com", Rank.CHIEF_ENGINEER),
                        tuple("ezio.ponte@example.com", Rank.BOSUN));
    }

    // A required field without a column keeps the user on the mapping step, with the reason next to the field;
    // nothing is built or saved.
    @Test
    void aRequiredFieldWithoutAColumnBlocksContinue() throws Exception {
        ship();
        MockHttpSession session = new MockHttpSession();
        upload(session, "crew.csv", """
                Name,Mail,Vessel,Nationality
                Anna Testa,anna.testa@example.com,MV Demo,Greek
                """, BASE + "/map");

        String map = html(get(BASE + "/map").session(session));
        // Only the last name is optional.
        assertThat(map.split("\\(optional\\)", -1)).hasSize(2);
        assertThat(map).containsPattern("(?s)<label class=\"form-label\" for=\"column-fullName2\">(?:(?!</label>).)*\\(optional\\)")
                .contains("Not imported:", "D · Nationality")
                .containsPattern("<select class=\"form-select\" id=\"column-rank\"[^>]*>\\s*<option value=\"\">Not in this file</option>"
                        + "(\\s*<option value=\"\\d\">[^<]*</option>)+\\s*</select>");

        String blocked = mockMvc.perform(post(BASE + "/map").session(session).with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(blocked).contains("Choose the column that holds it. It is required.",
                        "id=\"error-rank\"", "href=\"#column-rank\"")
                .containsPattern("<select class=\"form-select is-invalid\" id=\"column-rank\"");
        // The review is not reachable until the mapping is complete.
        mockMvc.perform(get(BASE + "/review").session(session)).andExpect(redirectedUrl(BASE + "/map"));

        // One column for two fields is refused too.
        String twice = mockMvc.perform(post(BASE + "/map").param("column.rank", "0").session(session).with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(twice).contains("This column is already used for another field. Choose another one.", "id=\"error-rank\"");

        mockMvc.perform(post(BASE + "/cancel").session(session).with(csrf()))
                .andExpect(redirectedUrl("/office/crew"));
        assertThat(invitationRepository.findAll()).isEmpty();
        assertThat(importMappingRepository.findAll()).isEmpty();
        // The upload is gone: every step leads back to the start.
        mockMvc.perform(get(BASE + "/map").session(session)).andExpect(redirectedUrl(BASE));
    }

    // The confirmed column and value mapping is saved with the import and filled in again for a file with the same
    // column names, in any order.
    @Test
    void theSavedMappingIsSuggestedForAFileWithTheSameColumns() throws Exception {
        ship();
        MockHttpSession first = new MockHttpSession();
        upload(first, "export-1.csv", """
                Ref;Person;Contact;Grade;Unit
                101;Anna Testa;anna.testa@example.com;C/E;MV Demo
                """, BASE + "/map");
        // Nothing to guess from these names: the user chooses every column.
        assertThat(html(get(BASE + "/map").session(first))).doesNotContain("Filled in from the mapping saved");
        mockMvc.perform(post(BASE + "/map").session(first).with(csrf()).param("column.fullName", "1")
                        .param("column.email", "2").param("column.rank", "3").param("column.shipImo", "4"))
                .andExpect(redirectedUrl(BASE + "/values"));
        mockMvc.perform(post(BASE + "/values").session(first).with(csrf()).param("value.0", "CHIEF_ENGINEER"))
                .andExpect(redirectedUrl(BASE + "/review"));
        mockMvc.perform(post(BASE + "/submit").session(first).with(csrf()))
                .andExpect(redirectedUrl(BASE + "/done"));
        assertThat(importMappingRepository.findAll()).hasSize(1);

        // The same columns in another order.
        MockHttpSession second = new MockHttpSession();
        upload(second, "export-2.csv", """
                Grade;Unit;Ref;Person;Contact
                C/E;MV Demo;102;Bruno Exemplo;bruno.exemplo@example.com
                """, BASE + "/map");
        String map = html(get(BASE + "/map").session(second));
        assertThat(map).contains("Filled in from the mapping saved on");
        assertSelected(map, "fullName", 3);
        assertSelected(map, "email", 4);
        assertSelected(map, "rank", 0);
        assertSelected(map, "shipImo", 1);
        mockMvc.perform(post(BASE + "/map").session(second).with(csrf()))
                .andExpect(redirectedUrl(BASE + "/values"));
        assertThat(html(get(BASE + "/values").session(second)))
                .contains("As saved for files with these columns.")
                .containsPattern("(?s)name=\"value\\.0\"(?:(?!</select>).)*?<option value=\"CHIEF_ENGINEER\"\\s+selected=\"selected\">");
        mockMvc.perform(post(BASE + "/values").session(second).with(csrf()))
                .andExpect(redirectedUrl(BASE + "/review"));
        mockMvc.perform(post(BASE + "/submit").session(second).with(csrf()))
                .andExpect(redirectedUrl(BASE + "/done"));
        assertThat(invitationRepository.findAll()).extracting(Invitation::getEmail, Invitation::getRank)
                .contains(tuple("bruno.exemplo@example.com", Rank.CHIEF_ENGINEER));
        // Still one mapping for these column names.
        assertThat(importMappingRepository.findAll()).hasSize(1);
    }

    // The review: filters that keep the edits, "Exclude all rows with errors", and the Done step's counts.
    @Test
    void rowsWithErrorsAreFilteredAndExcludedInOneAction() throws Exception {
        ship();
        MockHttpSession session = new MockHttpSession();
        upload(session, "crew.csv", """
                Full name,Email,Rank,Ship IMO
                Anna Testa,anna.testa@example.com,Bosun,9074729
                Bruno Exemplo,,Bosun,9074729
                Carla Motore,carla.motore@example.com,Oiler,MV Demo
                """, BASE + "/review");

        String all = review(session);
        assertThat(all).contains("Exclude the row with errors", "value=\"Carla Motore\"")
                .containsPattern("aria-pressed=\"true\"[^>]*>\\s*<svg[^>]*>.*?</svg>\\s*<span>All</span>\\s*"
                        + "<span class=\"import-filter-count num\">3</span>")
                .containsPattern("<span>With errors</span>\\s*<span class=\"import-filter-count num has-errors\">1</span>")
                // The ship matched by its exact name.
                .containsPattern("(?s)name=\"rows\\[2\\]\\.shipImo\"(?:(?!</select>).)*?<option value=\"9074729\"\\s+selected=\"selected\">");
        // The first two columns stay in place when the table scrolls sideways.
        assertThat(all).contains("<th scope=\"col\" class=\"import-col-row\">Row</th>",
                "<th scope=\"col\" class=\"import-col-name\">Full name</th>");

        // The errors filter keeps the edits made before it was pressed.
        mockMvc.perform(post(BASE + "/check").param("show", "errors").param("rows[0].fullName", "Anna Testa-Ponte")
                        .session(session).with(csrf()))
                .andExpect(redirectedUrl(BASE + "/review?show=errors"));
        String errors = html(get(BASE + "/review").param("show", "errors").session(session));
        assertThat(errors).contains("value=\"Bruno Exemplo\"").doesNotContain("value=\"Carla Motore\"");

        mockMvc.perform(post(BASE + "/exclude-errors").param("show", "errors").session(session).with(csrf()))
                .andExpect(redirectedUrl(BASE + "/review?show=errors"));
        assertThat(html(get(BASE + "/review").param("show", "errors").session(session)))
                .contains("No included row has errors.");
        assertThat(html(get(BASE + "/review").param("show", "excluded").session(session)))
                .contains("value=\"Bruno Exemplo\"", "status-neutral\">Excluded</span>")
                .doesNotContain("value=\"Anna Testa-Ponte\"");

        mockMvc.perform(post(BASE + "/submit").session(session).with(csrf()))
                .andExpect(redirectedUrl(BASE + "/done"));
        // The counts come with the redirect, once.
        String done = html(get(BASE + "/done").session(session));
        assertThat(done).contains("<dt>Invited</dt>", "<dd class=\"num\">2</dd>", "<dt>Skipped</dt>",
                        "<dd class=\"num\">1</dd>", "href=\"/office/crew\"", "Go to the crew list")
                .containsPattern("<li class=\"import-step is-current\"\\s+aria-current=\"step\">\\s*<span class=\"import-step-mark\" aria-hidden=\"true\">\\s*4");
        assertThat(invitationRepository.findAll()).extracting(Invitation::getEmail, Invitation::getFullName)
                .containsExactlyInAnyOrder(tuple("anna.testa@example.com", "Anna Testa-Ponte"),
                        tuple("carla.motore@example.com", "Carla Motore"));
        // Opened again later, Done leads to the list.
        mockMvc.perform(get(BASE + "/done").session(session)).andExpect(redirectedUrl("/office/crew"));
    }

    // Any structure: an .xls workbook with a title above the columns, and first and last name in two columns; a CSV
    // with ";".
    @Test
    void anXlsWithATitleRowAndASemicolonCsvAreRead() throws Exception {
        ship();
        MockHttpSession session = new MockHttpSession();
        byte[] xls;
        try (Workbook workbook = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Export");
            addRow(sheet, 0, "Crew list MV Demo, 12 Nov 2026");
            addRow(sheet, 2, "Surname", "Given name", "E-mail", "Rank", "IMO");
            addRow(sheet, 3, "Testa", "Anna", "anna.testa@example.com", "Bosun", "9074729");
            workbook.write(out);
            xls = out.toByteArray();
        }
        mockMvc.perform(multipart(BASE).file(new MockMultipartFile("file", "crew.xls", "application/vnd.ms-excel", xls))
                        .session(session).with(csrf()))
                .andExpect(redirectedUrl(BASE + "/map"));
        String map = html(get(BASE + "/map").session(session));
        // The column names are in row 3 of the file; the name comes from two columns.
        assertThat(map).containsPattern("<option value=\"1\"\\s+selected=\"selected\">Row 3: Surname, Given name");
        assertSelected(map, "fullName", 1);
        assertSelected(map, "fullName2", 0);
        mockMvc.perform(post(BASE + "/map").session(session).with(csrf()))
                .andExpect(redirectedUrl(BASE + "/review"));
        assertThat(review(session)).contains("value=\"Anna Testa\"").doesNotContain("is-invalid");

        MockHttpSession csv = new MockHttpSession();
        upload(csv, "crew.csv", "Full name;Email;Rank;Ship IMO\nBruno Exemplo;bruno.exemplo@example.com;Oiler;9074729\n",
                BASE + "/review");
        assertThat(review(csv)).contains("value=\"Bruno Exemplo\"", "value=\"bruno.exemplo@example.com\"");
    }

    // P18: a file with a Role column (the template from before) is accepted; the column is ignored and the review page
    // says so.
    @Test
    void anOldFileWithARoleColumnIsAcceptedAndTheColumnIgnored() throws Exception {
        ship();
        MockHttpSession session = new MockHttpSession();
        upload(session, "crew.csv", """
                Full name,Email,Rank,Role,Ship IMO
                Anna Testa,anna.testa@example.com,Bosun,MASTER,9074729
                Bruno Exemplo,bruno.exemplo@example.com,Chief Cook,CREW_MEMBER,9074729
                """, BASE + "/map");
        assertThat(html(get(BASE + "/map").session(session))).contains("D · Role");
        mockMvc.perform(post(BASE + "/map").session(session).with(csrf()))
                .andExpect(redirectedUrl(BASE + "/review"));

        assertThat(review(session))
                .contains("<p class=\"info-note\" id=\"roleColumnIgnored\">",
                        "This file has a Role column, from the earlier template. It is ignored: the rank sets each crew",
                        ">Access: Crew member</p>", ">Access: Chief cook</p>")
                .doesNotContain("rows[0].role");
        assertThat(html(get(BASE + "/review").session(session).param("lang", "el")))
                .contains("Αυτό το αρχείο έχει στήλη Role");

        mockMvc.perform(post(BASE + "/submit").session(session).with(csrf()))
                .andExpect(redirectedUrl(BASE + "/done"));
        assertThat(invitationRepository.findAll()).extracting(Invitation::getEmail, Invitation::getRole)
                .containsExactlyInAnyOrder(tuple("anna.testa@example.com", Role.CREW_MEMBER),
                        tuple("bruno.exemplo@example.com", Role.CHIEF_COOK));
    }

    // P18: the rank is required, and the one active Master / Chief Cook per ship rule is reported on the rank.
    @Test
    void rankErrorsAreReportedOnTheRank() throws Exception {
        Ship ship = ship();
        userService.createUser("master@example.com", "Mara Master", "secret123", Role.MASTER, ship, Rank.MASTER);
        MockHttpSession session = new MockHttpSession();
        upload(session, "crew.csv", """
                Full name,Email,Rank,Ship IMO
                Anna Testa,anna.testa@example.com,Master,9074729
                Bruno Exemplo,bruno.exemplo@example.com,,9074729
                Carla Cuoca,carla.cuoca@example.com,Chief Cook,9074729
                Dora Cuoca,dora.cuoca@example.com,Chief Cook,9074729
                Ezio Exemplo,ezio.exemplo@example.com,Skipper,MV Atlas
                """, BASE + "/values");
        // "Skipper" has no suggestion; decided per row, it stays as in the file.
        assertThat(html(get(BASE + "/values").session(session)))
                .contains("Decide per row on the review").containsPattern("name=\"value\\.0\"[^>]*>\\s*<option value=\"\">Decide per row");
        mockMvc.perform(post(BASE + "/values").session(session).with(csrf()))
                .andExpect(redirectedUrl(BASE + "/review"));

        String review = review(session);
        assertThat(review).contains("This ship already has an active crew member with this access.",
                "Choose a rank.", "The file has more than one crew member with this access for this ship.",
                "Not one of the ranks. Choose a rank.",
                // A ship name that names no active ship: kept, shown as written, and an error.
                ">MV Atlas</option>", "No active ship with this IMO number or name. Choose a ship.",
                "<span class=\"import-filter-count num has-errors\">5</span>");
        for (int row = 0; row < 5; row++) {
            assertThat(review).as("row " + row).containsPattern("<select class=\"form-select form-select-sm is-invalid\""
                    + "[^>]*name=\"rows\\[" + row + "\\]\\.rank\"");
        }
        mockMvc.perform(post(BASE + "/submit").session(session).with(csrf()))
                .andExpect(redirectedUrl(BASE + "/review"));
        assertThat(invitationRepository.findAll()).isEmpty();
    }

    // The one-Master rule is checked through UserService.checkShipRoleFree, which throws. Outside a test transaction
    // that exception used to mark the review's transaction rollback-only, and the review page failed (500).
    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void theReviewReportsATakenMasterWithoutFailing() throws Exception {
        Ship ship = ship();
        var master = userService.createUser("master@example.com", "Mara Master", "secret123", Role.MASTER, ship,
                Rank.MASTER);
        try {
            MockHttpSession session = new MockHttpSession();
            upload(session, "crew.csv", """
                    Full name,Email,Rank,Ship IMO
                    Anna Testa,anna.testa@example.com,Master,9074729
                    """, BASE + "/review");
            assertThat(review(session)).contains("This ship already has an active crew member with this access.");
        } finally {
            userRepository.delete(master);
            shipRepository.delete(ship);
        }
    }

    // P18: the template has no Role column; its Instructions list each rank with the access it gives.
    @Test
    void theTemplateHasNoRoleColumn() throws Exception {
        ship();
        byte[] file = mockMvc.perform(get(BASE + "/template")).andReturn().getResponse()
                .getContentAsByteArray();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            assertThat(cells(workbook.getSheet("Crew").getRow(0))).containsExactly("Full name", "Email", "Rank", "Ship IMO");
            List<List<String>> instructions = new ArrayList<>();
            for (Row row : workbook.getSheet("Instructions")) {
                instructions.add(cells(row));
            }
            assertThat(instructions).contains(List.of("MASTER", "Master", "Master"),
                    List.of("CHIEF_COOK", "Chief Cook", "Chief cook"),
                    List.of("CHIEF_ENGINEER", "Chief Engineer", "Crew member"),
                    List.of("BOSUN", "Bosun", "Crew member"));
            assertThat(instructions).noneMatch(row -> row.contains("Role") || row.contains("Role values"));
        }
    }

    // The upload page: any file type, the template as the easy path, the steps; Greek too.
    @Test
    void theUploadPageOffersAnyFileAndTheTemplate() throws Exception {
        String page = html(get(BASE));
        assertThat(page).contains("accept=\".xlsx,.xls,.csv\"", "XLSX, XLS or CSV, at most 10 MB.",
                        "any column names, in any order", "Starting from scratch?", "href=\"/office/crew/import/template\"",
                        "<nav class=\"import-steps\" aria-label=\"Import steps\">", "<span class=\"visually-hidden\">Step 1 of 4:</span>")
                .containsPattern("<li class=\"import-step is-current\"\\s+aria-current=\"step\">");
        assertThat(html(get(BASE).param("lang", "el"))).contains("Αντιστοίχιση στηλών", "Ανέβασμα και συνέχεια");

        String unreadable = mockMvc.perform(multipart(BASE).file(new MockMultipartFile("file", "crew.pdf",
                        "application/pdf", new byte[] {1, 2, 3})).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(unreadable).contains("The file could not be read. Upload an .xlsx, .xls or .csv file.");
    }

    // samples/crew-sample.xlsx with the columns renamed (as a crewing system might name them) and reordered.
    private static byte[] renamedAndReorderedSample() throws Exception {
        Map<String, String> names = Map.of("Full name", "Seafarer", "Email", "E-mail address", "Rank", "Position",
                "Ship IMO", "Vessel IMO No.");
        int[] order = {2, 3, 0, 1}; // Position, Vessel IMO No., Seafarer, E-mail address
        try (InputStream in = Files.newInputStream(Path.of("samples", "crew-sample.xlsx"));
             XSSFWorkbook workbook = new XSSFWorkbook(in);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet crew = workbook.getSheet("Crew");
            for (Row row : crew) {
                List<String> values = cells(row);
                for (int i = 0; i < order.length; i++) {
                    String value = order[i] < values.size() ? values.get(order[i]) : "";
                    row.getCell(i, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK)
                            .setCellValue(row.getRowNum() == 0 ? names.get(value) : value);
                }
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static void addRow(Sheet sheet, int index, String... values) {
        Row row = sheet.createRow(index);
        for (int i = 0; i < values.length; i++) {
            row.createCell(i).setCellValue(values[i]);
        }
    }

    // The option of the column chosen for the field is selected.
    private static void assertSelected(String map, String key, int column) {
        assertThat(map).as(key + " -> column " + column)
                .containsPattern("(?s)<select class=\"form-select\" id=\"column-" + key + "\" name=\"column\\." + key
                        + "\"[^>]*>(\\s*<option value=\"\\d*\">[^<]*</option>)*\\s*<option value=\"" + column
                        + "\"\\s+selected=\"selected\">");
    }

    private static List<String> cells(Row row) {
        List<String> values = new ArrayList<>();
        row.forEach(cell -> values.add(cell.getStringCellValue()));
        return values;
    }

    private void upload(MockHttpSession session, String filename, String csv, String next) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart(BASE).file(file).session(session).with(csrf()))
                .andExpect(redirectedUrl(next));
    }

    private String review(MockHttpSession session) throws Exception {
        return html(get(BASE + "/review").session(session));
    }

    private String html(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andExpect(content().string(Matchers.startsWith("<!DOCTYPE html>")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private Ship ship() {
        Ship ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setCreatedAt(Instant.now());
        return shipRepository.save(ship);
    }
}
