package com.nereonix.nereonet_mvp.announcement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.MessageSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.announcement.Announcement;
import com.nereonix.nereonet_mvp.core.announcement.AnnouncementRecipient;
import com.nereonix.nereonet_mvp.core.announcement.AnnouncementRecipientRepository;
import com.nereonix.nereonet_mvp.core.announcement.AnnouncementRepository;
import com.nereonix.nereonet_mvp.core.audit.AuditRepository;
import com.nereonix.nereonet_mvp.core.notification.Notification;
import com.nereonix.nereonet_mvp.core.notification.NotificationRepository;
import com.nereonix.nereonet_mvp.core.notification.NotificationType;
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
@WithUserDetails("office@example.com") // seeded by BootstrapData
class AnnouncementFlowTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ShipRepository shipRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    AnnouncementRepository announcementRepository;

    @Autowired
    AnnouncementRecipientRepository recipientRepository;

    @Autowired
    MessageSource messageSource;

    @Autowired
    NotificationRepository notificationRepository;

    @Autowired
    AuditRepository auditRepository;

    @Test
    void recipientsAreFixedWhenTheAnnouncementIsSent() throws Exception {
        Ship ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);
        crewMember("anna@example.com", "Anna Acked", ship);
        crewMember("paul@example.com", "Paul Pending", ship);

        mockMvc.perform(post("/office/announcements/send")
                        .param("target", "ships").param("shipIds", ship.getId().toString())
                        .param("title", "Safety drill")
                        .param("body", "Drill on Friday at 10:00.")
                        .with(csrf()))
                .andExpect(redirectedUrl("/office/announcements/" + announcementRepository.findAll().getFirst().getId()));

        Announcement announcement = announcementRepository.findAll().getFirst();
        assertThat(announcement.isFleetWide()).isFalse();
        assertThat(announcement.getTargetShips()).containsExactly(ship);
        assertThat(announcement.getCreatedBy().getEmail()).isEqualTo("office@example.com");

        // Joins after the announcement was sent: not a recipient.
        crewMember("lena@example.com", "Lena Later", ship);

        List<AnnouncementRecipient> recipients = recipientRepository.findAllByAnnouncementOrderByUserFullNameAsc(announcement);
        assertThat(recipients).extracting(r -> r.getUser().getFullName()).containsExactly("Anna Acked", "Paul Pending");
        recipients.getFirst().setAcknowledgedAt(Instant.now());
        recipientRepository.flush();

        String html = mockMvc.perform(get("/office/announcements/" + announcement.getId()))
                .andReturn().getResponse().getContentAsString();
        int acknowledged = html.indexOf("id=\"acknowledged\"");
        int pending = html.indexOf("id=\"pending\"");
        assertThat(acknowledged).isPositive().isLessThan(pending);
        assertThat(html.substring(acknowledged, pending)).contains("Anna Acked").doesNotContain("Paul Pending");
        assertThat(html.substring(pending)).contains("Paul Pending").doesNotContain("Anna Acked");
        assertThat(html).doesNotContain("Lena Later");

        String list = mockMvc.perform(get("/office/announcements"))
                .andReturn().getResponse().getContentAsString();
        assertThat(list).contains("Safety drill").contains("1 of 2 read");
    }

    // P10: the form's button is Review; the review page shows the target, the people per ship and the text as the crew
    // will see it, and saves nothing. Back to edit keeps the text; Send counts the recipients again.
    @Test
    void sendingGoesThroughTheReviewPage() throws Exception {
        Ship demo = ship("9074729", "MV Demo");
        Ship other = ship("9176187", "MV Other");
        crewMember("anna@example.com", "Anna Acked", demo);
        crewMember("paul@example.com", "Paul Pending", demo);
        crewMember("olga@example.com", "Olga Other", other);
        crewMember("dimitris@example.com", "Dimitris Disabled", other).setEnabled(false);
        userRepository.flush();

        String form = html(get("/office/announcements/new"));
        assertThat(form).contains("action=\"/office/announcements/review\"",
                "<button type=\"submit\" class=\"btn btn-primary\">Review</button>");

        String review = html(post("/office/announcements/review")
                .param("target", "fleet").param("title", "Safety drill")
                .param("body", "Drill on Friday.\r\nMuster at <10:00>.").with(csrf()));
        assertThat(announcementRepository.count()).isZero();
        assertThat(review).contains("<h1 class=\"page-title\">", "Review announcement", "All vessels",
                "action=\"/office/announcements/send\"",
                "<button type=\"submit\" class=\"btn btn-primary\">Send to 3 people on 2 vessels</button>",
                "formaction=\"/office/announcements/new\">Back to edit</button>",
                "<input type=\"hidden\" name=\"target\" value=\"fleet\">",
                "<input type=\"hidden\" name=\"title\" value=\"Safety drill\">",
                // The text as the crew will see it: escaped, line breaks kept (text-pre-line).
                "Drill on Friday.\nMuster at &lt;10:00&gt;.");
        assertThat(review).containsPattern("(?s)MV Demo</span>\\s*<span class=\"review-imo num\">IMO 9074729</span>\\s*</td>\\s*<td class=\"num\">2</td>")
                .containsPattern("(?s)MV Other</span>\\s*<span class=\"review-imo num\">IMO 9176187</span>\\s*</td>\\s*<td class=\"num\">1</td>")
                .containsPattern("(?s)Total</th>\\s*<td class=\"num fw-semibold\">3</td>");

        // Back to edit: the form again with the text as it was, nothing sent.
        String edit = html(post("/office/announcements/new")
                .param("target", "fleet").param("title", "Safety drill").param("body", "Drill on Friday.").with(csrf()));
        assertThat(announcementRepository.count()).isZero();
        assertThat(edit).contains("New announcement", "action=\"/office/announcements/review\"",
                "value=\"Safety drill\"",
                ">Drill on Friday.</textarea>")
                .containsPattern("id=\"target\" name=\"target\"[^>]*value=\"fleet\"[^>]*checked=\"checked\"");

        // Someone joins after the review: the recipients are counted again when it is sent.
        crewMember("lena@example.com", "Lena Later", other);
        MvcResult sent = mockMvc.perform(post("/office/announcements/send")
                        .param("target", "fleet").param("title", "Safety drill").param("body", "Drill on Friday.")
                        .with(csrf()))
                .andExpect(flash().attribute("successMessage", "announcement.created"))
                .andReturn();
        Announcement announcement = announcementRepository.findAll().getFirst();
        assertThat(sent.getResponse().getRedirectedUrl()).isEqualTo("/office/announcements/" + announcement.getId());
        assertThat((Object[]) sent.getFlashMap().get("successArgs")).containsExactly(4, 2);
        assertThat(recipientRepository.findAllByAnnouncementOrderByUserFullNameAsc(announcement)).hasSize(4);
        assertThat(messageSource.getMessage("announcement.created", new Object[] {4, 2}, Locale.ENGLISH))
                .isEqualTo("Announcement sent to 4 people on 2 vessels.");
        assertThat(messageSource.getMessage("announcement.review.send", new Object[] {1, 1}, Locale.ENGLISH))
                .isEqualTo("Send to 1 person on 1 vessel");
    }

    @Test
    void reviewOfOneShipAndAShipDeactivatedBeforeSending() throws Exception {
        Ship demo = ship("9074729", "MV Demo");
        crewMember("anna@example.com", "Anna Acked", demo);

        String review = html(post("/office/announcements/review")
                .param("target", "ships").param("shipIds", demo.getId().toString()).param("title", "Drill").param("body", "Friday")
                .with(csrf()));
        assertThat(review).contains("<p class=\"eyebrow\">MV Demo · IMO 9074729</p>", "Send to 1 person on 1 vessel")
                .doesNotContain("Total</th>", "Nobody would receive it");

        // Deactivated meanwhile: back to the form with an error on the target, nothing sent.
        demo.setActive(false);
        shipRepository.flush();
        String form = html(post("/office/announcements/send")
                .param("target", "ships").param("shipIds", demo.getId().toString()).param("title", "Drill").param("body", "Friday")
                .with(csrf()));
        assertThat(form).contains("The vessels you ticked are no longer active. Tick at least one active vessel.");
        assertThat(announcementRepository.count()).isZero();

        // Nobody on board: the review page says so.
        Ship empty = ship("9176187", "MV Empty");
        assertThat(html(post("/office/announcements/review")
                .param("target", "ships").param("shipIds", empty.getId().toString()).param("title", "Drill").param("body", "Friday")
                .with(csrf()))).contains("Nobody would receive it", "Send to 0 people on 1 vessel");
    }

    // P16, FR-GEN-008: the form and the review page say it is no emergency channel, right above their buttons.
    @Test
    void formAndReviewSayItIsNotAnEmergencyChannel() throws Exception {
        String notice = "Nereonix is not an emergency or safety channel. In an emergency, follow the vessel&#39;s "
                + "safety procedures.";
        assertThat(html(get("/office/announcements/new")))
                .containsPattern("(?s)<p class=\"info-note\">\\s*<svg.*?</svg>\\s*<span>" + notice + "</span>\\s*</p>"
                        + "\\s*<div class=\"form-actions\">\\s*<button type=\"submit\" class=\"btn btn-primary\">Review");
        assertThat(html(get("/office/announcements/new").param("lang", "el")))
                .contains("Το Nereonix δεν είναι κανάλι έκτακτης ανάγκης ή ασφάλειας.");

        Ship demo = ship("9074729", "MV Demo");
        String review = html(post("/office/announcements/review")
                .param("target", "ships").param("shipIds", demo.getId().toString()).param("title", "Drill").param("body", "Friday")
                .with(csrf()));
        assertThat(review).containsPattern("(?s)<span>" + notice + "</span>\\s*</p>\\s*<form method=\"post\" "
                + "action=\"/office/announcements/send\"");
    }

    // An invalid form never reaches the review page.
    @Test
    void reviewOfAnInvalidFormShowsTheFormWithItsErrors() throws Exception {
        String html = html(post("/office/announcements/review")
                .param("target", "fleet").param("title", "").param("body", "Friday").with(csrf()));
        assertThat(html).contains("Enter a title.", "action=\"/office/announcements/review\"")
                .doesNotContain("Review announcement");
    }

    // P27, P39: sending notifies each recipient; the crew list their own news items newest first. Opening one reads it
    // (no button): the first time the server records when, with an audit entry; opening it again changes nothing. The
    // office then sees who has read it, with the two ticks and the time.
    @Test
    void crewOpeningANewsItemReadsIt() throws Exception {
        Ship demo = ship("9074729", "MV Demo");
        User cook = crewMember("cook@example.com", "Demo Cook", demo);
        crewMember("anna@example.com", "Anna Testa", demo);

        Announcement drill = send(demo, "Safety drill", "Drill on Friday.\r\nMuster at <10:00>.");
        Announcement galley = send(null, "Galley inspection", "Next week.");
        announcementRepository.flush();

        // Each recipient gets a notification that opens the news item.
        Notification notification = notificationRepository.findAllByUserEmailOrderByCreatedAtDescIdDesc("cook@example.com")
                .stream().filter(n -> n.getLink().equals("/ship/news/" + drill.getId())).findFirst().orElseThrow();
        assertThat(notification.getType()).isEqualTo(NotificationType.ANNOUNCEMENT);
        assertThat(notification.getText()).isEqualTo("News · Safety drill");
        assertThat(notification.getSubject()).isEqualTo("MV Demo");
        assertThat(notification.getReadAt()).isNull();
        assertThat(notificationRepository.countByUserEmailAndReadAtIsNull("anna@example.com")).isEqualTo(2);

        // The list: newest first, both new (bold title, a dot and "New").
        String list = html(get("/ship/news").with(user(new CurrentUser(cook))));
        assertThat(list).contains("<title>News · Nereonet</title>", "<p class=\"eyebrow\">MV Demo · IMO 9074729</p>")
                .containsSubsequence("href=\"/ship/news/" + galley.getId() + "\"", "Galley inspection",
                        "<span class=\"news-state-dot\" aria-hidden=\"true\"></span><span>New</span>",
                        "href=\"/ship/news/" + drill.getId() + "\"", "Safety drill", "<span>New</span>")
                .doesNotContain("To acknowledge", "Acknowledged", "Announcement");
        assertThat(count(list, "announcement-row is-unread")).isEqualTo(2);
        // News is the active tab (and sidebar entry).
        assertThat(list).contains("<a class=\"tab-bar-link active\" href=\"/ship/news\" aria-current=\"page\">",
                "<span class=\"tab-bar-label\">News</span>", "<span class=\"sidebar-link-label\">News</span>");

        // The detail: sender, time in UTC, the text with its line breaks (escaped), and no button: opening it read it.
        String office = userRepository.findByEmail("office@example.com").orElseThrow().getFullName();
        String detail = html(get("/ship/news/{id}", drill.getId()).with(user(new CurrentUser(cook))));
        assertThat(detail).contains("<h1 class=\"page-title\">", "Safety drill", "Office · " + office, " UTC</time>",
                        "Drill on Friday.\nMuster at &lt;10:00&gt;.", "<p class=\"eyebrow\">MV Demo · IMO 9074729</p>",
                        "href=\"/ship/news\">News</a>")
                // It ends with the two ticks and "Read <date and time> UTC".
                .containsPattern("(?s)<p class=\"news-read-at\">\\s*<span class=\"read-receipt\">\\s*"
                        + "<svg class=\"read-ticks is-read\"[^>]*>.*?</svg>\\s*<span>Read</span>\\s*"
                        + "<time class=\"local-time text-nowrap\"[^>]*>[^<]+ UTC</time>")
                .doesNotContain("Anna Testa", "of 2 read", "I have read this", "/acknowledge\"");
        AnnouncementRecipient mine = recipientRepository.findByAnnouncementIdAndUserEmail(drill.getId(), "cook@example.com")
                .orElseThrow();
        Instant readAt = mine.getAcknowledgedAt();
        assertThat(readAt).isNotNull();
        assertThat(auditRepository.findAll()).filteredOn(e -> e.getAction().equals("ANNOUNCEMENT_ACKNOWLEDGED"))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getActor().getEmail()).isEqualTo("cook@example.com");
                    assertThat(e.getShip().getId()).isEqualTo(demo.getId());
                    assertThat(e.getDetails()).isEqualTo("\"Safety drill\"");
                });
        // Only the signed-in recipient's own row: Anna has not read it.
        assertThat(recipientRepository.findByAnnouncementIdAndUserEmail(drill.getId(), "anna@example.com")
                .orElseThrow().getAcknowledgedAt()).isNull();

        // Opening it again (a second tab, a reload) changes nothing: same time, no new audit entry.
        html(get("/ship/news/{id}", drill.getId()).with(user(new CurrentUser(cook))));
        recipientRepository.flush();
        assertThat(recipientRepository.findByAnnouncementIdAndUserEmail(drill.getId(), "cook@example.com")
                .orElseThrow().getAcknowledgedAt()).isEqualTo(readAt);
        assertThat(auditRepository.findAll()).filteredOn(e -> e.getAction().equals("ANNOUNCEMENT_ACKNOWLEDGED")).hasSize(1);

        // The "I have read this" endpoint is gone.
        mockMvc.perform(post("/ship/announcements/{id}/acknowledge", galley.getId())
                        .with(user(new CurrentUser(cook))).with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(recipientRepository.findByAnnouncementIdAndUserEmail(galley.getId(), "cook@example.com")
                .orElseThrow().getAcknowledgedAt()).isNull();

        // The list: the drill now read (regular title, the two ticks and "Read" for screen readers), the other new.
        String listAfter = html(get("/ship/news").with(user(new CurrentUser(cook))));
        assertThat(count(listAfter, "announcement-row is-unread")).isEqualTo(1);
        assertThat(listAfter).containsSubsequence("Galley inspection", "<span>New</span>", "Safety drill",
                "<svg class=\"read-ticks is-read\"", "<span class=\"visually-hidden\">Read</span>");

        // The office sees it: 1 of 2 read; the cook with the two ticks and the time, Anna with one grey tick.
        String officeDetail = html(get("/office/announcements/{id}", drill.getId()));
        assertThat(officeDetail).contains("1 of 2 read", "Read by vessel").doesNotContain("acknowledged<", "Acknowledged");
        int read = officeDetail.indexOf("id=\"acknowledged\"");
        int pending = officeDetail.indexOf("id=\"pending\"");
        assertThat(officeDetail.substring(read, pending)).contains("Demo Cook").doesNotContain("Anna Testa")
                .containsPattern("(?s)<span class=\"read-receipt\">\\s*<svg class=\"read-ticks is-read\".*?</svg>\\s*"
                        + "<span>Read</span>\\s*<time class=\"local-time text-nowrap\"[^>]*>[^<]+ UTC</time>");
        assertThat(officeDetail.substring(pending)).contains("Anna Testa")
                .containsPattern("(?s)<span class=\"read-receipt is-unread\">\\s*<svg class=\"read-ticks is-unread\".*?</svg>"
                        + "\\s*<span>Not read yet</span>");
        assertThat(html(get("/office/audit"))).contains("Announcement read");
        // In Greek on the office side.
        assertThat(html(get("/office/announcements/{id}", drill.getId()).param("lang", "el")))
                .contains("1 από 2 διάβασαν", "Δεν διαβάστηκε ακόμη", "<span>Διαβάστηκε</span>");
    }

    // P39: the old crew URLs (bookmarks, older notifications) move to /ship/news for good.
    @Test
    void theOldCrewUrlsRedirectToNews() throws Exception {
        Ship demo = ship("9074729", "MV Demo");
        User cook = crewMember("cook@example.com", "Demo Cook", demo);
        Announcement drill = send(demo, "Safety drill", "Drill on Friday.");

        mockMvc.perform(get("/ship/announcements").with(user(new CurrentUser(cook))))
                .andExpect(status().isMovedPermanently())
                .andExpect(redirectedUrl("/ship/news"));
        mockMvc.perform(get("/ship/announcements/{id}", drill.getId()).with(user(new CurrentUser(cook))))
                .andExpect(status().isMovedPermanently())
                .andExpect(redirectedUrl("/ship/news/" + drill.getId()));
        // Following the redirect is what reads it, not the redirect itself.
        assertThat(recipientRepository.findByAnnouncementIdAndUserEmail(drill.getId(), "cook@example.com")
                .orElseThrow().getAcknowledgedAt()).isNull();
        // The office keeps its URLs and its word.
        assertThat(html(get("/office/announcements"))).contains("<title>Announcements · Nereonet</title>",
                "<span class=\"sidebar-link-label\">Announcements</span>").doesNotContain(">News<");
    }

    // P37, P39: the office sends two news items to the Master's ship. The Master's list puts the ones not read yet
    // first, and the sidebar, the tab bar and the Master's home count them; opening the newer one reads it: it moves
    // below the older one, the counters drop, and the office's detail page lists the Master as read.
    @Test
    void theMasterOpensNewsAndTheListAndCountersFollow() throws Exception {
        Ship demo = ship("9074729", "MV Demo");
        User master = crewMember("master@example.com", "Demo Master", demo, Role.MASTER);
        crewMember("anna@example.com", "Anna Testa", demo);

        Announcement older = send(demo, "Bunkering plan", "Bunkering on Monday.");
        Announcement newer = send(demo, "Safety drill", "Drill on Friday.");
        announcementRepository.flush();

        String list = html(get("/ship/news").with(user(new CurrentUser(master))));
        assertThat(list).containsSubsequence("Safety drill", "<span>New</span>", "Bunkering plan", "<span>New</span>");
        assertThat(count(list, "announcement-row is-unread")).isEqualTo(2);
        // The counter, in the sidebar and on the tab: the number and the same in words.
        assertThat(list).containsPattern("(?s)data-nav=\"announcements\".*?<span class=\"nav-count num\" aria-hidden=\"true\">2</span>"
                        + "\\s*<span class=\"visually-hidden\">2 unread</span>")
                .containsPattern("(?s)tab-bar-link[^>]*href=\"/ship/news\".*?<span class=\"tab-bar-count num\" aria-hidden=\"true\">2</span>");
        // The Master's home: News to read, 2.
        assertThat(html(get("/ship").with(user(new CurrentUser(master)))))
                .containsPattern("(?s)href=\"/ship/news\".*?News to read.*?>2<");

        html(get("/ship/news/{id}", newer.getId()).with(user(new CurrentUser(master))));

        // The one not read yet comes first although it is older; the counters are 1.
        String after = html(get("/ship/news").with(user(new CurrentUser(master))));
        assertThat(after).containsSubsequence("Bunkering plan", "<span>New</span>", "Safety drill",
                "<span class=\"visually-hidden\">Read</span>");
        assertThat(count(after, "announcement-row is-unread")).isEqualTo(1);
        // (The notifications keep their own count: a notification is read on the notifications page.)
        assertThat(navItems(after, "announcements")).hasSize(2).allSatisfy(item ->
                assertThat(item).contains("<span class=\"visually-hidden\">1 unread</span>").doesNotContain("2 unread"));
        assertThat(html(get("/ship").with(user(new CurrentUser(master)))))
                .containsPattern("(?s)href=\"/ship/news\".*?News to read.*?>1<");

        // With nothing left to read there is no counter.
        html(get("/ship/news/{id}", older.getId()).with(user(new CurrentUser(master))));
        String none = html(get("/ship/news").with(user(new CurrentUser(master))));
        assertThat(navItems(none, "announcements")).hasSize(2).allSatisfy(item ->
                assertThat(item).doesNotContain("nav-count", "tab-bar-count"));

        // The office lists the Master under read, Anna under not read yet.
        String officeDetail = html(get("/office/announcements/{id}", newer.getId()));
        assertThat(officeDetail).contains("1 of 2 read");
        int read = officeDetail.indexOf("id=\"acknowledged\"");
        int pending = officeDetail.indexOf("id=\"pending\"");
        assertThat(read).isPositive().isLessThan(pending);
        assertThat(officeDetail.substring(read, pending)).contains("Demo Master").doesNotContain("Anna Testa");
        assertThat(officeDetail.substring(pending)).contains("Anna Testa").doesNotContain("Demo Master");
        // Office pages have no crew counter.
        assertThat(officeDetail).doesNotContain("unread</span>");
    }

    // P27: someone who is not a recipient (another ship, joined later, or no such announcement) gets 404, never the text.
    @Test
    void aNonRecipientGetsNotFound() throws Exception {
        Ship demo = ship("9074729", "MV Demo");
        Ship other = ship("9176187", "MV Other");
        crewMember("cook@example.com", "Demo Cook", demo);
        User olga = crewMember("olga@example.com", "Olga Other", other);
        Announcement drill = send(demo, "Safety drill", "Drill on Friday.");
        User later = crewMember("lena@example.com", "Lena Later", demo);

        for (User u : List.of(olga, later)) {
            // Opening it is a 404 and reads nothing.
            mockMvc.perform(get("/ship/news/{id}", drill.getId()).with(user(new CurrentUser(u))))
                    .andExpect(status().isNotFound());
            assertThat(html(get("/ship/news").with(user(new CurrentUser(u)))))
                    .doesNotContain("Safety drill")
                    .contains("No news yet", "News from the office to you appears here.");
        }
        mockMvc.perform(get("/ship/news/{id}", drill.getId() + 1000).with(user(new CurrentUser(olga))))
                .andExpect(status().isNotFound());
        assertThat(recipientRepository.findAllByAnnouncementOrderByUserFullNameAsc(drill))
                .allSatisfy(r -> assertThat(r.getAcknowledgedAt()).isNull());
        assertThat(auditRepository.findAll()).noneMatch(e -> e.getAction().equals("ANNOUNCEMENT_ACKNOWLEDGED"));
        // The crew pages are for ship roles only: the office never reads a news item by opening it.
        mockMvc.perform(get("/ship/news")).andExpect(status().isForbidden());
        mockMvc.perform(get("/ship/news/{id}", drill.getId())).andExpect(status().isForbidden());
    }

    // P28: one announcement to two of three ships reaches only their crew; the review page lists the two ships with the
    // people on each and the total; the list says "2 ships" (names in the title and on the phone card); the detail
    // page splits "0 of 3 acknowledged" by ship; the audit entry lists the target ship ids.
    @Test
    void anAnnouncementToSelectedShipsReachesOnlyTheirCrew() throws Exception {
        Ship alpha = ship("9074729", "MV Alpha");
        Ship beta = ship("9176187", "MV Beta");
        Ship gamma = ship("9395044", "MV Gamma");
        crewMember("anna@example.com", "Anna Alpha", alpha);
        crewMember("aris@example.com", "Aris Alpha", alpha);
        crewMember("bill@example.com", "Bill Beta", beta);
        crewMember("gina@example.com", "Gina Gamma", gamma);

        String review = html(post("/office/announcements/review")
                .param("target", "ships").param("shipIds", beta.getId().toString(), alpha.getId().toString())
                .param("title", "Port state control").param("body", "Inspection next week.").with(csrf()));
        assertThat(announcementRepository.count()).isZero();
        assertThat(review).contains("<p class=\"eyebrow\">2 vessels</p>", "Send to 3 people on 2 vessels",
                        "<input type=\"hidden\" name=\"target\" value=\"ships\">",
                        "<input type=\"hidden\" name=\"shipIds\" value=\"" + alpha.getId() + "\">",
                        "<input type=\"hidden\" name=\"shipIds\" value=\"" + beta.getId() + "\">")
                .doesNotContain("MV Gamma", "alert alert-warning review-left-out")
                .containsPattern("(?s)MV Alpha</span>.*?<td class=\"num\">2</td>.*?MV Beta</span>.*?<td class=\"num\">1</td>"
                        + ".*?Total</th>\\s*<td class=\"num fw-semibold\">3</td>");

        Announcement announcement = sendTo(List.of(alpha, beta), "Port state control");
        assertThat(announcement.isFleetWide()).isFalse();
        assertThat(announcement.getTargetShips()).containsExactly(alpha, beta);
        assertThat(recipientRepository.findAllByAnnouncementOrderByUserFullNameAsc(announcement))
                .extracting(r -> r.getUser().getFullName()).containsExactly("Anna Alpha", "Aris Alpha", "Bill Beta");
        assertThat(notificationRepository.countByUserEmailAndReadAtIsNull("bill@example.com")).isEqualTo(1);
        assertThat(notificationRepository.countByUserEmailAndReadAtIsNull("gina@example.com")).isZero();
        assertThat(auditRepository.findAll()).filteredOn(e -> e.getAction().equals("ANNOUNCEMENT_CREATED"))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getShip()).isNull();
                    assertThat(e.getDetails()).isEqualTo("Port state control (vessel ids " + alpha.getId() + ", "
                            + beta.getId() + ": MV Alpha, MV Beta; 3 recipients)");
                });

        // Gamma's crew never see it.
        User gina = userRepository.findByEmail("gina@example.com").orElseThrow();
        mockMvc.perform(get("/ship/news/{id}", announcement.getId()).with(user(new CurrentUser(gina))))
                .andExpect(status().isNotFound());
        // Beta's crew see their own vessel as the target, never the other vessels; opening it reads it (P39).
        User bill = userRepository.findByEmail("bill@example.com").orElseThrow();
        assertThat(html(get("/ship/news/{id}", announcement.getId()).with(user(new CurrentUser(bill)))))
                .contains("<p class=\"eyebrow\">MV Beta · IMO 9176187</p>").doesNotContain("MV Alpha", "2 vessels");

        // The list: "2 vessels", with the names as the title and for the phone card.
        assertThat(html(get("/office/announcements")))
                .contains("<span title=\"MV Alpha, MV Beta\">2 vessels</span>",
                        "<span class=\"target-names\">MV Alpha, MV Beta</span>", "1 of 3 read");

        // The detail page: 1 of 3 (Bill opened it), split by vessel, with the total.
        String detail = html(get("/office/announcements/{id}", announcement.getId()));
        assertThat(detail).contains("<p class=\"eyebrow\">2 vessels</p>", "1 of 3 read", "Read by vessel")
                .containsPattern("(?s)id=\"perShip\".*?MV Alpha</a>.*?<td class=\"num text-nowrap\">0 of 2 read</td>"
                        + ".*?MV Beta</a>.*?<td class=\"num text-nowrap\">1 of 1 read</td>"
                        + ".*?Total</th>\\s*<td class=\"num fw-semibold text-nowrap\">1 of 3 read</td>")
                .doesNotContain("MV Gamma");
    }

    // P28: Selected ships with no box ticked, or no target at all, is an error on the target field.
    @Test
    void selectedShipsWithNoShipTickedIsAnError() throws Exception {
        ship("9074729", "MV Alpha");
        String form = html(post("/office/announcements/review")
                .param("target", "ships").param("title", "Drill").param("body", "Friday").with(csrf()));
        assertThat(form).contains("id=\"targetError\"", "Tick at least one vessel.", "aria-describedby=\"targetError\"",
                        "action=\"/office/announcements/review\"")
                .doesNotContain("Review announcement");
        assertThat(html(post("/office/announcements/send")
                .param("target", "ships").param("title", "Drill").param("body", "Friday").with(csrf())))
                .contains("Tick at least one vessel.");
        assertThat(html(post("/office/announcements/review")
                .param("title", "Drill").param("body", "Friday").with(csrf())))
                .contains("Choose all vessels or selected vessels.");
        assertThat(announcementRepository.count()).isZero();
    }

    // P28: a ship deactivated between the form and sending is named on the review page and left out; with none left,
    // sending is blocked.
    @Test
    void aShipDeactivatedBeforeSendingIsLeftOut() throws Exception {
        Ship alpha = ship("9074729", "MV Alpha");
        Ship beta = ship("9176187", "MV Beta");
        crewMember("anna@example.com", "Anna Alpha", alpha);
        crewMember("bill@example.com", "Bill Beta", beta);

        // Deactivated after the form was opened: the review page names it and leaves it out.
        beta.setActive(false);
        shipRepository.flush();
        String review = html(post("/office/announcements/review")
                .param("target", "ships").param("shipIds", alpha.getId().toString(), beta.getId().toString())
                .param("title", "Drill").param("body", "Friday").with(csrf()));
        assertThat(review).contains("1 vessel left out", "MV Beta · IMO 9176187", "Send to 1 person on 1 vessel",
                        "<input type=\"hidden\" name=\"shipIds\" value=\"" + alpha.getId() + "\">")
                .doesNotContain("name=\"shipIds\" value=\"" + beta.getId() + "\"");

        // Deactivated after the review page: nothing is sent, the review page comes back saying so.
        beta.setActive(true);
        shipRepository.flush();
        html(post("/office/announcements/review")
                .param("target", "ships").param("shipIds", alpha.getId().toString(), beta.getId().toString())
                .param("title", "Drill").param("body", "Friday").with(csrf()));
        beta.setActive(false);
        shipRepository.flush();
        String again = html(post("/office/announcements/send")
                .param("target", "ships").param("shipIds", alpha.getId().toString(), beta.getId().toString())
                .param("title", "Drill").param("body", "Friday").with(csrf()));
        assertThat(again).contains("Review announcement", "1 vessel left out", "MV Beta · IMO 9176187",
                "Send to 1 person on 1 vessel");
        assertThat(announcementRepository.count()).isZero();

        // Sent from that page: only the active ship.
        Announcement sent = sendTo(List.of(alpha), "Drill");
        assertThat(sent.getTargetShips()).containsExactly(alpha);
        assertThat(recipientRepository.findAllByAnnouncementOrderByUserFullNameAsc(sent))
                .extracting(r -> r.getUser().getFullName()).containsExactly("Anna Alpha");

        // None left: blocked, back to the form with the error on the target.
        alpha.setActive(false);
        shipRepository.flush();
        assertThat(html(post("/office/announcements/send")
                .param("target", "ships").param("shipIds", alpha.getId().toString())
                .param("title", "Second").param("body", "Friday").with(csrf())))
                .contains("The vessels you ticked are no longer active. Tick at least one active vessel.",
                        "action=\"/office/announcements/review\"");
        assertThat(announcementRepository.count()).isEqualTo(1);
    }

    // P28: the fleet list's ship filter and each ship's Announcements tab show an announcement to all ships and every
    // announcement with that ship among its targets; New announcement on the tab ticks that ship.
    @Test
    void aShipsTabListsTheAnnouncementsItIsATargetOf() throws Exception {
        Ship alpha = ship("9074729", "MV Alpha");
        Ship beta = ship("9176187", "MV Beta");
        Ship gamma = ship("9395044", "MV Gamma");
        send(null, "To the fleet", "All of you.");
        sendTo(List.of(alpha), "Alpha only");
        sendTo(List.of(alpha, beta), "Alpha and Beta");
        sendTo(List.of(gamma), "Gamma only");
        announcementRepository.flush();

        assertThat(html(get("/office/ships/{id}/announcements", alpha.getId())))
                .contains("To the fleet", "Alpha only", "Alpha and Beta").doesNotContain("Gamma only");
        assertThat(html(get("/office/ships/{id}/announcements", beta.getId())))
                .contains("To the fleet", "Alpha and Beta").doesNotContain("Alpha only", "Gamma only");
        assertThat(html(get("/office/announcements").param("shipId", beta.getId().toString())))
                .contains("To the fleet", "Alpha and Beta").doesNotContain("Alpha only", "Gamma only");
        assertThat(announcementRepository.countForShip(alpha.getId())).isEqualTo(3);
        assertThat(announcementRepository.countForShip(beta.getId())).isEqualTo(2);
        assertThat(announcementRepository.countForShip(gamma.getId())).isEqualTo(2);

        // Sorted by target: All ships, then one ship by name, then several ships.
        assertThat(html(get("/office/announcements").param("sort", "target")))
                .containsSubsequence("To the fleet", "Alpha only", "Gamma only", "Alpha and Beta");

        // From the tab: Selected ships with that ship ticked, the others not.
        String form = html(get("/office/announcements/new").param("shipId", beta.getId().toString()));
        assertThat(form).containsPattern("<input type=\"radio\" id=\"targetShips\" name=\"target\"[^>]*checked=\"checked\"")
                .containsPattern("<input type=\"checkbox\" name=\"shipIds\"[^>]*value=\"" + beta.getId() + "\"[^>]*checked=\"checked\"")
                .doesNotContainPattern("<input type=\"radio\" id=\"target\" name=\"target\"[^>]*checked")
                .doesNotContainPattern("name=\"shipIds\"[^>]*value=\"" + alpha.getId() + "\"[^>]*checked");
        // Without it nothing is chosen.
        assertThat(html(get("/office/announcements/new"))).doesNotContainPattern("<input[^>]*checked");
    }

    // Sends an announcement as the office (to one ship, or to all ships when ship is null).
    private Announcement send(Ship ship, String title, String body) throws Exception {
        if (ship != null) {
            return sendTo(List.of(ship), title, body);
        }
        mockMvc.perform(post("/office/announcements/send")
                        .param("target", "fleet").param("title", title).param("body", body).with(csrf()))
                .andExpect(status().is3xxRedirection());
        return announcement(title);
    }

    private Announcement sendTo(List<Ship> ships, String title) throws Exception {
        return sendTo(ships, title, "Please read.");
    }

    // Sends an announcement to these ships (Selected ships) as the office.
    private Announcement sendTo(List<Ship> ships, String title, String body) throws Exception {
        mockMvc.perform(post("/office/announcements/send")
                        .param("target", "ships")
                        .param("shipIds", ships.stream().map(s -> s.getId().toString()).toArray(String[]::new))
                        .param("title", title).param("body", body).with(csrf()))
                .andExpect(status().is3xxRedirection());
        return announcement(title);
    }

    private Announcement announcement(String title) {
        return announcementRepository.findAll().stream().filter(a -> a.getTitle().equals(title)).findFirst()
                .orElseThrow();
    }

    // The sidebar entry and the tab of this section, each from its <li> to its </li>.
    private static List<String> navItems(String html, String section) {
        return java.util.regex.Pattern.compile("(?s)<li [^>]*data-nav=\"" + section + "\".*?</li>").matcher(html)
                .results().map(java.util.regex.MatchResult::group).toList();
    }

    private static int count(String html, String needle) {
        return html.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
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

    private User crewMember(String email, String fullName, Ship ship) {
        return crewMember(email, fullName, ship, Role.CREW_MEMBER);
    }

    private User crewMember(String email, String fullName, Ship ship, Role role) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("not-used");
        user.setFullName(fullName);
        user.setRole(role);
        user.setShip(ship);
        user.setEnabled(true);
        user.setCreatedAt(Instant.now());
        return userRepository.save(user);
    }
}
