package com.nereonix.nereonet_mvp.announcement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
