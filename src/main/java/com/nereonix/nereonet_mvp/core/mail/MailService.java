package com.nereonix.nereonet_mvp.core.mail;

import java.io.UnsupportedEncodingException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

/**
 * Sends emails from the system address (app.mail.from): plain text, or plain text with an HTML version (P29).
 * <p>
 * P30: the sender has a display name (app.mail.from-name, "Nereonix" by default), so the inbox shows a name rather
 * than a bare address, which mail filters also count against a message. The Message-ID uses the sender's domain
 * (spring.mail.properties.mail.from in application.yml) rather than this computer's name.
 */
@Service
public class MailService {

    /** The Content-ID of the logo in an HTML email: the template shows it as src="cid:nereonix-logo". */
    public static final String LOGO_CID = "nereonix-logo";
    /** The full logo for navy backgrounds (the email's header band), attached to the email, never a remote URL. */
    static final Resource LOGO = new ClassPathResource("static/img/brand/nereonix-logo-on-dark-600.png");

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender mailSender;
    private final InternetAddress from;

    public MailService(JavaMailSender mailSender, @Value("${app.mail.from}") String from,
                       @Value("${app.mail.from-name:Nereonix}") String fromName) {
        this.mailSender = mailSender;
        this.from = sender(from, fromName);
    }

    public void send(String to, String subject, String text) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from.toString());
        message.setTo(to);
        message.setSubject(subject);
        message.setText(text);
        mailSender.send(message);
        // Never the text: it may hold an invitation link.
        log.info("Email sent to {}: {}", to, subject);
    }

    /**
     * The plain text and an HTML version of the same email (HtmlEmails renders it), with the logo as an inline
     * image. MIME structure: multipart/related holding a multipart/alternative (text/plain, then text/html) and the
     * logo (image/png, Content-ID LOGO_CID), so a client shows either version and the HTML finds its image.
     */
    public void send(String to, String subject, String text, String html) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, MimeMessageHelper.MULTIPART_MODE_RELATED, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text, html);
            // After setText, as MimeMessageHelper requires for inline parts.
            helper.addInline(LOGO_CID, LOGO, "image/png");
        } catch (MessagingException e) {
            throw new MailPreparationException(e);
        }
        mailSender.send(message);
        log.info("Email sent to {}: {}", to, subject);
    }

    /** A file sent with an email (P44: the RFQ's workbook). */
    public record Attachment(String filename, String contentType, byte[] content) {
    }

    /**
     * As send(to, subject, text, html), with a Reply-To (the office user a supplier answers, P44) and attachments.
     * MIME structure: multipart/mixed holding the multipart/related of send(...) and the attachments.
     */
    public void send(String to, String replyTo, String subject, String text, String html,
                     List<Attachment> attachments) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED,
                    "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            if (replyTo != null && !replyTo.isBlank()) {
                helper.setReplyTo(replyTo);
            }
            helper.setSubject(subject);
            helper.setText(text, html);
            helper.addInline(LOGO_CID, LOGO, "image/png");
            for (Attachment attachment : attachments) {
                helper.addAttachment(attachment.filename(),
                        new org.springframework.core.io.ByteArrayResource(attachment.content()), attachment.contentType());
            }
        } catch (MessagingException e) {
            throw new MailPreparationException(e);
        }
        mailSender.send(message);
        log.info("Email sent to {}: {}", to, subject);
    }

    /**
     * "Nereonix &lt;noreply@example.com&gt;"; the address alone when the name is blank. A name written in
     * app.mail.from itself ("Crewing &lt;crewing@example.com&gt;") is kept.
     */
    static InternetAddress sender(String address, String name) {
        try {
            InternetAddress sender = new InternetAddress(address, true);
            if (sender.getPersonal() == null && name != null && !name.isBlank()) {
                sender.setPersonal(name.strip(), "UTF-8");
            }
            return sender;
        } catch (AddressException | UnsupportedEncodingException e) {
            throw new IllegalStateException("app.mail.from is not a valid email address", e);
        }
    }
}
