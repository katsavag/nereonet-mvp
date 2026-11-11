package com.nereonix.nereonet_mvp.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
import com.nereonix.nereonet_mvp.core.user.Role;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class InvitationFlowTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ShipRepository shipRepository;

    @Autowired
    InvitationRepository invitationRepository;

    @MockitoBean
    MailService mailService;

    @Test
    void officeUserInvitesOfficeUser() throws Exception {
        mockMvc.perform(post("/office/invitations")
                        .param("fullName", "New Office")
                        .param("email", "New.Office@Example.com")
                        .with(csrf()))
                .andExpect(redirectedUrl("/office/invitations"));

        Invitation invitation = onlyInvitation();
        assertThat(invitation.getEmail()).isEqualTo("new.office@example.com");
        assertThat(invitation.getRole()).isEqualTo(Role.OFFICE_USER);
        assertThat(invitation.getShip()).isNull();
        assertThat(invitation.getStatus()).isEqualTo(Invitation.Status.PENDING);
        verify(mailService, times(1))
                .send(eq("new.office@example.com"), anyString(), contains("/register?token="));
    }

    @Test
    void officeUserInvitesCrewMemberOfShip() throws Exception {
        Ship ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Test");
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);
        String url = "/office/ships/" + ship.getId() + "/invitations/new";

        mockMvc.perform(post(url)
                        .param("fullName", "Crew Member")
                        .param("email", "crew.member@example.com")
                        .param("role", "CREW_MEMBER")
                        .with(csrf()))
                .andExpect(redirectedUrl(url));

        Invitation invitation = onlyInvitation();
        assertThat(invitation.getRole()).isEqualTo(Role.CREW_MEMBER);
        assertThat(invitation.getShip().getId()).isEqualTo(ship.getId());
        assertThat(invitation.getStatus()).isEqualTo(Invitation.Status.PENDING);
        verify(mailService, times(1))
                .send(eq("crew.member@example.com"), anyString(), contains("/register?token="));
    }

    @Test
    void officeUserResendsPendingInvitation() throws Exception {
        mockMvc.perform(post("/office/invitations")
                        .param("fullName", "New Office")
                        .param("email", "new.office@example.com")
                        .with(csrf()))
                .andExpect(redirectedUrl("/office/invitations"));
        Invitation invitation = onlyInvitation();
        UUID oldTokenId = invitation.getTokenId();
        clearInvocations(mailService);

        mockMvc.perform(post("/office/invitations/" + invitation.getId() + "/resend").with(csrf()))
                .andExpect(redirectedUrl("/office/invitations"));

        Invitation resent = onlyInvitation();
        assertThat(resent.getTokenId()).isNotEqualTo(oldTokenId);
        assertThat(resent.getStatus()).isEqualTo(Invitation.Status.PENDING);
        verify(mailService, times(1))
                .send(eq("new.office@example.com"), anyString(), contains("/register?token="));
    }

    private Invitation onlyInvitation() {
        List<Invitation> invitations = invitationRepository.findAll();
        assertThat(invitations).hasSize(1);
        return invitations.getFirst();
    }
}
