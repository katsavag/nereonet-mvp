package com.nereonix.nereonet_mvp.core.invitation;

import java.util.List;
import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import com.nereonix.nereonet_mvp.core.mail.ActionEmail;
import com.nereonix.nereonet_mvp.core.mail.HtmlEmails;
import com.nereonix.nereonet_mvp.core.time.Dates;
import com.nereonix.nereonet_mvp.core.user.Role;

/**
 * The invitation email (P29): who invites, what the person is joining ("MV Demo as Second Officer", or the office),
 * the button to accept, the link as text and its expiry in UTC. Crew invitations are in English (crew screens are
 * English only); office invitations are in the language of the office user who sends them.
 */
@Component
public class InvitationEmails {

    private final MessageSource messageSource;
    private final HtmlEmails htmlEmails;

    public InvitationEmails(MessageSource messageSource, HtmlEmails htmlEmails) {
        this.messageSource = messageSource;
        this.htmlEmails = htmlEmails;
    }

    /** English for crew; for an office invitation, the sender's language (en or el). */
    public Locale locale(Invitation invitation, Locale senderLocale) {
        return invitation.getRole() == Role.OFFICE_USER ? HtmlEmails.officeLocale(senderLocale) : Locale.ENGLISH;
    }

    /** The plain text, as before P29. */
    public String text(Invitation invitation, String url, int validDays, Locale locale) {
        return message("invitation.email.text", locale, invitation.getFullName(), url, validDays);
    }

    public ActionEmail email(Invitation invitation, String url, Locale locale) {
        String inviter = invitation.getInvitedBy() != null ? invitation.getInvitedBy().getFullName()
                : message("email.invitation.inviterUnknown", locale);
        String joining = invitation.getShip() == null
                ? message("email.invitation.joining.office", locale, inviter)
                : message("email.invitation.joining.ship", locale, inviter, invitation.getShip().getName(),
                        invitation.getRank() != null ? message("rank." + invitation.getRank(), locale)
                                : message("registration.joining.role." + invitation.getRole(), locale));
        String subject = message("invitation.email.subject", locale);
        return new ActionEmail(locale.getLanguage(), subject, joining,
                message("email.invitation.heading", locale),
                List.of(message("email.greeting", locale, invitation.getFullName()), joining,
                        message("email.invitation.next", locale)),
                message("email.invitation.button", locale), url, message("email.linkHint", locale),
                message("email.invitation.expires", locale, Dates.dateTime(invitation.getExpiresAt(), locale)),
                htmlEmails.footer(locale, message("email.invitation.footer.sender", locale, inviter),
                        message("email.invitation.footer.reason", locale, inviter, invitation.getEmail())));
    }

    private String message(String key, Locale locale, Object... args) {
        return messageSource.getMessage(key, args, locale);
    }
}
