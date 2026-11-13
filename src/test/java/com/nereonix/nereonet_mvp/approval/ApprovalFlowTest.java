package com.nereonix.nereonet_mvp.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditEntry;
import com.nereonix.nereonet_mvp.core.audit.AuditRepository;
import com.nereonix.nereonet_mvp.core.config.DevData;
import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemCategory;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.library.LibraryItemRepository;
import com.nereonix.nereonet_mvp.core.library.LibraryItemVersionRepository;
import com.nereonix.nereonet_mvp.core.notification.Notification;
import com.nereonix.nereonet_mvp.core.notification.NotificationRepository;
import com.nereonix.nereonet_mvp.core.notification.NotificationType;
import com.nereonix.nereonet_mvp.core.request.ItemRequest;
import com.nereonix.nereonet_mvp.core.request.ItemRequestRepository;
import com.nereonix.nereonet_mvp.core.request.RequestLine;
import com.nereonix.nereonet_mvp.core.request.RequestLineRepository;
import com.nereonix.nereonet_mvp.core.request.RequestService;
import com.nereonix.nereonet_mvp.core.vetting.VettingService;
import com.nereonix.nereonet_mvp.core.request.RequestStatus;
import com.nereonix.nereonet_mvp.core.profile.CrewProfileRepository;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;
import com.nereonix.nereonet_mvp.core.user.UserService;

