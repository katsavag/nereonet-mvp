package com.nereonix.nereonet_mvp.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.invitation.Invitation;
import com.nereonix.nereonet_mvp.core.invitation.InvitationRepository;
import com.nereonix.nereonet_mvp.core.invitation.InvitationTokenService;
import com.nereonix.nereonet_mvp.core.mail.MailService;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RegistrationFlowTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ShipRepository shipRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    InvitationRepository invitationRepository;

    @Autowired
    InvitationTokenService tokenService;

    @MockitoBean
    MailService mailService;

    @Test
    void inviteeRegistersAndLogsIn() throws Exception {
        Ship ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Test");
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);
        Invitation invitation = new Invitation();
        invitation.setEmail("new.crew@example.com");
        invitation.setFullName("New Crew");
        invitation.setRole(Role.CREW_MEMBER);
        invitation.setShip(ship);
        invitation.setTokenId(UUID.randomUUID());
        invitation.setStatus(Invitation.Status.PENDING);
        invitation.setExpiresAt(Instant.now().plus(Duration.ofDays(7)));
        invitation.setInvitedBy(userRepository.findByEmail("office@example.com").orElseThrow()); // BootstrapData
        invitation.setCreatedAt(Instant.now());
        invitationRepository.save(invitation);
        String token = tokenService.create(invitation);

        mockMvc.perform(get("/register").param("token", token))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("new.crew@example.com")));

        mockMvc.perform(post("/register")
                        .param("token", token)
                        .param("password", "secret123")
                        .param("confirmPassword", "secret123")
                        .with(csrf()))
                .andExpect(redirectedUrl("/login"))
                .andExpect(flash().attribute("successMessage", "registration.success"));

        User user = userRepository.findByEmail("new.crew@example.com").orElseThrow();
        assertThat(user.getRole()).isEqualTo(Role.CREW_MEMBER);
        assertThat(user.getShip().getId()).isEqualTo(ship.getId());
        assertThat(invitation.getStatus()).isEqualTo(Invitation.Status.ACCEPTED);
        assertThat(invitation.getAcceptedAt()).isNotNull();

        mockMvc.perform(post("/login")
                        .param("email", "new.crew@example.com")
                        .param("password", "secret123")
                        .with(csrf()))
                .andExpect(redirectedUrl("/ship"));
    }
}
