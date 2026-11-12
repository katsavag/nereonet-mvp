package com.nereonix.nereonet_mvp.officeorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditEntry;
import com.nereonix.nereonet_mvp.core.audit.AuditRepository;
import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemCategory;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.notification.Notification;
import com.nereonix.nereonet_mvp.core.notification.NotificationRepository;
import com.nereonix.nereonet_mvp.core.notification.NotificationType;
import com.nereonix.nereonet_mvp.core.order.ShipOrder;
import com.nereonix.nereonet_mvp.core.order.ShipOrderLine;
import com.nereonix.nereonet_mvp.core.order.ShipOrderLineRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrderRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrderStatus;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserService;

/**
 * P36, the office's ship orders: the orders the ships have sent (never OPEN ones), one order with its lines by
 * category, confirming the port and delivery date (the Master and the Chief cook are notified each time, until the
 * order is closed), and the CSV export, which marks the orders exported. Office users only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class OfficeOrderFlowTest {

    private static final String CSV_HEADER = "imo_number,ship_name,order_id,sent_at,requested_port,needed_by,"
            + "confirmed_port,delivery_date,category,item_name,unit,quantity";
    private static final Instant SENT_AT = Instant.parse("2026-11-10T09:30:15.123Z");
    private static final LocalDate NEEDED_BY = LocalDate.of(2026, 11, 25);

    @Autowired MockMvc mockMvc;
    @Autowired ShipRepository shipRepository;
    @Autowired UserService userService;
    @Autowired ItemRepository itemRepository;
    @Autowired ShipOrderRepository orderRepository;
    @Autowired ShipOrderLineRepository lineRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired AuditRepository auditRepository;

    Ship ship;
    User master;
    User cook;
    User crewMember;
    Item rice;
    Item soap;

    @BeforeEach
    void setUp() {
        ship = new Ship();
        ship.setImoNumber("9074729");
        ship.setName("MV Demo");
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);
        master = crew("master@example.com", "Ship Master", Rank.MASTER);
        cook = crew("cook@example.com", "Demo Cook", Rank.CHIEF_COOK);
        crewMember = crew("ab@example.com", "Ana Santos", Rank.ABLE_SEAMAN);
        rice = item("Basmati rice", "kg", ItemCategory.PROVISIONS);
        soap = item("Shower gel", "pcs", ItemCategory.PERSONAL_ITEMS);
    }

    @Test
    void theOfficeConfirmsASentOrderAndExportsIt() throws Exception {
        ShipOrder order = sentOrderWithTwoLines();
        ShipOrder open = order(ShipOrderStatus.OPEN);

        // The list: the sent order with its details, never the OPEN one.
        String list = page("/office/orders");
        assertThat(list).contains("href=\"/office/orders/" + order.getId() + "\">Order #" + order.getId() + "</a>",
                        "MV Demo", "Ship Master", "Rotterdam", "25 Nov 2026", ">Sent</span>", "Not yet")
                .doesNotContain("Order #" + open.getId() + "<");
        // The order: its note and its lines by category, read-only.
        String detail = page("/office/orders/" + order.getId());
        assertThat(detail).contains("Deliver to the gangway", "Confirm delivery",
                        "name=\"confirmedPort\" value=\"Rotterdam\"", "action=\"/office/orders/export\"")
                .containsSubsequence(">Provisions<", "Basmati rice", ">kg<", ">25<", ">Personal items<", "Shower gel",
                        ">2.5<")
                .doesNotContain("name=\"quantity\"");

        LocalDate delivery = LocalDate.of(2026, 11, 20);
        confirm(order, "Antwerp", delivery.toString())
                .andExpect(redirectedUrl("/office/orders/" + order.getId()))
                .andExpect(flash().attribute("successMessage", "officeorder.confirmed"));

        assertThat(order.getStatus()).isEqualTo(ShipOrderStatus.CONFIRMED);
        assertThat(order.getConfirmedPort()).isEqualTo("Antwerp");
        assertThat(order.getDeliveryDate()).isEqualTo(delivery);
        assertThat(order.getConfirmedBy().getEmail()).isEqualTo("office@example.com");
        // The Master (with a link to the order) and the Chief cook are notified; nobody else on board.
        assertThat(notifications(master)).singleElement().satisfies(n -> {
            assertThat(n.getType()).isEqualTo(NotificationType.ORDER);
            assertThat(n.getSubject()).isEqualTo("MV Demo");
            assertThat(n.getText()).isEqualTo("Order confirmed: Antwerp, 20 Nov 2026");
            assertThat(n.getLink()).isEqualTo("/ship/orders/" + order.getId());
        });
        assertThat(notifications(cook)).singleElement().satisfies(n -> {
            assertThat(n.getText()).isEqualTo("Order confirmed: Antwerp, 20 Nov 2026");
            assertThat(n.getLink()).isNull();
        });
        assertThat(notifications(crewMember)).isEmpty();
        assertThat(audit("ORDER_CONFIRMED")).singleElement().extracting(AuditEntry::getDetails)
                .isEqualTo("Order #" + order.getId() + ", Antwerp, delivery 2026-11-20");

        // Export: a download, one row per line, Provisions first; the order is marked exported.
        MvcResult export = mockMvc.perform(post("/office/orders/export").param("ids", order.getId().toString())
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.startsWith("attachment; filename=\"ship-orders-")))
                .andReturn();
        byte[] body = export.getResponse().getContentAsByteArray();
        assertThat(body).startsWith(0xEF, 0xBB, 0xBF); // UTF-8 BOM
        List<String> rows = new String(body, StandardCharsets.UTF_8).substring(1).lines().toList();
        String common = "9074729,MV Demo," + order.getId() + ",2026-11-10T09:30:15Z,Rotterdam,2026-11-25,Antwerp,2026-11-20,";
        assertThat(rows).containsExactly(CSV_HEADER,
                common + "Provisions,Basmati rice,kg,25",
                common + "Personal items,Shower gel,pcs,2.5");
        assertThat(order.getExportedAt()).isNotNull();
        assertThat(audit("ORDERS_EXPORTED")).singleElement().satisfies(e -> {
            assertThat(e.getDetails()).isEqualTo("1 order, 2 lines, ids [" + order.getId() + "]");
            assertThat(e.getShip().getId()).isEqualTo(ship.getId());
        });
        assertThat(page("/office/orders")).contains(">Confirmed</span>", ">Exported</span>").doesNotContain("Not yet");
    }

    @Test
    void theConfirmationCanBeCorrectedUntilTheOrderIsClosed() throws Exception {
        ShipOrder order = sentOrderWithTwoLines();

        // Both fields are required: the page again with the errors, nothing saved, nobody notified.
        String errors = mockMvc.perform(post("/office/orders/{id}/confirm", order.getId())
                        .param("confirmedPort", " ").param("deliveryDate", "").with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(errors).contains("Enter the confirmed port.", "Enter the delivery date.");
        assertThat(order.getStatus()).isEqualTo(ShipOrderStatus.SENT);
        assertThat(notifications(master)).isEmpty();

        confirm(order, "Antwerp", "2026-11-20");
        assertThat(page("/office/orders/" + order.getId())).contains("Correct the delivery",
                "name=\"confirmedPort\" value=\"Antwerp\"", "value=\"2026-11-20\"");
        confirm(order, "Hamburg", "2026-11-22").andExpect(flash().attribute("successMessage", "officeorder.confirmed"));
        assertThat(order.getConfirmedPort()).isEqualTo("Hamburg");
        assertThat(notifications(master)).extracting(Notification::getText)
                .containsExactly("Order confirmed: Hamburg, 22 Nov 2026", "Order confirmed: Antwerp, 20 Nov 2026");
        assertThat(audit("ORDER_CONFIRMED")).extracting(AuditEntry::getDetails)
                .contains("Order #" + order.getId() + ", Hamburg, delivery 2026-11-22, corrected");

        // Closed: no form, and a confirmation sent anyway changes nothing.
        order.setStatus(ShipOrderStatus.CLOSED);
        assertThat(page("/office/orders/" + order.getId())).doesNotContain("id=\"confirmTitle\"", "/confirm\"");
        confirm(order, "Bremen", "2026-11-23").andExpect(flash().attribute("errorMessage", "officeorder.closed"));
        assertThat(order.getConfirmedPort()).isEqualTo("Hamburg");
        assertThat(notifications(master)).hasSize(2);
    }

    @Test
    void anOpenOrderIsNotTheOfficesAndOnlyTheOfficeSeesOrders() throws Exception {
        ShipOrder open = order(ShipOrderStatus.OPEN);
        mockMvc.perform(get("/office/orders/{id}", open.getId())).andExpect(status().isNotFound());
        confirm(open, "Antwerp", "2026-11-20").andExpect(status().isNotFound());
        assertThat(open.getStatus()).isEqualTo(ShipOrderStatus.OPEN);

        // Exporting nothing, or only an OPEN order: back to the list with a message.
        mockMvc.perform(post("/office/orders/export").with(csrf()))
                .andExpect(redirectedUrl("/office/orders"))
                .andExpect(flash().attribute("errorMessage", "officeorder.export.noneSelected"));
        mockMvc.perform(post("/office/orders/export").param("ids", open.getId().toString()).with(csrf()))
                .andExpect(redirectedUrl("/office/orders"))
                .andExpect(flash().attribute("errorMessage", "officeorder.export.nothing"));
        assertThat(open.getExportedAt()).isNull();

        // The crew never sees the office's orders.
        mockMvc.perform(get("/office/orders").with(user(new CurrentUser(master)))).andExpect(status().isForbidden());
    }

    @Test
    void anOrderCanBeExportedAgain() throws Exception {
        ShipOrder order = sentOrderWithTwoLines();
        Instant before = Instant.now().minus(1, ChronoUnit.DAYS);
        order.setExportedAt(before);
        assertThat(page("/office/orders")).contains(">Exported</span>");
        assertThat(page("/office/orders?exported=no")).doesNotContain("Order #" + order.getId() + "<");

        mockMvc.perform(post("/office/orders/export").param("ids", order.getId().toString()).with(csrf()))
                .andExpect(status().isOk());
        assertThat(order.getExportedAt()).isAfter(before);
    }

    // ---------------------------------------------------------------------------------------------------------------

    private ResultActions confirm(ShipOrder order, String port, String date) throws Exception {
        return mockMvc.perform(post("/office/orders/{id}/confirm", order.getId())
                .param("confirmedPort", port).param("deliveryDate", date).with(csrf()));
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

    // SENT by the Master: 25 kg of rice and 2.5 shower gels, to Rotterdam, with a note.
    private ShipOrder sentOrderWithTwoLines() {
        ShipOrder order = order(ShipOrderStatus.SENT);
        order.setSentBy(master);
        order.setSentAt(SENT_AT);
        order.setRequestedPort("Rotterdam");
        order.setNeededBy(NEEDED_BY);
        order.setOfficeNote("Deliver to the gangway");
        line(order, soap, "2.5");
        line(order, rice, "25.000");
        return order;
    }

    private ShipOrder order(ShipOrderStatus status) {
        ShipOrder order = new ShipOrder();
        order.setShip(ship);
        order.setStatus(status);
        order.setCreatedAt(SENT_AT.minus(1, ChronoUnit.DAYS));
        return orderRepository.save(order);
    }

    private void line(ShipOrder order, Item item, String quantity) {
        ShipOrderLine line = new ShipOrderLine();
        line.setOrder(order);
        line.setItem(item);
        line.setQuantity(new BigDecimal(quantity));
        order.getLines().add(lineRepository.save(line));
    }

    private User crew(String email, String name, Rank rank) {
        return userService.createUser(email, name, "secret123", rank.role(), ship, rank);
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
