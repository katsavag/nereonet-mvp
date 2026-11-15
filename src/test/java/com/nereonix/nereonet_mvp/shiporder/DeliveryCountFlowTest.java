package com.nereonix.nereonet_mvp.shiporder;

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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditEntry;
import com.nereonix.nereonet_mvp.core.audit.AuditRepository;
import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemCategory;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.notification.Notification;
import com.nereonix.nereonet_mvp.core.notification.NotificationRepository;
import com.nereonix.nereonet_mvp.core.order.DeliveryCountStatus;
import com.nereonix.nereonet_mvp.core.order.DeliveryInvoice;
import com.nereonix.nereonet_mvp.core.order.DeliveryInvoiceLine;
import com.nereonix.nereonet_mvp.core.order.DeliveryInvoiceLineRepository;
import com.nereonix.nereonet_mvp.core.order.DeliveryInvoiceRepository;
import com.nereonix.nereonet_mvp.core.order.DeliveryInvoiceStatus;
import com.nereonix.nereonet_mvp.core.order.InvoiceMatch;
import com.nereonix.nereonet_mvp.core.order.ShipOrder;
import com.nereonix.nereonet_mvp.core.order.ShipOrderLine;
import com.nereonix.nereonet_mvp.core.order.ShipOrderLineRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrderRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrderStatus;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.stock.StockMovement;
import com.nereonix.nereonet_mvp.core.stock.StockMovementRepository;
import com.nereonix.nereonet_mvp.core.stock.StockMovementType;
import com.nereonix.nereonet_mvp.core.supplier.Supplier;
import com.nereonix.nereonet_mvp.core.supplier.SupplierItemName;
import com.nereonix.nereonet_mvp.core.supplier.SupplierItemNameRepository;
import com.nereonix.nereonet_mvp.core.supplier.SupplierRepository;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserService;

