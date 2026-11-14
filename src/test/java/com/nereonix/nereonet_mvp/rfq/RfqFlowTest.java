package com.nereonix.nereonet_mvp.rfq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mail.MailSendException;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.XlsSheet;
import com.nereonix.nereonet_mvp.core.audit.AuditEntry;
import com.nereonix.nereonet_mvp.core.audit.AuditRepository;
import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemCategory;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.mail.MailService;
import com.nereonix.nereonet_mvp.core.order.ShipOrder;
import com.nereonix.nereonet_mvp.core.order.ShipOrderLine;
import com.nereonix.nereonet_mvp.core.order.ShipOrderLineRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrderRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrderStatus;
import com.nereonix.nereonet_mvp.core.rfq.QuoteStatus;
import com.nereonix.nereonet_mvp.core.rfq.Rfq;
import com.nereonix.nereonet_mvp.core.rfq.RfqLine;
import com.nereonix.nereonet_mvp.core.rfq.RfqQuote;
import com.nereonix.nereonet_mvp.core.rfq.RfqRepository;
import com.nereonix.nereonet_mvp.core.rfq.RfqStatus;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.supplier.ChannelType;
import com.nereonix.nereonet_mvp.core.supplier.Supplier;
import com.nereonix.nereonet_mvp.core.supplier.SupplierRepository;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserService;

