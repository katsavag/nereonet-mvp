package com.nereonix.nereonet_mvp.core.supplier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import com.nereonix.nereonet_mvp.core.mail.HtmlEmails;
import com.nereonix.nereonet_mvp.core.mail.MailService;
import com.nereonix.nereonet_mvp.core.rfq.Money;
import com.nereonix.nereonet_mvp.core.rfq.Rfq;
import com.nereonix.nereonet_mvp.core.rfq.RfqLine;
import com.nereonix.nereonet_mvp.core.rfq.RfqWorkbook;
import com.nereonix.nereonet_mvp.core.time.Dates;
import com.nereonix.nereonet_mvp.core.user.User;

/**
 * The emails to suppliers (P44), always in English (a supplier is outside the company and has no language setting):
 * the request for quotation, "not selected" and "withdrawn". Each has a plain-text part and an HTML part from
 * templates/email/supplier.html (the layout of the P29/P36 emails: navy band with the logo, white card, footer;
 * inline styles only), with the RFQ's lines as a table in the request. The texts are here rather than in
 * messages.properties because they are never translated.
 */
@Component
public class SupplierEmails {

    /** One line of the request's table, every value as shown. */
    public record Line(String category, String item, String unit, String quantity) {
    }

    /**
     * What an email says; the HTML and the plain text are made from it.
     *   lines       the request's items (empty for the short emails); afterLines the paragraphs under the table
     */
    public record Email(String subject, String preheader, String heading, List<String> paragraphs, List<Line> lines,
                        List<String> afterLines, List<String> footer) {
    }

    private final ITemplateEngine templateEngine;
    private final HtmlEmails htmlEmails;

    public SupplierEmails(ITemplateEngine templateEngine, HtmlEmails htmlEmails) {
        this.templateEngine = templateEngine;
        this.htmlEmails = htmlEmails;
    }

    /** The request for quotation: where, when, by when to answer, the items, what to give per item. */
    public Email request(Rfq rfq, Supplier supplier) {
        String vessel = vessel(rfq);
        String delivery = date(rfq.getDeliveryDate());
        String replyBy = date(rfq.getReplyBy());
        List<Line> lines = new ArrayList<>();
        for (RfqLine line : RfqWorkbook.byCategoryAndName(rfq.getLines())) {
            lines.add(new Line(line.getCategory().fileValue(), line.getItemName(), line.getUnit(),
                    Money.quantity(line.getQuantity(), Locale.ENGLISH)));
        }
        String file = RfqWorkbook.of(rfq).filename();
        return new Email(
                rfq.number() + ": request for quotation, " + rfq.getOrder().getShip().getName() + " at " + rfq.getPort(),
                "Please quote for " + lines.size() + (lines.size() == 1 ? " item" : " items") + " for delivery at "
                        + rfq.getPort() + " by " + delivery + ". Reply by " + replyBy + ".",
                "Request for quotation " + rfq.number(),
                List.of("Dear " + supplier.getName() + ",",
                        "Please quote your best prices for the items below, for delivery to " + vessel + " at "
                                + rfq.getPort() + " by " + delivery + ".",
                        "Please reply by " + replyBy + ", by answering this email."),
                lines,
                List.of("For each item, please give the unit price, the quantity you offer, the pack size and unit "
                                + "(for example 5 L) and any note. Please also give the delivery cost, your delivery "
                                + "date and how long your prices are valid. Prices in " + supplier.getCurrency()
                                + " unless you say otherwise.",
                        "The same list is attached as a spreadsheet (" + file + "): you can fill in your prices "
                                + "there, or send your own quotation."),
                footer(rfq, rfq.getSentBy()));
    }

    /** Another supplier's quote was chosen. */
    public Email notSelected(Rfq rfq, Supplier supplier) {
        return new Email(rfq.number() + ": not selected",
                "Our request " + rfq.number() + " is closed.",
                "Thank you for your time",
                List.of("Dear " + supplier.getName() + ",",
                        "Our request " + rfq.number() + " for " + vessel(rfq) + " at " + rfq.getPort()
                                + " is now closed. We have chosen another offer this time.",
                        "Thank you for your time; we look forward to your offers on future requests."),
                List.of(), List.of(), footer(rfq, rfq.getClosedBy()));
    }

    /** The office withdrew the request. */
    public Email withdrawn(Rfq rfq, Supplier supplier) {
        return new Email(rfq.number() + ": request withdrawn",
                "Our request " + rfq.number() + " no longer stands.",
                "Request withdrawn",
                List.of("Dear " + supplier.getName() + ",",
                        "We have withdrawn our request " + rfq.number() + " for " + vessel(rfq) + " at "
                                + rfq.getPort() + ". Please do not send a quotation for it, and disregard it if you "
                                + "already have.",
                        "Thank you for your time."),
                List.of(), List.of(), footer(rfq, rfq.getClosedBy()));
    }

    /** The HTML part, the logo as the inline image MailService attaches. */
    public String html(Email email) {
        return html(email, "cid:" + MailService.LOGO_CID);
    }

    /** The HTML part with the logo at logoSrc (the dev preview). */
    public String html(Email email, String logoSrc) {
        Context context = new Context(Locale.ENGLISH);
        context.setVariable("email", email);
        context.setVariable("logoSrc", logoSrc);
        return templateEngine.process("email/supplier", context);
    }

    /** The plain-text part: the same paragraphs, the table as one line per item. */
    public String text(Email email) {
        StringBuilder text = new StringBuilder();
        email.paragraphs().forEach(p -> text.append(p).append("\n\n"));
        String category = null;
        for (Line line : email.lines()) {
            if (!line.category().equals(category)) {
                category = line.category();
                text.append(category).append(":\n");
            }
            text.append("- ").append(line.item()).append(": ").append(line.quantity()).append(' ')
                    .append(line.unit()).append('\n');
        }
        if (!email.lines().isEmpty()) {
            text.append('\n');
        }
        email.afterLines().forEach(p -> text.append(p).append("\n\n"));
        text.append("--\n");
        email.footer().forEach(line -> text.append(line).append('\n'));
        return text.toString();
    }

    // "the vessel MV Demo (IMO 9074729)"
    private static String vessel(Rfq rfq) {
        return "the vessel " + rfq.getOrder().getShip().getName() + " (IMO " + rfq.getOrder().getShip().getImoNumber()
                + ")";
    }

    private List<String> footer(Rfq rfq, User officeUser) {
        String sender = officeUser == null ? "Sent by Nereonix on behalf of the office."
                : "Sent by Nereonix on behalf of " + officeUser.getFullName() + ". Replies go to "
                        + officeUser.getEmail() + ".";
        return htmlEmails.footer(Locale.ENGLISH, sender, "Reference: " + rfq.number() + ".");
    }

    private static String date(java.time.LocalDate date) {
        return java.time.format.DateTimeFormatter.ofPattern(Dates.DATE_PATTERN, Locale.ENGLISH).format(date);
    }
}
