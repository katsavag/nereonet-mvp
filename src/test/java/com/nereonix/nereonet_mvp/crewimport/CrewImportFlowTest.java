package com.nereonix.nereonet_mvp.crewimport;

import static org.assertj.core.api.Assertions.assertThat;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

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

    @MockitoBean
    MailService mailService;

    @Test
    void officeUserImportsCrewListAndInvitesEveryone() throws Exception {
        Ship ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);

        String csv = """
                Full name,Email,Rank,Role,Ship IMO
                Anna Testa,anna.testa@example.com,Chief Officer,crew_member,IMO 9074729
                Bruno Exemplo,bruno.exemplo@example.com,,MASTER,9074729
                """;
        MockMultipartFile file = new MockMultipartFile("file", "crew.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(multipart("/office/crew/import").file(file).session(session).with(csrf()))
                .andExpect(redirectedUrl("/office/crew/import/review"));

        mockMvc.perform(get("/office/crew/import/review").session(session))
                .andExpect(content().string(Matchers.allOf(
                        Matchers.containsString("value=\"Anna Testa\""),
                        Matchers.containsString("value=\"anna.testa@example.com\""),
                        Matchers.containsString("value=\"Bruno Exemplo\""),
                        Matchers.containsString("value=\"bruno.exemplo@example.com\""))));
        assertThat(invitationRepository.findAll()).isEmpty();

        mockMvc.perform(post("/office/crew/import/submit").session(session).with(csrf()))
                .andExpect(redirectedUrl("/office/crew/import"));

        List<Invitation> invitations = invitationRepository.findAll();
        assertThat(invitations).hasSize(2);
        assertThat(invitations).allMatch(invitation -> invitation.getStatus() == Invitation.Status.PENDING);
        verify(mailService, times(2)).send(anyString(), anyString(), contains("/register?token="));
        verify(mailService).send(eq("anna.testa@example.com"), anyString(), anyString());
        verify(mailService).send(eq("bruno.exemplo@example.com"), anyString(), anyString());
    }
}
