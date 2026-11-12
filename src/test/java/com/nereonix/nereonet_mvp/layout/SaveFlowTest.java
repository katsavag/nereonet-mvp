package com.nereonix.nereonet_mvp.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.library.LibraryItem;
import com.nereonix.nereonet_mvp.core.library.LibraryItemRepository;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;
import com.nereonix.nereonet_mvp.core.user.UserService;

/**
 * P15: typing a long text and pressing Save is safe on an unstable link. The behaviour itself is js/save.js (no
 * JavaScript runs here); these tests check what it relies on: the script on every page, the user's id on the body
 * (drafts are per user), the draft keys on the long-text forms, the marker of a confirmed save, the texts in both
 * office languages, and that a form refused after the session expired leads back to the form with the draft.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class SaveFlowTest {

    private static final Pattern BODY = Pattern.compile("<body([^>]*)>");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ShipRepository shipRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    UserService userService;

    @Autowired
    LibraryItemRepository libraryItemRepository;

    Ship ship;
    User master;
    LibraryItem libraryItem;

    @BeforeEach
    void setUp() throws Exception {
        ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);
        master = userService.createUser("master@example.com", "Mike Master", "secret123", Role.MASTER, ship, null);
        mockMvc.perform(post("/office/ships/{id}/library/new", ship.getId()).with(csrf())
                .param("title", "Galley rules").param("category", "SHIP_RULES").param("versionLabel", "1")
                .param("required", "false").param("text", "Wash your hands."));
        libraryItem = libraryItemRepository.findAll().getFirst();
    }

    @Test
    void everyPageHasTheScriptTheBannerAndTheSignedInUser() throws Exception {
        User office = userRepository.findByEmail("office@example.com").orElseThrow();
        String page = html(get("/office/ships"));
        assertThat(body(page)).contains("data-user=\"" + office.getId() + "\"");
        assertThat(page).containsPattern("<script src=\"/js/save(-[0-9a-f]+)?\\.js\"></script>")
                .containsOnlyOnce("id=\"offlineBanner\"")
                .contains("data-text=\"No connection. Your text is kept on this device.\"",
                        "data-blocked=\"Not sent: there is no connection. Send it again when the connection is back.\"",
                        "data-busy=\"Saving…\"")
                .containsOnlyOnce("<template id=\"draftNotice\">")
                .contains("data-template=\"Draft restored from {time}\"", ">Discard</button>")
                // Sign out says what it does while it is on its way.
                .contains("data-busy-label=\"Signing out…\"");

        // The crew's pages too, with their own id: one person's draft is never offered to another.
        String crewPage = html(get("/ship/library").with(user(new CurrentUser(master))));
        assertThat(body(crewPage)).contains("data-user=\"" + master.getId() + "\"");
        assertThat(crewPage).containsPattern("/js/save(-[0-9a-f]+)?\\.js");
    }

    @Test
    void signedOutPagesHaveNoUserAndNoDrafts() throws Exception {
        String login = mockMvc.perform(get("/login").with(anonymous()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body(login)).doesNotContain("data-user");
        assertThat(login).contains("id=\"offlineBanner\"", "data-busy-label=\"Signing in…\"")
                .doesNotContain("id=\"draftNotice\"")
                .containsPattern("/js/save(-[0-9a-f]+)?\\.js");
    }

    @Test
    void longTextFormsKeepADraftUnderTheirPagesUrl() throws Exception {
        String library = "/office/ships/" + ship.getId() + "/library";

        // Announcement: title and message, never the target (a draft never picks a ship).
        String announcement = html(get("/office/announcements/new").param("shipId", ship.getId().toString()));
        assertThat(announcement).contains("data-draft=\"/office/announcements/new\"")
                .contains("data-draft-skip id=\"target\"");
        // Shown again with its errors (URL /review): the same key.
        assertThat(html(post("/office/announcements/review").with(csrf()).param("target", "").param("title", "Drill")
                .param("body", ""))).contains("data-draft=\"/office/announcements/new\"");
        // Sending from the review page removes it once the server confirms.
        assertThat(html(post("/office/announcements/review").with(csrf()).param("target", ship.getId().toString())
                .param("title", "Drill").param("body", "Friday 10:00")))
                .contains("action=\"/office/announcements/send\" data-draft-for=\"/office/announcements/new\"");

        // Library: add an item, publish a new version, ship particulars; office and crew.
        assertThat(html(get(library + "/new"))).contains("data-draft=\"" + library + "/new\"");
        assertThat(html(get(library + "/" + libraryItem.getId() + "/edit")))
                .contains("data-draft=\"" + library + "/" + libraryItem.getId() + "/edit\"");
        assertThat(html(get(library + "/particulars"))).contains("data-draft=\"" + library + "/particulars\"");
        CurrentUser crew = new CurrentUser(master);
        assertThat(html(get("/ship/library/new").with(user(crew)))).contains("data-draft=\"/ship/library/new\"");
        assertThat(html(get("/ship/library/particulars").with(user(crew))))
                .contains("data-draft=\"/ship/library/particulars\"");
        assertThat(html(get("/ship/library/{id}/edit", libraryItem.getId()).with(user(crew))))
                .contains("data-draft=\"/ship/library/" + libraryItem.getId() + "/edit\"");

        // Short forms keep no draft.
        assertThat(html(get("/office/ships"))).doesNotContain("data-draft=");
    }

    @Test
    void aConfirmedSaveIsMarkedSoTheDraftIsRemoved() throws Exception {
        String saved = html(get("/ship/library").with(user(new CurrentUser(master)))
                .flashAttr("successMessage", "library.particulars.saved"));
        assertThat(saved).contains("data-flash=\"success\"");

        String failed = html(get("/office/requests").flashAttr("errorMessage", "request.export.noneSelected"));
        assertThat(failed).doesNotContain("data-flash=\"success\"");
    }

    @Test
    void anExpiredSessionLeadsBackToTheAnnouncementForm() throws Exception {
        // The review page (or the form shown again with errors) is a POST result: its URL now opens the form.
        mockMvc.perform(get("/office/announcements/review")).andExpect(redirectedUrl("/office/announcements/new"));
        mockMvc.perform(get("/office/announcements/send")).andExpect(redirectedUrl("/office/announcements/new"));

        // Sent from the review page after the session expired: sign in, back to the URL, on to the form.
        MockHttpSession session = (MockHttpSession) mockMvc.perform(post("/office/announcements/send")
                        .with(anonymous())
                        .param("title", "Drill").param("body", "Friday 10:00")
                        .header("Referer", "http://localhost/office/announcements/review"))
                .andExpect(redirectedUrl("/login?expired"))
                .andReturn().getRequest().getSession(true);
        mockMvc.perform(post("/login").session(session).param("email", "office@example.com")
                        .param("password", "office123").with(csrf()))
                .andExpect(redirectedUrl("/office/announcements/review"));
    }

    @Test
    void anExportComesBackAsAPage() throws Exception {
        // P21: the export POST redirects to a page, so its button never has to be given back by a timer.
        assertThat(html(get("/office/requests"))).contains("<form id=\"exportForm\" action=\"/office/requests/export\" method=\"post\"")
                .doesNotContain("data-download");
        String script = new ClassPathResource("static/js/save.js").getContentAsString(StandardCharsets.UTF_8);
        assertThat(script).doesNotContain("data-download");
    }

    @Test
    void textsInGreekForTheOffice() throws Exception {
        assertThat(html(get("/office/ships").param("lang", "el"))).contains(
                "data-text=\"Δεν υπάρχει σύνδεση. Το κείμενό σας φυλάσσεται σε αυτή τη συσκευή.\"",
                "data-busy=\"Αποθήκευση…\"", "data-template=\"Ανακτήθηκε πρόχειρο από τις {time}\"", ">Απόρριψη</button>");
    }

    @Test
    void scriptKeepsNoFilesOrPasswordsAndSurvivesBlockedStorage() throws Exception {
        String script = new ClassPathResource("static/js/save.js").getContentAsString(StandardCharsets.UTF_8);
        assertThat(script).contains("'hidden', 'password', 'file'", "data-draft-skip", "try {", "catch (e)",
                "'beforeunload'", "'pageshow'", "'online'", "'offline'", "[data-flash=\"success\"]",
                "user + ':' + url");
    }

    private String body(String html) {
        Matcher body = BODY.matcher(html);
        assertThat(body.find()).isTrue();
        return body.group(1);
    }

    private String html(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
