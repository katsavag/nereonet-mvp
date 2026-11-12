package com.nereonix.nereonet_mvp.core.mail;

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
import jakarta.mail.internet.MimeMessage;

// Sends emails from the system address (app.mail.from): plain text, or plain text with an HTML version (P29).
@Service
public class MailService {

    /** The Content-ID of the logo in an HTML email: the template shows it as src="cid:nereonix-logo". */
    public static final String LOGO_CID = "nereonix-logo";
    /** The full logo for navy backgrounds (the email's header band), attached to the email, never a remote URL. */
    static final Resource LOGO = new ClassPathResource("static/img/brand/nereonix-logo-on-dark-600.png");

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender mailSender;
    private final String from;

    public MailService(JavaMailSender mailSender, @Value("${app.mail.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    public void send(String to, String subject, String text) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
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
}
