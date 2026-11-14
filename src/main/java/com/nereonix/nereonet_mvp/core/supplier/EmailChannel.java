package com.nereonix.nereonet_mvp.core.supplier;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Component;

import com.nereonix.nereonet_mvp.core.export.XlsExport;
import com.nereonix.nereonet_mvp.core.mail.MailService;
import com.nereonix.nereonet_mvp.core.rfq.QuoteStatus;
import com.nereonix.nereonet_mvp.core.rfq.Rfq;
import com.nereonix.nereonet_mvp.core.rfq.RfqQuote;
import com.nereonix.nereonet_mvp.core.rfq.RfqWorkbook;
import com.nereonix.nereonet_mvp.core.user.User;

/**
 * The email channel (P44): the RFQ as an email to the supplier's address, from the system sender with Reply-To the
 * office user, so the supplier's answer lands in that person's inbox; the office then enters it by hand. The request
 * has the lines in its body (HTML and plain text, SupplierEmails) and the same lines as an .xls attachment
 * (RfqWorkbook). Subjects start with the RFQ number ("RFQ #12: ..."), so replies keep it.
 */
@Component
public class EmailChannel implements SupplierChannel {

    private static final Logger log = LoggerFactory.getLogger(EmailChannel.class);

    private final MailService mailService;
    private final SupplierEmails emails;

    public EmailChannel(MailService mailService, SupplierEmails emails) {
        this.mailService = mailService;
        this.emails = emails;
    }

    @Override
    public ChannelType type() {
        return ChannelType.EMAIL;
    }

    @Override
    public void sendRfq(Rfq rfq, Supplier supplier) throws ChannelException {
        SupplierEmails.Email email = emails.request(rfq, supplier);
        XlsExport.File file = RfqWorkbook.of(rfq);
        try {
            mailService.send(supplier.getEmail(), replyTo(rfq.getSentBy()), email.subject(), emails.text(email),
                    emails.html(email), List.of(new MailService.Attachment(file.filename(),
                            XlsExport.CONTENT_TYPE.toString(), file.content())));
        } catch (MailException e) {
            throw new ChannelException(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), e);
        }
    }

    @Override
    public void cancel(Rfq rfq) {
        for (RfqQuote quote : rfq.getQuotes()) {
            if (quote.getSupplier().getChannel() == type()
                    && (quote.getStatus() == QuoteStatus.SENT || quote.getStatus() == QuoteStatus.QUOTED)) {
                bestEffort(rfq, quote.getSupplier(), emails.withdrawn(rfq, quote.getSupplier()));
            }
        }
    }

    @Override
    public void notSelected(Rfq rfq, Supplier supplier) {
        bestEffort(rfq, supplier, emails.notSelected(rfq, supplier));
    }

    private void bestEffort(Rfq rfq, Supplier supplier, SupplierEmails.Email email) {
        try {
            mailService.send(supplier.getEmail(), replyTo(rfq.getClosedBy()), email.subject(), emails.text(email),
                    emails.html(email), List.of());
        } catch (MailException e) {
            log.warn("Could not email {} about {}: {}", supplier.getEmail(), rfq.number(), e.getMessage());
        }
    }

    private static String replyTo(User user) {
        return user == null ? null : user.getEmail();
    }
}