/**
 * P61 and P63, the Chief cook counts what arrived and the Master approves it. Without a read invoice the order is the
 * reference ("Ordered: 30 pcs", "30 arrived"); with one, its lines are matched to the order and guide the count in the
 * invoice's unit ("Basmati rice, 10 kg bag · Invoice: 12 bags"), the lines nothing was found for are chosen by the
 * cook ("Choose the product", learned for the supplier), the Master approves the differences in one step, and the
 * receipt keeps for the office what the invoice said and the marks. Who may do what. Signed in as the office unless a
 * request says otherwise.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class DeliveryCountFlowTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F'};
    private static final DateTimeFormatter INPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    @Autowired MockMvc mockMvc;
    @Autowired ShipRepository shipRepository;
    @Autowired UserService userService;
    @Autowired ItemRepository itemRepository;
    @Autowired ShipOrderRepository orderRepository;
    @Autowired ShipOrderLineRepository lineRepository;
    @Autowired DeliveryInvoiceRepository invoiceRepository;
    @Autowired DeliveryInvoiceLineRepository invoiceLineRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired SupplierItemNameRepository nameRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired AuditRepository auditRepository;
    @Autowired StockMovementRepository stockMovementRepository;

    Ship ship;
    User master;
    User cook;
    ShipOrder order;
    long id;
    ShipOrderLine tomatoLine;
    ShipOrderLine eggLine;
    ShipOrderLine gelLine;

    @BeforeEach
    void setUp() {
        ship = ship("9074729", "MV Demo");
        master = userService.createUser("master@example.com", "Ship Master", "secret123", Rank.MASTER.role(), ship,
                Rank.MASTER);
        cook = userService.createUser("cook@example.com", "Demo Cook", "secret123", Rank.CHIEF_COOK.role(), ship,
                Rank.CHIEF_COOK);
        // Sent yesterday, delivery scheduled: 25 kg tomatoes, 30 eggs, 6 shower gels.
        order = order(ship);
        id = order.getId();
        tomatoLine = line(order, item("Tomatoes", "kg", ItemCategory.PROVISIONS), "25");
        eggLine = line(order, item("Eggs", "pcs", ItemCategory.PROVISIONS), "30");
        gelLine = line(order, item("Shower gel", "pcs", ItemCategory.PERSONAL_ITEMS), "6");
    }

    @Test
    void withoutAnInvoiceTheCookCountsAgainstTheOrderAndTheMasterApprovesIt() throws Exception {
        // The count: To do and Done, one row per line with the quantity ordered, one empty input and one button.
        String page = as(cook, get("/ship/orders/{id}/count", id));
        assertThat(page).contains("Count what arrived", "To do", "Done", "3 still to do. Your count is saved as you go.",
                        "Finish", "name=\"quantity\"")
                .containsSubsequence("Provisions", "Eggs", "Ordered: 30 pcs", "30 arrived", "Tomatoes", "Ordered: 25 kg",
                        "25 arrived", "Personal items", "Shower gel", "Ordered: 6 pcs", "6 arrived")
                .doesNotContain("value=\"30\"", "Save draft", "Count by voice");

        // One tap: the eggs as ordered; back at the next row still to do.
        mockMvc.perform(row("o" + eggLine.getId(), "", ""))
                .andExpect(redirectedUrl("/ship/orders/" + id + "/count#row-o" + tomatoLine.getId()))
                .andExpect(flash().attribute("successMessage", "order.count.row.saved"));
        assertThat(eggLine.getCountedQuantity()).isEqualByComparingTo("30");
        assertThat(order.getDeliveryCountStatus()).isEqualTo(DeliveryCountStatus.DRAFT);
        assertThat(order.getCountedBy().getId()).isEqualTo(cook.getId());
        // Another number, with a note.
        mockMvc.perform(row("o" + tomatoLine.getId(), "23", "2 kg rotten, sent back"));
        assertThat(tomatoLine.getCountedQuantity()).isEqualByComparingTo("23");
        assertThat(tomatoLine.getCountNote()).isEqualTo("2 kg rotten, sent back");
        // P60: pcs are whole numbers; the error under the row, nothing saved.
        assertThat(as(cook, row("o" + gelLine.getId(), "2.5", ""))).contains("Use whole numbers for pcs.");
        assertThat(gelLine.getCountedQuantity()).isNull();
        assertThat(movements()).isEmpty();

        // To do only gets shorter; Done lists what was counted, with Change.
        assertThat(as(cook, get("/ship/orders/{id}/count", id))).contains("Shower gel", "1 still to do")
                .doesNotContain("Ordered: 30 pcs");
        assertThat(as(cook, get("/ship/orders/{id}/count", id).param("tab", "done")))
                .containsSubsequence("Eggs", "30 pcs", "Change", "Tomatoes", "23 kg", "2 kg rotten, sent back", "Change")
                .doesNotContain("Shower gel");
        assertThat(as(cook, get("/ship/orders/{id}/count", id).param("tab", "done")
                .param("change", "o" + tomatoLine.getId()))).contains("value=\"23\"", "Confirm 23");

        // Finish with a row still to do: it says so.
        assertThat(as(cook, get("/ship/orders/{id}/count/send", id))).contains("1 product is not counted yet",
                "Shower gel", "Count them", "Send anyway");
        // Send anyway: the summary.
        String summary = as(cook, get("/ship/orders/{id}/count/send", id).param("anyway", ""));
        assertThat(summary).contains("2 of 3 counted", "same as ordered", "The 2 that are different", "Send to Master",
                        "The Master checks it. Then the stock is updated.", "Add a note for the Master")
                .containsSubsequence("Tomatoes", "23 of 25 kg arrived", "2 kg rotten, sent back", "Change",
                        "Shower gel", "Not counted", "Change");

        mockMvc.perform(post("/ship/orders/{id}/count/send", id).param("note", "Meat comes tomorrow")
                        .with(user(new CurrentUser(cook))).with(csrf()))
                .andExpect(redirectedUrl("/ship/orders/" + id))
                .andExpect(flash().attribute("successMessage", "order.count.sent"));
        assertThat(order.getDeliveryCountStatus()).isEqualTo(DeliveryCountStatus.SENT_TO_MASTER);
        assertThat(order.getCountNote()).isEqualTo("Meat comes tomorrow");
        assertThat(audit("ORDER_DELIVERY_COUNTED")).singleElement().satisfies(e -> {
            assertThat(e.getDetails()).isEqualTo("Order #" + id + ", 2 of 3 counted, 2 different");
            assertThat(e.getActor().getId()).isEqualTo(cook.getId());
        });
        assertThat(notifications("master@example.com")).singleElement().satisfies(n -> {
            assertThat(n.getText()).isEqualTo("Order " + id + ": delivery counted by Demo Cook, waiting for your approval");
            assertThat(n.getLink()).isEqualTo("/ship/orders/" + id + "/receipt");
        });

        // Sent: read only for the cook; another row changes nothing.
        assertThat(as(cook, get("/ship/orders/{id}/count", id))).contains("Sent to the Master on", "waiting for approval",
                "2 of 3 counted").doesNotContain("Send to Master", "name=\"quantity\"");
        mockMvc.perform(row("o" + gelLine.getId(), "6", ""))
                .andExpect(flash().attribute("errorMessage", "order.count.alreadySent"));
        assertThat(gelLine.getCountedQuantity()).isNull();
        assertThat(as(cook, get("/ship/order"))).contains("Counted by the Chief cook, to approve", "View count");

        // The Master: one short page and one decision.
        assertThat(as(master, get("/ship"))).contains("Deliveries to receive", "Counted by the Chief cook, to approve");
        String approve = as(master, get("/ship/orders/{id}/receipt", id));
        assertThat(approve).contains("Approve the delivery", "Counted by Demo Cook, Chief cook", "arrived as ordered",
                        "The 2 that are different", "Show all 3", "Meat comes tomorrow", "Send back to cook",
                        "Approving updates the stock and tells the office. It cannot be changed afterwards.", "Change date")
                .containsSubsequence("Tomatoes", "23 of 25 kg arrived", "2 kg rotten, sent back", "Change",
                        "Shower gel", "Not counted")
                .doesNotContain("name=\"lines[0].received\"", "Review");
        // Show all: every row, read only.
        assertThat(as(master, get("/ship/orders/{id}/receipt", id).param("all", "")))
                .containsSubsequence("All 3", "Eggs", "30 of 30 pcs arrived", "Tomatoes", "Shower gel");
        // Change one line: its input, then "Changed from 23".
        assertThat(as(master, get("/ship/orders/{id}/receipt", id).param("change", "o" + tomatoLine.getId())))
                .contains("action=\"/ship/orders/" + id + "/receipt/change\"", "value=\"23\"");
        assertThat(as(master, change("o" + gelLine.getId(), "2.5"))).contains("Use whole numbers for pcs.");
        mockMvc.perform(change("o" + tomatoLine.getId(), "24"))
                .andExpect(redirectedUrl("/ship/orders/" + id + "/receipt#row-o" + tomatoLine.getId()));
        assertThat(tomatoLine.getApprovedQuantity()).isEqualByComparingTo("24");
        assertThat(tomatoLine.getCountedQuantity()).isEqualByComparingTo("23");
        assertThat(as(master, get("/ship/orders/{id}/receipt", id))).contains("24 of 25 kg arrived", "Changed from 23");

        // Send back to cook: a note is required; the Master's change goes, the cook's numbers stay.
        mockMvc.perform(post("/ship/orders/{id}/receipt/return", id).param("returnNote", " ")
                        .with(user(new CurrentUser(master))).with(csrf()))
                .andExpect(flash().attribute("errorMessage", "order.count.return.noteRequired"));
        mockMvc.perform(post("/ship/orders/{id}/receipt/return", id).param("returnNote", "Count the shower gel")
                        .with(user(new CurrentUser(master))).with(csrf()))
                .andExpect(redirectedUrl("/ship/orders/" + id))
                .andExpect(flash().attribute("successMessage", "order.count.returned"));
        assertThat(order.getDeliveryCountStatus()).isEqualTo(DeliveryCountStatus.RETURNED);
        assertThat(tomatoLine.getApprovedQuantity()).isNull();
        assertThat(tomatoLine.getCountedQuantity()).isEqualByComparingTo("23");
        assertThat(notifications("cook@example.com")).singleElement().satisfies(n ->
                assertThat(n.getText()).isEqualTo("Order " + id + ": the Master sent the delivery count back: Count the shower gel"));
        // No count waits now: P51's form, prefilled as ordered.
        assertThat(as(master, get("/ship/orders/{id}/receipt", id))).doesNotContain("Approve the delivery")
                .contains("value=\"25\"");

        // The cook: the Master's note on top; he counts the gel and sends again.
        assertThat(as(cook, get("/ship/orders/{id}/count", id))).containsSubsequence("The Master sent the count back on",
                "Count the shower gel", "Shower gel");
        mockMvc.perform(row("o" + gelLine.getId(), "", ""));
        assertThat(gelLine.getCountedQuantity()).isEqualByComparingTo("6");
        mockMvc.perform(post("/ship/orders/{id}/count/send", id).param("note", "")
                        .with(user(new CurrentUser(cook))).with(csrf()))
                .andExpect(flash().attribute("successMessage", "order.count.sent"));
        assertThat(order.getCountNote()).isNull();

        // P51's form posts lead to the approval while a count waits.
        mockMvc.perform(post("/ship/orders/{id}/receipt/confirm", id).with(user(new CurrentUser(master))).with(csrf()))
                .andExpect(redirectedUrl("/ship/orders/" + id + "/receipt"));
        // Approve without any file: the receipt needs one.
        assertThat(as(master, post("/ship/orders/{id}/receipt/approve", id).with(csrf())))
                .contains("Photograph the invoice first", "href=\"/ship/orders/" + id + "/invoice\"");
        mockMvc.perform(multipart("/ship/orders/{id}/receipt/files", id)
                        .file(new MockMultipartFile("file", "receipt.jpg", "image/jpeg", JPEG))
                        .with(user(new CurrentUser(master))).with(csrf()))
                .andExpect(status().isNoContent());
        // Change date: not in the future.
        assertThat(as(master, post("/ship/orders/{id}/receipt/approve", id).with(csrf())
                .param("receivedAt", INPUT.format(LocalDateTime.now(ZoneOffset.UTC).plusDays(1)))))
                .contains("in the future");
        assertThat(order.getStatus()).isEqualTo(ShipOrderStatus.CONFIRMED);

        // Approve: closed, stocked with the approved numbers, received when the cook sent the count.
        mockMvc.perform(post("/ship/orders/{id}/receipt/approve", id).with(user(new CurrentUser(master))).with(csrf()))
                .andExpect(redirectedUrl("/ship/orders/" + id))
                .andExpect(flash().attribute("successMessage", "order.approve.done"));
        assertThat(order.getStatus()).isEqualTo(ShipOrderStatus.CLOSED);
        assertThat(order.getDeliveryCountStatus()).isEqualTo(DeliveryCountStatus.APPROVED);
        assertThat(order.getReceivedAt()).isEqualTo(order.getCountSentAt().truncatedTo(ChronoUnit.MINUTES));
        assertThat(order.getReceivedBy().getId()).isEqualTo(master.getId());
        assertThat(eggLine.getReceivedQuantity()).isEqualByComparingTo("30");
        assertThat(tomatoLine.getReceivedQuantity()).isEqualByComparingTo("23");
        assertThat(tomatoLine.getReceiptNote()).isEqualTo("2 kg rotten, sent back");
        assertThat(gelLine.getReceivedQuantity()).isEqualByComparingTo("6");
        assertThat(order.getShortLines()).isEqualTo(1);
        assertThat(movements()).hasSize(2).allSatisfy(m -> {
            assertThat(m.getType()).isEqualTo(StockMovementType.RECEIPT);
            assertThat(m.getNote()).isEqualTo("Counted by Demo Cook");
        });
        assertThat(audit("ORDER_RECEIVED")).singleElement()
                .satisfies(e -> assertThat(e.getDetails()).endsWith(", counted by Demo Cook"));
        assertThat(notifications("cook@example.com").getFirst().getText())
                .isEqualTo("Order " + id + " received: stock updated for 2 items");
        assertThat(page("/office/orders/" + id)).contains("Delivery count", "Counted by Demo Cook on");

        // Counting is refused once received.
        mockMvc.perform(get("/ship/orders/{id}/count", id).with(user(new CurrentUser(cook))))
                .andExpect(redirectedUrl("/ship/orders/" + id))
                .andExpect(flash().attribute("errorMessage", "order.count.received"));
        mockMvc.perform(row("o" + eggLine.getId(), "1", ""))
                .andExpect(flash().attribute("errorMessage", "order.count.received"));
        assertThat(eggLine.getCountedQuantity()).isEqualByComparingTo("30");
    }

    @Test
    void theInvoiceGuidesTheCountAndTheReceiptKeepsItForTheOffice() throws Exception {
        Supplier kestrel = supplier("Kestrel Ship Supply");
        ShipOrderLine riceLine = line(order, item("Basmati rice", "kg", ItemCategory.PROVISIONS), "120");
        ShipOrderLine fetaLine = line(order, item("Feta cheese", "kg", ItemCategory.PROVISIONS), "10");
        Item oil = item("Olive oil", "lt", ItemCategory.PROVISIONS);
        Item lemonade = item("Lemonade", "pcs", ItemCategory.PROVISIONS);
        DeliveryInvoice invoice = invoice(order, "KESTREL SHIP SUPPLY S.A.");
        DeliveryInvoiceLine eggs = invoiceLine(invoice, "EGGS LARGE", "PCS", "30");
        DeliveryInvoiceLine tomatoes = invoiceLine(invoice, "TOMATOES / Ντομάτες", "KG", "25");
        DeliveryInvoiceLine rice = invoiceLine(invoice, "BASMATI RICE 10KG", "BAG", "12");
        DeliveryInvoiceLine feta = invoiceLine(invoice, "FETA 2KG", "KG", "10");
        DeliveryInvoiceLine olive = invoiceLine(invoice, "OLIVE OIL 5LT", "CAN", "4");
        DeliveryInvoiceLine drink = invoiceLine(invoice, "SPARKLING DRINK XYZ", "PCS", "24");
        DeliveryInvoiceLine napkins = invoiceLine(invoice, "PAPER NAPKINS", "PCS", "10");

        // The count: the invoice's quantity per row, in its unit; never a price, never the ordered quantity.
        String page = as(cook, get("/ship/orders/{id}/count", id));
        assertThat(page).containsSubsequence("Basmati rice, 10 kg bag", "Invoice: 12 bags", "bags", "12 arrived",
                        "Eggs", "Invoice: 30 pcs", "30 arrived", "Feta cheese", "Invoice: 10 kg", "10 arrived",
                        "Olive oil, 5 lt can", "Invoice: 4 cans", "4 arrived", "Tomatoes", "Invoice: 25 kg",
                        "Shower gel", "Not on the invoice", ">Confirm<",
                        "On the invoice, not found on the order", "SPARKLING DRINK XYZ", "Choose the product",
                        "PAPER NAPKINS", "Choose the product")
                .contains("8 still to do")
                .doesNotContain("Ordered:", "120", "1.85", "price");
        assertThat(eggs.getMatch()).isEqualTo(InvoiceMatch.MATCHED);
        assertThat(rice.getMatch()).isEqualTo(InvoiceMatch.MATCHED);
        assertThat(rice.getOrderLine().getId()).isEqualTo(riceLine.getId());
        assertThat(rice.getPackFactor()).isEqualByComparingTo("10");
        assertThat(rice.isConversionToReview()).isTrue();
        assertThat(tomatoes.isConversionToReview()).isFalse();
        assertThat(feta.getMatch()).isEqualTo(InvoiceMatch.CHECK);
        assertThat(feta.getOrderLine().getId()).isEqualTo(fetaLine.getId());
        // An item of the vessel not on the order: its own row.
        assertThat(olive.getMatch()).isEqualTo(InvoiceMatch.MATCHED);
        assertThat(olive.getOrderLine()).isNull();
        assertThat(olive.getItem().getId()).isEqualTo(oil.getId());
        assertThat(drink.getMatch()).isEqualTo(InvoiceMatch.PICK);
        assertThat(napkins.getMatch()).isEqualTo(InvoiceMatch.PICK);

        // He counts what he sees in the invoice's unit: 12 bags are 120 kg.
        mockMvc.perform(row("o" + riceLine.getId(), "", ""));
        assertThat(riceLine.getCountedQuantity()).isEqualByComparingTo("120");
        // Typed: 11 bags; then the bags are whole numbers (the row is on Done now: shown there).
        assertThat(as(cook, row("o" + riceLine.getId(), "11.5", ""))).contains("Use whole numbers for bags.");
        mockMvc.perform(row("o" + riceLine.getId(), "11", "One bag torn"));
        assertThat(riceLine.getCountedQuantity()).isEqualByComparingTo("110");
        mockMvc.perform(row("o" + eggLine.getId(), "", ""));
        mockMvc.perform(row("o" + tomatoLine.getId(), "", ""));
        mockMvc.perform(row("o" + fetaLine.getId(), "", ""));
        assertThat(fetaLine.getCountedQuantity()).isEqualByComparingTo("10");
        mockMvc.perform(row("i" + olive.getId(), "", ""));
        assertThat(olive.getCountedQuantity()).isEqualByComparingTo("20");
        // Not on the invoice: no reference, a number is needed.
        assertThat(as(cook, row("o" + gelLine.getId(), "", ""))).contains("Enter what arrived of Shower gel: 0 if nothing arrived.");
        mockMvc.perform(row("o" + gelLine.getId(), "6", ""));

        // Choose the product: the order's lines nothing is matched to, the vessel's other items, Not ordered.
        String choose = as(cook, get("/ship/orders/{id}/count/choose/{l}", id, drink.getId()));
        assertThat(choose).contains("Choose the product", "SPARKLING DRINK XYZ", "On this order", "Shower gel",
                        "Other items of the vessel", "Lemonade", "Not ordered")
                .doesNotContain(">Basmati rice<", ">Olive oil<");
        mockMvc.perform(post("/ship/orders/{id}/count/choose/{l}", id, drink.getId()).param("choice", "i" + lemonade.getId())
                        .with(user(new CurrentUser(cook))).with(csrf()))
                .andExpect(redirectedUrl("/ship/orders/" + id + "/count#row-i" + drink.getId()))
                .andExpect(flash().attribute("successMessage", "order.count.choose.saved"));
        assertThat(drink.getMatch()).isEqualTo(InvoiceMatch.CHOSEN);
        assertThat(drink.getItem().getId()).isEqualTo(lemonade.getId());
        assertThat(nameRepository.findAll()).singleElement().satisfies(n -> {
            assertThat(n.getSupplier().getId()).isEqualTo(kestrel.getId());
            assertThat(n.getDescription()).isEqualTo("sparkling drink xyz");
            assertThat(n.getItem().getId()).isEqualTo(lemonade.getId());
            assertThat(n.getPackFactor()).isEqualByComparingTo("1");
            assertThat(n.getCreatedBy().getId()).isEqualTo(cook.getId());
        });
        assertThat(audit("ORDER_INVOICE_LINE_MATCHED")).extracting(AuditEntry::getDetails)
                .containsExactly("Order #" + id + ": \"SPARKLING DRINK XYZ\" is Lemonade");
        assertThat(as(cook, get("/ship/orders/{id}/count", id))).contains("Lemonade", "Invoice: 24 pcs", "24 arrived");
        mockMvc.perform(row("i" + drink.getId(), "", ""));
        // Not ordered: never a row, the office sees it.
        mockMvc.perform(post("/ship/orders/{id}/count/choose/{l}", id, napkins.getId()).param("choice", "none")
                        .with(user(new CurrentUser(cook))).with(csrf()))
                .andExpect(flash().attribute("successMessage", "order.count.choose.notOrdered"));
        assertThat(napkins.getMatch()).isEqualTo(InvoiceMatch.NOT_ORDERED);
        assertThat(as(cook, get("/ship/orders/{id}/count", id).param("tab", "done")))
                .contains("Not ordered", "PAPER NAPKINS", "Basmati rice, 10 kg bag", "11 bags", "One bag torn");
        assertThat(nameRepository.findAll()).hasSize(1);

        // All counted: Finish goes straight to the summary.
        String summary = as(cook, get("/ship/orders/{id}/count/send", id));
        assertThat(summary).contains("All 7 counted", "same as the invoice", "The 2 that are different")
                .containsSubsequence("Basmati rice, 10 kg bag", "11 of 12 bags arrived", "One bag torn",
                        "Shower gel", "6 pcs arrived, not on the invoice")
                .doesNotContain("Eggs");
        mockMvc.perform(post("/ship/orders/{id}/count/send", id).with(user(new CurrentUser(cook))).with(csrf()))
                .andExpect(flash().attribute("successMessage", "order.count.sent"));

        // The Master: the same short summary; no matches, conversions or prices.
        String approve = as(master, get("/ship/orders/{id}/receipt", id));
        assertThat(approve).contains("arrived as invoiced", "The 2 that are different")
                .containsSubsequence("Basmati rice, 10 kg bag", "11 of 12 bags arrived", "One bag torn")
                .doesNotContain("review", "conversion", "SPARKLING", "price");
        // His change is in the row's unit too: 12 bags.
        mockMvc.perform(change("o" + riceLine.getId(), "12"));
        assertThat(riceLine.getApprovedQuantity()).isEqualByComparingTo("120");
        mockMvc.perform(multipart("/ship/orders/{id}/receipt/files", id)
                        .file(new MockMultipartFile("file", "invoice.jpg", "image/jpeg", JPEG))
                        .with(user(new CurrentUser(master))).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/ship/orders/{id}/receipt/approve", id).with(user(new CurrentUser(master))).with(csrf()))
                .andExpect(flash().attribute("successMessage", "order.approve.done"));
        assertThat(order.getStatus()).isEqualTo(ShipOrderStatus.CLOSED);

        // The receipt per line, in the item's unit, with what the invoice said and the marks for the office.
        assertThat(riceLine.getReceivedQuantity()).isEqualByComparingTo("120");
        assertThat(riceLine.getCountedQuantity()).isEqualByComparingTo("110");
        assertThat(riceLine.getInvoicedQuantity()).isEqualByComparingTo("120");
        assertThat(riceLine.getInvoiceLine().getId()).isEqualTo(rice.getId());
        assertThat(riceLine.isConversionToReview()).isTrue();
        assertThat(riceLine.isMatchToReview()).isFalse();
        assertThat(fetaLine.isMatchToReview()).isTrue();
        assertThat(fetaLine.getInvoicedQuantity()).isEqualByComparingTo("10");
        assertThat(gelLine.getInvoicedQuantity()).isNull();
        assertThat(gelLine.getReceivedQuantity()).isEqualByComparingTo("6");
        // The vessel's items not on the order: lines added on receipt, ordered 0.
        List<ShipOrderLine> added = lineRepository.findAll().stream().filter(ShipOrderLine::isAddedOnReceipt).toList();
        assertThat(added).extracting(l -> l.getItem().getName()).containsExactlyInAnyOrder("Olive oil", "Lemonade");
        assertThat(added).filteredOn(l -> l.getItem().getId().equals(oil.getId())).singleElement().satisfies(l -> {
            assertThat(l.getQuantity()).isZero();
            assertThat(l.getReceivedQuantity()).isEqualByComparingTo("20");
            assertThat(l.getInvoicedQuantity()).isEqualByComparingTo("20");
            assertThat(l.isConversionToReview()).isTrue();
            assertThat(l.getShortBy()).isNull();
        });
        assertThat(olive.getOrderLine()).isNotNull();
        // Stocked: the provisions, with the approved numbers; the shower gel never.
        assertThat(movements()).hasSize(6);
        // The uncertain match the cook confirmed is learned too.
        assertThat(nameRepository.findAll()).extracting(SupplierItemName::getDescription)
                .containsExactlyInAnyOrder("sparkling drink xyz", "feta 2kg");
        assertThat(audit("ORDER_INVOICE_LINE_MATCHED")).hasSize(2);

        // The next invoice of that supplier: matched automatically, no longer to review.
        ShipOrder next = order(ship);
        ShipOrderLine nextFeta = line(next, fetaLine.getItem(), "5");
        DeliveryInvoice nextInvoice = invoice(next, "Kestrel Ship Supply");
        DeliveryInvoiceLine nextLine = invoiceLine(nextInvoice, "Feta 2 kg", "KG", "5");
        DeliveryInvoiceLine nextDrink = invoiceLine(nextInvoice, "Sparkling drink - XYZ", "PCS", "12");
        as(cook, get("/ship/orders/{id}/count", next.getId()));
        assertThat(nextLine.getMatch()).isEqualTo(InvoiceMatch.LEARNED);
        assertThat(nextLine.getOrderLine().getId()).isEqualTo(nextFeta.getId());
        assertThat(nextDrink.getMatch()).isEqualTo(InvoiceMatch.LEARNED);
        assertThat(nextDrink.getItem().getId()).isEqualTo(lemonade.getId());
        // Another supplier's invoice with the same words: not learned from Kestrel.
        ShipOrder third = order(ship);
        line(third, fetaLine.getItem(), "5");
        DeliveryInvoiceLine otherDrink = invoiceLine(invoice(third, "Atlas Provisions"), "SPARKLING DRINK XYZ", "PCS", "12");
        as(cook, get("/ship/orders/{id}/count", third.getId()));
        assertThat(otherDrink.getMatch()).isEqualTo(InvoiceMatch.PICK);
    }

    @Test
    void anInvoiceQuantityNotReadIsCountedInTheItemsUnit() throws Exception {
        DeliveryInvoice invoice = invoice(order, "Atlas Provisions");
        DeliveryInvoiceLine tomatoes = invoiceLine(invoice, "Tomatoes", "KG", null);
        tomatoes.setQuantityNotRead(true);
        invoiceLine(invoice, "Eggs", "TRAY", "1");
        String page = as(cook, get("/ship/orders/{id}/count", id));
        // Tomatoes: on the invoice, not read; eggs by the tray: no factor to work out, so the item's unit.
        assertThat(page).containsSubsequence("Eggs", "Invoice: not read", "Tomatoes", "Invoice: not read")
                .doesNotContain("trays");
        mockMvc.perform(row("o" + eggLine.getId(), "28", ""));
        assertThat(eggLine.getCountedQuantity()).isEqualByComparingTo("28");
        // Learned for an unknown supplier under its name as read.
        mockMvc.perform(post("/ship/orders/{id}/count/choose/{l}", id, tomatoes.getId())
                        .param("choice", "o" + gelLine.getId()).with(user(new CurrentUser(cook))).with(csrf()));
        assertThat(nameRepository.findAll()).singleElement().satisfies(n -> {
            assertThat(n.getSupplier()).isNull();
            assertThat(n.getSupplierName()).isEqualTo("atlas provisions");
        });
    }

    @Test
    void onlyTheVesselsChiefCookCountsAndOnlyItsMasterApproves() throws Exception {
        User crew = userService.createUser("crew@example.com", "Ana Santos", "secret123", Rank.ABLE_SEAMAN.role(), ship,
                Rank.ABLE_SEAMAN);
        Ship other = ship("9176187", "MV Other");
        User otherCook = userService.createUser("cook2@example.com", "Other Cook", "secret123",
                Rank.CHIEF_COOK.role(), other, Rank.CHIEF_COOK);
        User otherMaster = userService.createUser("master2@example.com", "Other Master", "secret123",
                Rank.MASTER.role(), other, Rank.MASTER);

        for (User user : List.of(master, crew)) {
            mockMvc.perform(get("/ship/orders/{id}/count", id).with(user(new CurrentUser(user))))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/ship/orders/{id}/count/row", id).with(user(new CurrentUser(user))).with(csrf()))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/ship/orders/{id}/count", id)).andExpect(status().isForbidden());
        mockMvc.perform(get("/ship/orders/{id}/count", id).with(user(new CurrentUser(otherCook))))
                .andExpect(status().isNotFound());
        mockMvc.perform(row("o" + eggLine.getId(), "30", "").with(user(new CurrentUser(otherCook))))
                .andExpect(status().isNotFound());
        assertThat(eggLine.getCountedQuantity()).isNull();

        // Sent, then only the vessel's Master changes, approves or sends it back.
        mockMvc.perform(post("/ship/orders/{id}/count/send", id).with(user(new CurrentUser(cook))).with(csrf()))
                .andExpect(flash().attribute("successMessage", "order.count.sent"));
        for (String path : List.of("/receipt/return", "/receipt/approve", "/receipt/change")) {
            mockMvc.perform(post("/ship/orders/" + id + path).param("returnNote", "No")
                            .with(user(new CurrentUser(cook))).with(csrf()))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/ship/orders/" + id + path).param("returnNote", "No")
                            .with(user(new CurrentUser(otherMaster))).with(csrf()))
                    .andExpect(status().isNotFound());
        }
        assertThat(order.getDeliveryCountStatus()).isEqualTo(DeliveryCountStatus.SENT_TO_MASTER);
        assertThat(order.getStatus()).isEqualTo(ShipOrderStatus.CONFIRMED);

        // Not scheduled yet: nothing to count.
        ShipOrder sent = new ShipOrder();
        sent.setShip(ship);
        sent.setStatus(ShipOrderStatus.SENT);
        sent.setCreatedAt(Instant.now());
        sent.setSentBy(master);
        sent.setSentAt(Instant.now());
        orderRepository.save(sent);
        mockMvc.perform(get("/ship/orders/{id}/count", sent.getId()).with(user(new CurrentUser(cook))))
                .andExpect(redirectedUrl("/ship/orders/" + sent.getId()))
                .andExpect(flash().attribute("errorMessage", "order.count.notConfirmed"));
        assertThat(as(cook, get("/ship/order"))).doesNotContain("href=\"/ship/orders/" + sent.getId() + "/count\"");
    }

    // ---------------------------------------------------------------------------------------------------------------

    // One row confirmed by the cook.
    private MockHttpServletRequestBuilder row(String key, String quantity, String note) {
        return post("/ship/orders/{id}/count/row", id).param("key", key).param("quantity", quantity)
                .param("note", note).with(user(new CurrentUser(cook))).with(csrf());
    }

    // The Master changes one row.
    private MockHttpServletRequestBuilder change(String key, String quantity) {
        return post("/ship/orders/{id}/receipt/change", id).param("key", key).param("quantity", quantity)
                .with(user(new CurrentUser(master))).with(csrf());
    }

    private String as(User user, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(user(new CurrentUser(user)))).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String page(String url) throws Exception {
        return mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private List<Notification> notifications(String email) {
        return notificationRepository.findAllByUserEmailOrderByCreatedAtDescIdDesc(email);
    }

    private List<StockMovement> movements() {
        return stockMovementRepository.findOnShip(ship.getId(), List.of(StockMovementType.values()), Instant.EPOCH,
                Instant.now().plus(1, ChronoUnit.DAYS), Pageable.unpaged()).getContent();
    }

    private List<AuditEntry> audit(String action) {
        return auditRepository.findAll().stream().filter(e -> e.getAction().equals(action)).toList();
    }

    private Ship ship(String imo, String name) {
        Ship created = new Ship();
        created.setImoNumber(imo);
        created.setName(name);
        created.setCreatedAt(Instant.now());
        return shipRepository.save(created);
    }

    // Sent yesterday, delivery scheduled today.
    private ShipOrder order(Ship of) {
        ShipOrder created = new ShipOrder();
        created.setShip(of);
        created.setStatus(ShipOrderStatus.CONFIRMED);
        created.setCreatedAt(Instant.now().minus(2, ChronoUnit.DAYS));
        created.setSentBy(master);
        created.setSentAt(Instant.now().minus(1, ChronoUnit.DAYS));
        created.setRequestedPort("Rotterdam");
        created.setNeededBy(LocalDate.now(ZoneOffset.UTC).plusDays(5));
        created.setConfirmedPort("Antwerp");
        created.setDeliveryDate(LocalDate.now(ZoneOffset.UTC));
        return orderRepository.save(created);
    }

    private ShipOrderLine line(ShipOrder of, Item item, String quantity) {
        ShipOrderLine line = new ShipOrderLine();
        line.setOrder(of);
        line.setItem(item);
        line.setQuantity(new BigDecimal(quantity));
        line.setOriginalQuantity(new BigDecimal(quantity));
        of.getLines().add(lineRepository.save(line));
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

    private Supplier supplier(String name) {
        Supplier supplier = new Supplier();
        supplier.setName(name);
        supplier.setEmail("sales@example.com");
        supplier.setCurrency("EUR");
        supplier.setCreatedAt(Instant.now());
        return supplierRepository.save(supplier);
    }

    // An invoice read from the order's photos (P62).
    private DeliveryInvoice invoice(ShipOrder of, String supplier) {
        DeliveryInvoice invoice = new DeliveryInvoice();
        invoice.setOrder(of);
        invoice.setStatus(DeliveryInvoiceStatus.READ);
        invoice.setSupplierName(supplier);
        invoice.setReadAt(Instant.now());
        return invoiceRepository.save(invoice);
    }

    private DeliveryInvoiceLine invoiceLine(DeliveryInvoice invoice, String description, String unit, String quantity) {
        DeliveryInvoiceLine line = new DeliveryInvoiceLine();
        line.setInvoice(invoice);
        line.setPage(1);
        line.setDescription(description);
        line.setUnit(unit);
        line.setQuantity(quantity == null ? null : new BigDecimal(quantity));
        line.setReadQuantity(line.getQuantity());
        return invoiceLineRepository.save(line);
    }
}
