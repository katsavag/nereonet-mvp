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

    private Ship ship(String imoNumber, String name) {
        Ship ship = new Ship();
        ship.setImoNumber(imoNumber);
        ship.setName(name);
        ship.setCreatedAt(Instant.now());
        return shipRepository.save(ship);
    }
}
