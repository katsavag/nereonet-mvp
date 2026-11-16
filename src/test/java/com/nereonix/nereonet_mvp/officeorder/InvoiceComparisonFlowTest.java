package com.nereonix.nereonet_mvp.officeorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.XlsSheet;
import com.nereonix.nereonet_mvp.core.audit.AuditEntry;
import com.nereonix.nereonet_mvp.core.audit.AuditRepository;
import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemCategory;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.notification.Notification;
import com.nereonix.nereonet_mvp.core.notification.NotificationRepository;
import com.nereonix.nereonet_mvp.core.order.DeliveryInvoice;
import com.nereonix.nereonet_mvp.core.order.DeliveryInvoiceLine;
import com.nereonix.nereonet_mvp.core.order.DeliveryInvoiceLineRepository;
import com.nereonix.nereonet_mvp.core.order.DeliveryInvoiceRepository;
import com.nereonix.nereonet_mvp.core.order.DeliveryInvoiceStatus;
import com.nereonix.nereonet_mvp.core.order.CountSheet;
import com.nereonix.nereonet_mvp.core.order.InvoiceMatch;
import com.nereonix.nereonet_mvp.core.order.InvoiceMatchService;
import com.nereonix.nereonet_mvp.core.order.InvoiceMatchStatus;
import com.nereonix.nereonet_mvp.core.order.ShipOrder;
import com.nereonix.nereonet_mvp.core.order.ShipOrderLine;
import com.nereonix.nereonet_mvp.core.order.ShipOrderLineRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrderRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrderStatus;
import com.nereonix.nereonet_mvp.core.rfq.QuoteStatus;
import com.nereonix.nereonet_mvp.core.rfq.Rfq;
import com.nereonix.nereonet_mvp.core.rfq.RfqLine;
import com.nereonix.nereonet_mvp.core.rfq.RfqLineRepository;
import com.nereonix.nereonet_mvp.core.rfq.RfqQuote;
import com.nereonix.nereonet_mvp.core.rfq.RfqQuoteLine;
import com.nereonix.nereonet_mvp.core.rfq.RfqQuoteLineRepository;
import com.nereonix.nereonet_mvp.core.rfq.RfqQuoteRepository;
import com.nereonix.nereonet_mvp.core.rfq.RfqRepository;
import com.nereonix.nereonet_mvp.core.rfq.RfqStatus;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.supplier.Supplier;
import com.nereonix.nereonet_mvp.core.supplier.SupplierItemName;
import com.nereonix.nereonet_mvp.core.supplier.SupplierItemNameRepository;
import com.nereonix.nereonet_mvp.core.supplier.SupplierRepository;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserService;

