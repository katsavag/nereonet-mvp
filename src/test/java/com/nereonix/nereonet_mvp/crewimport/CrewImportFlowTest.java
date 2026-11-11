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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
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
import org.springframework.transaction.annotation.Transactional;

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

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ShipRepository shipRepository;

    @Autowired
    InvitationRepository invitationRepository;

    @Autowired
    UserService userService;

    @Autowired
    UserRepository userRepository;

    @MockitoBean
    MailService mailService;

    @Test
    void officeUserImportsCrewListAndInvitesEveryone() throws Exception {
        ship();
        // P18: no Role column; the rank sets the role.
        MockHttpSession session = upload("""
                Full name,Email,Rank,Ship IMO
                Anna Testa,anna.testa@example.com,Chief Officer,IMO 9074729
                Bruno Exemplo,bruno.exemplo@example.com,master,9074729
                Carla Motore,carla.motore@example.com,Chief Engineer,9074729
                """);

        String review = review(session);
        assertThat(review).contains("value=\"Anna Testa\"", "value=\"anna.testa@example.com\"",
                        "value=\"Bruno Exemplo\"", "value=\"bruno.exemplo@example.com\"")
                // The access each rank gives, under the rank; no role picker, no "Role column ignored" note.
                .contains(">Access: Crew member</p>", ">Access: Master</p>",
                        "data-access-hint=\"rankAccess1\"", "<optgroup label=\"Catering\">")
                .doesNotContain("rows[0].role", ">Role<", "roleColumnIgnored");
        assertThat(invitationRepository.findAll()).isEmpty();

        mockMvc.perform(post("/office/crew/import/submit").session(session).with(csrf()))
                .andExpect(redirectedUrl("/office/crew/import"));

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
    }

    // P18: a file made with the template from before, with a Role column, is accepted; the column is ignored and the
    // review page says so.
    @Test
    void anOldFileWithARoleColumnIsAcceptedAndTheColumnIgnored() throws Exception {
        ship();
        MockHttpSession session = upload("""
                Full name,Email,Rank,Role,Ship IMO
                Anna Testa,anna.testa@example.com,Bosun,MASTER,9074729
                Bruno Exemplo,bruno.exemplo@example.com,Chief Cook,CREW_MEMBER,9074729
                """);

        assertThat(review(session))
                .contains("<p class=\"info-note\" id=\"roleColumnIgnored\">",
                        "This file has a Role column, from the earlier template. It is ignored: the rank sets each crew",
                        ">Access: Crew member</p>", ">Access: Chief cook</p>")
                .doesNotContain("rows[0].role");
        assertThat(html(get("/office/crew/import/review").session(session).param("lang", "el")))
                .contains("Αυτό το αρχείο έχει στήλη Role");

        mockMvc.perform(post("/office/crew/import/submit").session(session).with(csrf()))
                .andExpect(redirectedUrl("/office/crew/import"));
        assertThat(invitationRepository.findAll()).extracting(Invitation::getEmail, Invitation::getRole)
                .containsExactlyInAnyOrder(tuple("anna.testa@example.com", Role.CREW_MEMBER),
                        tuple("bruno.exemplo@example.com", Role.CHIEF_COOK));
    }

    // P18: the rank is required, and the one active Master / Chief Cook per ship rule is reported on the rank.
    @Test
    void rankErrorsAreReportedOnTheRank() throws Exception {
        Ship ship = ship();
        userService.createUser("master@example.com", "Mara Master", "secret123", Role.MASTER, ship, Rank.MASTER);
        MockHttpSession session = upload("""
                Full name,Email,Rank,Ship IMO
                Anna Testa,anna.testa@example.com,Master,9074729
                Bruno Exemplo,bruno.exemplo@example.com,,9074729
                Carla Cuoca,carla.cuoca@example.com,Chief Cook,9074729
                Dora Cuoca,dora.cuoca@example.com,Chief Cook,9074729
                Ezio Exemplo,ezio.exemplo@example.com,Captain,9074729
                """);

        String review = review(session);
        assertThat(review).contains("This ship already has an active crew member with this access.",
                "Choose a rank.", "The file has more than one crew member with this access for this ship.",
                "Not one of the ranks. Choose a rank.", ">5</div>");
        for (int row = 0; row < 5; row++) {
            assertThat(review).as("row " + row).containsPattern("<select class=\"form-select form-select-sm is-invalid\""
                    + "[^>]*name=\"rows\\[" + row + "\\]\\.rank\"");
        }
        mockMvc.perform(post("/office/crew/import/submit").session(session).with(csrf()))
                .andExpect(redirectedUrl("/office/crew/import/review"));
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
            MockHttpSession session = upload("""
                    Full name,Email,Rank,Ship IMO
                    Anna Testa,anna.testa@example.com,Master,9074729
                    """);
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
        byte[] file = mockMvc.perform(get("/office/crew/import/template")).andReturn().getResponse()
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

    private static List<String> cells(Row row) {
        List<String> values = new ArrayList<>();
        row.forEach(cell -> values.add(cell.getStringCellValue()));
        return values;
    }

    private MockHttpSession upload(String csv) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "crew.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(multipart("/office/crew/import").file(file).session(session).with(csrf()))
                .andExpect(redirectedUrl("/office/crew/import/review"));
        return session;
    }

    private String review(MockHttpSession session) throws Exception {
        return html(get("/office/crew/import/review").session(session));
    }

    private String html(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
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
