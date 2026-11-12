package com.nereonix.nereonet_mvp.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditRepository;
import com.nereonix.nereonet_mvp.core.library.LibraryCategory;
import com.nereonix.nereonet_mvp.core.library.LibraryItem;
import com.nereonix.nereonet_mvp.core.library.LibraryItemRepository;
import com.nereonix.nereonet_mvp.core.library.LibraryItemVersion;
import com.nereonix.nereonet_mvp.core.library.LibraryItemVersionRepository;
import com.nereonix.nereonet_mvp.core.library.LibraryService;
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

    @Autowired
    AuditRepository auditRepository;

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

    // P22: the office reads an item without entering an edit form; New version and Retire are actions on that view.
    @Test
    @WithUserDetails("office@example.com") // seeded by BootstrapData
    void officeReadsItemOnItsReadView() throws Exception {
        Ship ship = ship();
        User master = shipUser(ship, "master@example.com", Role.MASTER);
        master.setFullName("Nikos Papadopoulos");
        LibraryItem item = textItem(ship, "Drug and alcohol policy", true, "1", "No alcohol on board.");

        // The list: the title links to the read view, the row has the Required badge and the date published (UTC);
        // there is no edit link any more.
        String list = mockMvc.perform(get("/office/ships/{id}/library", ship.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String itemUrl = "/office/ships/" + ship.getId() + "/library/" + item.getId();
        assertThat(list)
                .contains("href=\"" + itemUrl + "\"")
                .contains(">Required<")
                .contains("0 of 1 acknowledged")
                .doesNotContain(itemUrl + "/edit")
                .doesNotContain("Edit this item");

        // A new version, published by the Master, so the history has two rows, newest first.
        mockMvc.perform(post("/ship/library/" + item.getId() + "/edit").with(user(new CurrentUser(master))).with(csrf())
                        .param("versionLabel", "2")
                        .param("text", "No alcohol or drugs on board."))
                .andExpect(redirectedUrl("/ship/library/" + item.getId()));

        String view = mockMvc.perform(get(itemUrl))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(view)
                .contains("No alcohol or drugs on board.")
                .contains("href=\"" + itemUrl + "/edit\"")
                .contains(">New version<")
                .contains("action=\"" + itemUrl + "/retire\"")
                .doesNotContain("I have read this")
                .doesNotContain("<textarea");
        assertThat(view).containsPattern("(?s)Version history.*Version 2.*Current.*by Nikos Papadopoulos.*Version 1");

        // New version: Cancel goes back to the read view, and so does Publish.
        assertThat(mockMvc.perform(get(itemUrl + "/edit")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("href=\"" + itemUrl + "\"")
                .doesNotContain(itemUrl + "/retire");
        mockMvc.perform(post(itemUrl + "/edit").with(csrf()).param("versionLabel", "3").param("text", "Zero tolerance."))
                .andExpect(redirectedUrl(itemUrl));
    }

    // P22: the version history is for the Master and the office; other crew do not see it.
    @Test
    void onlyTheMasterSeesTheVersionHistoryOnBoard() throws Exception {
        Ship ship = ship();
        User master = shipUser(ship, "master@example.com", Role.MASTER);
        User crew = shipUser(ship, "crew@example.com", Role.CREW_MEMBER);
        LibraryItem item = textItem(ship, "Galley rules", false, "1", "Wash your hands.");

        mockMvc.perform(get("/ship/library/" + item.getId()).with(user(new CurrentUser(master))))
                .andExpect(content().string(Matchers.containsString("Version history")))
                .andExpect(content().string(Matchers.containsString(">New version<")));
        mockMvc.perform(get("/ship/library/" + item.getId()).with(user(new CurrentUser(crew))))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.not(Matchers.containsString("Version history"))));
    }

    // P22: a PDF opens in the browser (inline, in a new tab) with Download as a second action; DOCX stays a download.
    @Test
    @WithUserDetails("office@example.com")
    void pdfOpensInlineAndDownloadsOnRequest() throws Exception {
        Ship ship = ship();
        User crew = shipUser(ship, "crew@example.com", Role.CREW_MEMBER);
        byte[] pdf = "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        mockMvc.perform(multipart("/office/ships/{id}/library/new", ship.getId())
                        .file(new MockMultipartFile("file", "Garbage plan.pdf", "application/pdf", pdf))
                        .with(csrf())
                        .param("title", "Garbage plan")
                        .param("category", "SHIP_PLANS_AND_MAPS")
                        .param("versionLabel", "1")
                        .param("required", "false"))
                .andExpect(redirectedUrl("/office/ships/" + ship.getId() + "/library"));
        mockMvc.perform(multipart("/office/ships/{id}/library/new", ship.getId())
                        .file(new MockMultipartFile("file", "Crew list.docx",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", new byte[] {1, 2, 3}))
                        .with(csrf())
                        .param("title", "Crew list template")
                        .param("category", "OTHER")
                        .param("versionLabel", "1")
                        .param("required", "false"))
                .andExpect(redirectedUrl("/office/ships/" + ship.getId() + "/library"));
        List<LibraryItem> items = itemRepository.findAllByShipAndRetiredFalseAndCurrentVersionIsNotNullOrderByTitleAsc(ship);
        LibraryItem docx = items.get(0);
        LibraryItem plan = items.get(1);
        String officeFile = "/office/ships/" + ship.getId() + "/library/" + plan.getId() + "/file";

        // The read view: type icon, name, size; Open PDF in a new tab, then Download.
        assertThat(mockMvc.perform(get("/office/ships/{s}/library/{id}", ship.getId(), plan.getId()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("aria-label=\"PDF file\"")
                .contains("Garbage plan.pdf")
                .contains(pdf.length + " B")
                .containsPattern("href=\"" + officeFile + "\" target=\"_blank\" rel=\"noopener\"")
                .contains("href=\"" + officeFile + "?download=true\" download")
                .containsPattern("(?s)Open PDF.*Download");

        mockMvc.perform(get(officeFile))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", Matchers.startsWith("inline;")))
                .andExpect(header().string("Content-Disposition", Matchers.containsString("Garbage plan.pdf")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(pdf));
        mockMvc.perform(get(officeFile).param("download", "true"))
                .andExpect(header().string("Content-Disposition", Matchers.startsWith("attachment;")));

        // The crew get the same.
        mockMvc.perform(get("/ship/library/" + plan.getId() + "/file").with(user(new CurrentUser(crew))))
                .andExpect(header().string("Content-Disposition", Matchers.startsWith("inline;")));
        mockMvc.perform(get("/ship/library/" + plan.getId() + "/file").param("download", "true")
                        .with(user(new CurrentUser(crew))))
                .andExpect(header().string("Content-Disposition", Matchers.startsWith("attachment;")));

        // DOCX: a download, whatever is asked; no Open button.
        mockMvc.perform(get("/ship/library/" + docx.getId() + "/file").with(user(new CurrentUser(crew))))
                .andExpect(header().string("Content-Disposition", Matchers.startsWith("attachment;")));
        assertThat(mockMvc.perform(get("/ship/library/" + docx.getId()).with(user(new CurrentUser(crew))))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("aria-label=\"DOCX file\"")
                .doesNotContain("Open PDF")
                .contains("Download");
    }

    // P22: Send reminder notifies who has not acknowledged, at most once per 24 hours per item, and is audited.
    @Test
    @WithUserDetails("office@example.com")
    void officeSendsReminderOncePerDay() throws Exception {
        Ship ship = ship();
        User reader = shipUser(ship, "reader@example.com", Role.CREW_MEMBER);
        User late = shipUser(ship, "late@example.com", Role.CHIEF_COOK);
        LibraryItem item = textItem(ship, "Drug and alcohol policy", true, "1", "No alcohol on board.");
        mockMvc.perform(post("/ship/library/" + item.getId() + "/acknowledge")
                .with(user(new CurrentUser(reader))).with(csrf()));
        String overviewUrl = "/office/ships/" + ship.getId() + "/library/" + item.getId() + "/acknowledgements";

        // The overview offers it, behind a confirmation (with a plain page when the script did not load).
        assertThat(mockMvc.perform(get(overviewUrl)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("action=\"" + overviewUrl + "/remind\"")
                .contains("Send reminder");
        mockMvc.perform(get(overviewUrl + "/remind"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Send a reminder about Drug and alcohol policy?")));

        mockMvc.perform(post(overviewUrl + "/remind").with(csrf()))
                .andExpect(redirectedUrl(overviewUrl))
                .andExpect(flash().attribute("successMessage", "library.reminder.sent"));
        assertThat(notificationRepository.findAllByUserEmailOrderByCreatedAtDescIdDesc(late.getEmail()))
                .singleElement()
                .satisfies(n -> {
                    assertThat(n.getText()).isEqualTo("Library · Reminder: document to read");
                    assertThat(n.getLink()).isEqualTo("/ship/library/" + item.getId());
                });
        assertThat(notificationRepository.countByUserEmailAndReadAtIsNull(reader.getEmail())).isZero();
        assertThat(auditRepository.findAll()).filteredOn(e -> e.getAction().equals("LIBRARY_ACKNOWLEDGEMENT_REMINDER_SENT"))
                .singleElement()
                .satisfies(e -> assertThat(e.getDetails()).isEqualTo("\"Drug and alcohol policy\", version 1, 1 person"));

        // Within 24 hours: not offered, refused if sent anyway.
        assertThat(mockMvc.perform(get(overviewUrl)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain(overviewUrl + "/remind")
                .contains("The next one can be sent after");
        mockMvc.perform(post(overviewUrl + "/remind").with(csrf()))
                .andExpect(flash().attribute("errorMessage", "library.reminder.tooSoon"));
        assertThat(notificationRepository.countByUserEmailAndReadAtIsNull(late.getEmail())).isEqualTo(1);

        // A day later it can be sent again.
        item.setLastReminderAt(Instant.now().minus(LibraryService.REMINDER_INTERVAL).minusSeconds(1));
        itemRepository.flush();
        mockMvc.perform(post(overviewUrl + "/remind").with(csrf()))
                .andExpect(flash().attribute("successMessage", "library.reminder.sent"));
        assertThat(notificationRepository.countByUserEmailAndReadAtIsNull(late.getEmail())).isEqualTo(2);
    }

    @Test
    void onlyTheMasterSendsRemindersOnBoard() throws Exception {
        Ship ship = ship();
        User master = shipUser(ship, "master@example.com", Role.MASTER);
        User crew = shipUser(ship, "crew@example.com", Role.CREW_MEMBER);
        LibraryItem item = textItem(ship, "Drug and alcohol policy", true, "1", "No alcohol on board.");
        String remind = "/ship/library/" + item.getId() + "/acknowledgements/remind";

        mockMvc.perform(post(remind).with(user(new CurrentUser(crew))).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(remind).with(user(new CurrentUser(master))).with(csrf()))
                .andExpect(redirectedUrl("/ship/library/" + item.getId() + "/acknowledgements"));
        // The Master is not reminded of their own reminder.
        assertThat(notificationRepository.countByUserEmailAndReadAtIsNull(master.getEmail())).isZero();
        assertThat(notificationRepository.countByUserEmailAndReadAtIsNull(crew.getEmail())).isEqualTo(1);
    }

    private Ship ship() {
        Ship ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setCreatedAt(Instant.now());
        return shipRepository.save(ship);
    }

    private LibraryItem textItem(Ship ship, String title, boolean required, String versionLabel, String text) {
        LibraryItem item = new LibraryItem();
        item.setShip(ship);
        item.setTitle(title);
        item.setCategory(LibraryCategory.COMPANY_POLICIES);
        item.setRequired(required);
        item.setCreatedAt(Instant.now());
        itemRepository.save(item);
        LibraryItemVersion version = new LibraryItemVersion();
        version.setItem(item);
        version.setVersionLabel(versionLabel);
        version.setText(text);
        version.setPublishedAt(Instant.now());
        item.setCurrentVersion(versionRepository.save(version));
        itemRepository.flush();
        return item;
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
