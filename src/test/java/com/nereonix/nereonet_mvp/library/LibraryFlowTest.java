package com.nereonix.nereonet_mvp.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.library.LibraryCategory;
import com.nereonix.nereonet_mvp.core.library.LibraryItem;
import com.nereonix.nereonet_mvp.core.library.LibraryItemRepository;
import com.nereonix.nereonet_mvp.core.library.LibraryItemVersion;
import com.nereonix.nereonet_mvp.core.library.LibraryItemVersionRepository;
import com.nereonix.nereonet_mvp.core.notification.Notification;
import com.nereonix.nereonet_mvp.core.notification.NotificationRepository;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class LibraryFlowTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ShipRepository shipRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    LibraryItemRepository itemRepository;

    @Autowired
    LibraryItemVersionRepository versionRepository;

    @Autowired
    NotificationRepository notificationRepository;

    @Test
    void crewMemberSeesTheLibraryOfTheirShip() throws Exception {
        Ship ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);

        User crew = new User();
        crew.setEmail("crew@example.com");
        crew.setPasswordHash("not-used");
        crew.setFullName("Crew Member");
        crew.setRole(Role.CREW_MEMBER);
        crew.setShip(ship);
        crew.setEnabled(true);
        crew.setCreatedAt(Instant.now());
        userRepository.save(crew);

        LibraryItem item = new LibraryItem();
        item.setShip(ship);
        item.setTitle("Galley hygiene rules");
        item.setCategory(LibraryCategory.SHIP_RULES);
        item.setCreatedAt(Instant.now());
        itemRepository.save(item);
        LibraryItemVersion version = new LibraryItemVersion();
        version.setItem(item);
        version.setVersionLabel("2026-10");
        version.setText("Wash your hands.");
        version.setPublishedAt(Instant.now());
        item.setCurrentVersion(versionRepository.save(version));
        itemRepository.flush();

        mockMvc.perform(get("/ship/library").with(user(new CurrentUser(crew))))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Galley hygiene rules")))
                .andExpect(content().string(Matchers.containsString("Version 2026-10")));
    }

    @Test
    void masterAddsRequiredTextItemAndCrewIsNotified() throws Exception {
        Ship ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);
        User master = shipUser(ship, "master@example.com", Role.MASTER);
        User crew = shipUser(ship, "crew@example.com", Role.CREW_MEMBER);

        mockMvc.perform(post("/ship/library/new").with(user(new CurrentUser(master))).with(csrf())
                        .param("title", "Fire drill procedure")
                        .param("category", "SHIP_RULES")
                        .param("versionLabel", "1")
                        .param("required", "true")
                        .param("text", "Go to your muster station."))
                .andExpect(redirectedUrl("/ship/library"));

        List<LibraryItem> items = itemRepository.findAllByShipAndRetiredFalseAndCurrentVersionIsNotNullOrderByTitleAsc(ship);
        assertThat(items).hasSize(1);
        LibraryItem item = items.get(0);
        assertThat(item.isRequired()).isTrue();
        assertThat(versionRepository.findAll()).singleElement()
                .satisfies(version -> {
                    assertThat(version.getItem().getId()).isEqualTo(item.getId());
                    assertThat(version.getText()).isEqualTo("Go to your muster station.");
                });
        assertThat(item.getCurrentVersion().getVersionLabel()).isEqualTo("1");

        assertThat(notificationRepository.countByUserEmailAndReadAtIsNull(crew.getEmail())).isEqualTo(1);
        Notification notification = notificationRepository.findAllByUserEmailOrderByCreatedAtDescIdDesc(crew.getEmail()).get(0);
        assertThat(notification.getText()).isEqualTo("Library · New document to read");
        assertThat(notification.getLink()).isEqualTo("/ship/library/" + item.getId());
    }

    @Test
    void crewMemberAcknowledgesRequiredItem() throws Exception {
        Ship ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);
        User crew = shipUser(ship, "crew@example.com", Role.CREW_MEMBER);

        LibraryItem item = new LibraryItem();
        item.setShip(ship);
        item.setTitle("Drug and alcohol policy");
        item.setCategory(LibraryCategory.COMPANY_POLICIES);
        item.setRequired(true);
        item.setCreatedAt(Instant.now());
        itemRepository.save(item);
        LibraryItemVersion version = new LibraryItemVersion();
        version.setItem(item);
        version.setVersionLabel("2026-10");
        version.setText("No alcohol on board.");
        version.setPublishedAt(Instant.now());
        item.setCurrentVersion(versionRepository.save(version));
        itemRepository.flush();

        mockMvc.perform(get("/ship/library/" + item.getId()).with(user(new CurrentUser(crew))))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("I have read this")));

        mockMvc.perform(post("/ship/library/" + item.getId() + "/acknowledge")
                        .with(user(new CurrentUser(crew))).with(csrf()))
                .andExpect(redirectedUrl("/ship/library/" + item.getId()));

        mockMvc.perform(get("/ship/library/" + item.getId()).with(user(new CurrentUser(crew))))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Acknowledged · Version 2026-10")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("I have read this"))));

        mockMvc.perform(get("/ship/library").with(user(new CurrentUser(crew))))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Required · acknowledged")));
    }

    @Test
    void masterPublishesNewVersionAndCrewMustAcknowledgeAgain() throws Exception {
        Ship ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);
        User master = shipUser(ship, "master@example.com", Role.MASTER);
        User crew = shipUser(ship, "crew@example.com", Role.CREW_MEMBER);

        LibraryItem item = new LibraryItem();
        item.setShip(ship);
        item.setTitle("Drug and alcohol policy");
        item.setCategory(LibraryCategory.COMPANY_POLICIES);
        item.setRequired(true);
        item.setCreatedAt(Instant.now());
        itemRepository.save(item);
        LibraryItemVersion version = new LibraryItemVersion();
        version.setItem(item);
        version.setVersionLabel("1");
        version.setText("No alcohol on board.");
        version.setPublishedAt(Instant.now());
        item.setCurrentVersion(versionRepository.save(version));
        itemRepository.flush();

        mockMvc.perform(post("/ship/library/" + item.getId() + "/acknowledge")
                        .with(user(new CurrentUser(crew))).with(csrf()))
                .andExpect(redirectedUrl("/ship/library/" + item.getId()));
        mockMvc.perform(get("/ship/library").with(user(new CurrentUser(crew))))
                .andExpect(content().string(Matchers.containsString("Required · acknowledged")));

        mockMvc.perform(post("/ship/library/" + item.getId() + "/edit").with(user(new CurrentUser(master))).with(csrf())
                        .param("versionLabel", "2")
                        .param("text", "No alcohol or drugs on board."))
                .andExpect(redirectedUrl("/ship/library/" + item.getId()));

        mockMvc.perform(get("/ship/library/" + item.getId()).with(user(new CurrentUser(crew))))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Version 2")))
                .andExpect(content().string(Matchers.containsString("No alcohol or drugs on board.")))
                .andExpect(content().string(Matchers.containsString("I have read this")));
        mockMvc.perform(get("/ship/library").with(user(new CurrentUser(crew))))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Version 2")))
                .andExpect(content().string(Matchers.containsString("Required · to acknowledge")));
        assertThat(versionRepository.findAll()).hasSize(2);
    }

    @Test
    void masterRetiresItem() throws Exception {
        Ship ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);
        User master = shipUser(ship, "master@example.com", Role.MASTER);

        LibraryItem item = new LibraryItem();
        item.setShip(ship);
        item.setTitle("Old garbage plan");
        item.setCategory(LibraryCategory.SHIP_PLANS_AND_MAPS);
        item.setCreatedAt(Instant.now());
        itemRepository.save(item);
        LibraryItemVersion version = new LibraryItemVersion();
        version.setItem(item);
        version.setVersionLabel("1");
        version.setText("Old plan.");
        version.setPublishedAt(Instant.now());
        item.setCurrentVersion(versionRepository.save(version));
        itemRepository.flush();

        mockMvc.perform(post("/ship/library/" + item.getId() + "/retire")
                        .with(user(new CurrentUser(master))).with(csrf()))
                .andExpect(redirectedUrl("/ship/library"));

        mockMvc.perform(get("/ship/library").with(user(new CurrentUser(master))))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.not(Matchers.containsString("Old garbage plan"))));
        mockMvc.perform(get("/ship/library/" + item.getId()).with(user(new CurrentUser(master))))
                .andExpect(status().isNotFound());
    }

    @Test
    void masterEditsParticularsAndCrewSeesThem() throws Exception {
        Ship ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);
        User master = shipUser(ship, "master@example.com", Role.MASTER);
        master.setFullName("Nikos Papadopoulos");
        User crew = shipUser(ship, "crew@example.com", Role.CREW_MEMBER);

        mockMvc.perform(post("/ship/library/particulars").with(user(new CurrentUser(master))).with(csrf())
                        .param("text", "Length overall: 229 m\r\nMain engine: MAN B&W 6S60MC"))
                .andExpect(redirectedUrl("/ship/library"));

        mockMvc.perform(get("/ship/library").with(user(new CurrentUser(crew))))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Length overall: 229 m\nMain engine: MAN B&amp;W 6S60MC")))
                .andExpect(content().string(Matchers.containsString("Edited by Nikos Papadopoulos on")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("Edit ship particulars"))));
    }

    @Test
    void officeSeesWhoAcknowledgedRequiredItem() throws Exception {
        Ship ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);
        User reader = shipUser(ship, "reader@example.com", Role.CREW_MEMBER);
        shipUser(ship, "late@example.com", Role.CREW_MEMBER);
        User office = userRepository.findByEmail("office@example.com").orElseThrow(); // the startup office user

        LibraryItem item = new LibraryItem();
        item.setShip(ship);
        item.setTitle("Drug and alcohol policy");
        item.setCategory(LibraryCategory.COMPANY_POLICIES);
        item.setRequired(true);
        item.setCreatedAt(Instant.now());
        itemRepository.save(item);
        LibraryItemVersion version = new LibraryItemVersion();
        version.setItem(item);
        version.setVersionLabel("1");
        version.setText("No alcohol on board.");
        version.setPublishedAt(Instant.now());
        item.setCurrentVersion(versionRepository.save(version));
        itemRepository.flush();

        mockMvc.perform(post("/ship/library/" + item.getId() + "/acknowledge")
                        .with(user(new CurrentUser(reader))).with(csrf()))
                .andExpect(redirectedUrl("/ship/library/" + item.getId()));

        mockMvc.perform(get("/office/ships/" + ship.getId() + "/library").with(user(new CurrentUser(office))))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("1 of 2 acknowledged")));

        mockMvc.perform(get("/office/ships/" + ship.getId() + "/library/" + item.getId() + "/acknowledgements")
                        .with(user(new CurrentUser(office))))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.stringContainsInOrder(
                        "Acknowledged (1)", "reader@example.com", "Not yet acknowledged (1)", "late@example.com")));
    }

    // P16, FR-SIN-002: next to the file input of the add and new version forms (crew and office), named by the input.
    @Test
    @WithUserDetails("office@example.com") // seeded by BootstrapData
    void fileInputsWarnAgainstSecuritySensitiveMaterial() throws Exception {
        Ship ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);
        User master = shipUser(ship, "master@example.com", Role.MASTER);
        LibraryItem item = new LibraryItem();
        item.setShip(ship);
        item.setTitle("Garbage plan");
        item.setCategory(LibraryCategory.SHIP_PLANS_AND_MAPS);
        item.setCreatedAt(Instant.now());
        itemRepository.save(item);
        LibraryItemVersion version = new LibraryItemVersion();
        version.setItem(item);
        version.setVersionLabel("1");
        version.setText("Sort it.");
        version.setPublishedAt(Instant.now());
        item.setCurrentVersion(versionRepository.save(version));
        itemRepository.flush();

        String notice = "Do not upload security-sensitive material such as the Ship Security Plan.";
        List<String> pages = List.of("/ship/library/new", "/ship/library/" + item.getId() + "/edit",
                "/office/ships/" + ship.getId() + "/library/new",
                "/office/ships/" + ship.getId() + "/library/" + item.getId() + "/edit");
        for (String page : pages) {
            var request = get(page);
            if (page.startsWith("/ship/")) {
                request = request.with(user(new CurrentUser(master)));
            }
            String html = mockMvc.perform(request).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(html).as(page)
                    .contains("aria-describedby=\"fileHint fileSecurityNote fileInfo\"")
                    .containsPattern("(?s)<p class=\"info-note\" id=\"fileSecurityNote\">\\s*<svg.*?</svg>\\s*<span>"
                            + notice + "</span>");
        }
        assertThat(mockMvc.perform(get("/office/ships/{id}/library/new", ship.getId()).param("lang", "el"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("Μην ανεβάζετε υλικό ευαίσθητο για την ασφάλεια του πλοίου");
    }

    private User shipUser(Ship ship, String email, Role role) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("not-used");
        user.setFullName(email);
        user.setRole(role);
        user.setShip(ship);
        user.setEnabled(true);
        user.setCreatedAt(Instant.now());
        return userRepository.save(user);
    }
}
