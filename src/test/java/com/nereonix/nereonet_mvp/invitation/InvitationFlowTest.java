package com.nereonix.nereonet_mvp.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditRepository;
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

    @Autowired
    AuditRepository auditRepository;

    @MockitoBean
    MailService mailService;

    @Test
    void officeUserInvitesOfficeUser() throws Exception {
        mockMvc.perform(post("/office/invitations")
                        .param("fullName", "New Office")
                        .param("email", "New.Office@Example.com")
                        .with(csrf()))
                .andExpect(redirectedUrl("/office/users"));

        Invitation invitation = onlyInvitation();
        assertThat(invitation.getEmail()).isEqualTo("new.office@example.com");
        assertThat(invitation.getRole()).isEqualTo(Role.OFFICE_USER);
        assertThat(invitation.getShip()).isNull();
        assertThat(invitation.getStatus()).isEqualTo(Invitation.Status.PENDING);
        verify(mailService, times(1))
                .send(eq("new.office@example.com"), anyString(), contains("/register?token="), anyString());
    }

    @Test
    void officeUserInvitesCrewMemberOfShip() throws Exception {
        Ship ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Test");
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);
        // The former invitation page leads to the Crew tab's Add crew member modal (P17).
        mockMvc.perform(get("/office/ships/{id}/invitations/new", ship.getId()))
                .andExpect(redirectedUrl("/office/ships/" + ship.getId() + "/crew?add=true"));

        mockMvc.perform(post("/office/ships/{id}/crew/new", ship.getId())
                        .param("fullName", "Crew Member")
                        .param("email", "crew.member@example.com")
                        .param("rank", "ABLE_SEAMAN")
                        .param("shipId", ship.getId().toString())
                        .with(csrf()))
                // Back to the ship page's Crew tab, where the new member is listed as "Invited".
                .andExpect(redirectedUrl("/office/ships/" + ship.getId() + "/crew"));

        Invitation invitation = onlyInvitation();
        assertThat(invitation.getRole()).isEqualTo(Role.CREW_MEMBER);
        assertThat(invitation.getShip().getId()).isEqualTo(ship.getId());
        assertThat(invitation.getStatus()).isEqualTo(Invitation.Status.PENDING);
        verify(mailService, times(1))
                .send(eq("crew.member@example.com"), anyString(), contains("/register?token="), anyString());
    }

    @Test
    void officeUserResendsPendingInvitation() throws Exception {
        mockMvc.perform(post("/office/invitations")
                        .param("fullName", "New Office")
                        .param("email", "new.office@example.com")
                        .with(csrf()))
                .andExpect(redirectedUrl("/office/users"));
        Invitation invitation = onlyInvitation();
        UUID oldTokenId = invitation.getTokenId();
        clearInvocations(mailService);

        mockMvc.perform(post("/office/invitations/" + invitation.getId() + "/resend").with(csrf()))
                .andExpect(redirectedUrl("/office/users"));

        Invitation resent = onlyInvitation();
        assertThat(resent.getTokenId()).isNotEqualTo(oldTokenId);
        assertThat(resent.getStatus()).isEqualTo(Invitation.Status.PENDING);
        verify(mailService, times(1))
                .send(eq("new.office@example.com"), anyString(), contains("/register?token="), anyString());
    }

    // P07: office invitations are listed on the Office users page, with Resend and Cancel; the old page redirects.
    @Test
    void officeUsersPageListsPendingInvitations() throws Exception {
        mockMvc.perform(get("/office/invitations")).andExpect(redirectedUrl("/office/users"));
        mockMvc.perform(post("/office/invitations")
                        .param("fullName", "New Office")
                        .param("email", "new.office@example.com")
                        .with(csrf()))
                .andExpect(redirectedUrl("/office/users"));
        Invitation invitation = onlyInvitation();

        String html = mockMvc.perform(get("/office/users")).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Invite office user", ">New Office</td>", ">Invited</span>",
                "action=\"/office/invitations/" + invitation.getId() + "/resend\"",
                "action=\"/office/invitations/" + invitation.getId() + "/cancel\"",
                // the seeded office user, in the same table
                "title=\"office@example.com\"");

        mockMvc.perform(post("/office/invitations/" + invitation.getId() + "/cancel").with(csrf()))
                .andExpect(redirectedUrl("/office/users"));
        assertThat(onlyInvitation().getStatus()).isEqualTo(Invitation.Status.CANCELLED);
        assertThat(mockMvc.perform(get("/office/users")).andReturn().getResponse().getContentAsString())
                .doesNotContain(">New Office</td>");
    }

    // P17: a crew invitation is resent (expired or not) and cancelled with the same endpoints as an office invitation,
    // from the ship page's Crew tab, which it returns to; both are in the audit log with the ship.
    @Test
    void crewInvitationIsResentAndCancelledFromTheShipCrewTab() throws Exception {
        Ship ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Test");
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);
        mockMvc.perform(post("/office/ships/{id}/crew/new", ship.getId())
                .param("fullName", "Crew Member").param("email", "crew.member@example.com")
                .param("rank", "ABLE_SEAMAN").param("shipId", ship.getId().toString()).with(csrf()));
        Invitation invitation = onlyInvitation();
        invitation.setExpiresAt(Instant.now().minus(Duration.ofDays(2)));
        UUID oldTokenId = invitation.getTokenId();
        String crewTab = "/office/ships/" + ship.getId() + "/crew";
        String shipId = ship.getId().toString();

        assertThat(html(get(crewTab))).contains(">Invitation expired</span>", "2 days ago",
                "action=\"/office/invitations/" + invitation.getId() + "/resend\"",
                "<input type=\"hidden\" name=\"tab\" value=\"crew\">",
                "<input type=\"hidden\" name=\"shipId\" value=\"" + shipId + "\">",
                "href=\"/office/invitations/" + invitation.getId() + "/cancel?tab=crew&amp;shipId=" + shipId + "\"",
                "data-confirm-title=\"Cancel the invitation to Crew Member?\"");
        clearInvocations(mailService);

        // Resend: one click, back to the tab, with a new link and expiry date.
        mockMvc.perform(post("/office/invitations/{id}/resend", invitation.getId())
                        .param("tab", "crew").param("shipId", shipId).with(csrf()))
                .andExpect(redirectedUrl(crewTab))
                .andExpect(flash().attribute("successMessage", "invitation.resent"));
        Invitation resent = onlyInvitation();
        assertThat(resent.getTokenId()).isNotEqualTo(oldTokenId);
        assertThat(resent.getExpiresAt()).isAfter(Instant.now());
        verify(mailService, times(1))
                .send(eq("crew.member@example.com"), anyString(), contains("/register?token="), anyString());
        assertThat(html(get(crewTab))).contains(">Invited</span>", "expires in 7 days");

        // Cancel without the script: the confirmation page keeps the tab, then back to it.
        String confirm = html(get("/office/invitations/{id}/cancel", invitation.getId())
                .param("tab", "crew").param("shipId", shipId));
        assertThat(confirm).contains("Cancel the invitation to Crew Member?",
                "<input type=\"hidden\" name=\"tab\" value=\"crew\">",
                "<input type=\"hidden\" name=\"shipId\" value=\"" + shipId + "\">",
                "href=\"" + crewTab + "\">Keep invitation</a>", "<a href=\"" + crewTab + "\">Crew</a>");
        mockMvc.perform(post("/office/invitations/{id}/cancel", invitation.getId())
                        .param("tab", "crew").param("shipId", shipId).with(csrf()))
                .andExpect(redirectedUrl(crewTab))
                .andExpect(flash().attribute("successMessage", "invitation.cancelled"));
        assertThat(onlyInvitation().getStatus()).isEqualTo(Invitation.Status.CANCELLED);
        assertThat(html(get(crewTab))).doesNotContain("Crew Member");

        // Both in the audit log, with the ship.
        for (String action : List.of("INVITATION_RESENT", "INVITATION_CANCELLED")) {
            assertThat(auditRepository.findAll()).filteredOn(e -> e.getAction().equals(action)).singleElement()
                    .satisfies(e -> {
                        assertThat(e.getShip().getId()).isEqualTo(ship.getId());
                        assertThat(e.getDetails()).isEqualTo("crew.member@example.com as CREW_MEMBER");
                    });
        }
        // Again: no longer pending, back to the tab with a message.
        mockMvc.perform(post("/office/invitations/{id}/resend", invitation.getId())
                        .param("tab", "crew").param("shipId", shipId).with(csrf()))
                .andExpect(redirectedUrl(crewTab))
                .andExpect(flash().attribute("errorMessage", "invitation.error.notPending"));
    }

    // Where Resend and Cancel return to is never a URL from the request.
    @Test
    void resendAndCancelReturnOnlyToKnownLists() throws Exception {
        mockMvc.perform(post("/office/invitations")
                .param("fullName", "New Office").param("email", "new.office@example.com").with(csrf()));
        Long id = onlyInvitation().getId();
        mockMvc.perform(post("/office/invitations/{id}/resend", id).param("returnTo", "//evil.example.com")
                        .param("tab", "evil").param("status", "//evil").with(csrf()))
                .andExpect(redirectedUrl("/office/users"));
        mockMvc.perform(post("/office/invitations/{id}/resend", id).param("returnTo", "crew")
                        .param("status", "//evil").with(csrf()))
                .andExpect(redirectedUrl("/office/crew"));
        mockMvc.perform(post("/office/invitations/{id}/resend", id).param("returnTo", "overview").with(csrf()))
                .andExpect(redirectedUrl("/office"));
    }

    private String html(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private Invitation onlyInvitation() {
        List<Invitation> invitations = invitationRepository.findAll();
        assertThat(invitations).hasSize(1);
        return invitations.getFirst();
    }
}
