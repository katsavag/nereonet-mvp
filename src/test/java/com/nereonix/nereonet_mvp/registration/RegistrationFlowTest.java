package com.nereonix.nereonet_mvp.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.invitation.Invitation;
import com.nereonix.nereonet_mvp.core.invitation.InvitationRepository;
import com.nereonix.nereonet_mvp.core.invitation.InvitationTokenService;
import com.nereonix.nereonet_mvp.core.mail.MailService;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Rank;
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

    // P13: registered means signed in, on the home page, with no trip through the sign-in form.
    @Test
    void inviteeRegistersAndIsSignedIn() throws Exception {
        Ship ship = ship();
        Invitation invitation = invitation("new.crew@example.com", Role.CREW_MEMBER, ship, null);
        String token = tokenService.create(invitation);

        MvcResult form = mockMvc.perform(get("/register").param("token", token))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("new.crew@example.com")))
                .andReturn();
        MockHttpSession session = (MockHttpSession) form.getRequest().getSession();
        String sessionBefore = session.getId();

        MvcResult registered = mockMvc.perform(post("/register").session(session)
                        .param("token", token)
                        .param("password", "secret123")
                        .param("confirmPassword", "secret123")
                        .with(csrf()))
                .andExpect(redirectedUrl("/ship"))
                .andExpect(flash().attribute("successMessage", "registration.success"))
                .andExpect(authenticated().withUsername("new.crew@example.com").withRoles("CREW_MEMBER"))
                .andReturn();
        // A new session id, as after a form login (no session fixation).
        assertThat(registered.getRequest().getSession().getId()).isNotEqualTo(sessionBefore);

        User user = userRepository.findByEmail("new.crew@example.com").orElseThrow();
        assertThat(user.getRole()).isEqualTo(Role.CREW_MEMBER);
        assertThat(user.getShip().getId()).isEqualTo(ship.getId());
        assertThat(invitation.getStatus()).isEqualTo(Invitation.Status.ACCEPTED);
        assertThat(invitation.getAcceptedAt()).isNotNull();

        // The same session opens the home page, with the success message.
        mockMvc.perform(get("/ship").session(session)
                        .flashAttr("successMessage", "registration.success"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Your account is ready. You are signed in.")));

        // The password works on the sign-in form too.
        mockMvc.perform(post("/login")
                        .param("email", "new.crew@example.com")
                        .param("password", "secret123")
                        .with(csrf()))
                .andExpect(redirectedUrl("/ship"));
    }

    @Test
    void officeInviteeLandsOnTheOfficeHomePage() throws Exception {
        Invitation invitation = invitation("new.office@example.com", Role.OFFICE_USER, null, null);
        String token = tokenService.create(invitation);

        assertThat(page("/register?token=" + token)).contains("You are joining Nereonet as an office user.");

        mockMvc.perform(post("/register").param("token", token)
                        .param("password", "secret123").param("confirmPassword", "secret123").with(csrf()))
                .andExpect(redirectedUrl("/office"))
                .andExpect(authenticated().withUsername("new.office@example.com").withRoles("OFFICE_USER"));
    }

    @Test
    void failedRegistrationSignsNobodyIn() throws Exception {
        Invitation invitation = invitation("new.crew@example.com", Role.CREW_MEMBER, ship(), null);
        String token = tokenService.create(invitation);

        mockMvc.perform(post("/register").param("token", token)
                        .param("password", "short").param("confirmPassword", "other").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("registration/register"))
                .andExpect(unauthenticated());
        mockMvc.perform(post("/register").param("token", "not-a-token")
                        .param("password", "secret123").param("confirmPassword", "secret123").with(csrf()))
                .andExpect(view().name("registration/invalid"))
                .andExpect(unauthenticated());
        assertThat(userRepository.findByEmail("new.crew@example.com")).isEmpty();
    }

    // P13: what the person is joining, the password rules as a checklist, and the language switch that keeps the
    // invitation.
    @Test
    void formSaysWhatThePersonJoinsAndKeepsTheTokenInTheLanguageSwitch() throws Exception {
        Ship ship = ship();
        String withRank = tokenService.create(invitation("officer@example.com", Role.CREW_MEMBER, ship, Rank.SECOND_OFFICER));
        String withoutRank = tokenService.create(invitation("deckhand@example.com", Role.CREW_MEMBER, ship, null));

        String html = page("/register?token=" + withRank);
        assertThat(html).contains("You are joining MV Test as Second Officer.",
                        "href=\"?lang=en&amp;token=" + withRank + "\"", "href=\"?lang=el&amp;token=" + withRank + "\"",
                        "aria-describedby=\"passwordRules passwordCounter\"",
                        "data-rule=\"min-length\" data-min=\"8\"", ">At least 8 characters<",
                        "data-rule=\"match\" data-match=\"confirmPassword\"", ">Both passwords are the same<",
                        "data-password-toggle=\"password\"", "data-password-toggle=\"confirmPassword\"",
                        "id=\"passwordCapsLock\"", "id=\"confirmPasswordCapsLock\"")
                .doesNotContain("href=\"?lang=el\"");
        assertThat(page("/register?token=" + withoutRank)).contains("You are joining MV Test as a crew member.");

        // Switching language stays on the form of the same invitation.
        assertThat(mockMvc.perform(get("/register").param("lang", "el").param("token", withRank))
                .andExpect(status().isOk()).andExpect(view().name("registration/register"))
                .andReturn().getResponse().getContentAsString())
                .contains("Εντάσσεστε στο MV Test ως Β&#39; Αξιωματικός Γέφυρας.", "officer@example.com");

        // The server still checks the rules.
        mockMvc.perform(post("/register").param("token", withRank)
                        .param("password", "short").param("confirmPassword", "short").with(csrf()))
                .andExpect(view().name("registration/register"))
                .andExpect(content().string(Matchers.containsString("The password must be 8 to 72 characters.")));
    }

    // P13: the two causes, and no office contact while app.support-contact is not set.
    @Test
    void invalidLinkExplainsBothCauses() throws Exception {
        Invitation invitation = invitation("used@example.com", Role.CREW_MEMBER, ship(), null);
        String token = tokenService.create(invitation);
        invitation.setStatus(Invitation.Status.ACCEPTED);

        String html = page("/register?token=" + token);
        assertThat(html).contains("This link is invalid or has expired", "It was used already.", "It expired.",
                        "A link works for 7 days. Ask the office to send you a new invitation.", "href=\"/login\"")
                .doesNotContain("class=\"support-contact\"", "Office contact");
    }

    @Nested
    @TestPropertySource(properties = "app.support-contact=Crewing department, crewing@example.com")
    class WithSupportContact {

        @Test
        void invalidLinkShowsTheOfficeContact() throws Exception {
            assertThat(page("/register?token=not-a-token"))
                    .contains("Office contact", "Crewing department, crewing@example.com");
        }
    }

    private String page(String url) throws Exception {
        return mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private Ship ship() {
        Ship ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Test");
        ship.setCreatedAt(Instant.now());
        return shipRepository.save(ship);
    }

    private Invitation invitation(String email, Role role, Ship ship, Rank rank) {
        Invitation invitation = new Invitation();
        invitation.setEmail(email);
        invitation.setFullName("New Person");
        invitation.setRole(role);
        invitation.setShip(ship);
        invitation.setRank(rank);
        invitation.setTokenId(UUID.randomUUID());
        invitation.setStatus(Invitation.Status.PENDING);
        invitation.setExpiresAt(Instant.now().plus(Duration.ofDays(7)));
        invitation.setInvitedBy(userRepository.findByEmail("office@example.com").orElseThrow()); // BootstrapData
        invitation.setCreatedAt(Instant.now());
        return invitationRepository.save(invitation);
    }
}
