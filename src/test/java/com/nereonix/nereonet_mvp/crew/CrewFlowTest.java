package com.nereonix.nereonet_mvp.crew;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import java.time.Instant;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditEntry;
import com.nereonix.nereonet_mvp.core.audit.AuditRepository;
import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.invitation.Invitation;
import com.nereonix.nereonet_mvp.core.invitation.InvitationRepository;
import com.nereonix.nereonet_mvp.core.mail.MailService;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;
import com.nereonix.nereonet_mvp.core.user.UserService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class CrewFlowTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ShipRepository shipRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    UserService userService;

    @Autowired
    InvitationRepository invitationRepository;

    @Autowired
    AuditService auditService;

    @Autowired
    AuditRepository auditRepository;

    @MockitoBean
    MailService mailService;

    @Test
    void officeUserAddsCrewMember() throws Exception {
        Ship ship = ship("9074729", "MV Demo");

        mockMvc.perform(post("/office/crew/new")
                        .param("fullName", "Jane Cook")
                        .param("email", "jane.cook@example.com")
                        .param("rank", "CHIEF_COOK")
                        .param("role", "CHIEF_COOK")
                        .param("shipId", ship.getId().toString())
                        .with(csrf()))
                .andExpect(redirectedUrl("/office/crew"));

        List<Invitation> invitations = invitationRepository.findAll();
        assertThat(invitations).hasSize(1);
        Invitation invitation = invitations.getFirst();
        assertThat(invitation.getStatus()).isEqualTo(Invitation.Status.PENDING);
        assertThat(invitation.getRole()).isEqualTo(Role.CHIEF_COOK);
        assertThat(invitation.getShip().getId()).isEqualTo(ship.getId());
        assertThat(invitation.getRank()).isEqualTo(Rank.CHIEF_COOK);
        verify(mailService, times(1))
                .send(eq("jane.cook@example.com"), anyString(), contains("/register?token="));

        mockMvc.perform(get("/office/crew"))
                .andExpect(content().string(Matchers.containsString("jane.cook@example.com")));
    }

    @Test
    void officeUserMovesCrewMemberToAnotherShip() throws Exception {
        Ship from = ship("9074729", "MV Demo");
        Ship to = ship("9176187", "MV Aegean Spirit");
        User crew = userService.createUser("crew@example.com", "Crew Member", "secret123", Role.CREW_MEMBER, from,
                Rank.DECK_CADET);

        mockMvc.perform(post("/office/crew/" + crew.getId() + "/edit")
                        .param("fullName", "Crew Member")
                        .param("rank", "THIRD_OFFICER")
                        .param("role", "CREW_MEMBER")
                        .param("shipId", to.getId().toString())
                        .with(csrf()))
                .andExpect(redirectedUrl("/office/crew"));

        User saved = userRepository.findById(crew.getId()).orElseThrow();
        assertThat(saved.getShip().getId()).isEqualTo(to.getId());
        assertThat(saved.getRank()).isEqualTo(Rank.THIRD_OFFICER);

        mockMvc.perform(get("/office/crew").param("shipId", to.getId().toString()))
                .andExpect(content().string(Matchers.containsString("crew@example.com")));
    }

    @Test
    void officeUserDeletesDeactivatedCrewMember() throws Exception {
        Ship ship = ship("9074729", "MV Demo");
        mockMvc.perform(post("/office/crew/new")
                        .param("fullName", "Crew Member")
                        .param("email", "crew@example.com")
                        .param("role", "CREW_MEMBER")
                        .param("shipId", ship.getId().toString())
                        .with(csrf()))
                .andExpect(redirectedUrl("/office/crew"));
        User crew = userService.createUser("crew@example.com", "Crew Member", "secret123", Role.CREW_MEMBER, ship,
                null);
        auditService.record("USER_REGISTERED", ship, "crew@example.com", crew);
        mockMvc.perform(post("/office/users/" + crew.getId() + "/deactivate").with(csrf()))
                .andExpect(redirectedUrl("/office/crew"));

        mockMvc.perform(post("/office/users/" + crew.getId() + "/delete").with(csrf()))
                .andExpect(redirectedUrl("/office/crew"));

        assertThat(userRepository.findById(crew.getId())).isEmpty();
        assertThat(invitationRepository.findAll()).isEmpty();
        AuditEntry registered = auditRepository.findAll().stream()
                .filter(e -> e.getAction().equals("USER_REGISTERED")).findFirst().orElseThrow();
        assertThat(registered.getActor()).isNull();
        assertThat(registered.isActorDeleted()).isTrue();
    }

    // P17: the Status filter, a query parameter (/office/crew?status=expired), with the ship filter.
    @Test
    void crewListFiltersByStatus() throws Exception {
        Ship demo = ship("9074729", "MV Demo");
        Ship other = ship("9176187", "MV Other");
        userService.createUser("anna@example.com", "Anna Active", "secret123", Role.CREW_MEMBER, demo, null);
        User inactive = userService.createUser("ivan@example.com", "Ivan Inactive", "secret123", Role.CREW_MEMBER,
                demo, null);
        inactive.setEnabled(false);
        invitation("ingrid@example.com", "Ingrid Invited", demo, 3);
        invitation("erik@example.com", "Erik Expired", demo, -2);
        invitation("olaf@example.com", "Olaf Elsewhere", other, -2);

        assertThat(html(get("/office/crew").param("status", "active"))).contains("Anna Active")
                .doesNotContain("Ivan Inactive", "Ingrid Invited", "Erik Expired");
        assertThat(html(get("/office/crew").param("status", "inactive"))).contains("Ivan Inactive")
                .doesNotContain("Anna Active", "Ingrid Invited", "Erik Expired");
        assertThat(html(get("/office/crew").param("status", "invited")))
                .contains("Ingrid Invited", ">Invited</span>", "expires in 3 days")
                .doesNotContain("Anna Active", "Ivan Inactive", "Erik Expired");
        assertThat(html(get("/office/crew").param("status", "expired")))
                .contains("Erik Expired", "Olaf Elsewhere", ">Invitation expired</span>", "2 days ago",
                        "<option value=\"expired\" selected=\"selected\">Invitation expired</option>")
                .doesNotContain("Anna Active", "Ivan Inactive", "Ingrid Invited");
        // With the ship filter.
        assertThat(html(get("/office/crew").param("status", "expired").param("shipId", demo.getId().toString())))
                .contains("Erik Expired").doesNotContain("Olaf Elsewhere");
        // No or an unknown status: everyone.
        for (String status : new String[] {"", "unknown"}) {
            assertThat(html(get("/office/crew").param("status", status)))
                    .contains("Anna Active", "Ivan Inactive", "Ingrid Invited", "Erik Expired", "Olaf Elsewhere",
                            "<option value=\"\">All statuses</option>")
                    .doesNotContainPattern("<option value=\"(active|invited|expired|inactive)\" selected");
        }
        // The filter is labelled and offers the four statuses.
        assertThat(html(get("/office/crew"))).contains("<label for=\"filterStatus\"", "name=\"status\"",
                ">Active</option>", ">Invited</option>", ">Invitation expired</option>", ">Inactive</option>");
        assertThat(html(get("/office/crew").param("lang", "el"))).contains(">Όλες οι καταστάσεις</option>",
                ">Η πρόσκληση έληξε</option>");
    }

    // P17: Resend and Cancel invitation on the Crew list; both return to it with its filters.
    @Test
    void crewInvitationIsResentAndCancelledFromTheCrewList() throws Exception {
        Ship demo = ship("9074729", "MV Demo");
        Invitation expired = invitation("erik@example.com", "Erik Expired", demo, -2);
        String shipId = demo.getId().toString();
        String back = "/office/crew?shipId=" + shipId + "&status=expired";

        String list = html(get("/office/crew").param("shipId", shipId).param("status", "expired"));
        assertThat(list).contains("action=\"/office/invitations/" + expired.getId() + "/resend\"",
                "<input type=\"hidden\" name=\"returnTo\" value=\"crew\">",
                "<input type=\"hidden\" name=\"shipId\" value=\"" + shipId + "\">",
                "<input type=\"hidden\" name=\"status\" value=\"expired\">",
                ">Resend</button>", ">Cancel invitation</a>",
                "href=\"/office/invitations/" + expired.getId() + "/cancel?returnTo=crew&amp;shipId=" + shipId
                        + "&amp;status=expired\"",
                "data-confirm-tone=\"danger\" data-confirm-dismiss=\"Keep invitation\"");

        // One click resends an expired invitation; back to the filtered list.
        mockMvc.perform(post("/office/invitations/{id}/resend", expired.getId()).param("returnTo", "crew")
                        .param("shipId", shipId).param("status", "expired").with(csrf()))
                .andExpect(redirectedUrl(back));
        assertThat(invitationRepository.findById(expired.getId()).orElseThrow().getDisplayStatus())
                .isEqualTo(Invitation.DisplayStatus.PENDING);
        verify(mailService, times(1)).send(eq("erik@example.com"), anyString(), contains("/register?token="));

        // Cancel: the confirmation page (no script) keeps the filters; confirming returns to the list.
        assertThat(html(get("/office/invitations/{id}/cancel", expired.getId()).param("returnTo", "crew")
                .param("shipId", shipId).param("status", "expired")))
                .contains("<input type=\"hidden\" name=\"status\" value=\"expired\">",
                        "href=\"/office/crew?shipId=" + shipId + "&amp;status=expired\">Keep invitation</a>");
        mockMvc.perform(post("/office/invitations/{id}/cancel", expired.getId()).param("returnTo", "crew")
                        .param("shipId", shipId).param("status", "expired").with(csrf()))
                .andExpect(redirectedUrl(back));
        assertThat(invitationRepository.findById(expired.getId()).orElseThrow().getStatus())
                .isEqualTo(Invitation.Status.CANCELLED);
        assertThat(auditRepository.findAll()).filteredOn(e -> e.getAction().startsWith("INVITATION_"))
                .extracting(AuditEntry::getAction).containsExactlyInAnyOrder("INVITATION_RESENT", "INVITATION_CANCELLED");
        assertThat(auditRepository.findAll()).filteredOn(e -> e.getAction().startsWith("INVITATION_"))
                .allSatisfy(e -> assertThat(e.getShip().getId()).isEqualTo(demo.getId()));
    }

    // A PENDING crew invitation that expires in this many days (negative: expired that many days ago).
    private Invitation invitation(String email, String fullName, Ship ship, int days) {
        Invitation invitation = new Invitation();
        invitation.setEmail(email);
        invitation.setFullName(fullName);
        invitation.setRole(Role.CREW_MEMBER);
        invitation.setShip(ship);
        invitation.setTokenId(java.util.UUID.randomUUID());
        invitation.setStatus(Invitation.Status.PENDING);
        // Midday, so the hint's whole UTC days do not depend on the time the test runs.
        invitation.setExpiresAt(java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(days)
                .atTime(12, 0).toInstant(java.time.ZoneOffset.UTC));
        invitation.setCreatedAt(Instant.now());
        return invitationRepository.save(invitation);
    }

    private String html(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private Ship ship(String imoNumber, String name) {
        Ship ship = new Ship();
        ship.setImoNumber(imoNumber);
        ship.setName(name);
        ship.setCreatedAt(Instant.now());
        return shipRepository.save(ship);
    }
}
