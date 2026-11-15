package com.nereonix.nereonet_mvp.shiporder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditEntry;
import com.nereonix.nereonet_mvp.core.audit.AuditRepository;
import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemCategory;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.notification.NotificationRepository;
import com.nereonix.nereonet_mvp.core.notification.NotificationType;
import com.nereonix.nereonet_mvp.core.order.OrderReceiptFile;
import com.nereonix.nereonet_mvp.core.order.OrderReceiptFileRepository;
import com.nereonix.nereonet_mvp.core.order.OrderReceiptService;
import com.nereonix.nereonet_mvp.core.order.ShipOrder;
import com.nereonix.nereonet_mvp.core.order.ShipOrderLine;
import com.nereonix.nereonet_mvp.core.order.ShipOrderLineRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrderRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrderService;
import com.nereonix.nereonet_mvp.core.order.ShipOrderService.SendDetails;
import com.nereonix.nereonet_mvp.core.order.ShipOrderStatus;
import com.nereonix.nereonet_mvp.core.request.ItemRequest;
import com.nereonix.nereonet_mvp.core.request.RequestLine;
import com.nereonix.nereonet_mvp.core.request.RequestService;
import com.nereonix.nereonet_mvp.core.request.RequestStatus;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserService;

/**
 * P35, the Master's ship order: the OPEN order shows its lines by category with who asked for each; a line's quantity
 * is changed or the line removed, each saved at once; "Send to office" makes it SENT and read-only, records it and
 * notifies every enabled office user. Past orders are listed with their status. Only the Master of the ship changes
 * it; P55: the Chief cook reads the same pages, with nothing to act on, and the services refuse them. P59: the orders
 * in progress (sent, delivery scheduled) are on Vessel order above the collecting one, most urgent first, the Master's
 * scheduled deliveries with Confirm receipt; "Past orders" is "All orders"; the sidebar counter counts the orders that
 * need the Master, not lines.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ShipOrderFlowTest {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final Pattern PROGRESS_CARD = Pattern.compile("(?s)<li [^>]*data-order=\"(\\d+)\"[^>]*>.*?</li>");
    private static final Pattern ORDER_NAV = Pattern.compile("(?s)<li class=\"nav-item\" data-nav=\"order\">.*?</li>");
    private static final Pattern NAV_COUNT = Pattern.compile("<span class=\"nav-count num\" aria-hidden=\"true\">(\\d+)</span>");

    @Autowired MockMvc mockMvc;
    @Autowired ShipRepository shipRepository;
    @Autowired UserService userService;
    @Autowired ItemRepository itemRepository;
    @Autowired RequestService requestService;
    @Autowired ShipOrderRepository orderRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired AuditRepository auditRepository;
    @Autowired ShipOrderService shipOrderService;
    @Autowired OrderReceiptService receiptService;
    @Autowired OrderReceiptFileRepository receiptFileRepository;
    @Autowired ShipOrderLineRepository lineRepository;

    Ship ship;
    User master;
    User cook;
    User crewMember;
    User otherMaster;
    User office;
    User disabledOffice;
    Item rice;
    Item soap;

    @BeforeEach
    void setUp() {
        ship = ship("9074729", "MV Demo");
        Ship otherShip = ship("9123456", "MV Other");
        master = crew("master@example.com", "Ship Master", Rank.MASTER, ship);
        cook = crew("cook@example.com", "Demo Cook", Rank.CHIEF_COOK, ship);
        crewMember = crew("ab@example.com", "Ana Santos", Rank.ABLE_SEAMAN, ship);
        otherMaster = crew("other.master@example.com", "Other Master", Rank.MASTER, otherShip);
        office = userService.createUser("office.order@example.com", "Office Order", "secret123", Role.OFFICE_USER,
                null, null);
        disabledOffice = userService.createUser("office.gone@example.com", "Office Gone", "secret123",
                Role.OFFICE_USER, null, null);
        disabledOffice.setEnabled(false);
        rice = item("Basmati rice", "kg", ItemCategory.PROVISIONS);
        soap = item("Shower gel", "pcs", ItemCategory.PERSONAL_ITEMS);
    }

    @Test
    void theMasterChangesAQuantityAndSendsTheOrderToTheOffice() throws Exception {
        ShipOrder order = openOrderWithTwoLines();
        ShipOrderLine riceLine = line(order, rice);
        assertThat(page("/ship/order")).contains("Vessel order", "1 order needs you");

        changeQuantity(riceLine, "12").andExpect(redirectedUrl("/ship/order"))
                .andExpect(flash().attribute("successMessage", "order.quantity.saved"));
        assertThat(riceLine.getQuantity()).isEqualByComparingTo("12");

        LocalDate neededBy = LocalDate.now(ZoneOffset.UTC).plusDays(10);
        send("Rotterdam", neededBy.toString(), "Deliver to the gangway")
                .andExpect(redirectedUrl("/ship/orders/" + order.getId()))
                .andExpect(flash().attribute("successMessage", "order.sent"));

        assertThat(order.getStatus()).isEqualTo(ShipOrderStatus.SENT);
        assertThat(order.getRequestedPort()).isEqualTo("Rotterdam");
        assertThat(order.getNeededBy()).isEqualTo(neededBy);
        assertThat(order.getOfficeNote()).isEqualTo("Deliver to the gangway");
        assertThat(order.getSentBy().getId()).isEqualTo(master.getId());
        assertThat(order.getSentAt()).isNotNull();
        assertThat(notificationRepository.countByUserEmailAndReadAtIsNull(office.getEmail())).isEqualTo(1);
        assertThat(notificationRepository.findAllByUserEmailOrderByCreatedAtDescIdDesc(office.getEmail()))
                .singleElement().satisfies(n -> {
                    assertThat(n.getText()).isEqualTo("New order from MV Demo");
                    assertThat(n.getType()).isEqualTo(NotificationType.ORDER);
                });
        assertThat(notificationRepository.countByUserEmailAndReadAtIsNull(disabledOffice.getEmail())).isZero();
        // Editing the order notified nobody on the ship.
        assertThat(notificationRepository.countByUserEmailAndReadAtIsNull(cook.getEmail())).isEqualTo(1);
        assertThat(auditRepository.findAll()).filteredOn(e -> e.getAction().equals("ORDER_SENT"))
                .singleElement().extracting(AuditEntry::getDetails)
                .isEqualTo("Order #" + order.getId() + ", 2 lines, Rotterdam, needed by " + neededBy);

        // Read-only now: the detail page has no forms, the order page is empty and edits change nothing.
        String detail = page("/ship/orders/" + order.getId());
        assertThat(detail).contains("Sent to office", "Rotterdam", "Deliver to the gangway", "Basmati rice")
                .doesNotContain("/ship/order/lines/");
        // P59: the sent order is in progress, above the empty collecting order.
        assertThat(main(page("/ship/order"))).containsSubsequence("Orders in progress", "Order #" + order.getId(),
                "Sent to office", "2 items", "waiting for the office to schedule the delivery", "Current order",
                "Nothing to order yet", "Approved requests are collected here.");
        changeQuantity(riceLine, "1").andExpect(flash().attribute("errorMessage", "order.notOpen"));
        assertThat(riceLine.getQuantity()).isEqualByComparingTo("12");

        // The next approved request opens a new OPEN order.
        approve(submit(cook, line(rice, "3")));
        assertThat(orderRepository.findAllByShipIdAndStatus(ship.getId(), ShipOrderStatus.OPEN))
                .singleElement().satisfies(open -> assertThat(open.getId()).isNotEqualTo(order.getId()));
    }

    @Test
    void theOrderShowsItsLinesByCategoryWithWhoAskedForThem() throws Exception {
        openOrderWithTwoLines();

        String html = page("/ship/order");

        assertThat(html).contains("Provisions", "Personal items", "Basmati rice", "Shower gel",
                "Chief Cook 6, A. Santos 4", "A. Santos 2", "Collecting", "Send to office");
        assertThat(html.indexOf("Provisions")).isLessThan(html.indexOf("Personal items"));
    }

    @Test
    void withoutAnOpenOrderThereIsNothingToOrder() throws Exception {
        String html = page("/ship/order");

        assertThat(html).contains("Nothing to order yet", "Approved requests are collected here.")
                .doesNotContain("/ship/order/send", "Orders in progress", "Current order");
        send("Rotterdam", LocalDate.now(ZoneOffset.UTC).toString(), "")
                .andExpect(flash().attribute("errorMessage", "order.nothingToSend"));
    }

    @Test
    void aQuantityMustBeAboveZero() throws Exception {
        ShipOrderLine riceLine = line(openOrderWithTwoLines(), rice);

        for (String bad : List.of("0", "-1", "", "abc", "1.2345")) {
            changeQuantity(riceLine, bad).andExpect(redirectedUrl("/ship/order"))
                    .andExpect(flash().attribute("errorMessage", "order.quantity.error"));
        }
        assertThat(riceLine.getQuantity()).isEqualByComparingTo("10");
    }

    @Test
    void removingALineKeepsTheRequestsApproved() throws Exception {
        ShipOrder order = openOrderWithTwoLines();
        ShipOrderLine soapLine = line(order, soap);

        mockMvc.perform(post("/ship/order/lines/" + soapLine.getId() + "/remove")
                        .with(user(new CurrentUser(master))).with(csrf()))
                .andExpect(redirectedUrl("/ship/order"))
                .andExpect(flash().attribute("successMessage", "order.line.removed"));

        assertThat(order.getLines()).singleElement().satisfies(l -> assertThat(l.getItem().getId()).isEqualTo(rice.getId()));
        assertThat(page("/ship/order")).doesNotContain("Shower gel");
    }

    @Test
    void theSendFormNeedsAPortAndADateNotInThePast() throws Exception {
        ShipOrder order = openOrderWithTwoLines();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String range = "Enter a date between " + DAY.format(today) + " and " + DAY.format(today.plusYears(2)) + ".";

        // P39: from today to two years ahead, the shared date rule; the picker offers the same days.
        String html = send("  ", today.minusDays(1).toString(), "")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("Enter the requested port.", range)
                .contains("min=\"" + today + "\"", "max=\"" + today.plusYears(2) + "\"");
        assertThat(send("Rotterdam", today.plusYears(2).plusDays(1).toString(), "")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains(range);
        assertThat(order.getStatus()).isEqualTo(ShipOrderStatus.OPEN);
        assertThat(notificationRepository.countByUserEmailAndReadAtIsNull(office.getEmail())).isZero();
    }

    @Test
    void pastOrdersAreListedNewestFirstWithTheirStatus() throws Exception {
        ShipOrder first = openOrderWithTwoLines();
        send("Rotterdam", LocalDate.now(ZoneOffset.UTC).plusDays(5).toString(), "");
        first.setStatus(ShipOrderStatus.CONFIRMED);
        first.setConfirmedPort("Antwerp");
        first.setDeliveryDate(LocalDate.now(ZoneOffset.UTC).plusDays(7));
        approve(submit(cook, line(rice, "1")));
        ShipOrder second = orderRepository.findByShipIdAndStatus(ship.getId(), ShipOrderStatus.OPEN).orElseThrow();
        send("Piraeus", LocalDate.now(ZoneOffset.UTC).plusDays(9).toString(), "");
        second.setSentAt(first.getSentAt().plusSeconds(60));

        String html = page("/ship/orders");

        assertThat(html).contains("<title>All orders · Nereonet</title>", "<span>All orders</span>", "Delivery scheduled",
                "Delivery: Antwerp", "Sent to office", "Requested: Piraeus");
        assertThat(html.indexOf("Order #" + second.getId())).isLessThan(html.indexOf("Order #" + first.getId()));
        mockMvc.perform(get("/ship/orders/" + openOrderAfter().getId()).with(user(new CurrentUser(master))))
                .andExpect(redirectedUrl("/ship/order"));
    }

    @Test
    void onlyTheMasterOfTheShipUsesTheOrder() throws Exception {
        ShipOrder order = openOrderWithTwoLines();
        ShipOrderLine riceLine = line(order, rice);

        mockMvc.perform(get("/ship/order").with(user(new CurrentUser(crewMember)))).andExpect(status().isForbidden());
        mockMvc.perform(get("/ship/orders").with(user(new CurrentUser(crewMember)))).andExpect(status().isForbidden());
        mockMvc.perform(get("/ship/orders/" + order.getId()).with(user(new CurrentUser(crewMember))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/ship/order/lines/" + riceLine.getId() + "/quantity").param("quantity", "1")
                .with(user(new CurrentUser(cook))).with(csrf())).andExpect(status().isForbidden());
        mockMvc.perform(post("/ship/order/lines/" + riceLine.getId() + "/remove")
                .with(user(new CurrentUser(cook))).with(csrf())).andExpect(status().isForbidden());
        mockMvc.perform(post("/ship/order/lines/" + riceLine.getId() + "/quantity").param("quantity", "1")
                .with(user(new CurrentUser(otherMaster))).with(csrf())).andExpect(status().isNotFound());
        mockMvc.perform(post("/ship/order/lines/" + riceLine.getId() + "/remove")
                .with(user(new CurrentUser(otherMaster))).with(csrf())).andExpect(status().isNotFound());
        mockMvc.perform(get("/ship/orders/" + order.getId()).with(user(new CurrentUser(otherMaster))))
                .andExpect(status().isNotFound());
        assertThat(riceLine.getQuantity()).isEqualByComparingTo("10");
        assertThat(order.getLines()).hasSize(2);
    }

    @Test
    void theChiefCookReadsTheOpenOrderWithNothingToActOn() throws Exception {
        openOrderWithTwoLines();

        String html = cookPage("/ship/order");
        String main = main(html);

        assertThat(main).contains("Provisions", "Personal items", "Basmati rice", "Shower gel",
                "Chief Cook 6, A. Santos 4", "Collecting", "Only the Master can change or send the order.")
                .doesNotContain("<form", "<input", "/ship/order/lines/", "/ship/order/send", ">Send to office<",
                        ">Remove<");
        // The sidebar's Vessel order, without the Master's counter.
        assertThat(html).contains("href=\"/ship/order\"").doesNotContain("need you</span>");
        assertThat(page("/ship/order")).contains("1 order needs you");
    }

    @Test
    void theChiefCookReadsASentOrderWithItsLinesAndStatus() throws Exception {
        ShipOrder order = openOrderWithTwoLines();
        LocalDate neededBy = LocalDate.now(ZoneOffset.UTC).plusDays(10);
        send("Rotterdam", neededBy.toString(), "");
        order.setStatus(ShipOrderStatus.CONFIRMED);
        order.setConfirmedPort("Antwerp");
        order.setDeliveryDate(neededBy.plusDays(1));

        String list = main(cookPage("/ship/orders"));
        assertThat(list).contains("Order #" + order.getId(), "Delivery scheduled", "Requested: Rotterdam",
                DAY.format(neededBy), "Delivery: Antwerp", DAY.format(neededBy.plusDays(1)),
                "href=\"/ship/orders/" + order.getId() + "\"");

        String detail = main(cookPage("/ship/orders/" + order.getId()));
        assertThat(detail).contains("Delivery scheduled", "Basmati rice", "Shower gel", "Chief Cook 6, A. Santos 4",
                "Rotterdam", "Antwerp", "Only the Master can change or send the order.")
                .doesNotContain("<form", "<input", "Confirm receipt", "/receipt", ">Send to office<");
        // The Master still has Confirm receipt.
        assertThat(page("/ship/orders/" + order.getId())).contains("Confirm receipt")
                .doesNotContain("Only the Master can change or send the order.");
    }

    @Test
    void theChiefCookSeesWhatWasReceivedButNotTheReceiptFiles() throws Exception {
        ShipOrder order = openOrderWithTwoLines();
        send("Rotterdam", LocalDate.now(ZoneOffset.UTC).plusDays(10).toString(), "");
        line(order, rice).setReceivedQuantity(new BigDecimal("7"));
        line(order, soap).setReceivedQuantity(new BigDecimal("2"));
        order.setStatus(ShipOrderStatus.CLOSED);
        order.setReceivedAt(Instant.now());
        order.setReceivedBy(master);
        OrderReceiptFile file = new OrderReceiptFile();
        file.setOrder(order);
        file.setFileName("delivery-note.pdf");
        file.setFileContentType("application/pdf");
        file.setFileSize(100);
        file.setStoragePath("receipts/none.pdf");
        file.setUploadedBy(master);
        file.setUploadedAt(Instant.now());
        receiptFileRepository.save(file);

        String detail = main(cookPage("/ship/orders/" + order.getId()));
        assertThat(detail).contains("Received", ">7<", "Short by 3 kg")
                .doesNotContain("delivery-note.pdf", "/receipt/files/", "Delivery receipt");
        assertThat(page("/ship/orders/" + order.getId())).contains("delivery-note.pdf");
        mockMvc.perform(get("/ship/orders/{id}/receipt/files/{f}", order.getId(), file.getId())
                .with(user(new CurrentUser(cook)))).andExpect(status().isForbidden());
    }

    @Test
    void theChiefCookCannotSendTheOrder() throws Exception {
        ShipOrder order = openOrderWithTwoLines();

        mockMvc.perform(post("/ship/order/send").param("requestedPort", "Rotterdam")
                        .param("neededBy", LocalDate.now(ZoneOffset.UTC).plusDays(5).toString())
                        .with(user(new CurrentUser(cook))).with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(order.getStatus()).isEqualTo(ShipOrderStatus.OPEN);
        assertThat(order.getSentAt()).isNull();
        assertThat(order.getRequestedPort()).isNull();
        assertThat(notificationRepository.countByUserEmailAndReadAtIsNull(office.getEmail())).isZero();
        assertThat(auditRepository.findAll()).noneMatch(e -> e.getAction().equals("ORDER_SENT"));
    }

    // P59: Vessel order lists the orders sent and not received yet above the collecting one, most urgent first: the
    // scheduled deliveries by delivery date, then the orders waiting for the office by sent date. The Master confirms
    // receipt from the card; the Chief cook only opens the order. Received orders are only under All orders.
    @Test
    void ordersInProgressAreAboveTheCollectingOrderMostUrgentFirst() throws Exception {
        openOrderWithTwoLines();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant now = Instant.now();
        ShipOrder waitingLonger = sentOrder(ShipOrderStatus.SENT, now.minus(Duration.ofDays(3)), null, null, rice);
        ShipOrder waiting = sentOrder(ShipOrderStatus.SENT, now.minus(Duration.ofDays(1)), null, null, rice, soap);
        ShipOrder later = sentOrder(ShipOrderStatus.CONFIRMED, now.minus(Duration.ofDays(10)), "Hamburg",
                today.plusDays(9), rice);
        ShipOrder sooner = sentOrder(ShipOrderStatus.CONFIRMED, now.minus(Duration.ofDays(5)), "Antwerp",
                today.plusDays(5), rice, soap);
        ShipOrder received = sentOrder(ShipOrderStatus.CLOSED, now.minus(Duration.ofDays(30)), "Piraeus",
                today.minusDays(20), rice);
        // A line the office removed is not an item of the order.
        line(sooner, soap).setRemoved(true);

        String main = main(page("/ship/order"));
        String progress = main.substring(main.indexOf("id=\"inProgressTitle\""), main.indexOf(">Current order<"));
        assertThat(PROGRESS_CARD.matcher(progress).results().map(m -> Long.valueOf(m.group(1))).toList())
                .containsExactly(sooner.getId(), later.getId(), waitingLonger.getId(), waiting.getId());
        assertThat(progress).doesNotContain("Order #" + received.getId(), "Piraeus");
        assertThat(card(progress, sooner)).contains("Order #" + sooner.getId(), "Delivery scheduled", "1 item",
                        "Delivery: Antwerp, " + DAY.format(today.plusDays(5)),
                        "href=\"/ship/orders/" + sooner.getId() + "/receipt\"", ">Confirm receipt<",
                        "aria-label=\"Confirm receipt of order #" + sooner.getId() + "\"",
                        "href=\"/ship/orders/" + sooner.getId() + "\"", ">Open order<",
                        "aria-label=\"Open order #" + sooner.getId() + "\"")
                .doesNotContain("is-overdue", "was due");
        assertThat(card(progress, waiting)).contains("Sent to office", "2 items", "Sent on "
                        + DAY.format(waiting.getSentAt().atZone(ZoneOffset.UTC)) + " · waiting for the office to schedule the delivery",
                        ">Open order<")
                .doesNotContain("/receipt", "Confirm receipt");
        // The collecting order follows, unchanged, under its own heading.
        assertThat(main).containsSubsequence(">Orders in progress<", ">Current order<", "Collecting", "Basmati rice",
                "Chief Cook 6, A. Santos 4", "Send to office");

        // The Chief cook sees the same cards, with Open order only.
        String cook = main(cookPage("/ship/order"));
        assertThat(PROGRESS_CARD.matcher(cook).results().map(m -> Long.valueOf(m.group(1))).toList())
                .containsExactly(sooner.getId(), later.getId(), waitingLonger.getId(), waiting.getId());
        assertThat(cook).contains("Delivery: Antwerp", ">Open order<").doesNotContain("/receipt", "Confirm receipt");

        // All orders (Past orders before P59) still lists every sent order, the received one too.
        String all = main(page("/ship/orders"));
        for (ShipOrder o : List.of(sooner, later, waitingLonger, waiting, received)) {
            assertThat(all).contains("href=\"/ship/orders/" + o.getId() + "\"");
        }
        assertThat(main).contains("href=\"/ship/orders\">All orders</a>");
    }

    @Test
    void aDeliveryWhoseDateHasPassedStaysOnTopAndSaysSo() throws Exception {
        LocalDate due = LocalDate.now(ZoneOffset.UTC).minusDays(3);
        ShipOrder waiting = sentOrder(ShipOrderStatus.SENT, Instant.now().minus(Duration.ofDays(1)), null, null, rice);
        ShipOrder overdue = sentOrder(ShipOrderStatus.CONFIRMED, Instant.now().minus(Duration.ofDays(20)), "Antwerp",
                due, rice);

        String main = main(page("/ship/order"));
        assertThat(PROGRESS_CARD.matcher(main).results().map(m -> Long.valueOf(m.group(1))).toList())
                .containsExactly(overdue.getId(), waiting.getId());
        // In words, with a warning icon: not colour alone.
        assertThat(card(main, overdue)).contains("is-overdue", "status-warning",
                        "Delivery was due " + DAY.format(due) + ", Antwerp", ">Confirm receipt<")
                .doesNotContain("Delivery: Antwerp");
        assertThat(card(main, waiting)).doesNotContain("is-overdue", "was due");
    }

    // P59: the sidebar counter of Vessel order counts the orders that need the Master: the OPEN order once it has a
    // line (to send) and the scheduled deliveries (to receive); not lines, not the orders waiting for the office.
    @Test
    void theOrderCounterCountsTheOrdersThatNeedTheMaster() throws Exception {
        ShipOrder empty = new ShipOrder();
        empty.setShip(ship);
        empty.setStatus(ShipOrderStatus.OPEN);
        empty.setCreatedAt(Instant.now());
        orderRepository.save(empty);
        assertThat(orderCounter(master)).as("an empty OPEN order").isZero();

        sentOrder(ShipOrderStatus.SENT, Instant.now().minus(Duration.ofDays(2)), null, null, rice);
        assertThat(orderCounter(master)).as("a SENT order only").isZero();

        Item oil = item("Olive oil", "lt", ItemCategory.PROVISIONS);
        for (Item item : List.of(rice, soap, oil)) {
            ShipOrderLine line = new ShipOrderLine();
            line.setOrder(empty);
            line.setItem(item);
            line.setQuantity(BigDecimal.ONE);
            empty.getLines().add(lineRepository.save(line));
        }
        lineRepository.flush();
        assertThat(orderCounter(master)).as("an OPEN order with 3 lines").isEqualTo(1);
        assertThat(page("/ship/library")).contains("<span class=\"visually-hidden\">1 order needs you</span>");

        sentOrder(ShipOrderStatus.CONFIRMED, Instant.now().minus(Duration.ofDays(5)), "Antwerp",
                LocalDate.now(ZoneOffset.UTC).plusDays(3), rice);
        sentOrder(ShipOrderStatus.CLOSED, Instant.now().minus(Duration.ofDays(30)), "Piraeus",
                LocalDate.now(ZoneOffset.UTC).minusDays(20), rice);
        assertThat(orderCounter(master)).as("the OPEN order with lines and one CONFIRMED").isEqualTo(2);
        assertThat(page("/ship/library")).contains("<span class=\"visually-hidden\">2 orders need you</span>");
        // The More page says the same.
        assertThat(page("/ship/more")).contains("2 orders need you");

        // The Chief cook has the entry and no counter; another ship's Master counts their own ship.
        String cookNav = section(ORDER_NAV, cookPage("/ship/library"));
        assertThat(cookNav).contains("href=\"/ship/order\"").doesNotContain("nav-count");
        assertThat(orderCounter(otherMaster)).isZero();
    }

    // Security does not depend on the pages: the services refuse the Chief cook too (P55).
    @Test
    void theServicesRefuseAnyoneButTheMaster() throws Exception {
        ShipOrder order = openOrderWithTwoLines();
        ShipOrderLine riceLine = line(order, rice);
        SendDetails details = new SendDetails("Rotterdam", LocalDate.now(ZoneOffset.UTC).plusDays(5), null);

        for (User other : List.of(cook, crewMember, office)) {
            assertThatThrownBy(() -> shipOrderService.send(other, details)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> shipOrderService.changeQuantity(other, riceLine.getId(), BigDecimal.ONE))
                    .isInstanceOf(AccessDeniedException.class);
            // Refused before the quantity is checked.
            assertThatThrownBy(() -> shipOrderService.changeQuantity(other, riceLine.getId(), BigDecimal.ZERO))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> shipOrderService.removeLine(other, riceLine.getId()))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> receiptService.confirmReceipt(other, order.getId(),
                    new OrderReceiptService.Receipt(Instant.now(), Map.of()))).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> receiptService.removeFiles(other, order.getId(), List.of()))
                    .isInstanceOf(AccessDeniedException.class);
        }
        master.setEnabled(false);
        assertThatThrownBy(() -> shipOrderService.send(master, details)).isInstanceOf(AccessDeniedException.class);
        master.setEnabled(true);

        // A request approved by anyone but the ship's enabled Master never reaches the order.
        ItemRequest request = submit(crewMember, line(rice, "1"));
        request.setStatus(RequestStatus.APPROVED);
        for (User decidedBy : List.of(cook, otherMaster)) {
            request.setDecidedBy(decidedBy);
            assertThatThrownBy(() -> shipOrderService.addApprovedRequest(request))
                    .isInstanceOf(AccessDeniedException.class);
        }
        assertThat(riceLine.getQuantity()).isEqualByComparingTo("10");
        assertThat(order.getStatus()).isEqualTo(ShipOrderStatus.OPEN);
    }

    // The number on the sidebar's Vessel order for this user; 0 without the pill.
    private long orderCounter(User u) throws Exception {
        String html = mockMvc.perform(get("/ship/library").with(user(new CurrentUser(u)))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Matcher count = NAV_COUNT.matcher(section(ORDER_NAV, html));
        return count.find() ? Long.parseLong(count.group(1)) : 0;
    }

    // One card of Orders in progress.
    private static String card(String html, ShipOrder order) {
        return PROGRESS_CARD.matcher(html).results().filter(m -> m.group(1).equals(order.getId().toString()))
                .map(java.util.regex.MatchResult::group).findFirst().orElseThrow();
    }

    private static String section(Pattern pattern, String html) {
        Matcher m = pattern.matcher(html);
        assertThat(m.find()).as(pattern.pattern()).isTrue();
        return m.group();
    }

    // An order of the ship sent at this time, with one line of 1 per item.
    private ShipOrder sentOrder(ShipOrderStatus status, Instant sentAt, String confirmedPort, LocalDate deliveryDate,
                                Item... items) {
        ShipOrder order = new ShipOrder();
        order.setShip(ship);
        order.setStatus(status);
        order.setRequestedPort("Rotterdam");
        order.setNeededBy(LocalDate.now(ZoneOffset.UTC).plusDays(30));
        order.setSentBy(master);
        order.setSentAt(sentAt);
        order.setConfirmedPort(confirmedPort);
        order.setDeliveryDate(deliveryDate);
        order.setCreatedAt(sentAt);
        orderRepository.save(order);
        for (Item item : items) {
            ShipOrderLine line = new ShipOrderLine();
            line.setOrder(order);
            line.setItem(item);
            line.setQuantity(BigDecimal.ONE);
            line.setOriginalQuantity(BigDecimal.ONE);
            order.getLines().add(lineRepository.save(line));
        }
        return order;
    }

    // The page's <main>: the sidebar and its forms (sign out) are left out.
    private static String main(String html) {
        return html.substring(html.indexOf("<main"), html.indexOf("</main>"));
    }

    private String cookPage(String url) throws Exception {
        return mockMvc.perform(get(url).with(user(new CurrentUser(cook)))).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // Rice 6 from the Chief cook and 4 from a crew member (one line of 10), shower gel 2 from the crew member.
    private ShipOrder openOrderWithTwoLines() throws Exception {
        approve(submit(cook, line(rice, "6")));
        approve(submit(crewMember, line(rice, "4"), line(soap, "2")));
        ShipOrder order = orderRepository.findByShipIdAndStatus(ship.getId(), ShipOrderStatus.OPEN).orElseThrow();
        assertThat(order.getLines()).hasSize(2);
        return order;
    }

    private ShipOrder openOrderAfter() throws Exception {
        approve(submit(cook, line(rice, "1")));
        return orderRepository.findByShipIdAndStatus(ship.getId(), ShipOrderStatus.OPEN).orElseThrow();
    }

    private static ShipOrderLine line(ShipOrder order, Item item) {
        return order.getLines().stream().filter(l -> l.getItem().getId().equals(item.getId())).findFirst().orElseThrow();
    }

    private ResultActions changeQuantity(ShipOrderLine line, String quantity) throws Exception {
        return mockMvc.perform(post("/ship/order/lines/" + line.getId() + "/quantity").param("quantity", quantity)
                .with(user(new CurrentUser(master))).with(csrf()));
    }

    private ResultActions send(String port, String neededBy, String note) throws Exception {
        return mockMvc.perform(post("/ship/order/send").param("requestedPort", port).param("neededBy", neededBy)
                .param("officeNote", note).with(user(new CurrentUser(master))).with(csrf()));
    }

    private String page(String url) throws Exception {
        return mockMvc.perform(get(url).with(user(new CurrentUser(master)))).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void approve(ItemRequest request) {
        assertThat(requestService.approve(master, request.getId())).isEqualTo(RequestService.Decision.DECIDED);
    }

    private ItemRequest submit(User requestedBy, RequestLine... lines) {
        ItemRequest request = new ItemRequest();
        request.setShip(requestedBy.getShip());
        request.setRequestedBy(requestedBy);
        request.getLines().addAll(List.of(lines));
        return requestService.submit(request);
    }

    private static RequestLine line(Item item, String quantity) {
        RequestLine line = new RequestLine();
        line.setItem(item);
        line.setQuantity(new BigDecimal(quantity));
        return line;
    }

    private User crew(String email, String name, Rank rank, Ship s) {
        return userService.createUser(email, name, "secret123", rank.role(), s, rank);
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

    private Ship ship(String imo, String name) {
        Ship s = new Ship();
        s.setImoNumber(imo);
        s.setName(name);
        s.setCreatedAt(Instant.now());
        return shipRepository.save(s);
    }
}