/**
 * P33, the Master's approvals: RequestService.submit saves a PENDING request, records it and notifies the Master;
 * /ship/approvals lists the ship's waiting requests (oldest first) and decided ones; a request shows its lines by
 * category with "New item" badges and, when declined, the reason. Only the Master, and only their own ship.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ApprovalFlowTest {

    @Autowired MockMvc mockMvc;
    @Autowired ShipRepository shipRepository;
    @Autowired UserService userService;
    @Autowired ItemRepository itemRepository;
    @Autowired ItemRequestRepository requestRepository;
    @Autowired RequestService requestService;
    @Autowired VettingService vettingService;
    @Autowired NotificationRepository notificationRepository;
    @Autowired AuditRepository auditRepository;
    @Autowired UserRepository userRepository;
    @Autowired RequestLineRepository lineRepository;
    @Autowired LibraryItemRepository libraryItemRepository;
    @Autowired LibraryItemVersionRepository versionRepository;
    @Autowired CrewProfileRepository profileRepository;

    Ship ship;
    Ship otherShip;
    User master;
    User cook;
    User crewMember;
    User otherMaster;
    Item rice;
    Item eggs;
    Item showerGel;

    @BeforeEach
    void setUp() {
        ship = ship("9074729", "MV Demo");
        otherShip = ship("9123456", "MV Other");
        master = crew("master@example.com", "Ship Master", Rank.MASTER, ship);
        cook = crew("cook@example.com", "Demo Cook", Rank.CHIEF_COOK, ship);
        crewMember = crew("ab@example.com", "Maria Santos", Rank.ABLE_SEAMAN, ship);
        otherMaster = crew("other.master@example.com", "Other Master", Rank.MASTER, otherShip);
        rice = item(ship, "Basmati rice", "kg", ItemCategory.PROVISIONS);
        eggs = item(ship, "Eggs", "pcs", ItemCategory.PROVISIONS);
        showerGel = item(ship, "Shower gel", "pcs", ItemCategory.PERSONAL_ITEMS);
    }

    @Test
    void theMasterSeesAPendingRequestUnderWaiting() throws Exception {
        ItemRequest request = submit(cook, Instant.now().minus(Duration.ofHours(3)), line(rice, "25"), line(eggs, "120"));

        String html = page("/ship/approvals", master);
        assertThat(html).contains("<title>Approvals · Nereonet</title>", "MV Demo · IMO 9074729")
                .containsPattern("(?s)href=\"/ship/approvals\" aria-current=\"page\">\\s*<svg[^>]*>.*?</svg>\\s*<span>Waiting</span>")
                .contains("href=\"/ship/approvals/" + request.getId() + "\">Demo Cook · Chief cook</a>",
                        "Request #" + request.getId(), "2 items");
    }

    @Test
    void waitingIsOldestFirstAndDecidedShowsTheDecisionNewestFirst() throws Exception {
        ItemRequest newer = submit(crewMember, Instant.now().minus(Duration.ofHours(1)), line(showerGel, "2"));
        ItemRequest older = submit(cook, Instant.now().minus(Duration.ofHours(5)), line(rice, "10"));
        ItemRequest approved = submit(cook, Instant.now().minus(Duration.ofDays(3)), line(eggs, "60"));
        decide(approved, RequestStatus.APPROVED, null, Instant.now().minus(Duration.ofDays(2)));
        ItemRequest declined = submit(crewMember, Instant.now().minus(Duration.ofDays(2)), line(showerGel, "1"));
        decide(declined, RequestStatus.REJECTED, "Not this month.", Instant.now().minus(Duration.ofDays(1)));

        String waiting = page("/ship/approvals", master);
        assertThat(waiting.indexOf("/ship/approvals/" + older.getId() + "\""))
                .isPositive().isLessThan(waiting.indexOf("/ship/approvals/" + newer.getId() + "\""));
        assertThat(waiting).doesNotContain("/ship/approvals/" + approved.getId() + "\"",
                "/ship/approvals/" + declined.getId() + "\"");

        String decided = page("/ship/approvals?tab=decided", master);
        assertThat(decided).contains("Approved", "Declined").doesNotContain("/ship/approvals/" + older.getId() + "\"");
        assertThat(decided.indexOf("/ship/approvals/" + declined.getId() + "\""))
                .isPositive().isLessThan(decided.indexOf("/ship/approvals/" + approved.getId() + "\""));
    }

    @Test
    void theRequestShowsItsLinesByCategoryWithNewItemsAndTheDeclineReason() throws Exception {
        RequestLine newItem = new RequestLine();
        newItem.setNewItemName("Saffron");
        newItem.setNewItemUnit("g");
        newItem.setQuantity(new BigDecimal("5"));
        ItemRequest request = submit(crewMember, null, line(showerGel, "2"), line(eggs, "12"), newItem);

        String html = page("/ship/approvals/" + request.getId(), master);
        assertThat(html).contains("Request #" + request.getId(), "Maria Santos · Crew member", "Waiting for the master",
                "Saffron", "New item");
        assertThat(html.indexOf(">Provisions<")).isPositive().isLessThan(html.indexOf(">Personal items<"));
        assertThat(html.indexOf("Eggs")).isLessThan(html.indexOf(">Personal items<"));
        assertThat(html.indexOf("Shower gel")).isGreaterThan(html.indexOf(">Personal items<"));

        decide(request, RequestStatus.REJECTED, "Not this month.", Instant.now());
        assertThat(page("/ship/approvals/" + request.getId(), master)).contains("Declined", "Not this month.");
    }

    @Test
    void onlyTheMasterOfTheShip() throws Exception {
        ItemRequest request = submit(cook, null, line(rice, "25"));

        for (User other : List.of(crewMember, cook)) {
            mockMvc.perform(get("/ship/approvals").with(user(new CurrentUser(other)))).andExpect(status().isForbidden());
            mockMvc.perform(get("/ship/approvals/" + request.getId()).with(user(new CurrentUser(other))))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/ship/approvals/" + request.getId()).with(user(new CurrentUser(otherMaster))))
                .andExpect(status().isNotFound());
        assertThat(page("/ship/approvals", otherMaster)).doesNotContain("/ship/approvals/" + request.getId() + "\"");
    }

    @Test
    void submitNotifiesTheMasterAndRecordsIt() throws Exception {
        ItemRequest request = submit(cook, null, line(rice, "25"), line(eggs, "120"));

        assertThat(request.getStatus()).isEqualTo(RequestStatus.PENDING);
        List<Notification> notifications = notificationRepository.findAllByUserEmailOrderByCreatedAtDescIdDesc(
                master.getEmail());
        assertThat(notifications).singleElement().satisfies(n -> {
            assertThat(n.getType()).isEqualTo(NotificationType.REQUEST);
            assertThat(n.getText()).isEqualTo("New request to approve");
            assertThat(n.getLink()).isEqualTo("/ship/approvals/" + request.getId());
        });
        assertThat(notificationRepository.findAllByUserEmailOrderByCreatedAtDescIdDesc(otherMaster.getEmail())).isEmpty();
        assertThat(auditRepository.findAll()).filteredOn(e -> e.getAction().equals("REQUEST_SUBMITTED"))
                .singleElement().extracting(AuditEntry::getDetails).isEqualTo("Request #" + request.getId() + ", 2 lines");

        // The Master's sidebar counts it.
        assertThat(page("/ship", master)).containsPattern("<span[^>]*>Approvals</span>\\s*<span class=\"nav-count num\""
                + " aria-hidden=\"true\">1</span>");
    }

    @Test
    void theDevDataSeedsTwoPendingRequestsOnce() throws Exception {
        DevData devData = new DevData(shipRepository, userRepository, userService, itemRepository, requestRepository,
                libraryItemRepository, versionRepository, profileRepository, requestService, vettingService);
        devData.run(null);
        devData.run(null);

        List<ItemRequest> pending = requestRepository.findAllByShipIdAndStatusOrderByCreatedAtAscIdAsc(ship.getId(),
                RequestStatus.PENDING);
        assertThat(pending).hasSize(2);
        ItemRequest fromCook = pending.get(0);
        ItemRequest fromCrew = pending.get(1);
        assertThat(fromCook.getRequestedBy().getId()).isEqualTo(cook.getId());
        assertThat(fromCook.getLines()).extracting(RequestLine::getName)
                .containsExactly("Basmati rice", "Olive oil", "Eggs");
        assertThat(fromCook.getLines()).allMatch(l -> l.getCategory() == ItemCategory.PROVISIONS);
        assertThat(fromCrew.getRequestedBy().getRole()).isEqualTo(Role.CREW_MEMBER);
        assertThat(fromCrew.getLines()).extracting(RequestLine::getName, RequestLine::getCategory)
                .containsExactly(tuple("Shower gel", ItemCategory.PERSONAL_ITEMS), tuple("Eggs", ItemCategory.PROVISIONS));
        assertThat(notificationRepository.findAllByUserEmailOrderByCreatedAtDescIdDesc(master.getEmail())).hasSize(2);
    }

    @Test
    void aLineHasEitherAnItemOrANewItemName() {
        RequestLine both = line(rice, "1");
        both.setNewItemName("Saffron");
        assertThatThrownBy(() -> submit(cook, null, both)).isInstanceOf(IllegalArgumentException.class);
        RequestLine neither = new RequestLine();
        neither.setQuantity(BigDecimal.ONE);
        assertThatThrownBy(() -> submit(cook, null, neither)).isInstanceOf(IllegalArgumentException.class);
    }

    private ItemRequest submit(User requestedBy, Instant createdAt, RequestLine... lines) {
        ItemRequest request = new ItemRequest();
        request.setShip(requestedBy.getShip());
        request.setRequestedBy(requestedBy);
        request.setCreatedAt(createdAt);
        request.getLines().addAll(List.of(lines));
        return requestService.submit(request);
    }

    private void decide(ItemRequest request, RequestStatus status, String reason, Instant at) {
        request.setStatus(status);
        request.setRejectionReason(reason);
        request.setDecidedBy(master);
        request.setDecidedAt(at);
        requestRepository.saveAndFlush(request);
    }

    private static RequestLine line(Item item, String quantity) {
        RequestLine line = new RequestLine();
        line.setItem(item);
        line.setQuantity(new BigDecimal(quantity));
        return line;
    }

    private String page(String url, User u) throws Exception {
        return mockMvc.perform(get(url).with(user(new CurrentUser(u)))).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private User crew(String email, String name, Rank rank, Ship s) {
        return userService.createUser(email, name, "secret123", rank.role(), s, rank);
    }

    private Item item(Ship s, String name, String unit, ItemCategory category) {
        Item item = new Item();
        item.setShip(s);
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
