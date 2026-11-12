package com.nereonix.nereonet_mvp.dev;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import org.springframework.context.annotation.Profile;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.nereonix.nereonet_mvp.core.invitation.Invitation;
import com.nereonix.nereonet_mvp.core.invitation.InvitationEmails;
import com.nereonix.nereonet_mvp.core.mail.HtmlEmails;
import com.nereonix.nereonet_mvp.core.passwordreset.PasswordResetEmails;
import com.nereonix.nereonet_mvp.core.passwordreset.PasswordResetService;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;

/**
 * Dev profile only (P29): the HTML emails with sample data, as a browser shows them, to check the template without
 * sending anything. The logo comes from the app instead of the email's inline attachment.
 *   /dev/email/invitation              a crew invitation (English)
 *   /dev/email/invitation?office       an office invitation, in the page language (add lang=el for Greek)
 *   /dev/email/password-reset          a password reset, in the page language
 */
@Controller
@Profile("dev")
public class DevEmailPreviewController {

    private static final String LOGO = "/img/brand/nereonix-logo-on-dark-600.png";
    private static final String SAMPLE_URL = "http://localhost:8080/register?token=sample";

    private final InvitationEmails invitationEmails;
    private final PasswordResetEmails resetEmails;
    private final HtmlEmails htmlEmails;

    public DevEmailPreviewController(InvitationEmails invitationEmails, PasswordResetEmails resetEmails,
                                     HtmlEmails htmlEmails) {
        this.invitationEmails = invitationEmails;
        this.resetEmails = resetEmails;
        this.htmlEmails = htmlEmails;
    }

    @GetMapping(value = "/dev/email/invitation", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String invitation(@RequestParam(required = false) String office) {
        User inviter = new User();
        inviter.setFullName("Maria Papadopoulou");
        Invitation invitation = new Invitation();
        invitation.setInvitedBy(inviter);
        invitation.setExpiresAt(Instant.now().plus(Duration.ofDays(7)));
        if (office != null) {
            invitation.setEmail("nikos.georgiou@example.com");
            invitation.setFullName("Nikos Georgiou");
            invitation.setRole(Role.OFFICE_USER);
        } else {
            Ship ship = new Ship();
            ship.setName("MV Aegean Star");
            invitation.setEmail("jane.doe@example.com");
            invitation.setFullName("Jane Doe");
            invitation.setRole(Role.CREW_MEMBER);
            invitation.setShip(ship);
            invitation.setRank(Rank.SECOND_OFFICER);
        }
        Locale locale = invitationEmails.locale(invitation, LocaleContextHolder.getLocale());
        return htmlEmails.render(invitationEmails.email(invitation, SAMPLE_URL, locale), LOGO);
    }

    @GetMapping(value = "/dev/email/password-reset", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String passwordReset() {
        Locale locale = HtmlEmails.officeLocale(LocaleContextHolder.getLocale());
        return htmlEmails.render(resetEmails.email("Jane Doe", "jane.doe@example.com",
                "http://localhost:8080/password/reset?token=sample",
                Instant.now().plus(PasswordResetService.VALID_FOR), locale), LOGO);
    }
}
