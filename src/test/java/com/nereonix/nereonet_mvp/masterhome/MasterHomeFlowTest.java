package com.nereonix.nereonet_mvp.masterhome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.announcement.AnnouncementService;
import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemCategory;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.library.LibraryAcknowledgement;
import com.nereonix.nereonet_mvp.core.library.LibraryAcknowledgementRepository;
import com.nereonix.nereonet_mvp.core.library.LibraryCategory;
import com.nereonix.nereonet_mvp.core.library.LibraryItem;
import com.nereonix.nereonet_mvp.core.library.LibraryItemRepository;
import com.nereonix.nereonet_mvp.core.library.LibraryItemVersion;
import com.nereonix.nereonet_mvp.core.library.LibraryItemVersionRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrder;
import com.nereonix.nereonet_mvp.core.order.ShipOrderRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrderStatus;
import com.nereonix.nereonet_mvp.core.request.ItemRequest;
import com.nereonix.nereonet_mvp.core.request.RequestLine;
import com.nereonix.nereonet_mvp.core.request.RequestService;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserService;

/**
 * The Master's home page (/ship as MASTER): the ship's name and IMO number; cards with a number each (Requests to
 * approve, Ship order, Deliveries to receive since P59, Announcements to acknowledge, Library to acknowledge, Crew on
 * board), quiet at zero and highlighted with something to do; the three most recently sent orders. The Chief cook and Crew members keep the crew
 * home page.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MasterHomeFlowTest {

    private static final Pattern RECENT_ORDERS =
            Pattern.compile("(?s)<section [^>]*aria-labelledby=\"recentOrdersTitle\">.*?</section>");
    private static final Pattern ORDER_TITLE = Pattern.compile("stretched-link\" href=\"/ship/orders/\\d+\">([^<]+)</a>");

    @Autowired MockMvc mockMvc;
    @Autowired ShipRepository shipRepository;
    @Autowired UserService userService;
    @Autowired ItemRepository itemRepository;
    @Autowired RequestService requestService;
    @Autowired ShipOrderRepository orderRepository;
    @Autowired LibraryItemRepository libraryItemRepository;
    @Autowired LibraryItemVersionRepository versionRepository;
    @Autowired LibraryAcknowledgementRepository acknowledgementRepository;
    @Autowired AnnouncementService announcementService;

    Ship ship;
    Ship otherShip;
    User master;
    User cook;
    User crewMember;
    User otherCook;
    Item rice;

    @BeforeEach
    void setUp() {
        ship = ship("9074729", "MV Demo");
        otherShip = ship("9123456", "MV Other");
        master = crew("master@example.com", "Ship Master", Rank.MASTER, ship);
        cook = crew("cook@example.com", "Demo Cook", Rank.CHIEF_COOK, ship);
        crewMember = crew("ab@example.com", "Ana Santos", Rank.ABLE_SEAMAN, ship);
        otherCook = crew("other.cook@example.com", "Other Cook", Rank.CHIEF_COOK, otherShip);
        rice = item("Basmati rice", "kg");
    }

    @Test
    void onePendingRequestIsOneRequestToApprove() throws Exception {
        submit(cook, "6");

        String html = home(master);
        assertThat(html).contains("<title>Home · Nereonet</title>", ">MV Demo</span>",
                "<p class=\"page-subtitle\">IMO 9074729</p>");
        // P65: no Approvals card; the number waiting is on the Approvals tab (phones) and sidebar entry.
        assertThat(html).doesNotContain("data-card=\"approvals\"")
                .containsPattern("(?s)<a class=\"tab-bar-link\" href=\"/ship/approvals\">.*?"
                        + "<span class=\"tab-bar-count num\" aria-hidden=\"true\">1</span>.*?<span class=\"tab-bar-label\">Approvals</span>")
                .containsPattern("<span class=\"sidebar-link-label\">Approvals</span>\\s*"
                        + "<span class=\"nav-count num\" aria-hidden=\"true\">1</span>");
        // Nothing else waits: quiet cards with a zero, no tint.
        for (String key : List.of("order", "deliveries", "announcements", "library")) {
            assertThat(card(html, key)).as(key).contains("<span class=\"home-card-count num\">0</span>")
                    .doesNotContain("is-due", ">to do<");
        }
        // Not the crew home page.
        assertThat(html).doesNotContain("toReadTitle", "My certificates");
    }

    @Test
    @WithUserDetails("office@example.com") // sends the announcement; the page is opened as the Master
    void eachCardCountsItsOwnShipAndPerson() throws Exception {
        // Requests: two PENDING here, one approved (it goes into the OPEN order), one on another ship.
        submit(cook, "1");
        submit(crewMember, "2");
        ItemRequest approved = submit(crewMember, "3");
        requestService.approve(master, approved.getId());
        submit(otherCook, "4");
        // Library: two Required items, one acknowledged by the Master; one not required; one retired.
        LibraryItem policy = libraryItem(ship, "Drug and alcohol policy", true);
        libraryItem(ship, "Fire drill procedure", true);
        libraryItem(ship, "Galley hygiene rules", false);
        libraryItem(ship, "Old garbage plan", true).setRetired(true);
        libraryItem(otherShip, "Other ship policy", true);
        acknowledge(master, policy);
        // Announcements: one to the Master's ship.
        announcementService.create("Safety drill", "Drill on Friday.", true, false, List.of(ship.getId()),
                "office@example.com");
        // Crew on board: three enabled, one disabled, one on another ship.
        crew("gone@example.com", "Gone Sailor", Rank.ORDINARY_SEAMAN, ship).setEnabled(false);
        libraryItemRepository.flush();

        String html = home(master);
        assertThat(html).containsPattern("<span class=\"sidebar-link-label\">Approvals</span>\\s*"
                + "<span class=\"nav-count num\" aria-hidden=\"true\">2</span>");
        assertThat(card(html, "order")).contains("href=\"/ship/order\"", ">Vessel order<", ">1</span>", "is-due");
        assertThat(card(html, "announcements"))
                .contains("href=\"/ship/news\"", ">News to read<", ">1</span>", "is-due");
        assertThat(card(html, "library"))
                .contains("href=\"/ship/library\"", ">Library to read<", ">1</span>", "is-due");
        // Crew on board informs: never highlighted.
        assertThat(card(html, "crew")).contains("href=\"/ship/crew\"", ">Crew on board<", ">3</span>")
                .doesNotContain("is-due");
        // The cards in page order.
        assertThat(Pattern.compile("data-card=\"([a-z]+)\"").matcher(html).results().map(m -> m.group(1)).toList())
                .containsExactly("order", "deliveries", "announcements", "library", "crew");
    }

    // P59: the scheduled deliveries (CONFIRMED) of the Master's ship; one opens its receipt, more open Vessel order.
    @Test
    void deliveriesToReceiveCountsTheScheduledDeliveries() throws Exception {
        Instant now = Instant.now();
        sentOrder(ShipOrderStatus.SENT, now.minus(Duration.ofDays(1)), null, null);
        sentOrder(ShipOrderStatus.CLOSED, now.minus(Duration.ofDays(30)), "Hamburg", LocalDate.of(2026, 10, 15));
        ShipOrder first = sentOrder(ShipOrderStatus.CONFIRMED, now.minus(Duration.ofDays(5)), "Antwerp",
                LocalDate.of(2026, 11, 20));
        ShipOrder otherShips = sentOrder(ShipOrderStatus.CONFIRMED, now.minus(Duration.ofDays(5)), "Piraeus",
                LocalDate.of(2026, 11, 21));
        otherShips.setShip(otherShip);

        assertThat(card(home(master), "deliveries")).contains(">Deliveries to receive<",
                "href=\"/ship/orders/" + first.getId() + "/receipt\"", ">1</span>", "is-due", ">to do<");

        sentOrder(ShipOrderStatus.CONFIRMED, now.minus(Duration.ofDays(3)), "Rotterdam", LocalDate.of(2026, 11, 25));
        assertThat(card(home(master), "deliveries")).contains("href=\"/ship/order\"", ">2</span>", "is-due");
    }

    @Test
    void recentOrdersAreTheThreeLastSent() throws Exception {
        Instant now = Instant.now();
        sentOrder(ShipOrderStatus.CLOSED, now.minus(Duration.ofDays(40)), "Piraeus", LocalDate.of(2026, 10, 1));
        ShipOrder confirmed = sentOrder(ShipOrderStatus.CONFIRMED, now.minus(Duration.ofDays(20)), "Antwerp",
                LocalDate.of(2026, 11, 20));
        ShipOrder received = sentOrder(ShipOrderStatus.CLOSED, now.minus(Duration.ofDays(30)), "Hamburg",
                LocalDate.of(2026, 10, 15));
        ShipOrder sent = sentOrder(ShipOrderStatus.SENT, now.minus(Duration.ofDays(1)), null, null);
        ShipOrder open = new ShipOrder();
        open.setShip(ship);
        open.setStatus(ShipOrderStatus.OPEN);
        open.setCreatedAt(now);
        orderRepository.save(open);

        String recent = section(RECENT_ORDERS, home(master));
        assertThat(ORDER_TITLE.matcher(recent).results().map(m -> m.group(1)).toList()).containsExactly(
                "Order #" + sent.getId(), "Order #" + confirmed.getId(), "Order #" + received.getId());
        assertThat(recent).containsSubsequence("Waiting for the office to schedule the delivery", ">Pending</span>",
                        "Delivery: Antwerp", "Delivery date", "20 Nov 2026", "Delivery scheduled",
                        "Delivery: Hamburg", "15 Oct 2026", "Received")
                .contains("href=\"/ship/orders\">All orders</a>")
                .doesNotContain("Piraeus");
    }

    @Test
    void noOrdersSentYet() throws Exception {
        assertThat(section(RECENT_ORDERS, home(master))).contains("No orders sent yet");
    }

    @Test
    void theChiefCookAndCrewKeepTheCrewHomePage() throws Exception {
        for (User u : List.of(cook, crewMember)) {
            assertThat(home(u)).contains("toReadTitle", "My certificates").doesNotContain("data-card=\"approvals\"");
        }
        // P52: the Chief cook has a Stores card (in the Master's card style); P54: crew members have a Requests card
        // (P65: the Chief cook no longer, Requests is their tab with the same number).
        assertThat(home(cook)).containsOnlyOnce("data-card=").contains("data-card=\"stores\"");
        assertThat(home(crewMember)).containsOnlyOnce("data-card=").contains("data-card=\"requests\"");
    }

    private String home(User u) throws Exception {
        return mockMvc.perform(get("/ship").with(user(new CurrentUser(u)))).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String card(String html, String key) {
        return section(Pattern.compile("(?s)<a class=\"home-card[^\"]*\"[^>]*data-card=\"" + key + "\">.*?</a>"), html);
    }

    private static String section(Pattern pattern, String html) {
        Matcher m = pattern.matcher(html);
        assertThat(m.find()).as(pattern.pattern()).isTrue();
        return m.group();
    }

    private ItemRequest submit(User requestedBy, String quantity) {
        Item item = requestedBy.getShip().getId().equals(ship.getId()) ? rice : item(otherShip, "Pasta", "kg");
        RequestLine line = new RequestLine();
        line.setItem(item);
        line.setQuantity(new BigDecimal(quantity));
        ItemRequest request = new ItemRequest();
        request.setShip(requestedBy.getShip());
        request.setRequestedBy(requestedBy);
        request.getLines().add(line);
        return requestService.submit(request);
    }

    private ShipOrder sentOrder(ShipOrderStatus status, Instant sentAt, String confirmedPort, LocalDate deliveryDate) {
        ShipOrder order = new ShipOrder();
        order.setShip(ship);
        order.setStatus(status);
        order.setRequestedPort("Rotterdam");
        order.setNeededBy(LocalDate.of(2026, 12, 1));
        order.setSentBy(master);
        order.setSentAt(sentAt);
        order.setConfirmedPort(confirmedPort);
        order.setDeliveryDate(deliveryDate);
        order.setCreatedAt(sentAt);
        return orderRepository.save(order);
    }

    private LibraryItem libraryItem(Ship s, String title, boolean required) {
        LibraryItem item = new LibraryItem();
        item.setShip(s);
        item.setTitle(title);
        item.setCategory(LibraryCategory.COMPANY_POLICIES);
        item.setRequired(required);
        item.setCreatedAt(Instant.now());
        libraryItemRepository.save(item);
        LibraryItemVersion version = new LibraryItemVersion();
        version.setItem(item);
        version.setVersionLabel("1");
        version.setText("Read this.");
        version.setPublishedAt(Instant.now());
        item.setCurrentVersion(versionRepository.save(version));
        return item;
    }

    private void acknowledge(User u, LibraryItem item) {
        LibraryAcknowledgement acknowledgement = new LibraryAcknowledgement();
        acknowledgement.setVersion(item.getCurrentVersion());
        acknowledgement.setUser(u);
        acknowledgement.setAcknowledgedAt(Instant.now());
        acknowledgementRepository.save(acknowledgement);
    }

    private User crew(String email, String name, Rank rank, Ship s) {
        return userService.createUser(email, name, "secret123", rank.role(), s, rank);
    }

    private Item item(String name, String unit) {
        return item(ship, name, unit);
    }

    private Item item(Ship s, String name, String unit) {
        Item item = new Item();
        item.setShip(s);
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
