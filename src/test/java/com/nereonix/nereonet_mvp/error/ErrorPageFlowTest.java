package com.nereonix.nereonet_mvp.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.Cookie;

/**
 * P12: specific error pages, inside the normal frame when signed in, and the sign-in round trip after a form was sent
 * with an expired session or an invalid CSRF token.
 * MockMvc does not forward to /error as the server does; renderError() makes that second request with the status
 * and URL the first one ended with, as the server would.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ErrorPageFlowTest {

    // Seeded by BootstrapData from application-test.yml
    private static final String OFFICE_EMAIL = "office@example.com";
    private static final String OFFICE_PASSWORD = "office123";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ShipRepository shipRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    void crewMemberOpeningAnOfficeUrlSeesThe403PageWithTheShipSidebar() throws Exception {
        CurrentUser crew = new CurrentUser(crewMember("crew@example.com", "crew123"));

        MvcResult denied = mockMvc.perform(get("/office/ships").with(user(crew)))
                .andExpect(status().isForbidden()).andReturn();
        String html = renderError(denied, "/office/ships", user(crew), "error/403");

        assertThat(html).contains("<title>No access · Nereonet</title>", "Error 403",
                        "You don&#39;t have access to this page.")
                // Home is the user's own: /ship for the crew.
                .contains("href=\"/ship\" class=\"btn btn-primary\">Go to start page</a>")
                // The ship sidebar, so navigation still works; nothing of the office's.
                .contains("class=\"sidebar ", "href=\"/ship/library\"", "Sign out")
                .doesNotContain("href=\"/office/ships\"", "error-reference");
    }

    @Test
    void unknownLibraryItemShowsThe404Page() throws Exception {
        CurrentUser crew = new CurrentUser(crewMember("crew@example.com", "crew123"));

        MvcResult missing = mockMvc.perform(get("/ship/library/{id}", 999_999).with(user(crew)))
                .andExpect(status().isNotFound()).andReturn();
        String html = renderError(missing, "/ship/library/999999", user(crew), "error/404");

        assertThat(html).contains("<title>Page not found · Nereonet</title>", "Error 404",
                        "This page doesn&#39;t exist or was removed.", "href=\"/ship\" class=\"btn btn-primary\"")
                .contains("href=\"/ship/library\"")
                // The page keeps the section it failed under active (P07).
                .containsPattern("<a class=\"nav-link sidebar-link active\" href=\"/ship/library\" aria-current=\"page\">");
    }

    @Test
    void officeSees404InTheirLanguageWithTheOfficeSidebar() throws Exception {
        CurrentUser office = officeUser();

        MvcResult missing = mockMvc.perform(get("/office/ships/{id}", 999_999).with(user(office)))
                .andExpect(status().isNotFound()).andReturn();
        String html = mockMvc.perform(errorRequest(missing, "/office/ships/999999").with(user(office))
                        .cookie(new Cookie("lang", "el")))
                .andExpect(status().isNotFound()).andExpect(view().name("error/404"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Η σελίδα δεν βρέθηκε", "Αυτή η σελίδα δεν υπάρχει ή αφαιρέθηκε.", "Σφάλμα 404",
                "href=\"/office\" class=\"btn btn-primary\"", "href=\"/office/crew\"");
    }

    @Test
    void serverErrorShowsAReferenceToQuoteToTheOffice() throws Exception {
        String html = mockMvc.perform(get("/error").accept(MediaType.TEXT_HTML).with(user(officeUser()))
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/office/ships")
                        .requestAttr(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("boom")))
                .andExpect(status().isInternalServerError()).andExpect(view().name("error/5xx"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("<title>Something went wrong · Nereonet</title>", "Error 500",
                        "Something went wrong on our side. Try again.",
                        "If it keeps happening, quote this reference to the office:",
                        "href=\"/office\" class=\"btn btn-primary\"", "class=\"sidebar ")
                // The time in UTC and an eight-character reference without 0, O, 1 or I.
                .containsPattern("<time class=\"local-time text-nowrap\" datetime=\"\\d{4}-\\d\\d-\\d\\dT[0-9:]{8}Z\">"
                        + "\\d{1,2} \\w{3} \\d{4} · \\d\\d:\\d\\d UTC</time>")
                .containsPattern("<span class=\"num\">[2-9A-HJ-NP-Z]{8}</span>")
                .doesNotContain("boom");
    }

    @Test
    void signedOutErrorPageUsesTheSignInFrame() throws Exception {
        String html = mockMvc.perform(get("/error").accept(MediaType.TEXT_HTML)
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/register/nothing/here"))
                .andExpect(status().isNotFound()).andExpect(view().name("error/404"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("This page doesn&#39;t exist or was removed.", "login-box",
                        "href=\"/\" class=\"btn btn-primary btn-lg w-100\"")
                .doesNotContain("class=\"sidebar ");
    }

    @Test
    void formSentAfterTheSessionExpiredGoesToSignInAndBackToThePage() throws Exception {
        // No session any more, so no CSRF token: the server cannot accept the form.
        MvcResult refused = mockMvc.perform(post("/office/crew/5/edit").param("fullName", "Jane Doe")
                        .header("Referer", "http://localhost/office/crew?ship=3"))
                .andExpect(redirectedUrl("/login?expired")).andReturn();
        MockHttpSession session = (MockHttpSession) refused.getRequest().getSession(false);

        assertThat(mockMvc.perform(get("/login").param("expired", "").session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .contains("You were signed out. Sign in to continue.");
        assertThat(mockMvc.perform(get("/login").param("expired", "").param("lang", "el"))
                .andReturn().getResponse().getContentAsString())
                .contains("Αποσυνδεθήκατε. Συνδεθείτε για να συνεχίσετε.");
        assertThat(mockMvc.perform(get("/login")).andReturn().getResponse().getContentAsString())
                .doesNotContain("You were signed out.");

        signIn(session, OFFICE_EMAIL, OFFICE_PASSWORD).andExpect(redirectedUrl("/office/crew?ship=3"));
    }

    @Test
    void formWithAnInvalidTokenSignsTheUserOut() throws Exception {
        MockHttpSession session = new MockHttpSession();
        signIn(session, OFFICE_EMAIL, OFFICE_PASSWORD).andExpect(redirectedUrl("/office"));
        mockMvc.perform(get("/office").session(session)).andExpect(status().isOk());

        MvcResult refused = mockMvc.perform(post("/office/ships/new").session(session).with(csrf().useInvalidToken())
                        .header("Referer", "http://localhost/office/ships"))
                .andExpect(redirectedUrl("/login?expired")).andReturn();

        // Signed out: the old session is gone, the office needs a new sign-in.
        assertThat(session.isInvalid()).isTrue();
        MockHttpSession next = (MockHttpSession) refused.getRequest().getSession(false);
        mockMvc.perform(get("/office").session(next)).andExpect(redirectedUrl("/login"));

        signIn(next, OFFICE_EMAIL, OFFICE_PASSWORD).andExpect(redirectedUrl("/office/ships"));
    }

    @Test
    void pageOpenedWhileSignedOutOpensAfterSignIn() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/office/certificates?filter=expired").session(session))
                .andExpect(redirectedUrl("/login"));

        signIn(session, OFFICE_EMAIL, OFFICE_PASSWORD).andExpect(redirectedUrl("/office/certificates?filter=expired"));
    }

    @Test
    void signInNeverReturnsToAnotherSiteOrTheOtherSidesPages() throws Exception {
        crewMember("crew@example.com", "crew123");

        // Another site, or a path that would leave this one: home instead.
        for (String referer : new String[] {"https://evil.example/office/crew", "http://localhost//evil.example/x",
                "http://localhost/login?error", "not a url"}) {
            MockHttpSession session = expiredFormFrom(referer);
            signIn(session, OFFICE_EMAIL, OFFICE_PASSWORD).andExpect(redirectedUrl("/office"));
        }
        // On a shared PC the next person may be crew: an office page is not theirs.
        signIn(expiredFormFrom("http://localhost/office/crew"), "crew@example.com", "crew123")
                .andExpect(redirectedUrl("/ship"));
    }

    private MockHttpSession expiredFormFrom(String referer) throws Exception {
        return (MockHttpSession) mockMvc.perform(post("/office/crew/5/edit").header("Referer", referer))
                .andExpect(redirectedUrl("/login?expired"))
                .andReturn().getRequest().getSession(true);
    }

    private ResultActions signIn(MockHttpSession session, String email,
            String password) throws Exception {
        return mockMvc.perform(post("/login").session(session).param("email", email).param("password", password)
                .with(csrf()));
    }

    /** The error page the server shows after a request that ended with an error status. */
    private String renderError(MvcResult result, String uri, RequestPostProcessor user, String viewName)
            throws Exception {
        return mockMvc.perform(errorRequest(result, uri).with(user))
                .andExpect(status().is(result.getResponse().getStatus()))
                .andExpect(view().name(viewName))
                .andReturn().getResponse().getContentAsString();
    }

    private MockHttpServletRequestBuilder errorRequest(MvcResult result, String uri) {
        return get("/error").accept(MediaType.TEXT_HTML)
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, result.getResponse().getStatus())
                .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, uri)
                .requestAttr(RequestDispatcher.ERROR_MESSAGE, String.valueOf(result.getResponse().getErrorMessage()));
    }

    private CurrentUser officeUser() {
        return new CurrentUser(userRepository.findByEmail(OFFICE_EMAIL).orElseThrow());
    }

    private User crewMember(String email, String password) {
        Ship ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setFullName("Crew Member");
        user.setRole(Role.CREW_MEMBER);
        user.setShip(ship);
        user.setEnabled(true);
        user.setCreatedAt(Instant.now());
        return userRepository.save(user);
    }
}
