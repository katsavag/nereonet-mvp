package com.nereonix.nereonet_mvp.core.passwordreset;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import com.nereonix.nereonet_mvp.core.mail.ActionEmail;
import com.nereonix.nereonet_mvp.core.mail.HtmlEmails;
import com.nereonix.nereonet_mvp.core.time.Dates;

/**
 * The password reset email (P14), with the HTML version of P29 in the invitation's template: the button to set a new
 * password, the link as text, when it stops working (UTC), and why the person got it. In the language of the page the
 * link was asked for on.
 */
@Component
public class PasswordResetEmails {

    private final MessageSource messageSource;
    private final HtmlEmails htmlEmails;

    public PasswordResetEmails(MessageSource messageSource, HtmlEmails htmlEmails) {
        this.messageSource = messageSource;
        this.htmlEmails = htmlEmails;
    }

    /** The plain text, as before P29. */
    public String text(String fullName, String url, Locale locale) {
        return message("passwordReset.email.text", locale, fullName, url, PasswordResetService.VALID_FOR.toMinutes());
    }

    public ActionEmail email(String fullName, String email, String url, Instant expiresAt, Locale locale) {
        String intro = message("email.passwordReset.intro", locale);
        return new ActionEmail(locale.getLanguage(), message("passwordReset.email.subject", locale), intro,
                message("email.passwordReset.heading", locale),
                List.of(message("email.greeting", locale, fullName), intro),
                message("email.passwordReset.button", locale), url, message("email.linkHint", locale),
                message("email.passwordReset.expires", locale, Dates.dateTime(expiresAt, locale)),
                htmlEmails.footer(locale, message("email.passwordReset.footer.sender", locale),
                        message("email.passwordReset.footer.reason", locale, email)));
    }

    private String message(String key, Locale locale, Object... args) {
        return messageSource.getMessage(key, args, locale);
    }
}
