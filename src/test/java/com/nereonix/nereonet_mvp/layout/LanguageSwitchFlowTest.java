package com.nereonix.nereonet_mvp.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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

import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.UserService;

/**
 * P16: the English / Greek switch changes only the language. Its links keep the page and every query parameter
 * (ship and status filters, page number, token), and never carry what a form posted.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class LanguageSwitchFlowTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ShipRepository shipRepository;

    @Autowired
    UserService userService;

    Ship demo;

    @BeforeEach
    void setUp() {
        demo = ship("9074729", "MV Demo");
        Ship other = ship("9176187", "MV Other");
        userService.createUser("anna@example.com", "Anna Demo", "secret123", Role.CREW_MEMBER, demo, null);
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
        String html = html(post("/office/announcements/review").param("target", demo.getId().toString())
                .param("title", "Secret drill").param("body", "Friday").with(csrf()));
        assertThat(link(html, "English")).isEqualTo("?lang=en");
        assertThat(link(html, "Ελληνικά")).isEqualTo("?lang=el");
    }

    private String link(String html, String label) {
        Matcher matcher = Pattern.compile("href=\"([^\"]*)\">" + label + "</a>").matcher(html);
        assertThat(matcher.find()).as("link " + label).isTrue();
        return matcher.group(1).replace("&amp;", "&");
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
