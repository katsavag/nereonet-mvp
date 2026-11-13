package com.nereonix.nereonet_mvp.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.certificate.CertificateRepository;
import com.nereonix.nereonet_mvp.core.notification.Notification;
import com.nereonix.nereonet_mvp.core.notification.NotificationRepository;
import com.nereonix.nereonet_mvp.core.notification.NotificationType;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserService;

import jakarta.persistence.EntityManager;

/**
 * P23: notifications stay unread until handled. Showing the list marks nothing; opening one, its Mark as read and
 * Mark all as read do, and the counters follow. Grouped by UTC day, 25 to a page.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class NotificationFlowTest {

    private static final Pattern SIDEBAR_COUNT = Pattern.compile(
            "(?s)data-nav=\"notifications\">.*?<span class=\"nav-count num\" aria-hidden=\"true\">([^<]+)</span>");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ShipRepository shipRepository;

    @Autowired
    UserService userService;

    @Autowired
    NotificationRepository notificationRepository;

    @Autowired
    CertificateRepository certificateRepository;

    @Autowired
    EntityManager entityManager;

    Ship ship;
    User crew;
    CurrentUser me;

    @BeforeEach
    void setUp() {
        ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);
        crew = userService.createUser("crew@example.com", "Crew Member", "secret123", Role.CREW_MEMBER, ship, null);
        me = new CurrentUser(crew);
    }

    @Test
    void visitingTheListKeepsTheUnreadCount() throws Exception {
        notification(crew, "Library · New document to read", "/ship/library/1", Instant.now());
        notification(crew, "Library · Reminder: document to read", "/ship/library/1", Instant.now());

        String page = page("/notifications");
        page("/notifications");

        assertThat(unread()).isEqualTo(2);
        assertThat(sidebarCount(page)).isEqualTo("2");
        // Unread: a dot, bold text (the class) and "Unread:" for screen readers; the type and the ship are named.
        assertThat(page).contains("class=\"notification is-unread\"", "class=\"notification-dot\"",
                "<span class=\"visually-hidden\" data-unread-label>Unread: </span>",
                "<span class=\"visually-hidden\">Library</span>",
                "<p class=\"notification-subject\"", ">MV Demo</p>",
                ">Mark all as read</span>", "aria-label=\"Mark as read: Library · New document to read\"");
    }

    @Test
    void openingANotificationMarksItReadAndFollowsItsLink() throws Exception {
        Notification opened = notification(crew, "Library · New document to read", "/ship/library/7", Instant.now());
        notification(crew, "Library · Reminder: document to read", "/ship/library/8", Instant.now());

        mockMvc.perform(post("/notifications/{id}/open", opened.getId()).with(user(me)).with(csrf()))
                .andExpect(redirectedUrl("/ship/library/7"));

        assertThat(notificationRepository.findById(opened.getId()).orElseThrow().getReadAt()).isNotNull();
        assertThat(unread()).isEqualTo(1);
        // Read now: regular weight, no dot, no Mark as read, and a plain link to the item.
        String page = page("/notifications");
        assertThat(sidebarCount(page)).isEqualTo("1");
        assertThat(page).containsPattern("(?s)<li class=\"notification\" data-notification>.*?"
                + "<a class=\"notification-text notification-open stretched-link\" href=\"/ship/library/7\"");
        assertThat(page).containsOnlyOnce("aria-label=\"Mark as read:");
    }

    @Test
    void markOneAsRead() throws Exception {
        Notification first = notification(crew, "First", null, Instant.now());
        Notification second = notification(crew, "Second", null, Instant.now());

        // Without the script: back to the same page of the list, with a message.
        mockMvc.perform(post("/notifications/{id}/read", first.getId()).with(user(me)).with(csrf())
                        .param("page", "1").param("size", "50"))
                .andExpect(redirectedUrl("/notifications?size=50"))
                .andExpect(flash().attribute("successMessage", "notification.markedRead"));
        assertThat(notificationRepository.findById(first.getId()).orElseThrow().getReadAt()).isNotNull();
        assertThat(unread()).isEqualTo(1);

        // With the script: the new count and the counters' words.
        mockMvc.perform(post("/notifications/{id}/read", second.getId()).with(user(me)).with(csrf())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(0))
                .andExpect(jsonPath("$.countLabel").value("0 unread"))
                .andExpect(jsonPath("$.bellLabel").value("Notifications, 0 unread"));
        assertThat(unread()).isZero();
        assertThat(page("/notifications")).doesNotContain("nav-count", "is-unread", "Mark all as read");
    }

    @Test
    void markAllAsRead() throws Exception {
        notification(crew, "First", null, Instant.now());
        notification(crew, "Second", "/ship/library/1", Instant.now());
        User other = userService.createUser("other@example.com", "Other Member", "secret123", Role.CREW_MEMBER, ship, null);
        notification(other, "Not mine", null, Instant.now());

        mockMvc.perform(post("/notifications/read-all").with(user(me)).with(csrf()))
                .andExpect(redirectedUrl("/notifications"))
                .andExpect(flash().attribute("successMessage", "notification.allMarkedRead"));

        assertThat(unread()).isZero();
        assertThat(notificationRepository.countByUserEmailAndReadAtIsNull(other.getEmail())).isEqualTo(1);

        notification(crew, "Third", null, Instant.now());
        mockMvc.perform(post("/notifications/read-all").with(user(me)).with(csrf()).accept(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.unread").value(0));
        assertThat(unread()).isZero();
    }

    // P43: Mark all as read clears the bell for the signed-in user only; no certificate or vetting state changes,
    // so the Certificates counter (what needs the office) stays as it was.
    @Test
    void markAllAsReadClearsTheBellOnlyAndChangesNoCounter() throws Exception {
        User office = userService.createUser("office2@example.com", "Office Two", "secret123", Role.OFFICE_USER, null, null);
        CurrentUser me2 = new CurrentUser(office);
        notification(office, "Your certificate STCW expires in 30 days", null, Instant.now());
        notification(crew, "Not mine", null, Instant.now());
        Certificate certificate = new Certificate();
        certificate.setUser(crew);
        certificate.setType("STCW");
        certificate.setExpiresOn(LocalDate.now(ZoneOffset.UTC).plusDays(10));
        certificate.setCreatedAt(Instant.now());
        certificateRepository.save(certificate);

        String before = page("/notifications", me2);
        assertThat(sidebarCount(before)).isEqualTo("1");
        assertThat(before).containsPattern("(?s)>Certificates</span>\\s*<span class=\"nav-count num\" aria-hidden=\"true\">1</span>");

        mockMvc.perform(post("/notifications/read-all").with(user(me2)).with(csrf()))
                .andExpect(redirectedUrl("/notifications"));
        entityManager.clear(); // the bulk update does not touch the entities loaded in this test's transaction

        String after = page("/notifications", me2);
        assertThat(sidebarCount(after)).isNull();
        assertThat(after).containsPattern("(?s)>Certificates</span>\\s*<span class=\"nav-count num\" aria-hidden=\"true\">1</span>")
                .doesNotContain("is-unread", "Mark all as read");
        assertThat(certificateRepository.findById(certificate.getId()).orElseThrow().getExpiresOn())
                .isEqualTo(LocalDate.now(ZoneOffset.UTC).plusDays(10));
        // The crew member's own unread notification is untouched.
        assertThat(unread()).isEqualTo(1);
    }

    @Test
    void onlyTheirOwnNotificationsAndOnlyByPost() throws Exception {
        User other = userService.createUser("other@example.com", "Other Member", "secret123", Role.CREW_MEMBER, ship, null);
        Notification notMine = notification(other, "Not mine", "/ship/library/1", Instant.now());
        notification(crew, "Mine", null, Instant.now());

        mockMvc.perform(post("/notifications/{id}/open", notMine.getId()).with(user(me)).with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/notifications/{id}/read", notMine.getId()).with(user(me)).with(csrf()))
                .andExpect(status().isNotFound());
        // Without the CSRF token nothing is marked (the app answers with a redirect, not the action).
        mockMvc.perform(post("/notifications/read-all").with(user(me)))
                .andExpect(status().is3xxRedirection());
        assertThat(unread()).isEqualTo(1);
        assertThat(notificationRepository.countByUserEmailAndReadAtIsNull(other.getEmail())).isEqualTo(1);
        assertThat(page("/notifications")).doesNotContain("Not mine");
    }

    @Test
    void aLinkOutsideTheAppIsNotFollowed() throws Exception {
        Notification odd = notification(crew, "Odd", "//evil.example.com", Instant.now());

        mockMvc.perform(post("/notifications/{id}/open", odd.getId()).with(user(me)).with(csrf()))
                .andExpect(redirectedUrl("/notifications"));
    }

    @Test
    void groupedByDayAndPagedAt10() throws Exception {
        Instant now = Instant.now();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        Instant startOfToday = today.atStartOfDay(ZoneOffset.UTC).toInstant();
        notification(crew, "From today", null, now);
        notification(crew, "From yesterday", null, startOfToday.minus(Duration.ofHours(2)));
        notification(crew, "From last week", null, startOfToday.minus(Duration.ofDays(6)));
        for (int i = 0; i < 25; i++) {
            notification(crew, "Old " + i, null, startOfToday.minus(Duration.ofDays(30)));
        }

        // 10 a page (P43; 25 before).
        String first = page("/notifications");
        assertThat(first).containsSubsequence(">Today</h2>", "From today", ">Yesterday</h2>", "From yesterday",
                ">" + date(today.minusDays(6)) + "</h2>", "From last week", ">" + date(today.minusDays(30)) + "</h2>");
        assertThat(count(first, "data-notification>")).isEqualTo(10);
        assertThat(first).contains("1 to 10 of 28", "href=\"/notifications?page=2\"", "href=\"/notifications?size=20\"");
        // Every row says its time in UTC.
        assertThat(first).containsPattern("<time class=\"local-time text-nowrap\" datetime=\"[^\"]+\">\\d\\d:\\d\\d UTC</time>");

        String third = page("/notifications?page=3");
        assertThat(count(third, "data-notification>")).isEqualTo(8);
        assertThat(third).contains(">" + date(today.minusDays(30)) + "</h2>").doesNotContain("From today");
        assertThat(count(page("/notifications?size=50"), "data-notification>")).isEqualTo(28);
        assertThat(unread()).isEqualTo(28);
    }

    @Test
    void theTypeIconAndTheSubjectAreShown() throws Exception {
        Notification certificate = notification(crew, "Your certificate STCW expires in 60 days", null, Instant.now());
        certificate.setType(NotificationType.CERTIFICATE);
        certificate.setSubject("Crew Member · MV Demo");
        Notification old = notification(crew, "Saved before P23", null, Instant.now());
        old.setType(null);
        old.setSubject(null);
        notificationRepository.flush();

        assertThat(page("/notifications")).contains("<span class=\"visually-hidden\">Certificate</span>",
                ">Crew Member · MV Demo</p>", "<span class=\"visually-hidden\">Notification</span>");
    }

    @Test
    void officeUsersSeeItInGreek() throws Exception {
        User office = userService.createUser("office2@example.com", "Office Two", "secret123", Role.OFFICE_USER, null, null);
        Notification n = notification(office, "Something", null, Instant.now());

        assertThat(page("/notifications?lang=el", new CurrentUser(office)))
                .contains("Σήμερα", "Επισήμανση όλων ως αναγνωσμένων", "Μη αναγνωσμένη: ");
        mockMvc.perform(post("/notifications/{id}/read", n.getId()).with(user(new CurrentUser(office))).with(csrf())
                        .param("lang", "el").accept(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.bellLabel").value("Ειδοποιήσεις, 0 μη αναγνωσμένες"));
    }

    private Notification notification(User user, String text, String link, Instant createdAt) {
        Notification n = new Notification();
        n.setUser(user);
        n.setType(NotificationType.LIBRARY);
        n.setSubject(ship.getName());
        n.setText(text);
        n.setLink(link);
        n.setCreatedAt(createdAt);
        return notificationRepository.save(n);
    }

    private long unread() {
        return notificationRepository.countByUserEmailAndReadAtIsNull(crew.getEmail());
    }

    private String page(String url) throws Exception {
        return page(url, me);
    }

    private String page(String url, CurrentUser who) throws Exception {
        return mockMvc.perform(get(url).with(user(who)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String sidebarCount(String page) {
        Matcher matcher = SIDEBAR_COUNT.matcher(page);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String date(LocalDate date) {
        return date.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH));
    }

    private static int count(String text, String part) {
        int count = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) {
            count++;
        }
        return count;
    }
}
