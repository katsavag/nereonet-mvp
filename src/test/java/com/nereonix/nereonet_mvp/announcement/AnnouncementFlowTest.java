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
                        .param("target", ship.getId().toString())
                        .param("title", "Safety drill")
                        .param("body", "Drill on Friday at 10:00.")
                        .with(csrf()))
                .andExpect(redirectedUrl("/office/announcements/" + announcementRepository.findAll().getFirst().getId()));

        Announcement announcement = announcementRepository.findAll().getFirst();
        assertThat(announcement.getShip()).isEqualTo(ship);
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
        assertThat(list).contains("Safety drill").contains("1 of 2 acknowledged");
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
        assertThat(review).contains("<h1 class=\"page-title\">", "Review announcement", "All ships",
                "action=\"/office/announcements/send\"",
                "<button type=\"submit\" class=\"btn btn-primary\">Send to 3 people</button>",
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
                "<option value=\"fleet\" selected=\"selected\">All ships</option>", "value=\"Safety drill\"",
                ">Drill on Friday.</textarea>");

        // Someone joins after the review: the recipients are counted again when it is sent.
        crewMember("lena@example.com", "Lena Later", other);
        MvcResult sent = mockMvc.perform(post("/office/announcements/send")
                        .param("target", "fleet").param("title", "Safety drill").param("body", "Drill on Friday.")
                        .with(csrf()))
                .andExpect(flash().attribute("successMessage", "announcement.created"))
                .andReturn();
        Announcement announcement = announcementRepository.findAll().getFirst();
        assertThat(sent.getResponse().getRedirectedUrl()).isEqualTo("/office/announcements/" + announcement.getId());
        assertThat((Object[]) sent.getFlashMap().get("successArgs")).containsExactly(4);
        assertThat(recipientRepository.findAllByAnnouncementOrderByUserFullNameAsc(announcement)).hasSize(4);
        assertThat(messageSource.getMessage("announcement.created", new Object[] {4}, Locale.ENGLISH))
                .isEqualTo("Announcement sent to 4 people.");
        assertThat(messageSource.getMessage("announcement.review.send", new Object[] {1}, Locale.ENGLISH))
                .isEqualTo("Send to 1 person");
    }

    @Test
    void reviewOfOneShipAndAShipDeactivatedBeforeSending() throws Exception {
        Ship demo = ship("9074729", "MV Demo");
        crewMember("anna@example.com", "Anna Acked", demo);

        String review = html(post("/office/announcements/review")
                .param("target", demo.getId().toString()).param("title", "Drill").param("body", "Friday")
                .with(csrf()));
        assertThat(review).contains("<p class=\"eyebrow\">MV Demo · IMO 9074729</p>", "Send to 1 person")
                .doesNotContain("Total</th>", "Nobody would receive it");

        // Deactivated meanwhile: back to the form with an error on the target, nothing sent.
        demo.setActive(false);
        shipRepository.flush();
        String form = html(post("/office/announcements/send")
                .param("target", demo.getId().toString()).param("title", "Drill").param("body", "Friday")
                .with(csrf()));
        assertThat(form).contains("Choose all ships or one ship.");
        assertThat(announcementRepository.count()).isZero();

        // Nobody on board: the review page says so.
        Ship empty = ship("9176187", "MV Empty");
        assertThat(html(post("/office/announcements/review")
                .param("target", empty.getId().toString()).param("title", "Drill").param("body", "Friday")
                .with(csrf()))).contains("Nobody would receive it", "Send to 0 people");
    }

    // P16, FR-GEN-008: the form and the review page say it is no emergency channel, right above their buttons.
    @Test
    void formAndReviewSayItIsNotAnEmergencyChannel() throws Exception {
        String notice = "Nereonix is not an emergency or safety channel. In an emergency, follow the ship&#39;s "
                + "safety procedures.";
        assertThat(html(get("/office/announcements/new")))
                .containsPattern("(?s)<p class=\"info-note\">\\s*<svg.*?</svg>\\s*<span>" + notice + "</span>\\s*</p>"
                        + "\\s*<div class=\"form-actions\">\\s*<button type=\"submit\" class=\"btn btn-primary\">Review");
        assertThat(html(get("/office/announcements/new").param("lang", "el")))
                .contains("Το Nereonix δεν είναι κανάλι έκτακτης ανάγκης ή ασφάλειας.");

        Ship demo = ship("9074729", "MV Demo");
        String review = html(post("/office/announcements/review")
                .param("target", demo.getId().toString()).param("title", "Drill").param("body", "Friday")
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

    // P27: sending notifies each recipient; the crew list their own announcements newest first, open one, acknowledge
    // it once (recorded and audited); pressing again changes nothing; the office then sees who acknowledged.
    @Test
    void crewListOpenAndAcknowledgeTheirAnnouncements() throws Exception {
        Ship demo = ship("9074729", "MV Demo");
        User cook = crewMember("cook@example.com", "Demo Cook", demo);
        crewMember("anna@example.com", "Anna Testa", demo);

        Announcement drill = send(demo, "Safety drill", "Drill on Friday.\r\nMuster at <10:00>.");
        Announcement galley = send(null, "Galley inspection", "Next week.");
        announcementRepository.flush();

        // Each recipient gets a notification that opens the announcement.
        Notification notification = notificationRepository.findAllByUserEmailOrderByCreatedAtDescIdDesc("cook@example.com")
                .stream().filter(n -> n.getLink().equals("/ship/announcements/" + drill.getId())).findFirst().orElseThrow();
        assertThat(notification.getType()).isEqualTo(NotificationType.ANNOUNCEMENT);
        assertThat(notification.getText()).isEqualTo("Announcement · Safety drill");
        assertThat(notification.getSubject()).isEqualTo("MV Demo");
        assertThat(notification.getReadAt()).isNull();
        assertThat(notificationRepository.countByUserEmailAndReadAtIsNull("anna@example.com")).isEqualTo(2);

        // The list: newest first, both to acknowledge (bold title and the badge's words).
        String list = html(get("/ship/announcements").with(user(new CurrentUser(cook))));
        assertThat(list).contains("<title>Announcements · Nereonet</title>", "<p class=\"eyebrow\">MV Demo · IMO 9074729</p>")
                .containsSubsequence("href=\"/ship/announcements/" + galley.getId() + "\"", "Galley inspection",
                        "To acknowledge", "href=\"/ship/announcements/" + drill.getId() + "\"", "Safety drill",
                        "To acknowledge");
        assertThat(count(list, "announcement-row is-unread")).isEqualTo(2);
        // Announcements is the active tab (and sidebar entry).
        assertThat(list).contains("<a class=\"tab-bar-link active\" href=\"/ship/announcements\" aria-current=\"page\">");

        // The detail: sender, time in UTC, the text with its line breaks (escaped), I have read this. Nobody else.
        String office = userRepository.findByEmail("office@example.com").orElseThrow().getFullName();
        String detail = html(get("/ship/announcements/{id}", drill.getId()).with(user(new CurrentUser(cook))));
        assertThat(detail).contains("<h1 class=\"page-title\">", "Safety drill", "Office · " + office, " UTC</time>",
                        "Drill on Friday.\nMuster at &lt;10:00&gt;.",
                        "<div class=\"ack-bar ack-bar-full is-pinned\">",
                        "action=\"/ship/announcements/" + drill.getId() + "/acknowledge\"",
                        ">I have read this</button>", "<p class=\"eyebrow\">MV Demo · IMO 9074729</p>")
                .doesNotContain("Anna Testa", "Acknowledged ·", "of 2 acknowledged");

        mockMvc.perform(post("/ship/announcements/{id}/acknowledge", drill.getId())
                        .with(user(new CurrentUser(cook))).with(csrf()))
                .andExpect(redirectedUrl("/ship/announcements/" + drill.getId()))
                .andExpect(flash().attribute("successMessage", "announcement.ship.acknowledgedMessage"));
        AnnouncementRecipient mine = recipientRepository.findByAnnouncementIdAndUserEmail(drill.getId(), "cook@example.com")
                .orElseThrow();
        Instant acknowledgedAt = mine.getAcknowledgedAt();
        assertThat(acknowledgedAt).isNotNull();
        assertThat(auditRepository.findAll()).filteredOn(e -> e.getAction().equals("ANNOUNCEMENT_ACKNOWLEDGED"))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getActor().getEmail()).isEqualTo("cook@example.com");
                    assertThat(e.getShip().getId()).isEqualTo(demo.getId());
                    assertThat(e.getDetails()).isEqualTo("\"Safety drill\"");
                });

        // The button is replaced by the acknowledgement and its time.
        String after = html(get("/ship/announcements/{id}", drill.getId()).with(user(new CurrentUser(cook))));
        assertThat(after).containsPattern("(?s)<p class=\"alert alert-success mb-0\">\\s*<span>Acknowledged ·</span>\\s*"
                        + "<time class=\"local-time text-nowrap\"[^>]*>[^<]+ UTC</time>")
                .contains("<div class=\"ack-bar ack-bar-full\">")
                .doesNotContain("I have read this", "/acknowledge\"");

        // Pressing again (a second tab, a resend) changes nothing: same time, no new audit entry, no message.
        mockMvc.perform(post("/ship/announcements/{id}/acknowledge", drill.getId())
                        .with(user(new CurrentUser(cook))).with(csrf()))
                .andExpect(redirectedUrl("/ship/announcements/" + drill.getId()))
                .andExpect(flash().attributeCount(0));
        recipientRepository.flush();
        assertThat(recipientRepository.findByAnnouncementIdAndUserEmail(drill.getId(), "cook@example.com")
                .orElseThrow().getAcknowledgedAt()).isEqualTo(acknowledgedAt);
        assertThat(auditRepository.findAll()).filteredOn(e -> e.getAction().equals("ANNOUNCEMENT_ACKNOWLEDGED")).hasSize(1);

        // The list: the drill now acknowledged (regular title), the other still to acknowledge.
        String listAfter = html(get("/ship/announcements").with(user(new CurrentUser(cook))));
        assertThat(count(listAfter, "announcement-row is-unread")).isEqualTo(1);
        assertThat(listAfter).containsSubsequence("Galley inspection", "To acknowledge", "Safety drill", "Acknowledged");

        // The office sees it: 1 of 2, the cook acknowledged, Anna not yet.
        String officeDetail = html(get("/office/announcements/{id}", drill.getId()));
        assertThat(officeDetail).contains("1 of 2 acknowledged");
        int acknowledged = officeDetail.indexOf("id=\"acknowledged\"");
        int pending = officeDetail.indexOf("id=\"pending\"");
        assertThat(officeDetail.substring(acknowledged, pending)).contains("Demo Cook").doesNotContain("Anna Testa");
        assertThat(html(get("/office/audit"))).contains("Announcement acknowledged");
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
            mockMvc.perform(get("/ship/announcements/{id}", drill.getId()).with(user(new CurrentUser(u))))
                    .andExpect(status().isNotFound());
            mockMvc.perform(post("/ship/announcements/{id}/acknowledge", drill.getId())
                            .with(user(new CurrentUser(u))).with(csrf()))
                    .andExpect(status().isNotFound());
            assertThat(html(get("/ship/announcements").with(user(new CurrentUser(u)))))
                    .doesNotContain("Safety drill")
                    .contains("No announcements yet", "Messages from the office to you appear here.");
        }
        mockMvc.perform(get("/ship/announcements/{id}", drill.getId() + 1000).with(user(new CurrentUser(olga))))
                .andExpect(status().isNotFound());
        assertThat(recipientRepository.findAllByAnnouncementOrderByUserFullNameAsc(drill))
                .allSatisfy(r -> assertThat(r.getAcknowledgedAt()).isNull());
        assertThat(auditRepository.findAll()).noneMatch(e -> e.getAction().equals("ANNOUNCEMENT_ACKNOWLEDGED"));
        // The crew pages are for ship roles only.
        mockMvc.perform(get("/ship/announcements")).andExpect(status().isForbidden());
    }

    // Sends an announcement as the office (to one ship, or to all ships when ship is null).
    private Announcement send(Ship ship, String title, String body) throws Exception {
        mockMvc.perform(post("/office/announcements/send")
                        .param("target", ship == null ? "fleet" : ship.getId().toString())
                        .param("title", title).param("body", body).with(csrf()))
                .andExpect(status().is3xxRedirection());
        return announcementRepository.findAll().stream().filter(a -> a.getTitle().equals(title)).findFirst()
                .orElseThrow();
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
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("not-used");
        user.setFullName(fullName);
        user.setRole(Role.CREW_MEMBER);
        user.setShip(ship);
        user.setEnabled(true);
        user.setCreatedAt(Instant.now());
        return userRepository.save(user);
    }
}
