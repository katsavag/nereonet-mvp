package com.nereonix.nereonet_mvp.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.FlashMap;

import com.nereonix.nereonet_mvp.announcement.AnnouncementForm;
import com.nereonix.nereonet_mvp.core.form.FormFields;
import com.nereonix.nereonet_mvp.core.invitation.Invitation;
import com.nereonix.nereonet_mvp.core.invitation.InvitationRepository;
import com.nereonix.nereonet_mvp.core.invitation.InvitationTokenService;
import com.nereonix.nereonet_mvp.core.library.LibraryItemRepository;
import com.nereonix.nereonet_mvp.core.library.ParticularsForm;
import com.nereonix.nereonet_mvp.core.mail.MailService;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;
import com.nereonix.nereonet_mvp.core.user.UserService;
import com.nereonix.nereonet_mvp.ship.EditShipForm;

/**
 * P05 form pattern on the rendered forms: error summary, character counters read from the form classes, "(optional)"
 * labels, read-only values as text, the same actions on every full-page form, file limits, and the invitation
 * success message naming the address.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class FormsFlowTest {

    private static final Pattern CONTROL = Pattern.compile("<(input|select|textarea)\\b[^>]*>");
    private static final Pattern FORM_ACTIONS = Pattern.compile("(?s)<div class=\"form-actions\">(.*?)</div>");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ShipRepository shipRepository;

    @Autowired
    UserService userService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    InvitationRepository invitationRepository;

    @Autowired
    InvitationTokenService tokenService;

    @Autowired
    LibraryItemRepository libraryItemRepository;

    @Autowired
    FormFields formFields;

    @MockitoBean
    MailService mailService;

    @Test
    void fullPageFormWithTwoErrorsShowsSummaryLinkingToEachFieldInOrder() throws Exception {
        User crew = crewMember(ship());

        String html = mockMvc.perform(post("/office/crew/{id}/certificates/new", crew.getId())
                        .param("type", "").param("number", "x".repeat(101)).param("expiresOn", "").with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        String summary = summary(html);
        assertThat(summary).contains("tabindex=\"-1\"", "This form has 3 errors");
        // In the order of the fields on the page, each linking to its field.
        assertThat(summary.indexOf("href=\"#type\"")).isPositive()
                .isLessThan(summary.indexOf("href=\"#number\""));
        assertThat(summary.indexOf("href=\"#number\"")).isLessThan(summary.indexOf("href=\"#expiresOn\""));
        // The messages also stay under the fields.
        assertThat(html).contains("<div class=\"invalid-feedback\">Enter the certificate type.</div>");
        // Script that moves focus to the summary.
        assertThat(html).contains("/js/forms");
    }

    @Test
    void modalFormWithTwoErrorsShowsSummaryWithCheckErrorsLinkedToTheirField() throws Exception {
        String html = mockMvc.perform(post("/office/ships/new")
                        .param("imoNumber", "123").param("name", "").param("flag", "Atlantis").with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        String summary = summary(html);
        assertThat(summary).contains("This form has 3 errors", "href=\"#imoNumber\"", "href=\"#name\"",
                "href=\"#flag\"");
        assertThat(summary).doesNotContain("imoNumberValid", "flagValid");
    }

    @Test
    void formWithOneErrorHasNoSummary() throws Exception {
        String html = mockMvc.perform(post("/office/announcements/new")
                        .param("target", "fleet").param("title", "").param("body", "Drill on Friday").with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Enter a title.").doesNotContain("error-summary\"");
    }

    @Test
    void countersShowTheLimitFromTheFormClassWithoutJavaScript() throws Exception {
        assertThat(formFields.maxLength(new AnnouncementForm(), "body")).isEqualTo(5000);
        assertThat(formFields.maxLength(new ParticularsForm(), "text")).isEqualTo(20_000);
        assertThat(formFields.maxLength(new EditShipForm(), "flag")).isNull();

        String html = page("/office/announcements/new");
        assertThat(html).contains("data-counter-for=\"body\"", "data-max=\"5000\"", "Up to 5,000 characters",
                "data-counter-for=\"title\"", "data-max=\"200\"", "aria-describedby=\"bodyHint bodyCounter\"",
                "data-template=\"{count} / {max}\"", "data-over-template=\"{count} / {max} · {over} too many\"");

        String greek = mockMvc.perform(get("/office/announcements/new").param("lang", "el"))
                .andReturn().getResponse().getContentAsString();
        assertThat(greek).contains("Έως 5.000 χαρακτήρες");

        Ship ship = ship();
        assertThat(page("/office/ships/" + ship.getId() + "/library/particulars"))
                .contains("data-counter-for=\"text\"", "data-max=\"20000\"", "Up to 20,000 characters");
        assertThat(page("/office/ships/" + ship.getId() + "/items/new"))
                .contains("data-counter-for=\"unit\"", "data-max=\"20\"");
    }

    @Test
    void optionalFieldsAreMarkedAndRequiredFieldsHaveNoAsterisk() throws Exception {
        Ship ship = ship();
        User crew = crewMember(ship);
        for (String page : List.of("/office/crew/" + crew.getId() + "/edit", "/office/ships",
                "/office/crew/" + crew.getId() + "/certificates/new", "/office/ships/" + ship.getId() + "/items/new",
                "/office/ships/" + ship.getId() + "/invitations/new")) {
            String html = page(page);
            assertThat(html).as(page).contains("<span class=\"form-label-optional\">(optional)</span>");
            assertThat(html).as(page).doesNotContainPattern("<label[^>]*>[^<]*\\*");
        }
        assertThat(page("/office/crew/" + crew.getId() + "/certificates/new")).doesNotContain(">Optional.<");
    }

    @Test
    void valuesThatCannotChangeAreTextNotInputs() throws Exception {
        Ship ship = ship();
        User crew = crewMember(ship);
        mockMvc.perform(post("/office/ships/{id}/library/new", ship.getId()).with(csrf())
                .param("title", "Galley rules").param("category", "SHIP_RULES").param("versionLabel", "1")
                .param("required", "false").param("text", "Wash your hands."));
        Long itemId = libraryItemRepository.findAll().getFirst().getId();

        Map<String, List<String>> textValues = Map.of(
                "/office/crew/" + crew.getId() + "/edit", List.of("crew@example.com"),
                "/office/ships/" + ship.getId() + "/library/" + itemId + "/edit", List.of("Galley rules", "<dd>1</dd>"),
                "/office/ships/" + ship.getId() + "/library/particulars", List.of("IMO 9074729"),
                "/register?token=" + invitationToken(ship), List.of("new.crew@example.com", "New Crew"));
        for (var entry : textValues.entrySet()) {
            String html = entry.getKey().startsWith("/register")
                    ? mockMvc.perform(get(entry.getKey()).with(anonymous()))
                            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()
                    : page(entry.getKey());
            assertThat(html).as(entry.getKey()).contains("<dl class=\"static-fields\">");
            int start = html.indexOf("<dl class=\"static-fields\">");
            String dl = html.substring(start, html.indexOf("</dl>", start));
            assertThat(dl).as(entry.getKey()).contains(entry.getValue());
            Matcher controls = CONTROL.matcher(html);
            while (controls.find()) {
                assertThat(controls.group()).as("control on " + entry.getKey())
                        .doesNotContain("readonly", "disabled");
            }
        }
    }

    @Test
    void fullPageFormsHavePrimaryThenCancelBottomLeft() throws Exception {
        Ship ship = ship();
        User crew = crewMember(ship);
        mockMvc.perform(post("/office/ships/{id}/library/new", ship.getId()).with(csrf())
                .param("title", "Galley rules").param("category", "SHIP_RULES").param("versionLabel", "1")
                .param("required", "false").param("text", "Wash your hands."));
        Long itemId = libraryItemRepository.findAll().getFirst().getId();
        String library = "/office/ships/" + ship.getId() + "/library";

        Map<String, String> primaryByPage = Map.ofEntries(
                Map.entry("/office/crew/" + crew.getId() + "/edit", "Save"),
                Map.entry("/office/crew/" + crew.getId() + "/certificates/new", "Save"),
                Map.entry("/office/ships/" + ship.getId() + "/items/new", "Save"),
                Map.entry("/office/announcements/new", "Send"),
                Map.entry("/office/ships/" + ship.getId() + "/invitations/new", "Send invitation"),
                Map.entry(library + "/new", "Add item"),
                Map.entry(library + "/" + itemId + "/edit", "Publish"),
                Map.entry(library + "/particulars", "Save"),
                Map.entry("/office/crew/import", "Upload and review"),
                Map.entry("/office/ships/" + ship.getId() + "/items/import", "Upload and review"));
        for (var entry : primaryByPage.entrySet()) {
            Matcher actions = FORM_ACTIONS.matcher(page(entry.getKey()));
            assertThat(actions.find()).as("form actions on " + entry.getKey()).isTrue();
            assertThat(actions.group(1).strip()).as(entry.getKey()).matches(
                    "(?s)<button type=\"submit\" class=\"btn btn-primary\">" + Pattern.quote(entry.getValue())
                            + "</button>\\s*<a class=\"btn btn-ghost\" href=\"[^\"]+\">Cancel</a>");
        }
    }

    @Test
    void modalFootersHaveCancelThenPrimary() throws Exception {
        for (String page : List.of("/office/ships", "/office/users", "/office/crew")) {
            Matcher footers = Pattern.compile("(?s)<div class=\"modal-footer\">(.*?)</div>").matcher(page(page));
            int found = 0;
            while (footers.find()) {
                found++;
                assertThat(footers.group(1).strip()).as(page).matches(
                        "(?s)<button type=\"button\" class=\"btn btn-ghost\" data-bs-dismiss=\"modal\">Cancel</button>"
                                + "\\s*<button type=\"submit\" class=\"btn btn-primary\">[^<]+</button>");
            }
            assertThat(found).as("modal footers on " + page).isPositive();
        }
    }

    @Test
    void invitationsHaveNoRepeatEmailAndTheSuccessMessageNamesTheAddress() throws Exception {
        assertThat(page("/office/users")).doesNotContain("confirmEmail", "Repeat email");
        Ship ship = ship();
        assertThat(page("/office/ships/" + ship.getId() + "/invitations/new")).doesNotContain("confirmEmail");

        MvcResult sent = mockMvc.perform(post("/office/invitations")
                        .param("fullName", "New Office").param("email", "New.Office@Example.com").with(csrf()))
                .andExpect(redirectedUrl("/office/users"))
                .andExpect(flash().attribute("successMessage", "invitation.sent"))
                .andReturn();
        FlashMap flashMap = sent.getFlashMap();
        String html = mockMvc.perform(get("/office/users").flashAttrs(flashMap))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Invitation sent to new.office@example.com.");

        MvcResult added = mockMvc.perform(post("/office/crew/new")
                        .param("fullName", "Jane Doe").param("email", "jane@example.com").param("role", "CREW_MEMBER")
                        .param("shipId", ship.getId().toString()).with(csrf()))
                .andExpect(redirectedUrl("/office/crew")).andReturn();
        assertThat(mockMvc.perform(get("/office/crew").flashAttrs(added.getFlashMap()))
                .andReturn().getResponse().getContentAsString())
                .contains("Invitation sent to jane@example.com.");
    }

    @Test
    void fileInputsShowTypesAndLimitBeforeAndTheChosenFileAfter() throws Exception {
        Ship ship = ship();
        String library = page("/office/ships/" + ship.getId() + "/library/new");
        assertThat(library).contains("PDF, DOCX or XLSX, at most 10 MB.", "data-max-size=\"10485760\"",
                "data-file-info=\"fileInfo\"", "<p id=\"fileInfo\" class=\"form-file-info\" aria-live=\"polite\" hidden>");
        for (String page : List.of("/office/crew/import", "/office/ships/" + ship.getId() + "/items/import")) {
            assertThat(page(page)).as(page).contains("XLSX or CSV, at most 10 MB.", "data-file-info=\"fileInfo\"");
        }
    }

    private String summary(String html) {
        Matcher summary = Pattern.compile("(?s)<div class=\"alert alert-danger error-summary\".*?</ul>").matcher(html);
        assertThat(summary.find()).as("error summary").isTrue();
        return summary.group();
    }

    private String page(String url) throws Exception {
        return mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private Ship ship() {
        Ship ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setCreatedAt(Instant.now());
        return shipRepository.save(ship);
    }

    private User crewMember(Ship ship) {
        return userService.createUser("crew@example.com", "Crew Member", "secret123", Role.CREW_MEMBER, ship,
                Rank.DECK_CADET);
    }

    private String invitationToken(Ship ship) {
        Invitation invitation = new Invitation();
        invitation.setEmail("new.crew@example.com");
        invitation.setFullName("New Crew");
        invitation.setRole(Role.CREW_MEMBER);
        invitation.setShip(ship);
        invitation.setTokenId(UUID.randomUUID());
        invitation.setStatus(Invitation.Status.PENDING);
        invitation.setExpiresAt(Instant.now().plus(Duration.ofDays(7)));
        invitation.setInvitedBy(userRepository.findByEmail("office@example.com").orElseThrow());
        invitation.setCreatedAt(Instant.now());
        invitationRepository.save(invitation);
        return tokenService.create(invitation);
    }
}
