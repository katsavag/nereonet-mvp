package com.nereonix.nereonet_mvp.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;

import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;

/**
 * P29: the invitation email as it leaves the app, parsed back from its bytes: plain text and HTML as two alternative
 * parts, the logo as an inline image the HTML refers to by Content-ID (never a remote URL), the accept link in both
 * parts, and the texts the HTML promises (who invites, to what, the button, the expiry in UTC, the footer). Crew
 * invitations are in English; office invitations follow the language of the office user who sends them.
 */
@SpringBootTest(properties = "app.support-contact=Crewing department, crewing@example.com")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData as "John Doe"
class InvitationEmailTest {

    private static final Pattern LINK = Pattern.compile("http://localhost:8080/register\\?token=[\\w.-]+");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ShipRepository shipRepository;

    @Autowired
    Environment environment;

    @MockitoBean
    JavaMailSender mailSender;

    Ship ship;

    @BeforeEach
    void setUp() {
        when(mailSender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage((Session) null));
        ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Aegean Star");
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);
    }

    @Test
    void aCrewInvitationHasTextAndHtmlAndTheLogoInline() throws Exception {
        mockMvc.perform(post("/office/ships/{id}/crew/new", ship.getId())
                        .param("fullName", "Jane Doe").param("email", "jane@example.com")
                        .param("rank", "SECOND_OFFICER").param("shipId", ship.getId().toString())
                        // The office works in Greek; the crew email stays English.
                        .param("lang", "el")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        Email email = sent();
        assertThat(email.subject).isEqualTo("You are invited to Nereonet");
        // P30: a sender name, not a bare address; the Message-ID on the sender's domain (session property mail.from).
        assertThat(email.from).isEqualTo("Nereonix <noreply@nereonet.local>");
        assertThat(environment.getProperty("spring.mail.properties.mail.from")).isEqualTo("noreply@nereonet.local");
        assertThat(email.root).startsWith("multipart/related");
        assertThat(email.alternative).startsWith("multipart/alternative");
        assertThat(email.alternativeParts).as("two alternative parts: text, then HTML").hasSize(2);
        assertThat(email.alternativeParts.get(0)).startsWith("text/plain");
        assertThat(email.alternativeParts.get(1)).startsWith("text/html");

        // The logo: an inline PNG with a Content-ID the HTML refers to; no remote image anywhere.
        assertThat(email.logoContentType).startsWith("image/png");
        assertThat(email.logoContentId).isEqualTo("<nereonix-logo>");
        assertThat(email.logoDisposition).isEqualTo(Part.INLINE);
        assertThat(email.logoBytes).isGreaterThan(1000);
        assertThat(email.html).contains("src=\"cid:nereonix-logo\"", "width=\"80\" height=\"56\" alt=\"\"")
                .doesNotContain("src=\"http")
                // P30: the preheader is hidden with display:none only, no text in the background colour or 1px high.
                .doesNotContain("font-size:1px", "opacity:0", "max-height:0;overflow:hidden;opacity");

        // The accept link in both parts, the same link.
        Matcher inText = LINK.matcher(email.text);
        assertThat(inText.find()).as("link in the text").isTrue();
        String link = inText.group();
        assertThat(email.html).contains("href=\"" + link + "\"", ">" + link + "</a>");

        // What the HTML says: brand as text, who invites to what, one button, the expiry in UTC, the footer.
        assertThat(email.html).contains("<html lang=\"en\">", ">Nereonix</td>", "Hello Jane Doe,",
                        "John Doe invited you to join MV Aegean Star as Second Officer.", ">Accept invitation</a>",
                        "If the button does not work, copy this link into your browser:",
                        "Sent by Nereonix on behalf of John Doe.", "You got this email because John Doe invited jane@example.com.",
                        "Questions? Contact: Crewing department, crewing@example.com")
                .containsPattern("The invitation expires on \\d{1,2} \\w{3} \\d{4} · \\d{2}:\\d{2} UTC\\.")
                // Dark-mode and image-blocking safe: every text has its own colour on its own background.
                .contains("bgcolor=\"#10223a\"", "color:#ffffff", "bgcolor=\"#ffffff\"", "bgcolor=\"#f4f1ea\"",
                        "<meta name=\"color-scheme\" content=\"light\">", "max-width:600px",
                        "font-family:Inter,", "padding:12px 28px;font-size:16px;line-height:20px");
        assertThat(email.text).startsWith("Hello Jane Doe,").contains("The link is valid for 7 days.");
    }

    @Test
    void anOfficeInvitationFollowsTheLanguageOfTheSender() throws Exception {
        mockMvc.perform(post("/office/invitations").param("fullName", "Nikos Georgiou")
                        .param("email", "nikos@example.com").param("lang", "el").with(csrf()))
                .andExpect(status().is3xxRedirection());

        Email email = sent();
        assertThat(email.subject).isEqualTo("Πρόσκληση στο Nereonet");
        assertThat(email.alternativeParts).hasSize(2);
        assertThat(email.text).startsWith("Γεια σας Nikos Georgiou,").contains("Ο σύνδεσμος ισχύει για 7 ημέρες.");
        Matcher inText = LINK.matcher(email.text);
        assertThat(inText.find()).isTrue();
        assertThat(email.html).contains("<html lang=\"el\">", "href=\"" + inText.group() + "\"",
                "Πρόσκληση από John Doe να ενταχθείτε στο γραφείο.", ">Αποδοχή πρόσκλησης</a>",
                "Ερωτήσεις; Επικοινωνία: Crewing department, crewing@example.com");

        // In English when the sender works in English.
        org.mockito.Mockito.clearInvocations(mailSender);
        mockMvc.perform(post("/office/invitations").param("fullName", "Eva Office")
                        .param("email", "eva@example.com").param("lang", "en").with(csrf()))
                .andExpect(status().is3xxRedirection());
        Email english = sent();
        assertThat(english.subject).isEqualTo("You are invited to Nereonet");
        assertThat(english.html).contains("John Doe invited you to join the office.");
    }

    /** The one email sent, written to bytes and parsed back, as a mail client receives it. */
    private Email sent() throws Exception {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage built = captor.getValue();
        built.saveChanges();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        built.writeTo(bytes);
        MimeMessage message = new MimeMessage(null, new ByteArrayInputStream(bytes.toByteArray()));

        Email email = new Email();
        email.from = MimeUtility.decodeText(message.getHeader("From", null));
        email.subject = MimeUtility.decodeText(message.getHeader("Subject", null));
        email.root = message.getContentType();
        Multipart related = (Multipart) message.getContent();
        for (int i = 0; i < related.getCount(); i++) {
            BodyPart part = related.getBodyPart(i);
            if (part.isMimeType("multipart/alternative")) {
                email.alternative = part.getContentType();
                Multipart alternative = (Multipart) part.getContent();
                for (int j = 0; j < alternative.getCount(); j++) {
                    BodyPart body = alternative.getBodyPart(j);
                    email.alternativeParts.add(body.getContentType());
                    if (body.isMimeType("text/plain")) {
                        email.text = (String) body.getContent();
                    } else if (body.isMimeType("text/html")) {
                        email.html = (String) body.getContent();
                    }
                }
            } else if (part.isMimeType("image/*")) {
                email.logoContentType = part.getContentType();
                email.logoContentId = part.getHeader("Content-ID")[0];
                email.logoDisposition = part.getDisposition();
                try (InputStream in = part.getInputStream()) {
                    email.logoBytes = in.readAllBytes().length;
                }
            }
        }
        assertThat(email.text).as("text part").isNotNull();
        assertThat(email.html).as("HTML part").isNotNull();
        return email;
    }

    private static final class Email {
        String from;
        String subject;
        String root;
        String alternative;
        final List<String> alternativeParts = new ArrayList<>();
        String text;
        String html;
        String logoContentType;
        String logoContentId;
        String logoDisposition;
        int logoBytes;
    }
}