/**
 * P44, requests for quotes: from a vessel order the office has been sent, the request (port, dates, suppliers, a
 * review before anything is sent), one email per supplier with the items and the .xls file, the quotes typed in, the
 * sealed comparison (per item unit, Not offered, the cheapest complete quote, other currencies apart), the award
 * (the others told, the order's Confirm form prefilled), declines, no reply, failed emails, cancelling, the Overview
 * rows and the audit entries.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class RfqFlowTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    private static final LocalDate NEEDED_BY = TODAY.plusDays(10);

    @Autowired MockMvc mockMvc;
    @Autowired ShipRepository shipRepository;
    @Autowired UserService userService;
    @Autowired ItemRepository itemRepository;
    @Autowired ShipOrderRepository orderRepository;
    @Autowired ShipOrderLineRepository lineRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired RfqRepository rfqRepository;
    @Autowired AuditRepository auditRepository;

    @MockitoBean MailService mailService;

    Ship ship;
    User master;
    Item rice;
    Item oil;
    Item soap;
    ShipOrder order;
    Supplier vanos;
    Supplier harbour;
    Supplier ocean;

    @BeforeEach
    void setUp() {
        ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);
        master = userService.createUser("master@example.com", "Ship Master", "secret123", Rank.MASTER.role(), ship,
                Rank.MASTER);
        rice = item("Basmati rice", "kg", ItemCategory.PROVISIONS);
        oil = item("Olive oil", "lt", ItemCategory.PROVISIONS);
        soap = item("Shower gel", "pcs", ItemCategory.PERSONAL_ITEMS);

        order = new ShipOrder();
        order.setShip(ship);
        order.setStatus(ShipOrderStatus.SENT);
        order.setCreatedAt(Instant.now());
        order.setSentBy(master);
        order.setSentAt(Instant.now());
        order.setRequestedPort("Piraeus");
        order.setNeededBy(NEEDED_BY);
        orderRepository.save(order);
        line(soap, "12");
        line(rice, "25");
        line(oil, "10");

        // By name: Harbour, Ocean, VANOS; only VANOS serves Piraeus, so it is listed first.
        harbour = supplier("Harbour Provisions", "sales@harbour.example.com", "Rotterdam, Antwerp", "EUR");
        ocean = supplier("Ocean Supply", "sales@ocean.example.com", "Houston", "USD");
        vanos = supplier("VANOS (Piraeus)", "quotes.vanos@example.com", "Piraeus, Perama", "EUR");
        supplier("Old Chandler", "old@example.com", "Piraeus", "EUR").setActive(false);
    }

    @Test
    void theOfficeAsksThreeSuppliersComparesTheQuotesAndAwardsOne() throws Exception {
        // The order offers Request quotes.
        assertThat(page("/office/orders/" + order.getId()))
                .contains("href=\"/office/orders/" + order.getId() + "/rfq/new\"", "Request quotes");

        // The form: port and delivery date from the order, the active suppliers, those serving the port first.
        String form = page("/office/orders/" + order.getId() + "/rfq/new");
        assertThat(form).contains("value=\"Piraeus\"", "value=\"" + NEEDED_BY + "\"", "Serves Piraeus",
                        "value=\"" + TODAY.plusDays(3) + "\"")
                .containsSubsequence("VANOS (Piraeus)", "Harbour Provisions", "Ocean Supply")
                .doesNotContain("Old Chandler")
                .containsSubsequence(">Provisions<", "Basmati rice", "Olive oil", ">Personal items<", "Shower gel");

        // At least one supplier; the reply-by date not after the delivery.
        String errors = mockMvc.perform(post("/office/orders/{id}/rfq/review", order.getId()).param("port", "Piraeus")
                        .param("deliveryDate", NEEDED_BY.toString()).param("replyBy", NEEDED_BY.plusDays(1).toString())
                        .with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(errors).contains("Choose at least one supplier.",
                "The reply-by date must be on or before the delivery date.");

        // The review: what will be sent, to whom; nothing sent yet.
        String review = mockMvc.perform(request(post("/office/orders/{id}/rfq/review", order.getId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(review).contains("Review and send", "Send to 3 suppliers", "quotes.vanos@example.com",
                "sales@harbour.example.com", "Basmati rice");
        verify(mailService, never()).send(anyString(), any(), anyString(), anyString(), anyString(), anyList());

        // Send: one email per supplier, the RFQ open.
        mockMvc.perform(request(post("/office/orders/{id}/rfq", order.getId())))
                .andExpect(flash().attribute("successMessage", "rfq.sent"));
        Rfq rfq = rfqRepository.findAllByOrderIdOrderByIdDesc(order.getId()).getFirst();
        String rfqUrl = "/office/orders/" + order.getId() + "/rfqs/" + rfq.getId();
        assertThat(rfq.getStatus()).isEqualTo(RfqStatus.OPEN);
        assertThat(rfq.getLines()).hasSize(3);
        assertThat(rfq.getQuotes()).extracting(RfqQuote::getStatus).containsOnly(QuoteStatus.SENT);

        List<Sent> sent = sentEmails();
        assertThat(sent).extracting(Sent::to).containsExactly("sales@harbour.example.com", "sales@ocean.example.com",
                "quotes.vanos@example.com");
        Sent toVanos = sent.get(2);
        String number = "RFQ #" + rfq.getId();
        assertThat(toVanos.replyTo()).isEqualTo("office@example.com");
        assertThat(toVanos.subject()).isEqualTo(number + ": request for quotation, MV Demo at Piraeus");
        assertThat(toVanos.text()).contains("Dear VANOS (Piraeus),", "MV Demo (IMO 9074729) at Piraeus",
                        "Provisions:\n- Basmati rice: 25 kg\n- Olive oil: 10 lt\n", "Personal items:\n- Shower gel: 12 pcs",
                        "Prices in EUR", "rfq-" + rfq.getId() + ".xls", "Reference: " + number + ".")
                .doesNotContain("Harbour", "Ocean");
        assertThat(toVanos.html()).contains("Basmati rice", "Request for quotation " + number);
        assertThat(toVanos.attachments()).singleElement().satisfies(a -> {
            assertThat(a.filename()).isEqualTo("rfq-" + rfq.getId() + ".xls");
            XlsSheet sheet = XlsSheet.of(a.content());
            assertThat(sheet.name()).isEqualTo("RFQ");
            assertThat(sheet.header()).containsExactly("rfq", "vessel", "imo_number", "port", "delivery_date",
                    "reply_by", "category", "item_name", "unit", "quantity", "unit_price", "quantity_offered",
                    "pack_size", "pack_unit", "note");
            assertThat(sheet.body()).extracting(row -> row.get(7))
                    .containsExactly("Basmati rice", "Olive oil", "Shower gel");
        });
        assertThat(audit("RFQ_SENT")).singleElement().extracting(AuditEntry::getDetails).asString()
                .startsWith(number + " for order #" + order.getId() + ", 3 lines, Piraeus")
                .endsWith("to Harbour Provisions, Ocean Supply, VANOS (Piraeus)");

        // Only one open RFQ per order: Request quotes goes to it.
        mockMvc.perform(get("/office/orders/{id}/rfq/new", order.getId()))
                .andExpect(redirectedUrl(rfqUrl))
                .andExpect(flash().attribute("errorMessage", "rfq.error.alreadyOpen"));
        assertThat(page("/office/orders/" + order.getId())).contains("Open " + number);

        // The RFQ: every supplier Sent, no comparison yet.
        assertThat(page(rfqUrl)).contains(">Sent</span>", "The comparison appears once two suppliers have quoted.");

        // VANOS: rice per kg, oil in 5 lt cans, no shower gel. Harbour: everything, oil per litre.
        enterQuote(rfq, vanos, "EUR", "40", TODAY.plusDays(8), new String[][] {
                {"2.00", "25", "", ""}, {"12.00", "2", "5", "lt"}, {"", "", "", ""}})
                .andExpect(flash().attribute("successMessage", "rfq.quote.saved"));
        enterQuote(rfq, harbour, "EUR", "30", TODAY.plusDays(9), new String[][] {
                {"2.10", "25", "", ""}, {"2.50", "10", "", ""}, {"1.00", "12", "", ""}});

        // Two quotes in EUR: the Overview lists the RFQ as ready to compare.
        assertThat(page("/office")).contains("2 quotes ready to compare", "Compare quotes", "href=\"" + rfqUrl + "\"");

        // Ocean quotes in USD: listed apart, never compared.
        enterQuote(rfq, ocean, "USD", "", TODAY.plusDays(9), new String[][] {
                {"2.00", "25", "", ""}, {"", "", "", ""}, {"", "", "", ""}});

        String compared = page(rfqUrl);
        assertThat(compared).contains("Quotes in EUR", "EUR 2.40 / lt", "EUR 2.50 / lt", "EUR 24.00", "Not offered",
                        "lowest", "Cheapest complete quote", "EUR 114.00", "EUR 119.50", "Incomplete: 2 items offered",
                        "Other currencies, not compared", "USD 50.00")
                .doesNotContain("Quotes in USD");
        // The cheapest complete quote is Harbour's (VANOS is cheaper but has no shower gel).
        assertThat(compared).containsPattern("(?s)<th scope=\"col\" class=\"rfq-compare-supplier is-cheapest\">\\s*"
                + "<span class=\"rfq-compare-supplier-name\">Harbour Provisions</span>");
        assertThat(audit("RFQ_QUOTE_ENTERED")).extracting(AuditEntry::getDetails).contains(
                number + ", VANOS (Piraeus), EUR 114.00, 2 of 3 lines",
                number + ", Harbour Provisions, EUR 119.50, 3 of 3 lines");

        // Award Harbour: the others are told they were not selected; the order shows the award.
        RfqQuote harbourQuote = quoteOf(rfq, harbour);
        mockMvc.perform(post(rfqUrl + "/quotes/{q}/award", harbourQuote.getId()).with(csrf()))
                .andExpect(redirectedUrl("/office/orders/" + order.getId()))
                .andExpect(flash().attribute("successMessage", "rfq.awarded"));
        assertThat(rfq.getStatus()).isEqualTo(RfqStatus.AWARDED);
        assertThat(rfq.getAwardedQuote()).isEqualTo(harbourQuote);
        List<Sent> closing = sentEmails().subList(3, sentEmails().size());
        assertThat(closing).extracting(Sent::to)
                .containsExactlyInAnyOrder("sales@ocean.example.com", "quotes.vanos@example.com");
        assertThat(closing).allSatisfy(e -> {
            assertThat(e.subject()).isEqualTo(number + ": not selected");
            assertThat(e.text()).contains("We have chosen another offer this time.").doesNotContain("Harbour", "119");
            assertThat(e.attachments()).isEmpty();
        });
        assertThat(audit("RFQ_AWARDED")).singleElement().extracting(AuditEntry::getDetails)
                .isEqualTo(number + " for order #" + order.getId() + " awarded to Harbour Provisions, EUR 119.50");

        // The order: Awarded to, and the Confirm form starts with the RFQ's port and the quote's delivery date.
        assertThat(page("/office/orders/" + order.getId())).contains("Awarded to", "Harbour Provisions · EUR 119.50",
                "name=\"confirmedPort\" value=\"Piraeus\"", "value=\"" + TODAY.plusDays(9) + "\"");

        // Closed: no more quotes or awards.
        enterQuote(rfq, vanos, "EUR", "0", TODAY.plusDays(8), new String[][] {
                {"1.00", "25", "", ""}, {"", "", "", ""}, {"", "", "", ""}})
                .andExpect(flash().attribute("errorMessage", "rfq.error.closed"));
        mockMvc.perform(post(rfqUrl + "/quotes/{q}/award", quoteOf(rfq, vanos).getId()).with(csrf()))
                .andExpect(flash().attribute("errorMessage", "rfq.error.closed"));
        assertThat(rfq.getAwardedQuote()).isEqualTo(harbourQuote);
    }

    @Test
    void declinedNoReplyFailedAndCancelled() throws Exception {
        doThrow(new MailSendException("Mailbox full")).when(mailService)
                .send(eq("sales@ocean.example.com"), any(), anyString(), anyString(), anyString(), anyList());
        mockMvc.perform(request(post("/office/orders/{id}/rfq", order.getId())))
                .andExpect(flash().attribute("errorMessage", "rfq.sent.failed"));
        Rfq rfq = rfqRepository.findAllByOrderIdOrderByIdDesc(order.getId()).getFirst();
        String rfqUrl = "/office/orders/" + order.getId() + "/rfqs/" + rfq.getId();
        RfqQuote oceanQuote = quoteOf(rfq, ocean);
        assertThat(oceanQuote.getStatus()).isEqualTo(QuoteStatus.FAILED);
        assertThat(page(rfqUrl)).contains(">Failed</span>", "Not delivered: Mailbox full", "Send again");

        // Sent again once the mailbox works.
        doNothing().when(mailService)
                .send(eq("sales@ocean.example.com"), any(), anyString(), anyString(), anyString(), anyList());
        mockMvc.perform(post(rfqUrl + "/quotes/{q}/resend", oceanQuote.getId()).with(csrf()))
                .andExpect(flash().attribute("successMessage", "rfq.quote.resent"));
        assertThat(oceanQuote.getStatus()).isEqualTo(QuoteStatus.SENT);

        // VANOS declines, with a reason.
        RfqQuote vanosQuote = quoteOf(rfq, vanos);
        mockMvc.perform(post(rfqUrl + "/quotes/{q}/decline", vanosQuote.getId()).param("reason", " Out of stock ")
                        .with(csrf()))
                .andExpect(flash().attribute("successMessage", "rfq.quote.declined"));
        assertThat(vanosQuote.getStatus()).isEqualTo(QuoteStatus.DECLINED);
        assertThat(audit("RFQ_QUOTE_DECLINED")).singleElement().extracting(AuditEntry::getDetails)
                .isEqualTo("RFQ #" + rfq.getId() + ", VANOS (Piraeus): Out of stock");

        // The reply-by date passes: those not answered show No reply, and the Overview lists the RFQ.
        rfq.setReplyBy(TODAY.minusDays(1));
        assertThat(page(rfqUrl)).contains(">Declined</span>", "Out of stock", ">No reply</span>");
        assertThat(page("/office")).contains("Reply-by date passed", "2 suppliers not answered", "Open request");

        // Cancel: the suppliers still asked are told; the order can get a new request.
        mockMvc.perform(post(rfqUrl + "/cancel").with(csrf()))
                .andExpect(flash().attribute("successMessage", "rfq.cancelled"));
        assertThat(rfq.getStatus()).isEqualTo(RfqStatus.CANCELLED);
        assertThat(sentEmails()).filteredOn(e -> e.subject().endsWith("request withdrawn")).extracting(Sent::to)
                .containsExactlyInAnyOrder("sales@harbour.example.com", "sales@ocean.example.com");
        assertThat(audit("RFQ_CANCELLED")).hasSize(1);
        assertThat(page("/office")).doesNotContain("Reply-by date passed");
        assertThat(page("/office/orders/" + order.getId())).contains("href=\"/office/orders/" + order.getId()
                + "/rfq/new\"");
    }

    @Test
    void aQuoteNeedsAPricedLineAndComparableUnits() throws Exception {
        mockMvc.perform(request(post("/office/orders/{id}/rfq", order.getId())));
        Rfq rfq = rfqRepository.findAllByOrderIdOrderByIdDesc(order.getId()).getFirst();

        String noLines = enterQuote(rfq, vanos, "EUR", "", TODAY.plusDays(8), new String[][] {
                {"", "", "", ""}, {"", "", "", ""}, {"", "", "", ""}})
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(noLines).contains("Enter a price for at least one item.");

        String badUnit = enterQuote(rfq, vanos, "EURO", "-1", null, new String[][] {
                {"2.00", "", "", ""}, {"12.00", "2", "5", "kg"}, {"", "", "", ""}})
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(badUnit).contains("Enter the quantity offered.", "This unit cannot be compared with lt.",
                "Enter a three-letter currency code, such as EUR.", "Enter an amount of 0 or more, with at most 2 decimals.",
                "Enter the delivery date.");
        assertThat(quoteOf(rfq, vanos).getStatus()).isEqualTo(QuoteStatus.SENT);
        assertThat(audit("RFQ_QUOTE_ENTERED")).isEmpty();
    }

    @Test
    void onlyASentOrderGetsARequestAndOnlyTheOffice() throws Exception {
        order.setStatus(ShipOrderStatus.CONFIRMED);
        mockMvc.perform(get("/office/orders/{id}/rfq/new", order.getId()))
                .andExpect(redirectedUrl("/office/orders/" + order.getId()))
                .andExpect(flash().attribute("errorMessage", "rfq.error.orderNotSent"));
        mockMvc.perform(request(post("/office/orders/{id}/rfq", order.getId())))
                .andExpect(flash().attribute("errorMessage", "rfq.error.orderNotSent"));
        assertThat(rfqRepository.findAllByOrderIdOrderByIdDesc(order.getId())).isEmpty();
        assertThat(page("/office/orders/" + order.getId())).doesNotContain("/rfq/new");

        mockMvc.perform(get("/office/orders/{id}/rfq/new", order.getId()).with(user(new CurrentUser(master))))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------------------------------------------------------

    private record Sent(String to, String replyTo, String subject, String text, String html,
                        List<MailService.Attachment> attachments) {
    }

    @SuppressWarnings("unchecked")
    private List<Sent> sentEmails() {
        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> replyTo = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<MailService.Attachment>> attachments = ArgumentCaptor.forClass(List.class);
        verify(mailService, atLeast(0)).send(to.capture(), replyTo.capture(), subject.capture(), text.capture(),
                html.capture(), attachments.capture());
        List<Sent> sent = new ArrayList<>();
        for (int i = 0; i < to.getAllValues().size(); i++) {
            sent.add(new Sent(to.getAllValues().get(i), replyTo.getAllValues().get(i), subject.getAllValues().get(i),
                    text.getAllValues().get(i), html.getAllValues().get(i), attachments.getAllValues().get(i)));
        }
        return sent;
    }

    // The request: Piraeus, delivery on the needed-by date, reply in 3 days, all three active suppliers.
    private MockHttpServletRequestBuilder request(MockHttpServletRequestBuilder post) {
        return post.param("port", "Piraeus").param("deliveryDate", NEEDED_BY.toString())
                .param("replyBy", TODAY.plusDays(3).toString())
                .param("supplierIds", vanos.getId().toString(), harbour.getId().toString(), ocean.getId().toString())
                .with(csrf());
    }

    // lines: per RFQ line by item name (rice, oil, shower gel): unit price, quantity offered, pack size, pack unit.
    private ResultActions enterQuote(Rfq rfq, Supplier supplier, String currency, String deliveryCost,
                                     LocalDate deliveryDate, String[][] lines) throws Exception {
        RfqQuote quote = quoteOf(rfq, supplier);
        MockHttpServletRequestBuilder post = post("/office/orders/{o}/rfqs/{r}/quotes/{q}", order.getId(), rfq.getId(),
                quote.getId())
                .param("currency", currency).param("deliveryCost", deliveryCost)
                .param("deliveryDate", deliveryDate == null ? "" : deliveryDate.toString())
                .with(csrf());
        List<RfqLine> byName = rfq.getLines().stream()
                .sorted(java.util.Comparator.comparing(RfqLine::getItemName)).toList();
        for (int i = 0; i < byName.size(); i++) {
            String p = "lines[" + i + "].";
            post.param(p + "lineId", byName.get(i).getId().toString())
                    .param(p + "unitPrice", lines[i][0]).param(p + "quantityOffered", lines[i][1])
                    .param(p + "packSize", lines[i][2]).param(p + "packUnit", lines[i][3]);
        }
        return mockMvc.perform(post);
    }

    private static RfqQuote quoteOf(Rfq rfq, Supplier supplier) {
        return rfq.getQuotes().stream().filter(q -> q.getSupplier().equals(supplier)).findFirst().orElseThrow();
    }

    private String page(String url) throws Exception {
        return mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private List<AuditEntry> audit(String action) {
        return auditRepository.findAll().stream().filter(e -> e.getAction().equals(action)).toList();
    }

    private void line(Item item, String quantity) {
        ShipOrderLine line = new ShipOrderLine();
        line.setOrder(order);
        line.setItem(item);
        line.setQuantity(new BigDecimal(quantity));
        order.getLines().add(lineRepository.save(line));
    }

    private Supplier supplier(String name, String email, String ports, String currency) {
        Supplier supplier = new Supplier();
        supplier.setName(name);
        supplier.setEmail(email);
        supplier.setPortsServed(ports);
        supplier.setCurrency(currency);
        supplier.setActive(true);
        supplier.setChannel(ChannelType.EMAIL);
        supplier.setCreatedAt(Instant.now());
        return supplierRepository.save(supplier);
    }

    private Item item(String name, String unit, ItemCategory category) {
        Item item = new Item();
        item.setShip(ship);
        item.setName(name);
        item.setUnit(unit);
        item.setCategory(category);
        item.setCreatedAt(Instant.now());
        return itemRepository.save(item);
    }
}
