package com.nereonix.nereonet_mvp.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;
import com.nereonix.nereonet_mvp.core.user.UserService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginFlowTest {

    private static final String WRONG = "Wrong email or password.";
    private static final String DEACTIVATED = "This account is deactivated. Contact your office.";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserService userService;

    @Autowired
    UserRepository userRepository;

    @Test
    void officeUserLogsInAndOpensOfficePage() throws Exception {
        // Seeded by BootstrapData from application-test.yml
        MockHttpSession session = (MockHttpSession) mockMvc.perform(post("/login")
                        .param("email", "office@example.com")
                        .param("password", "office123")
                        .with(csrf()))
                .andExpect(redirectedUrl("/office"))
                .andReturn().getRequest().getSession(false);

        mockMvc.perform(get("/office").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("John Doe")));
    }

    // P13: "deactivated" only with the right password; a wrong password never tells whether the account exists or
    // what state it is in.
    @Test
    @Transactional
    void deactivatedAccountIsNamedOnlyAfterTheRightPassword() throws Exception {
        User user = userService.createUser("gone@example.com", "Gone User", "secret123", Role.OFFICE_USER, null, null);
        user.setEnabled(false);
        userRepository.saveAndFlush(user);

        mockMvc.perform(post("/login").param("email", "gone@example.com").param("password", "secret123").with(csrf()))
                .andExpect(redirectedUrl("/login?deactivated"))
                .andExpect(unauthenticated());
        assertThat(page("/login?deactivated")).contains(DEACTIVATED).doesNotContain(WRONG);

        mockMvc.perform(post("/login").param("email", "gone@example.com").param("password", "wrong-one").with(csrf()))
                .andExpect(redirectedUrl("/login?error"))
                .andExpect(unauthenticated());
        mockMvc.perform(post("/login").param("email", "nobody@example.com").param("password", "secret123").with(csrf()))
                .andExpect(redirectedUrl("/login?error"));
        assertThat(page("/login?error")).contains(WRONG).doesNotContain(DEACTIVATED);

        assertThat(mockMvc.perform(get("/login").param("deactivated", "").param("lang", "el"))
                .andReturn().getResponse().getContentAsString())
                .contains("Ο λογαριασμός έχει απενεργοποιηθεί. Επικοινωνήστε με το γραφείο σας.");
    }

    // P13: show / hide and Caps Lock on the password field. Both start inert (hidden button, empty hint): without the
    // script the field is an ordinary password field. On phones the brand panel is a header bar (authFrame).
    @Test
    void passwordFieldHasToggleAndCapsLockHint() throws Exception {
        String html = page("/login").replaceAll("\\s+", " ");

        assertThat(html).contains("class=\"auth-frame\"", "class=\"auth-brand ", "class=\"login-box w-100\"",
                        "/js/password", "<div class=\"password-field\">")
                .containsPattern("<button type=\"button\" class=\"password-toggle\" hidden data-password-toggle=\"password\""
                        + "[^>]*aria-controls=\"password\" aria-label=\"Show password\"[^>]*>Show</button>")
                .containsPattern("<p class=\"caps-lock-hint\" aria-live=\"polite\" id=\"passwordCapsLock\""
                        + " data-caps-lock-for=\"password\" data-text=\"Caps Lock is on.\"></p>")
                // the language switch is still there, without a token
                .contains("href=\"?lang=el\"");
    }

    // P30: the panel has the brand (mark and "Nereonix" as text) and, only when app.organisation.name is set, the
    // organisation; not set here.
    @Test
    void signInPanelHasTheBrandAndNoOrganisationWhenNoneIsSet() throws Exception {
        String html = page("/login").replaceAll("\\s+", " ");
        assertThat(html).containsPattern("<div class=\"auth-brand bg-navy bg-grid\">( <!--[^>]*-->)? <div class=\"brand\">")
                .contains("<div class=\"brand-name\">Nereonix</div>")
                .doesNotContain("auth-organisation", "Organisation");
    }

    @Nested
    @TestPropertySource(properties = {"app.organisation.name=Nereonix Bulk Carriers", "app.organisation.code=NEREONIX"})
    class WithOrganisation {

        @Test
        void signInPanelShowsTheOrganisationInEitherLanguage() throws Exception {
            assertThat(page("/login").replaceAll("\\s+", " ")).contains("<p class=\"auth-organisation\">"
                    + " <span class=\"auth-organisation-label\">Organisation</span>"
                    + " <span class=\"auth-organisation-name\">Nereonix Bulk Carriers</span>"
                    + " <span class=\"auth-organisation-code\">NEREONIX</span> </p>");
            assertThat(page("/password/forgot?lang=el")).contains("<span class=\"auth-organisation-label\">Εταιρεία</span>",
                    "Nereonix Bulk Carriers");
        }
    }

    private String page(String url) throws Exception {
        return mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
}