/**
 * The office compares order, delivery and invoice: the Chief cook counts against the invoice and the Master
 * approves, then the office sees the summary, the points to review and the lines that differ with the money at
 * stake and the quoted prices, accepts the differences, confirms or changes the points (a changed match or conversion
 * never moves the store: the cook is asked to count), corrects a line read wrong, and marks it matched. The Overview,
 * the list and the export follow. Signed in as the office unless a request says otherwise.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class InvoiceComparisonFlowTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F'};
    private static final String CHECK = "Page 1: the lines add up to 349.50, the page says 359.50";

    @Autowired MockMvc mockMvc;
    @Autowired ShipRepository shipRepository;
    @Autowired UserService userService;
    @Autowired ItemRepository itemRepository;
    @Autowired ShipOrderRepository orderRepository;
    @Autowired ShipOrderLineRepository lineRepository;
    @Autowired DeliveryInvoiceRepository invoiceRepository;
    @Autowired DeliveryInvoiceLineRepository invoiceLineRepository;
    @Autowired InvoiceMatchService matchService;
    @Autowired SupplierRepository supplierRepository;
    @Autowired SupplierItemNameRepository nameRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired AuditRepository auditRepository;
    @Autowired RfqRepository rfqRepository;
    @Autowired RfqLineRepository rfqLineRepository;
    @Autowired RfqQuoteRepository quoteRepository;
    @Autowired RfqQuoteLineRepository quoteLineRepository;

    Ship ship;
    User master;
    User cook;
    ShipOrder order;
    long id;
    Supplier kestrel;

    @BeforeEach
    void setUp() {
        ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);
        master = userService.createUser("master@example.com", "Ship Master", "secret123", Rank.MASTER.role(), ship,
                Rank.MASTER);
        cook = userService.createUser("cook@example.com", "Demo Cook", "secret123", Rank.CHIEF_COOK.role(), ship,
                Rank.CHIEF_COOK);
        kestrel = new Supplier();
        kestrel.setName("Kestrel Ship Supply");
        kestrel.setEmail("sales@example.com");
        kestrel.setCurrency("EUR");
        kestrel.setCreatedAt(Instant.now());
        supplierRepository.save(kestrel);
        order = order();
        id = order.getId();
    }

    @Test
    void theOfficeComparesAcceptsReviewsAndMarksTheInvoiceMatched() throws Exception {
        ShipOrderLine rice = line(item("Basmati rice", "kg", ItemCategory.PROVISIONS), "120");
        line(item("Eggs", "pcs", ItemCategory.PROVISIONS), "30");
        line(item("Tomatoes", "kg", ItemCategory.PROVISIONS), "25");
        line(item("Feta cheese", "kg", ItemCategory.PROVISIONS), "10");
        ShipOrderLine gel = line(item("Shower gel", "pcs", ItemCategory.PERSONAL_ITEMS), "6");
        award(rice, "1.80");
        DeliveryInvoice invoice = invoice();
        invoiceLine(invoice, 1, "EGGS LARGE", "PCS", "30", "0.25", "7.50");
        DeliveryInvoiceLine tomatoes = invoiceLine(invoice, 2, "TOMATOES", "KG", "25", "1.20", "30.00");
        tomatoes.setNeedsCheck(true);
        DeliveryInvoiceLine riceLine = invoiceLine(invoice, 3, "BASMATI RICE 10KG", "BAG", "12", "18.50", "222.00");
        DeliveryInvoiceLine feta = invoiceLine(invoice, 4, "FETA 2KG", "KG", "10", "8.00", "80.00");
        DeliveryInvoiceLine napkins = invoiceLine(invoice, 5, "PAPER NAPKINS", "PCS", "10", "1.00", "10.00");

        // The ship counts (11 bags of rice, the shower gel not on the invoice, the napkins not ordered) and approves.
        countAndApprove(napkins, "o" + rice.getId(), "11", "o" + gel.getId(), "6");
        assertThat(order.getStatus()).isEqualTo(ShipOrderStatus.CLOSED);
        assertThat(order.getInvoiceStatus()).isEqualTo(InvoiceMatchStatus.TO_MATCH);
        assertThat(order.getInvoiceDiffLines()).isEqualTo(3);
        assertThat(feta.getMatch()).isEqualTo(InvoiceMatch.CHECK);
        assertThat(riceLine.isConversionToReview()).isTrue();
        // An item of the vessel added after the count (so the napkins were not matched to it on board).
        Item napkinItem = item("Paper napkins", "pcs", ItemCategory.PERSONAL_ITEMS);

        // The office's page: the summary, the points to review, the lines that differ, the rest folded.
        String page = page("/office/orders/" + id);
        assertThat(page).containsSubsequence("Order, delivery and invoice", "3 of 6 lines differ",
                        "Kestrel Ship Supply S.A.", "A-4471", "359.50",
                        "Provisions: 4 lines, 1 differ", "Personal items: 1 line, 1 differ", "Not ordered: 1 line, 1 differ",
                        "Ordered value (quoted prices)", "EUR 216.00", "Invoiced total", "EUR 359.50",
                        "Value of the differences", "View invoice",
                        "To review", CHECK, "Page 1, line 2: TOMATOES", "hard to read",
                        "Match to review: FETA 2KG → Feta cheese",
                        "Conversion to review: BASMATI RICE 10KG, BAG → × 10 kg",
                        "Lines that differ", "Basmati rice", "EUR 24.50", "120", "110", "12 bags × 10 kg",
                        "Received 10 kg less than invoiced: a credit to claim",
                        "Invoiced 0.05 more per kg than quoted",
                        "Unit price", "EUR 18.50", "per bag", "Quoted", "EUR 1.80", "per kg", "One bag torn",
                        "Shower gel", "Not on the invoice",
                        "PAPER NAPKINS", "Not ordered", "EUR 10.00",
                        "3 lines agree: show", "Eggs", "Feta cheese", "Tomatoes",
                        "7 points are left to decide", "Mark as matched")
                .doesNotContain("shortage-handled");

        // The Overview: "Invoice to match", replacing the shortage row; the list's column and filter.
        assertThat(page("/office")).contains("Order #" + id + " · invoice to match", "3 lines differ", ">Compare<")
                .doesNotContain("received short");
        ShipOrder plain = order();
        assertThat(page("/office/orders")).contains("3 differ", ">None<");
        assertThat(page("/office/orders?invoice=differ")).contains("Order #" + id + "</a>")
                .doesNotContain("Order #" + plain.getId() + "</a>");

        // Nothing marked matched while points are open.
        mockMvc.perform(post("/office/orders/{id}/invoice/matched", id).with(csrf()))
                .andExpect(redirectedUrl("/office/orders/" + id + "#compare"))
                .andExpect(flash().attribute("errorMessage", "officeorder.compare.openLeft"));

        // Accept needs a note.
        accept("o" + rice.getId(), " ").andExpect(flash().attribute("errorMessage", "officeorder.compare.accept.noteRequired"));
        accept("o" + rice.getId(), "Credit note requested from the supplier")
                .andExpect(flash().attribute("successMessage", "officeorder.compare.accepted"));
        assertThat(page("/office/orders/" + id)).contains("Accepted by John Doe", "Credit note requested from the supplier");

        // Correct: the check, the match (learned for the supplier by the office), the conversion.
        confirm("check:" + CHECK).andExpect(flash().attribute("successMessage", "officeorder.compare.confirmed"));
        confirm("match:" + feta.getId());
        assertThat(nameRepository.findAll()).filteredOn(n -> n.getDescription().equals("feta 2kg")).singleElement()
                .satisfies(n -> assertThat(n.getCreatedBy().getEmail()).isEqualTo("office@example.com"));
        confirm("conv:" + riceLine.getId());
        assertThat(nameRepository.findAll()).extracting(SupplierItemName::getDescription).contains("basmati rice 10kg");

        // Change a conversion: the comparison changes (the acceptance no longer holds), the store does not, the cook
        // is asked to count.
        mockMvc.perform(post("/office/orders/{id}/invoice/lines/{l}/factor", id, riceLine.getId()).param("factor", "0")
                        .with(csrf()))
                .andExpect(flash().attribute("errorMessage", "officeorder.compare.factor.invalid"));
        mockMvc.perform(post("/office/orders/{id}/invoice/lines/{l}/factor", id, riceLine.getId()).param("factor", "5")
                        .with(csrf()))
                .andExpect(flash().attribute("successMessage", "officeorder.compare.factor.changedStock"));
        assertThat(rice.getInvoicedQuantity()).isEqualByComparingTo("60");
        assertThat(rice.getReceivedQuantity()).isEqualByComparingTo("110");
        assertThat(page("/office/orders/" + id)).contains("Received 50 kg more than invoiced",
                "The inventory was updated with the old match. Ask the vessel to count these items.");
        assertThat(notifications(cook)).extracting(Notification::getText).contains("Order " + id
                + ": the office corrected \"BASMATI RICE 10KG\" (Basmati rice). The inventory was updated before the "
                + "correction: please count these items.");
        mockMvc.perform(post("/office/orders/{id}/invoice/lines/{l}/factor", id, riceLine.getId()).param("factor", "10")
                        .with(csrf()));
        assertThat(page("/office/orders/" + id)).contains("Accepted by John Doe");

        // Change a match after receipt: the napkins are the vessel's item, a line added on receipt with nothing
        // received; the cook is asked to count.
        assertThat(page("/office/orders/" + id + "/invoice/lines/" + napkins.getId() + "/match"))
                .contains("On the invoice: PAPER NAPKINS", "On this order", "Basmati rice (kg)",
                        "Other items of the vessel", "Paper napkins (pcs)", "Not ordered");
        mockMvc.perform(post("/office/orders/{id}/invoice/lines/{l}/match", id, napkins.getId())
                        .param("choice", "i" + napkinItem.getId()).with(csrf()))
                .andExpect(flash().attribute("successMessage", "officeorder.compare.match.changedStock"));
        assertThat(napkins.getMatch()).isEqualTo(InvoiceMatch.CHOSEN);
        ShipOrderLine napkinLine = napkins.getOrderLine();
        assertThat(napkinLine.isAddedOnReceipt()).isTrue();
        assertThat(napkinLine.getReceivedQuantity()).isZero();
        assertThat(napkinLine.getInvoicedQuantity()).isEqualByComparingTo("10");
        assertThat(audit("ORDER_INVOICE_MATCH_CHANGED")).extracting(AuditEntry::getDetails)
                .containsExactly("Order #" + id + ": \"PAPER NAPKINS\" nothing -> Paper napkins");

        // A line read wrong, corrected on View invoice: the value as read is kept.
        String view = page("/office/orders/" + id + "/invoice");
        assertThat(view).contains("Page 1 of 1", "Photo of page 1", "What was read", CHECK, "TOMATOES", "hard to read",
                "Correct this line", "Matched to Basmati rice");
        mockMvc.perform(post("/office/orders/{id}/invoice/lines/{l}", id, tomatoes.getId()).param("quantity", "-1")
                        .with(csrf()))
                .andExpect(flash().attribute("errorMessage", "officeorder.invoice.correct.invalid"));
        mockMvc.perform(post("/office/orders/{id}/invoice/lines/{l}", id, tomatoes.getId())
                        .param("description", "TOMATOES FRESH").param("amount", "30,00").with(csrf()))
                .andExpect(redirectedUrl("/office/orders/" + id + "/invoice?page=1#line-" + tomatoes.getId()))
                .andExpect(flash().attribute("successMessage", "officeorder.invoice.correct.saved"));
        assertThat(tomatoes.getDescription()).isEqualTo("TOMATOES FRESH");
        assertThat(tomatoes.getDescriptionRead()).isEqualTo("TOMATOES");
        assertThat(tomatoes.getOfficeChangedBy().getEmail()).isEqualTo("office@example.com");
        assertThat(page("/office/orders/" + id + "/invoice")).contains("As read: TOMATOES", "Changed by John Doe",
                "changed by the office");
        assertThat(audit("ORDER_INVOICE_LINE_CORRECTED")).extracting(AuditEntry::getDetails)
                .containsExactly("Order #" + id + ", page 1 line 2: description \"TOMATOES\" -> \"TOMATOES FRESH\"");

        // The rest accepted: Mark as matched.
        accept("o" + gel.getId(), "Not charged: nothing to do");
        accept("o" + napkinLine.getId(), "Credit note requested");
        mockMvc.perform(post("/office/orders/{id}/invoice/matched", id).with(csrf()))
                .andExpect(flash().attribute("successMessage", "officeorder.compare.matched"));
        assertThat(order.getInvoiceStatus()).isEqualTo(InvoiceMatchStatus.MATCHED);
        assertThat(order.getInvoiceMatchedBy().getEmail()).isEqualTo("office@example.com");
        assertThat(order.getShortageHandledAt()).isNotNull();
        assertThat(audit("ORDER_INVOICE_MATCHED")).singleElement().satisfies(e -> assertThat(e.getDetails())
                .startsWith("Order #" + id + ", 6 lines, 3 differences accepted: ").contains("Credit note requested"));
        assertThat(page("/office/orders/" + id)).contains("Matched by John Doe").doesNotContain(">Mark as matched<");
        assertThat(page("/office")).doesNotContain("Order #" + id + " · invoice to match");
        assertThat(page("/office/orders")).contains(">Matched<");
        // Matched: nothing changes any more.
        accept("o" + gel.getId(), "Again").andExpect(flash().attribute("errorMessage", "officeorder.compare.notOpen"));

        // The export, for accounting: invoiced, the invoice's unit, price and amount, the difference.
        XlsSheet sheet = XlsSheet.of(mockMvc.perform(post("/office/orders/export").param("ids", String.valueOf(id))
                .with(csrf())).andReturn().getResponse().getContentAsByteArray());
        List<Object> header = sheet.header();
        List<Object> riceRow = sheet.body().stream().filter(r -> "Basmati rice".equals(r.get(header.indexOf("item_name"))))
                .findFirst().orElseThrow();
        assertThat(riceRow.subList(header.indexOf("received_quantity"), header.indexOf("received_quantity") + 1))
                .containsExactly(110.0);
        assertThat(riceRow.subList(header.indexOf("invoiced_quantity"), header.size()))
                .containsExactly(120.0, "BAG", 18.5, 222.0, -10.0);

        // Office only: never on a ship page.
        mockMvc.perform(get("/office/orders/{id}/invoice", id).with(user(new CurrentUser(master))))
                .andExpect(status().isForbidden());
        assertThat(mockMvc.perform(get("/ship/orders/{id}", id).with(user(new CurrentUser(master)))).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("Order, delivery and invoice", "18.50");
        // An order without a read invoice has no comparison.
        mockMvc.perform(get("/office/orders/{id}/invoice", plain.getId())).andExpect(status().isNotFound());
    }

    @Test
    void whenEveryLineAgreesOneClickClosesIt() throws Exception {
        ShipOrderLine eggs = line(item("Eggs", "pcs", ItemCategory.PROVISIONS), "30");
        DeliveryInvoice invoice = invoice();
        invoice.setCheckNotes(null);
        invoiceLine(invoice, 1, "EGGS LARGE", "PCS", "30", "0.25", "7.50");
        mockMvc.perform(get("/ship/orders/{id}/count", id).with(user(new CurrentUser(cook))));
        saveCount(Map.of());
        sendAndApprove();

        assertThat(order.getInvoiceStatus()).isEqualTo(InvoiceMatchStatus.TO_MATCH);
        assertThat(order.getInvoiceDiffLines()).isZero();
        assertThat(page("/office/orders/" + id)).contains("The line agrees", "Nothing is left to decide")
                .doesNotContain(">Lines that differ<", ">To review<");
        assertThat(page("/office")).contains("All lines agree: confirm");
        assertThat(page("/office/orders")).contains("All agree: confirm");
        mockMvc.perform(post("/office/orders/{id}/invoice/matched", id).with(csrf()))
                .andExpect(flash().attribute("successMessage", "officeorder.compare.matched"));
        assertThat(order.getInvoiceStatus()).isEqualTo(InvoiceMatchStatus.MATCHED);
    }

    // ---------------------------------------------------------------------------------------------------------------

    // The cook counts: every row its reference ("12 arrived") but the two given; the napkins not ordered.
    private void countAndApprove(DeliveryInvoiceLine notOrdered, String key1, String quantity1, String key2,
                                 String quantity2) throws Exception {
        mockMvc.perform(get("/ship/orders/{id}/count", id).with(user(new CurrentUser(cook))))
                .andExpect(status().isOk());
        saveCount(Map.of(key1, quantity1, "note-" + key1, "One bag torn", key2, quantity2));
        // A line marked "Not ordered", as a cook could on board before P92.
        notOrdered.setMatch(InvoiceMatch.NOT_ORDERED);
        sendAndApprove();
    }

    private void sendAndApprove() throws Exception {
        mockMvc.perform(post("/ship/orders/{id}/count/send", id).with(user(new CurrentUser(cook))).with(csrf()))
                .andExpect(flash().attribute("successMessage", "order.count.sent"));
        mockMvc.perform(multipart("/ship/orders/{id}/receipt/files", id)
                        .file(new MockMultipartFile("file", "invoice.jpg", "image/jpeg", JPEG))
                        .with(user(new CurrentUser(master))).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/ship/orders/{id}/receipt/approve", id).with(user(new CurrentUser(master))).with(csrf()))
                .andExpect(flash().attribute("successMessage", "order.approve.done"));
    }

    // Save count: every row its reference, but the rows given (key: quantity; "note-" + key: its note).
    private void saveCount(Map<String, String> typed) throws Exception {
        MockHttpServletRequestBuilder save = post("/ship/orders/{id}/count", id).with(user(new CurrentUser(cook)))
                .with(csrf());
        for (CountSheet.Row row : matchService.sheet(order).getRows()) {
            save.param("q-" + row.getKey(), typed.getOrDefault(row.getKey(), row.text(row.getReference())));
            save.param("note-" + row.getKey(), typed.getOrDefault("note-" + row.getKey(), ""));
        }
        mockMvc.perform(save);
    }

    private org.springframework.test.web.servlet.ResultActions accept(String rowKey, String note) throws Exception {
        return mockMvc.perform(post("/office/orders/{id}/invoice/accept", id).param("key", rowKey).param("note", note)
                .with(csrf()));
    }

    private org.springframework.test.web.servlet.ResultActions confirm(String key) throws Exception {
        return mockMvc.perform(post("/office/orders/{id}/invoice/confirm", id).param("key", key).with(csrf()));
    }

    private String page(String url) throws Exception {
        return mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private List<Notification> notifications(User user) {
        return notificationRepository.findAllByUserEmailOrderByCreatedAtDescIdDesc(user.getEmail());
    }

    private List<AuditEntry> audit(String action) {
        return auditRepository.findAll().stream().filter(e -> e.getAction().equals(action)).toList();
    }

    // Sent two days ago, delivery scheduled today.
    private ShipOrder order() {
        ShipOrder created = new ShipOrder();
        created.setShip(ship);
        created.setStatus(ShipOrderStatus.CONFIRMED);
        created.setCreatedAt(Instant.now().minus(3, ChronoUnit.DAYS));
        created.setSentBy(master);
        created.setSentAt(Instant.now().minus(2, ChronoUnit.DAYS));
        created.setRequestedPort("Piraeus");
        created.setNeededBy(LocalDate.now(ZoneOffset.UTC).plusDays(5));
        created.setConfirmedPort("Piraeus");
        created.setDeliveryDate(LocalDate.now(ZoneOffset.UTC));
        return orderRepository.save(created);
    }

    private ShipOrderLine line(Item item, String quantity) {
        ShipOrderLine line = new ShipOrderLine();
        line.setOrder(order);
        line.setItem(item);
        line.setQuantity(new BigDecimal(quantity));
        line.setOriginalQuantity(new BigDecimal(quantity));
        order.getLines().add(lineRepository.save(line));
        return line;
    }

    private Item item(String name, String unit, ItemCategory category) {
        Item item = new Item();
        item.setShip(ship);
        item.setName(name);
        item.setUnit(unit);
        item.setCategory(category);
        item.setCreatedAt(Instant.now());
        item.setActive(true);
        return itemRepository.save(item);
    }

    // An awarded request for quotes: Kestrel quoted the rice at this price per kg.
    private void award(ShipOrderLine line, String price) {
        Rfq rfq = new Rfq();
        rfq.setOrder(order);
        rfq.setStatus(RfqStatus.AWARDED);
        rfq.setPort("Piraeus");
        rfq.setDeliveryDate(LocalDate.now(ZoneOffset.UTC));
        rfq.setReplyBy(LocalDate.now(ZoneOffset.UTC));
        rfq.setSentAt(Instant.now().minus(2, ChronoUnit.DAYS));
        rfq.setClosedAt(Instant.now().minus(1, ChronoUnit.DAYS));
        rfqRepository.save(rfq);
        RfqLine rfqLine = new RfqLine();
        rfqLine.setRfq(rfq);
        rfqLine.setItem(line.getItem());
        rfqLine.setItemName(line.getItem().getName());
        rfqLine.setUnit(line.getItem().getUnit());
        rfqLine.setCategory(line.getItem().getCategory());
        rfqLine.setQuantity(line.getQuantity());
        rfqLineRepository.save(rfqLine);
        rfq.getLines().add(rfqLine);
        RfqQuote quote = new RfqQuote();
        quote.setRfq(rfq);
        quote.setSupplier(kestrel);
        quote.setStatus(QuoteStatus.QUOTED);
        quote.setCurrency("EUR");
        quoteRepository.save(quote);
        RfqQuoteLine quoteLine = new RfqQuoteLine();
        quoteLine.setQuote(quote);
        quoteLine.setLine(rfqLine);
        quoteLine.setUnitPrice(new BigDecimal(price));
        quoteLine.setQuantityOffered(line.getQuantity());
        quoteLineRepository.save(quoteLine);
        quote.getLines().add(quoteLine);
        rfq.getQuotes().add(quote);
        rfq.setAwardedQuote(quote);
    }

    // The invoice read from the order's photo: one page, its total off by 10 (a check that failed).
    private DeliveryInvoice invoice() {
        DeliveryInvoice invoice = new DeliveryInvoice();
        invoice.setOrder(order);
        invoice.setStatus(DeliveryInvoiceStatus.READ);
        invoice.setSupplierName("Kestrel Ship Supply S.A.");
        invoice.setInvoiceNumber("A-4471");
        invoice.setInvoiceDate(LocalDate.now(ZoneOffset.UTC));
        invoice.setNet(new BigDecimal("359.50"));
        invoice.setTotal(new BigDecimal("359.50"));
        invoice.setCheckNotes(CHECK);
        invoice.setReadAt(Instant.now());
        return invoiceRepository.save(invoice);
    }

    private DeliveryInvoiceLine invoiceLine(DeliveryInvoice invoice, int number, String description, String unit,
                                            String quantity, String price, String amount) {
        DeliveryInvoiceLine line = new DeliveryInvoiceLine();
        line.setInvoice(invoice);
        line.setPage(1);
        line.setLineNumber(number);
        line.setDescription(description);
        line.setUnit(unit);
        line.setQuantity(new BigDecimal(quantity));
        line.setReadQuantity(line.getQuantity());
        line.setUnitPrice(new BigDecimal(price));
        line.setReadUnitPrice(line.getUnitPrice());
        line.setAmount(new BigDecimal(amount));
        line.setReadAmount(line.getAmount());
        return invoiceLineRepository.save(line);
    }
}
