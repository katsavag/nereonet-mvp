package com.nereonix.nereonet_mvp.shiporder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
import com.nereonix.nereonet_mvp.core.order.ShipOrder;
import com.nereonix.nereonet_mvp.core.order.ShipOrderLine;
import com.nereonix.nereonet_mvp.core.order.ShipOrderRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrderStatus;
import com.nereonix.nereonet_mvp.core.request.ItemRequest;
import com.nereonix.nereonet_mvp.core.request.RequestLine;
import com.nereonix.nereonet_mvp.core.request.RequestService;
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
 * notifies every enabled office user. Past orders are listed with their status. Only the Master of the ship.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ShipOrderFlowTest {

    @Autowired MockMvc mockMvc;
    @Autowired ShipRepository shipRepository;
    @Autowired UserService userService;
    @Autowired ItemRepository itemRepository;
    @Autowired RequestService requestService;
    @Autowired ShipOrderRepository orderRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired AuditRepository auditRepository;

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
        assertThat(page("/ship/order")).contains("Ship order", "2 lines in the order");

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
        assertThat(page("/ship/order")).contains("Nothing to order yet", "Approved requests are collected here.");
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
                .doesNotContain("/ship/order/send");
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

        String html = send("  ", LocalDate.now(ZoneOffset.UTC).minusDays(1).toString(), "")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("Enter the requested port.", "The date can&#39;t be in the past.");
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

        assertThat(html).contains("Confirmed by office", "Confirmed: Antwerp", "Sent to office", "Requested: Piraeus");
        assertThat(html.indexOf("Order #" + second.getId())).isLessThan(html.indexOf("Order #" + first.getId()));
        mockMvc.perform(get("/ship/orders/" + openOrderAfter().getId()).with(user(new CurrentUser(master))))
                .andExpect(redirectedUrl("/ship/order"));
    }

    @Test
    void onlyTheMasterOfTheShipUsesTheOrder() throws Exception {
        ShipOrder order = openOrderWithTwoLines();
        ShipOrderLine riceLine = line(order, rice);

        mockMvc.perform(get("/ship/order").with(user(new CurrentUser(cook)))).andExpect(status().isForbidden());
        mockMvc.perform(get("/ship/orders").with(user(new CurrentUser(crewMember)))).andExpect(status().isForbidden());
        mockMvc.perform(post("/ship/order/lines/" + riceLine.getId() + "/quantity").param("quantity", "1")
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
