package com.nereonix.nereonet_mvp.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserService;

/**
 * P16: the English / Greek switch changes only the language. Its links keep the page and every query parameter
 * (ship and status filters, page number, token), and never carry what a form posted.
 * P29: it reads "EN | ΕΛ" and sits at the right of the top bar on every office page (never in the sidebar), at the
 * top right of the form side on the signed-out pages, and nowhere on crew pages. The current language is marked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class LanguageSwitchFlowTest {

    private static final Pattern TOP_BAR = Pattern.compile("(?s)<header class=\"topbar topbar-office\">.*?</header>");
    private static final Pattern SWITCH = Pattern.compile("(?s)<div class=\"lang-switch\" role=\"group\" aria-label=\"([^\"]+)\">.*?</div>");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ShipRepository shipRepository;

    @Autowired
    UserService userService;

    Ship demo;
    User anna;

    @BeforeEach
    void setUp() {
        demo = ship("9074729", "MV Demo");
        Ship other = ship("9176187", "MV Other");
        anna = userService.createUser("anna@example.com", "Anna Demo", "secret123", Role.CREW_MEMBER, demo, null);
        userService.createUser("olga@example.com", "Olga Other", "secret123", Role.CREW_MEMBER, other, null);
    }

    @Test
    void switchingLanguageOnTheFilteredCrewListStaysOnIt() throws Exception {
        // A URL, not .param(): MockMvc does not put .param() values in the query string, as a browser does.
        String english = html(get(URI.create("/office/crew?shipId=" + demo.getId())));
        assertThat(link(english, "Ελληνικά")).isEqualTo("?lang=el&shipId=" + demo.getId());
        assertThat(link(english, "English")).isEqualTo("?lang=en&shipId=" + demo.getId());

        // Following the link (relative: the browser keeps /office/crew): Greek, and still only MV Demo's crew.
        mockMvc.perform(get(URI.create("/office/crew" + link(english, "Ελληνικά"))))
                .andExpect(status().isOk())
                .andExpect(model().attribute("shipId", demo.getId()));
        String greek = html(get(URI.create("/office/crew" + link(english, "Ελληνικά"))));
        assertThat(greek).contains("<html lang=\"el\">", "Anna Demo").doesNotContain("Olga Other");
        // From Greek back to English: the same filtered list, lang not repeated.
        assertThat(link(greek, "English")).isEqualTo("?lang=en&shipId=" + demo.getId());
    }

    @Test
    void everyQueryParameterIsKeptAsItIsAndOnlyLangChanges() throws Exception {
        String html = html(get(URI.create("/office/crew?shipId=" + demo.getId()
                + "&status=expired&page=2&lang=el&q=a%20b%26c&flag")));
        String kept = "&shipId=" + demo.getId() + "&status=expired&page=2&q=a%20b%26c&flag";
        assertThat(link(html, "English")).isEqualTo("?lang=en" + kept);
        assertThat(link(html, "Ελληνικά")).isEqualTo("?lang=el" + kept);
    }

    @Test
    void aPageWithoutParametersGetsOnlyTheLanguage() throws Exception {
        String html = html(get("/office/ships"));
        assertThat(link(html, "English")).isEqualTo("?lang=en");
        assertThat(link(html, "Ελληνικά")).isEqualTo("?lang=el");
    }

    // A page shown in answer to a POST (the announcement review): the posted fields stay out of the links.
    @Test
    void whatAFormPostedNeverEndsUpInTheLinks() throws Exception {
        String html = html(post("/office/announcements/review").param("target", "ships").param("shipIds", demo.getId().toString())
                .param("title", "Secret drill").param("body", "Friday").with(csrf()));
        assertThat(link(html, "English")).isEqualTo("?lang=en");
        assertThat(link(html, "Ελληνικά")).isEqualTo("?lang=el");
    }

    // P29: "EN | ΕΛ" at the right of the top bar, after the page name, on every office page; one switch per page.
    @Test
    void everyOfficePageHasTheSwitchAtTheRightOfTheTopBar() throws Exception {
        List<String> pages = List.of("/office", "/office/ships", "/office/ships/" + demo.getId(),
                "/office/ships/" + demo.getId() + "/items", "/office/ships/" + demo.getId() + "/items/new",
                "/office/crew", "/office/crew/" + anna.getId() + "/edit", "/office/crew/import", "/office/certificates",
                "/office/announcements", "/office/announcements/new", "/office/orders", "/office/users",
                "/office/audit", "/notifications");
        for (String page : pages) {
            String html = html(get(page));
            Matcher topBar = TOP_BAR.matcher(html);
            assertThat(topBar.find()).as("top bar on " + page).isTrue();
            assertThat(topBar.group()).as("switch in the top bar on " + page)
                    .containsSubsequence("class=\"topbar-title fw-bold\"", "class=\"topbar-lang\"",
                            "aria-label=\"Language\"", ">EN</a>", "class=\"lang-switch-divider\" aria-hidden=\"true\"",
                            ">ΕΛ</a>", "id=\"notificationBell\"");
            assertThat(SWITCH.matcher(html).results().count()).as("one switch on " + page).isEqualTo(1);
            assertThat(sidebar(html)).as("no switch in the sidebar on " + page).doesNotContain("lang-switch", "lang=el");
        }
    }

    @Test
    void theCurrentLanguageIsBoldWithAriaCurrentAndEachLinkHasItsLanguageAndName() throws Exception {
        String english = html(get("/office/ships"));
        assertThat(anchor(english, "English")).contains("class=\"lang-switch-link is-current\"", "lang=\"en\"",
                "hreflang=\"en\"", "aria-current=\"true\"");
        assertThat(anchor(english, "Ελληνικά")).contains("class=\"lang-switch-link\"", "lang=\"el\"", "hreflang=\"el\"")
                .doesNotContain("aria-current", "is-current");

        String greek = html(get("/office/ships").param("lang", "el"));
        assertThat(greek).contains("aria-label=\"Γλώσσα\"");
        assertThat(anchor(greek, "Ελληνικά")).contains("is-current", "aria-current=\"true\"");
        assertThat(anchor(greek, "English")).doesNotContain("aria-current", "is-current");
    }

    @Test
    void signedOutPagesHaveItTopRightOfTheFormAndCrewPagesHaveNone() throws Exception {
        for (String page : List.of("/login", "/password/forgot")) {
            String html = html(get(page).with(anonymous()));
            assertThat(html).as(page).containsPattern("(?s)<div class=\"auth-panel\">\\s*<div class=\"auth-lang\">\\s*"
                    + "<div class=\"lang-switch\" role=\"group\" aria-label=\"Language\">");
            assertThat(anchor(html, "English")).as(page).contains("aria-current=\"true\"");
        }
        String crew = html(get("/ship").with(user(new CurrentUser(anna))));
        assertThat(crew).doesNotContain("lang-switch", "lang=el", "topbar-office");
    }

    /** The href of the switch's link named label ("English", "Ελληνικά"). */
    private String link(String html, String label) {
        Matcher href = Pattern.compile("href=\"([^\"]*)\"").matcher(anchor(html, label));
        assertThat(href.find()).as("href of " + label).isTrue();
        return href.group(1).replace("&amp;", "&");
    }

    /** The opening tag of the switch's link named label. */
    private String anchor(String html, String label) {
        Matcher matcher = Pattern.compile("<a class=\"lang-switch-link[^>]*aria-label=\"" + label + "\"[^>]*>").matcher(html);
        assertThat(matcher.find()).as("link " + label).isTrue();
        return matcher.group();
    }

    private String sidebar(String html) {
        Matcher aside = Pattern.compile("(?s)<aside class=\"sidebar .*?</aside>").matcher(html);
        assertThat(aside.find()).as("sidebar").isTrue();
        return aside.group();
    }

    private String html(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private Ship ship(String imoNumber, String name) {
        Ship ship = new Ship();
        ship.setImoNumber(imoNumber);
        ship.setName(name);
        ship.setCreatedAt(Instant.now());
        return shipRepository.save(ship);
    }
}
