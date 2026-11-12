package com.nereonix.nereonet_mvp.approval;

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
import com.nereonix.nereonet_mvp.core.notification.Notification;
import com.nereonix.nereonet_mvp.core.notification.NotificationRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrder;
import com.nereonix.nereonet_mvp.core.order.ShipOrderLine;
import com.nereonix.nereonet_mvp.core.order.ShipOrderRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrderStatus;
import com.nereonix.nereonet_mvp.core.request.ItemRequest;
import com.nereonix.nereonet_mvp.core.request.ItemRequestRepository;
import com.nereonix.nereonet_mvp.core.request.RequestLine;
import com.nereonix.nereonet_mvp.core.request.RequestService;
import com.nereonix.nereonet_mvp.core.request.RequestStatus;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserService;

/**
 * P34, the Master approves or declines a request as a whole. Approving adds its lines to the ship's one OPEN order
 * (creating the items asked for by name), declining keeps the reason; either notifies the person who asked, records
 * the decision and goes back to the list. Only a PENDING request can be decided, only by the Master of its ship.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ApprovalDecisionFlowTest {

    @Autowired MockMvc mockMvc;
    @Autowired ShipRepository shipRepository;
    @Autowired UserService userService;
    @Autowired ItemRepository itemRepository;
    @Autowired ItemRequestRepository requestRepository;
    @Autowired RequestService requestService;
    @Autowired ShipOrderRepository orderRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired AuditRepository auditRepository;

    Ship ship;
    User master;
    User cook;
    User crewMember;
    User otherMaster;
    Item rice;

    @BeforeEach
    void setUp() {
        ship = ship("9074729", "MV Demo");
        Ship otherShip = ship("9123456", "MV Other");
        master = crew("master@example.com", "Ship Master", Rank.MASTER, ship);
        cook = crew("cook@example.com", "Demo Cook", Rank.CHIEF_COOK, ship);
        crewMember = crew("ab@example.com", "Maria Santos", Rank.ABLE_SEAMAN, ship);
        otherMaster = crew("other.master@example.com", "Other Master", Rank.MASTER, otherShip);
        rice = item("Basmati rice", "kg");
    }

    @Test
    void twoApprovedRequestsForTheSameItemMakeOneOrderLine() throws Exception {
        ItemRequest fromCook = submit(cook, line(rice, "5"));
        ItemRequest fromCrew = submit(crewMember, line(rice, "5"));

        approve(fromCook, master).andExpect(redirectedUrl("/ship/approvals"))
                .andExpect(flash().attribute("successMessage", "approval.approved"));
        approve(fromCrew, master).andExpect(redirectedUrl("/ship/approvals"));

        List<ShipOrder> open = orderRepository.findAllByShipIdAndStatus(ship.getId(), ShipOrderStatus.OPEN);
        assertThat(open).singleElement().satisfies(order -> assertThat(order.getLines()).singleElement()
                .satisfies(line -> {
                    assertThat(line.getItem().getId()).isEqualTo(rice.getId());
                    assertThat(line.getQuantity()).isEqualByComparingTo("10");
                }));
        ShipOrderLine orderLine = open.get(0).getLines().get(0);
        for (ItemRequest request : List.of(fromCook, fromCrew)) {
            assertThat(request.getStatus()).isEqualTo(RequestStatus.APPROVED);
            assertThat(request.getDecidedBy().getId()).isEqualTo(master.getId());
            assertThat(request.getDecidedAt()).isNotNull();
            assertThat(request.getLines()).singleElement().extracting(RequestLine::getShipOrderLine).isSameAs(orderLine);
        }
        for (User requester : List.of(cook, crewMember)) {
            assertThat(notificationRepository.countByUserEmailAndReadAtIsNull(requester.getEmail())).isEqualTo(1);
        }
        assertThat(notificationRepository.findAllByUserEmailOrderByCreatedAtDescIdDesc(cook.getEmail()))
                .singleElement().satisfies(n -> {
                    assertThat(n.getText()).isEqualTo("Your request was approved");
                    assertThat(n.getLink()).isEqualTo("/ship/requests/" + fromCook.getId());
                });
        assertThat(auditRepository.findAll()).filteredOn(e -> e.getAction().equals("REQUEST_APPROVED"))
                .extracting(AuditEntry::getDetails)
                .containsExactlyInAnyOrder("Request #" + fromCook.getId() + ", 1 lines",
                        "Request #" + fromCrew.getId() + ", 1 lines");
    }

    @Test
    void decliningKeepsTheReasonAndAddsNothingToTheOrder() throws Exception {
        ItemRequest request = submit(cook, line(rice, "5"));

        decline(request, master, "Too much stock on board").andExpect(redirectedUrl("/ship/approvals"))
                .andExpect(flash().attribute("successMessage", "approval.declined"));

        assertThat(request.getStatus()).isEqualTo(RequestStatus.REJECTED);
        assertThat(request.getRejectionReason()).isEqualTo("Too much stock on board");
        assertThat(request.getDecidedBy().getId()).isEqualTo(master.getId());
        assertThat(request.getLines()).allMatch(l -> l.getShipOrderLine() == null);
        assertThat(orderRepository.findAllByShipIdAndStatus(ship.getId(), ShipOrderStatus.OPEN)).isEmpty();
        assertThat(notificationRepository.findAllByUserEmailOrderByCreatedAtDescIdDesc(cook.getEmail()))
                .singleElement().extracting(Notification::getText)
                .isEqualTo("Your request was declined: Too much stock on board");
        assertThat(auditRepository.findAll()).filteredOn(e -> e.getAction().equals("REQUEST_DECLINED"))
                .singleElement().extracting(AuditEntry::getDetails)
                .isEqualTo("Request #" + request.getId() + ": Too much stock on board");
    }

    @Test
    void anEmptyReasonIsAFormErrorAndSavesNothing() throws Exception {
        ItemRequest request = submit(cook, line(rice, "5"));

        String html = decline(request, master, "   ").andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("Give a reason.", "bootstrap.Modal.getOrCreateInstance('#declineModal').show()");
        assertThat(request.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(request.getDecidedAt()).isNull();
        assertThat(notificationRepository.countByUserEmailAndReadAtIsNull(cook.getEmail())).isZero();
    }

    @Test
    void onlyAPendingRequestShowsTheButtonsAndCanBeDecided() throws Exception {
        ItemRequest request = submit(cook, line(rice, "5"));
        assertThat(page("/ship/approvals/" + request.getId())).contains(">Approve<", ">Decline<", "id=\"declineModal\"");

        approve(request, master);
        // A second click, and a decline after it, change nothing and say so.
        approve(request, master).andExpect(redirectedUrl("/ship/approvals/" + request.getId()))
                .andExpect(flash().attribute("errorMessage", "approval.notPending"));
        decline(request, master, "Changed my mind").andExpect(flash().attribute("errorMessage", "approval.notPending"));
        assertThat(request.getStatus()).isEqualTo(RequestStatus.APPROVED);
        assertThat(orderRepository.findAllByShipIdAndStatus(ship.getId(), ShipOrderStatus.OPEN).get(0).getLines())
                .singleElement().extracting(ShipOrderLine::getQuantity).satisfies(q -> assertThat(q).isEqualByComparingTo("5"));
        assertThat(notificationRepository.countByUserEmailAndReadAtIsNull(cook.getEmail())).isEqualTo(1);
        assertThat(page("/ship/approvals/" + request.getId())).doesNotContain(">Approve<", "id=\"declineModal\"");

        ItemRequest cancelled = submit(crewMember, line(rice, "1"));
        cancelled.setStatus(RequestStatus.CANCELLED);
        approve(cancelled, master).andExpect(flash().attribute("errorMessage", "approval.notPending"));
        assertThat(cancelled.getStatus()).isEqualTo(RequestStatus.CANCELLED);
        assertThat(page("/ship/approvals/" + cancelled.getId())).doesNotContain(">Approve<", ">Decline<");
    }

    @Test
    void approvingCreatesTheNewItemsOrLinksToOneOfTheSameName() throws Exception {
        RequestLine saffron = newItem("Saffron", "g", "5");
        RequestLine riceByName = newItem("basmati RICE", null, "2");
        RequestLine salt = newItem("Sea salt", null, "1");
        ItemRequest request = submit(cook, saffron, riceByName, salt);

        approve(request, master);

        Item created = itemRepository.findFirstByShipIdAndNameIgnoreCaseOrderByIdAsc(ship.getId(), "Saffron").orElseThrow();
        assertThat(created.getUnit()).isEqualTo("g");
        assertThat(created.getCategory()).isEqualTo(ItemCategory.PROVISIONS);
        assertThat(created.isActive()).isTrue();
        assertThat(saffron.getItem()).isSameAs(created);
        assertThat(riceByName.getItem().getId()).isEqualTo(rice.getId());
        assertThat(salt.getItem().getUnit()).isEqualTo(RequestService.NEW_ITEM_DEFAULT_UNIT);
        assertThat(itemRepository.countByShipId(ship.getId())).isEqualTo(3);
        assertThat(orderRepository.findAllByShipIdAndStatus(ship.getId(), ShipOrderStatus.OPEN).get(0).getLines())
                .extracting(l -> l.getItem().getName()).containsExactly("Saffron", "Basmati rice", "Sea salt");
    }

    @Test
    void onlyTheMasterOfTheShipDecides() throws Exception {
        ItemRequest request = submit(cook, line(rice, "5"));

        approve(request, cook).andExpect(status().isForbidden());
        decline(request, crewMember, "No").andExpect(status().isForbidden());
        approve(request, otherMaster).andExpect(status().isNotFound());
        decline(request, otherMaster, "No").andExpect(status().isNotFound());
        assertThat(request.getStatus()).isEqualTo(RequestStatus.PENDING);
    }

    private ResultActions approve(ItemRequest request, User u) throws Exception {
        return mockMvc.perform(post("/ship/approvals/" + request.getId() + "/approve")
                .with(user(new CurrentUser(u))).with(csrf()));
    }

    private ResultActions decline(ItemRequest request, User u, String reason) throws Exception {
        return mockMvc.perform(post("/ship/approvals/" + request.getId() + "/decline").param("reason", reason)
                .with(user(new CurrentUser(u))).with(csrf()));
    }

    private String page(String url) throws Exception {
        return mockMvc.perform(get(url).with(user(new CurrentUser(master)))).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
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

    private static RequestLine newItem(String name, String unit, String quantity) {
        RequestLine line = new RequestLine();
        line.setNewItemName(name);
        line.setNewItemUnit(unit);
        line.setQuantity(new BigDecimal(quantity));
        return line;
    }

    private User crew(String email, String name, Rank rank, Ship s) {
        return userService.createUser(email, name, "secret123", rank.role(), s, rank);
    }

    private Item item(String name, String unit) {
        Item item = new Item();
        item.setShip(ship);
        item.setName(name);
        item.setUnit(unit);
        item.setCategory(ItemCategory.PROVISIONS);
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
