package com.nereonix.nereonet_mvp.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.certificate.CertificateRepository;
import com.nereonix.nereonet_mvp.core.mail.MailService;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserService;

/**
 * P03 button hierarchy on the rendered office screens, with rows in every table: at most one filled primary button
 * outside modals, and danger buttons only in a row's overflow menu or in a modal, never directly in a row.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class ButtonHierarchyFlowTest {

    private static final Pattern ROW_MENU = Pattern.compile("(?s)<details class=\"row-menu dropdown\">.*?</details>");
    private static final Pattern DANGER = Pattern.compile("btn-(outline-)?danger|dropdown-item-danger");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ShipRepository shipRepository;

    @Autowired
    UserService userService;

    @Autowired
    CertificateRepository certificateRepository;

    @MockitoBean
    MailService mailService;

    @Test
    void officeScreensHaveOnePrimaryAndNoRedButtonInARow() throws Exception {
        Ship ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);
        User crew = userService.createUser("crew@example.com", "Crew Member", "secret123", Role.CREW_MEMBER, ship,
                Rank.DECK_CADET);
        Certificate certificate = new Certificate();
        certificate.setUser(crew);
        certificate.setType("STCW Basic Safety");
        certificate.setExpiresOn(LocalDate.now(ZoneOffset.UTC).plusDays(400));
        certificate.setCreatedAt(Instant.now());
        certificateRepository.save(certificate);
        mockMvc.perform(post("/office/ships/{id}/items/new", ship.getId())
                .param("name", "Basmati rice").param("unit", "kg").with(csrf()));
        mockMvc.perform(post("/office/invitations")
                .param("fullName", "New Office").param("email", "new.office@example.com").with(csrf()));

        List<String> pages = List.of("/office/ships", "/office/crew", "/office/users", "/office/invitations",
                "/office/certificates", "/office/announcements", "/office/requests", "/office/audit",
                "/office/ships/" + ship.getId() + "/items", "/office/ships/" + ship.getId() + "/items/import",
                "/office/ships/" + ship.getId() + "/library", "/office/crew/" + crew.getId() + "/edit",
                "/office/crew/import");
        for (String page : pages) {
            String html = withoutModals(mockMvc.perform(get(page)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            assertThat(count(html, "btn btn-primary")).as("filled primary buttons on " + page).isLessThanOrEqualTo(1);
            assertThat(DANGER.matcher(ROW_MENU.matcher(html).replaceAll("")).find())
                    .as("danger button outside an overflow menu on " + page).isFalse();
        }

        // The overflow menus name their row and hold the state changes, as ordinary forms.
        String ships = mockMvc.perform(get("/office/ships")).andReturn().getResponse().getContentAsString();
        assertThat(ROW_MENU.matcher(ships).results().map(r -> r.group()).toList())
                .anySatisfy(menu -> assertThat(menu).contains("More actions: MV Demo",
                        "/office/ships/" + ship.getId() + "/deactivate", "dropdown-item-danger"));
        String crewList = mockMvc.perform(get("/office/crew")).andReturn().getResponse().getContentAsString();
        assertThat(ROW_MENU.matcher(crewList).results().map(r -> r.group()).toList())
                .anySatisfy(menu -> assertThat(menu).contains("More actions: Crew Member",
                        "/office/users/" + crew.getId() + "/deactivate"));
        String crewEdit = mockMvc.perform(get("/office/crew/" + crew.getId() + "/edit"))
                .andReturn().getResponse().getContentAsString();
        assertThat(ROW_MENU.matcher(crewEdit).results().map(r -> r.group()).toList())
                .anySatisfy(menu -> assertThat(menu).contains("More actions: STCW Basic Safety",
                        "/certificates/" + certificate.getId() + "/delete"));
    }

    /** The page without its Bootstrap modals (div class="modal ..." up to its closing div). */
    private static String withoutModals(String html) {
        StringBuilder out = new StringBuilder(html);
        int start;
        while ((start = out.indexOf("<div class=\"modal ")) >= 0) {
            Matcher tags = Pattern.compile("<div\\b|</div>").matcher(out);
            int depth = 0;
            int end = -1;
            tags.region(start, out.length());
            while (tags.find()) {
                depth += tags.group().equals("</div>") ? -1 : 1;
                if (depth == 0) {
                    end = tags.end();
                    break;
                }
            }
            assertThat(end).as("modal closed").isPositive();
            out.delete(start, end);
        }
        return out.toString();
    }

    private static int count(String html, String needle) {
        List<Integer> found = new ArrayList<>();
        for (int i = html.indexOf(needle); i >= 0; i = html.indexOf(needle, i + 1)) {
            found.add(i);
        }
        return found.size();
    }
}
