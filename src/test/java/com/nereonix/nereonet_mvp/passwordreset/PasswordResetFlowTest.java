package com.nereonix.nereonet_mvp.passwordreset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditEntry;
import com.nereonix.nereonet_mvp.core.audit.AuditRepository;
import com.nereonix.nereonet_mvp.core.invitation.Invitation;
import com.nereonix.nereonet_mvp.core.invitation.InvitationRepository;
import com.nereonix.nereonet_mvp.core.invitation.InvitationTokenService;
import com.nereonix.nereonet_mvp.core.mail.MailService;
import com.nereonix.nereonet_mvp.core.passwordreset.PasswordReset;
import com.nereonix.nereonet_mvp.core.passwordreset.PasswordResetRepository;
import com.nereonix.nereonet_mvp.core.passwordreset.PasswordResetTokenService;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;
import com.nereonix.nereonet_mvp.core.user.UserService;

/** Forgot password (P14): request, unknown address, reset, reused link, expired link, and the limits. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PasswordResetFlowTest {

    private static final Pattern LINK = Pattern.compile("http://localhost:8080(/password/reset\\?token=\\S+)");
    private static final String CONFIRMATION = "If an account exists for this address, we sent a link";

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired UserRepository userRepository;
    @Autowired AuditRepository auditRepository;
    @Autowired PasswordResetRepository resetRepository;
    @Autowired PasswordResetTokenService resetTokens;
    @Autowired InvitationRepository invitationRepository;
    @Autowired InvitationTokenService invitationTokens;

    @MockitoBean
    MailService mailService;

    @Test
    void signInPageLinksToForgotPassword() throws Exception {
        assertThat(page("/login")).contains("<p class=\"auth-link\"><a href=\"/password/forgot\">Forgot your password?</a></p>");
        assertThat(page("/password/forgot")).contains("name=\"email\"", "Send link", "href=\"/login\"");
    }

    @Test
    void requestThenResetSignsTheUserInAndRecordsIt() throws Exception {
        user("maria@example.com", "old-password");

        mockMvc.perform(post("/password/forgot").param("email", " Maria@Example.com ").with(csrf()))
                .andExpect(redirectedUrl("/password/forgot/sent"));
        assertThat(page("/password/forgot/sent")).contains(CONFIRMATION, "works once and for 60 minutes");

        String text = emailTo("maria@example.com");
        assertThat(text).contains("Hello Maria Test", "works once and for 60 minutes")
                .doesNotContain("old-password");
        String link = link(text);

        // The link's page: the account, the rules, show / hide and the checklist of registration; the language
        // switch keeps the token.
        String form = page(link);
        assertThat(form).contains("Set a new password", "maria@example.com", "data-password-toggle=\"password\"",
                "data-password-toggle=\"confirmPassword\"", "id=\"passwordRules\"", ">At least 8 characters<",
                "href=\"?lang=el&amp;token=" + token(link) + "\"");

        // Same rules as registration, checked by the server.
        mockMvc.perform(post("/password/reset").param("token", token(link))
                        .param("password", "short").param("confirmPassword", "other").with(csrf()))
                .andExpect(view().name("passwordreset/reset"))
                .andExpect(unauthenticated());

        mockMvc.perform(post("/password/reset").param("token", token(link))
                        .param("password", "new-password").param("confirmPassword", "new-password").with(csrf()))
                .andExpect(redirectedUrl("/office"))
                .andExpect(flash().attribute("successMessage", "passwordReset.success"))
                .andExpect(authenticated().withUsername("maria@example.com"));

        AuditEntry entry = auditRepository.findAll().stream()
                .filter(e -> e.getAction().equals("PASSWORD_RESET")).findFirst().orElseThrow();
        assertThat(entry.getActor().getEmail()).isEqualTo("maria@example.com");
        assertThat(entry.getDetails()).isEqualTo("maria@example.com");

        mockMvc.perform(post("/login").param("email", "maria@example.com").param("password", "old-password").with(csrf()))
                .andExpect(redirectedUrl("/login?error"));
        mockMvc.perform(post("/login").param("email", "maria@example.com").param("password", "new-password").with(csrf()))
                .andExpect(redirectedUrl("/office"));
    }

    // Acceptance: the confirmation is byte-identical for a known and an unknown address; the unknown one gets no email.
    @Test
    void unknownAddressGetsTheSameAnswerAndNoEmail() throws Exception {
        user("known@example.com", "old-password");

        MockHttpServletResponse known = mockMvc.perform(post("/password/forgot")
                .param("email", "known@example.com").with(csrf())).andReturn().getResponse();
        MockHttpServletResponse unknown = mockMvc.perform(post("/password/forgot")
                .param("email", "nobody@example.com").with(csrf())).andReturn().getResponse();
        assertThat(unknown.getStatus()).isEqualTo(known.getStatus()).isEqualTo(302);
        assertThat(unknown.getRedirectedUrl()).isEqualTo(known.getRedirectedUrl());

        byte[] knownPage = mockMvc.perform(get(known.getRedirectedUrl())).andReturn().getResponse().getContentAsByteArray();
        byte[] unknownPage = mockMvc.perform(get(unknown.getRedirectedUrl())).andReturn().getResponse().getContentAsByteArray();
        assertThat(unknownPage).isEqualTo(knownPage);
        assertThat(new String(knownPage, java.nio.charset.StandardCharsets.UTF_8))
                .contains(CONFIRMATION).doesNotContain("known@example.com", "_csrf");

        verify(mailService, timeout(5000)).send(eq("known@example.com"), anyString(), anyString());
        verify(mailService, after(300).never()).send(eq("nobody@example.com"), anyString(), anyString());
    }

    // Acceptance: a link used once shows the invalid-link page the second time, and changes nothing.
    @Test
    void reusedLinkShowsTheInvalidLinkPage() throws Exception {
        user("reuse@example.com", "old-password");
        mockMvc.perform(post("/password/forgot").param("email", "reuse@example.com").with(csrf()));
        String link = link(emailTo("reuse@example.com"));
        mockMvc.perform(post("/password/reset").param("token", token(link))
                        .param("password", "first-new").param("confirmPassword", "first-new").with(csrf()))
                .andExpect(redirectedUrl("/office"));

        assertInvalidLinkPage(page(link));
        mockMvc.perform(post("/password/reset").param("token", token(link))
                        .param("password", "second-new").param("confirmPassword", "second-new").with(csrf()))
                .andExpect(view().name("passwordreset/invalid"))
                .andExpect(unauthenticated());
        mockMvc.perform(post("/login").param("email", "reuse@example.com").param("password", "first-new").with(csrf()))
                .andExpect(redirectedUrl("/office"));
    }

    @Test
    void expiredLinkShowsTheInvalidLinkPage() throws Exception {
        User user = user("late@example.com", "old-password");
        PasswordReset reset = new PasswordReset();
        reset.setTokenId(UUID.randomUUID());
        reset.setUserId(user.getId());
        reset.setEmail(user.getEmail());
        reset.setCreatedAt(Instant.now().minus(Duration.ofMinutes(61)));
        reset.setExpiresAt(Instant.now().minus(Duration.ofMinutes(1)));
        resetRepository.save(reset);

        assertInvalidLinkPage(page("/password/reset?token=" + resetTokens.create(reset)));
        assertInvalidLinkPage(page("/password/reset?token=not-a-token"));
        assertInvalidLinkPage(page("/password/reset"));
    }

    @Test
    void anotherLinkStopsWorkingOnceOneIsUsed() throws Exception {
        user("twice@example.com", "old-password");
        mockMvc.perform(post("/password/forgot").param("email", "twice@example.com").with(csrf()));
        mockMvc.perform(post("/password/forgot").param("email", "twice@example.com").with(csrf()));
        ArgumentCaptor<String> texts = ArgumentCaptor.forClass(String.class);
        verify(mailService, timeout(5000).times(2)).send(eq("twice@example.com"), anyString(), texts.capture());
        String first = link(texts.getAllValues().get(0));
        String second = link(texts.getAllValues().get(1));

        mockMvc.perform(post("/password/reset").param("token", token(second))
                        .param("password", "new-password").param("confirmPassword", "new-password").with(csrf()))
                .andExpect(redirectedUrl("/office"));
        assertInvalidLinkPage(page(first));
    }

    @Test
    void atMostFiveEmailsPerAddressPerHour() throws Exception {
        user("busy@example.com", "old-password");
        for (int i = 0; i < 7; i++) {
            mockMvc.perform(post("/password/forgot").param("email", "busy@example.com").with(csrf()))
                    .andExpect(redirectedUrl("/password/forgot/sent"));
        }
        verify(mailService, after(500).times(5)).send(eq("busy@example.com"), anyString(), anyString());
    }

    @Test
    void deactivatedAccountGetsNoEmailAndItsLinksStopWorking() throws Exception {
        User user = user("gone@example.com", "old-password");
        mockMvc.perform(post("/password/forgot").param("email", "gone@example.com").with(csrf()));
        String link = link(emailTo("gone@example.com"));

        user.setEnabled(false);
        userRepository.saveAndFlush(user);
        mockMvc.perform(post("/password/forgot").param("email", "gone@example.com").with(csrf()))
                .andExpect(redirectedUrl("/password/forgot/sent"));
        verify(mailService, after(300).times(1)).send(eq("gone@example.com"), anyString(), anyString());
        assertInvalidLinkPage(page(link));
    }

    @Test
    void invitationTokenIsNotAResetToken() throws Exception {
        Invitation invitation = new Invitation();
        invitation.setEmail("office@example.com");
        invitation.setFullName("John Doe");
        invitation.setRole(Role.OFFICE_USER);
        invitation.setTokenId(UUID.randomUUID());
        invitation.setStatus(Invitation.Status.PENDING);
        invitation.setExpiresAt(Instant.now().plus(Duration.ofDays(7)));
        invitation.setCreatedAt(Instant.now());
        invitationRepository.save(invitation);

        assertInvalidLinkPage(page("/password/reset?token=" + invitationTokens.create(invitation)));
    }

    @Test
    void pagesAreInGreekToo() throws Exception {
        assertThat(mockMvc.perform(get("/password/forgot").param("lang", "el")).andReturn().getResponse()
                .getContentAsString()).contains("Ξεχάσατε τον κωδικό σας;", "Αποστολή συνδέσμου");
        assertThat(mockMvc.perform(get("/password/forgot/sent").param("lang", "el")).andReturn().getResponse()
                .getContentAsString()).contains("Αν υπάρχει λογαριασμός με αυτή τη διεύθυνση");
        assertThat(mockMvc.perform(get("/password/reset").param("lang", "el")).andReturn().getResponse()
                .getContentAsString()).contains("Ο σύνδεσμος επαναφοράς δεν είναι έγκυρος ή έχει λήξει");

        // The email is in the language of the page the request came from.
        user("greek@example.com", "old-password");
        mockMvc.perform(post("/password/forgot").param("email", "greek@example.com").param("lang", "el").with(csrf()));
        verify(mailService, timeout(5000)).send(eq("greek@example.com"), eq("Νέος κωδικός για το Nereonet"),
                contains("Ο σύνδεσμος λειτουργεί μία φορά και για 60 λεπτά."));
    }

    @Test
    void emptyOrInvalidAddressIsAskedAgain() throws Exception {
        assertThat(mockMvc.perform(post("/password/forgot").param("email", "").with(csrf()))
                .andExpect(status().isOk()).andExpect(view().name("passwordreset/forgot"))
                .andReturn().getResponse().getContentAsString()).contains("Enter your email address.");
        assertThat(mockMvc.perform(post("/password/forgot").param("email", "not an address").with(csrf()))
                .andExpect(view().name("passwordreset/forgot"))
                .andReturn().getResponse().getContentAsString()).contains("Enter a valid email address");
        verify(mailService, after(200).never()).send(anyString(), anyString(), anyString());
    }

    private void assertInvalidLinkPage(String html) {
        assertThat(html).contains("This reset link is invalid or has expired", "works once and for 60 minutes",
                "href=\"/password/forgot\" class=\"btn btn-primary btn-lg w-100 mt-3\">Request a new link</a>");
    }

    private User user(String email, String password) {
        return userService.createUser(email, email.startsWith("maria") ? "Maria Test" : "Test User", password,
                Role.OFFICE_USER, null, null);
    }

    private String emailTo(String email) {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(mailService, timeout(5000)).send(eq(email), eq("Set a new Nereonet password"), text.capture());
        return text.getValue();
    }

    private static String link(String text) {
        Matcher m = LINK.matcher(text);
        assertThat(m.find()).as("reset link in " + text).isTrue();
        return m.group(1);
    }

    private static String token(String link) {
        return link.substring(link.indexOf("token=") + "token=".length());
    }

    private String page(String url) throws Exception {
        return mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
}
