package com.nereonix.nereonet_mvp.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
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

import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.certificate.CertificateRepository;
import com.nereonix.nereonet_mvp.core.invitation.Invitation;
import com.nereonix.nereonet_mvp.core.invitation.InvitationRepository;
import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.library.LibraryItem;
import com.nereonix.nereonet_mvp.core.library.LibraryItemRepository;
import com.nereonix.nereonet_mvp.core.mail.MailService;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;
import com.nereonix.nereonet_mvp.core.user.UserService;

/**
 * P10: every deactivate, delete, cancel and retire action asks first, with the same dialog: its form opts in with
 * data-confirm-* attributes and has no submit button, and its trigger is a link to the plain confirmation page (the
 * same URL with GET), which is where it leads when js/confirm.js did not load.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class ConfirmFlowTest {

    private static final Pattern FORM = Pattern.compile("(?s)<form([^>]*)>(.*?)</form>");
    private static final Pattern ACTION = Pattern.compile("action=\"([^\"]*/(?:deactivate|delete|cancel|retire))\"");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ShipRepository shipRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    UserService userService;

    @Autowired
    CertificateRepository certificateRepository;

    @Autowired
    InvitationRepository invitationRepository;

    @Autowired
    ItemRepository itemRepository;

    @Autowired
    LibraryItemRepository libraryItemRepository;

    @MockitoBean
    MailService mailService;

    Ship ship;
    User crew;
    User master;
    User former;
    Certificate certificate;
    Invitation invitation;
    Item item;
    LibraryItem libraryItem;

    @BeforeEach
    void setUp() throws Exception {
        ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);
        crew = userService.createUser("jane@example.com", "Jane Doe", "secret123", Role.CREW_MEMBER, ship,
                Rank.DECK_CADET);
        master = userService.createUser("master@example.com", "Mike Master", "secret123", Role.MASTER, ship, null);
        former = userService.createUser("former@example.com", "Fred Former", "secret123", Role.CREW_MEMBER, ship,
                null);
        former.setEnabled(false);
        certificate = new Certificate();
        certificate.setUser(crew);
        certificate.setType("STCW Basic Safety");
        certificate.setExpiresOn(LocalDate.now(ZoneOffset.UTC).plusDays(400));
        certificate.setCreatedAt(Instant.now());
        certificateRepository.save(certificate);
        mockMvc.perform(post("/office/invitations").param("fullName", "Olga Office")
                .param("email", "olga@example.com").with(csrf()));
        invitation = invitationRepository.findAll().stream()
                .filter(i -> i.getEmail().equals("olga@example.com")).findFirst().orElseThrow();
        mockMvc.perform(post("/office/ships/{id}/items/new", ship.getId())
                .param("name", "Basmati rice").param("unit", "kg")
                .param("category", "PROVISIONS").with(csrf()));
        item = itemRepository.findAll().getFirst();
        mockMvc.perform(post("/office/ships/{id}/library/new", ship.getId()).with(csrf())
                .param("title", "Galley rules").param("category", "SHIP_RULES").param("versionLabel", "1")
                .param("required", "false").param("text", "Wash your hands."));
        libraryItem = libraryItemRepository.findAll().getFirst();
        userRepository.flush();
    }

    @Test
    void noTemplateUsesTheBrowsersConfirm() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/resources/templates"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".html")).toList()) {
                String html = Files.readString(file, StandardCharsets.UTF_8);
                if (html.contains("onsubmit") || html.contains("confirm(")) {
                    found.add(file.toString());
                }
            }
        }
        assertThat(found).isEmpty();
    }

    @Test
    void everyDestructiveActionAsksFirstWithTheSameDialog() throws Exception {
        String crewTab = "/office/ships/" + ship.getId() + "/crew";
        List<String> actions = new ArrayList<>();
        actions.addAll(confirmForms(get("/office/ships")));
        actions.addAll(confirmForms(get(crewTab)));
        actions.addAll(confirmForms(get("/office/crew")));
        actions.addAll(confirmForms(get("/office/users")));
        actions.addAll(confirmForms(get("/office/crew/" + crew.getId() + "/edit")));
        actions.addAll(confirmForms(get("/office/ships/" + ship.getId() + "/items")));
        // Retire is on the item's read view (P22), for the office as for the Master.
        actions.addAll(confirmForms(get("/office/ships/" + ship.getId() + "/library/" + libraryItem.getId())));
        actions.addAll(confirmForms(get("/ship/library/" + libraryItem.getId()).with(user(new CurrentUser(master)))));

        assertThat(actions).contains(
                "/office/ships/" + ship.getId() + "/deactivate",
                "/office/users/" + crew.getId() + "/deactivate",
                "/office/users/" + former.getId() + "/delete",
                "/office/invitations/" + invitation.getId() + "/cancel",
                "/office/crew/" + crew.getId() + "/certificates/" + certificate.getId() + "/delete",
                "/office/ships/" + ship.getId() + "/items/" + item.getId() + "/deactivate",
                "/office/ships/" + ship.getId() + "/library/" + libraryItem.getId() + "/retire",
                "/ship/library/" + libraryItem.getId() + "/retire");

        // The dialog is on every signed-in page, once, with the script; the bespoke retire modal is gone.
        String page = html(get("/office/ships"));
        assertThat(page).containsOnlyOnce("<dialog id=\"confirmDialog\"")
                .containsPattern("<script src=\"/js/confirm(-[0-9a-f]+)?\\.js\"></script>");
        assertThat(html(get("/office/ships/" + ship.getId() + "/library/" + libraryItem.getId() + "/edit")))
                .doesNotContain("retireModal");

        // The texts name the record and give the consequence.
        assertThat(html(get("/office/ships"))).contains(
                "data-confirm-title=\"Deactivate MV Demo?\"",
                "data-confirm-body=\"No new crew can be invited to MV Demo until it is activated again.\"",
                "data-confirm-label=\"Deactivate\"", "data-confirm-tone=\"neutral\"");
        assertThat(html(get(crewTab))).contains(
                "data-confirm-body=\"Jane Doe will not be able to sign in until activated again.\"",
                "href=\"/office/users/" + crew.getId() + "/deactivate?tab=crew\"",
                "href=\"/office/ships/" + ship.getId() + "/deactivate?tab=crew\"");
        assertThat(html(get("/office/users"))).contains(
                "data-confirm-title=\"Cancel the invitation to Olga Office?\"",
                "data-confirm-body=\"The link sent to olga@example.com stops working.\"",
                "data-confirm-label=\"Cancel invitation\"", "data-confirm-tone=\"danger\" data-confirm-dismiss=\"Keep invitation\"");
        assertThat(html(get("/office/crew"))).contains("data-confirm-title=\"Delete Fred Former?\"");

        // Activate needs no confirmation: an ordinary form.
        assertThat(html(get("/office/crew"))).containsPattern(
                "<form action=\"/office/users/" + former.getId() + "/activate\" method=\"post\">");
    }

    @Test
    void withoutJavaScriptTheTriggerOpensAPlainConfirmationPage() throws Exception {
        // Ship from the Crew tab: the page keeps the tab, Cancel goes back to it, confirming deactivates.
        String page = html(get("/office/ships/{id}/deactivate", ship.getId()).param("tab", "crew"));
        assertThat(page).contains("<span>Deactivate MV Demo?</span>",
                "No new crew can be invited to MV Demo until it is activated again.",
                "action=\"/office/ships/" + ship.getId() + "/deactivate\"",
                "<input type=\"hidden\" name=\"tab\" value=\"crew\">",
                "<button type=\"submit\" class=\"btn btn-primary\">Deactivate</button>",
                "href=\"/office/ships/" + ship.getId() + "/crew\">Cancel</a>",
                "<a href=\"/office/ships\">Vessels</a>");
        assertThat(shipRepository.findById(ship.getId()).orElseThrow().isActive()).isTrue();
        mockMvc.perform(post("/office/ships/{id}/deactivate", ship.getId()).param("tab", "crew").with(csrf()))
                .andExpect(redirectedUrl("/office/ships/" + ship.getId() + "/crew"));
        assertThat(shipRepository.findById(ship.getId()).orElseThrow().isActive()).isFalse();
        // An unknown tab is never a URL.
        assertThat(html(get("/office/ships/{id}/deactivate", ship.getId()).param("tab", "//evil.example.com")))
                .contains("href=\"/office/ships\">Cancel</a>").doesNotContain("evil");

        // Danger: the confirm button is the red outline one.
        assertThat(html(get("/office/users/{id}/delete", former.getId()))).contains("Delete Fred Former?",
                "Fred Former (former@example.com) will be deleted for good and cannot be restored.",
                "<button type=\"submit\" class=\"btn btn-outline-danger\">Delete</button>",
                "href=\"/office/crew\">Cancel</a>");
        assertThat(html(get("/office/users/{id}/deactivate", crew.getId()))).contains("Deactivate Jane Doe?",
                "Jane Doe will not be able to sign in until activated again.");
        assertThat(html(get("/office/invitations/{id}/cancel", invitation.getId()))).contains(
                "Cancel the invitation to Olga Office?", "The link sent to olga@example.com stops working.",
                ">Cancel invitation</button>", "href=\"/office/users\">Keep invitation</a>");
        assertThat(html(get("/office/crew/{id}/certificates/{cid}/delete", crew.getId(), certificate.getId())))
                .contains("Delete STCW Basic Safety?",
                        "The certificate STCW Basic Safety of Jane Doe will be deleted for good and cannot be restored.",
                        "href=\"/office/crew/" + crew.getId() + "/edit\">Cancel</a>");
        assertThat(html(get("/office/ships/{id}/items/{itemId}/deactivate", ship.getId(), item.getId())))
                .contains("Deactivate Basmati rice?",
                        "The crew of MV Demo cannot request Basmati rice until it is activated again.");
        assertThat(html(get("/office/ships/{id}/library/{itemId}/retire", ship.getId(), libraryItem.getId())))
                .contains("Retire Galley rules?", "The crew will no longer see Galley rules, and it cannot be brought back.");
        assertThat(html(get("/ship/library/{id}/retire", libraryItem.getId()).with(user(new CurrentUser(master)))))
                .contains("Retire Galley rules?", "href=\"/ship/library/" + libraryItem.getId() + "\">Cancel</a>");
        // The same rule as the action: only the Master may retire.
        mockMvc.perform(get("/ship/library/{id}/retire", libraryItem.getId()).with(user(new CurrentUser(crew))))
                .andExpect(status().isForbidden());

        // Office screens are also Greek.
        assertThat(html(get("/office/ships/{id}/deactivate", ship.getId()).param("lang", "el")))
                .contains("Απενεργοποίηση του MV Demo;");

        // An invitation that is no longer pending: back to the list with a message.
        mockMvc.perform(post("/office/invitations/{id}/cancel", invitation.getId()).with(csrf()));
        mockMvc.perform(get("/office/invitations/{id}/cancel", invitation.getId()))
                .andExpect(redirectedUrl("/office/users"));
    }

    // Forms posting to a destructive action on the page: each opts in to the dialog, has no submit button, and its
    // trigger links to the action's own URL (the confirmation page). Returns the actions.
    private List<String> confirmForms(MockHttpServletRequestBuilder request) throws Exception {
        String html = html(request);
        List<String> actions = new ArrayList<>();
        Matcher form = FORM.matcher(html);
        while (form.find()) {
            Matcher action = ACTION.matcher(form.group(1));
            if (!action.find()) {
                continue;
            }
            String url = action.group(1);
            assertThat(form.group(1)).as(url).contains("class=\"confirm-form\"", "data-confirm-title=\"",
                    "data-confirm-body=\"", "data-confirm-label=\"").containsPattern("data-confirm-tone=\"(danger|neutral)\"");
            assertThat(form.group(2)).as(url).doesNotContain("<button", "type=\"submit\"")
                    .containsPattern("href=\"" + Pattern.quote(url) + "(\\?tab=\\w+)?\"\\s+data-confirm-open");
            actions.add(url);
        }
        return actions;
    }

    private String html(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
