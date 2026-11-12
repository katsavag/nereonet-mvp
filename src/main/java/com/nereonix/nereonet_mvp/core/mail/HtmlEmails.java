package com.nereonix.nereonet_mvp.core.mail;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

/**
 * The HTML version of an ActionEmail (P29), from the Thymeleaf template templates/email/action.html: a table layout
 * at most 600px wide with inline CSS only, the navy header band with the logo and "Nereonix" as text, the white card
 * with one navy button, the link again as text, and the footer. MailService sends it next to the plain text.
 */
@Component
public class HtmlEmails {

    private final ITemplateEngine templateEngine;
    private final MessageSource messageSource;
    private final String supportContact;

    public HtmlEmails(ITemplateEngine templateEngine, MessageSource messageSource,
                      @Value("${app.support-contact:}") String supportContact) {
        this.templateEngine = templateEngine;
        this.messageSource = messageSource;
        this.supportContact = supportContact == null || supportContact.isBlank() ? null : supportContact.strip();
    }

    /** The email as sent: the logo is the inline image MailService attaches. */
    public String render(ActionEmail email) {
        return render(email, "cid:" + MailService.LOGO_CID);
    }

    /** The email with the logo at logoSrc: the dev preview (DevEmailPreviewController) uses the app's own URL. */
    public String render(ActionEmail email, String logoSrc) {
        Context context = new Context(Locale.forLanguageTag(email.language()));
        context.setVariable("email", email);
        context.setVariable("logoSrc", logoSrc);
        return templateEngine.process("email/action", context);
    }

    /** The footer lines given, then "Questions? {support contact}" when app.support-contact is set. */
    public List<String> footer(Locale locale, String... lines) {
        List<String> footer = new ArrayList<>(List.of(lines));
        if (supportContact != null) {
            footer.add(messageSource.getMessage("email.footer.support", new Object[] {supportContact}, locale));
        }
        return footer;
    }

    /** "en" or "el": the language an email to an office user follows. */
    public static Locale officeLocale(Locale locale) {
        return locale != null && "el".equals(locale.getLanguage()) ? Locale.forLanguageTag("el") : Locale.ENGLISH;
    }
}
