package com.nereonix.nereonet_mvp.shiprequest;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditEntry;
import com.nereonix.nereonet_mvp.core.audit.AuditRepository;
import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemCategory;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.notification.Notification;
import com.nereonix.nereonet_mvp.core.notification.NotificationRepository;
import com.nereonix.nereonet_mvp.core.notification.NotificationType;
import com.nereonix.nereonet_mvp.core.request.ItemRequest;
import com.nereonix.nereonet_mvp.core.request.ItemRequestRepository;
import com.nereonix.nereonet_mvp.core.request.RequestLine;
import com.nereonix.nereonet_mvp.core.request.RequestService;
import com.nereonix.nereonet_mvp.core.request.RequestStatus;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.stock.StockLevel;
import com.nereonix.nereonet_mvp.core.stock.StockLevelRepository;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserService;

/**
 * The Chief cook's and crew's requests to the Master: the cook sends items from the list and new items, the
 * request waits for the Master (who is notified), and while it waits the person who asked can change or cancel it.
 * Only the person who asked sees it; the Master never asks (403). The cook sees what is running low; crew never see
 * stock.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ShipRequestFlowTest {

    @Autowired MockMvc mockMvc;
    @Autowired ShipRepository shipRepository;
    @Autowired UserService userService;
    @Autowired ItemRepository itemRepository;
    @Autowired ItemRequestRepository requestRepository;
    @Autowired RequestService requestService;
    @Autowired StockLevelRepository levelRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired AuditRepository auditRepository;

    Ship ship;
    User master;
    User cook;
    User crewMember;
    User otherCrewMember;
    Item rice;
    Item oil;
    Item sugar;
    Item showerGel;
    Item retired;

    @BeforeEach
    void setUp() {
        ship = ship("9074729", "MV Demo");
        master = crew("master@example.com", "Ship Master", Rank.MASTER, ship);
        cook = crew("cook@example.com", "Demo Cook", Rank.CHIEF_COOK, ship);
        crewMember = crew("ab@example.com", "Maria Santos", Rank.ABLE_SEAMAN, ship);
        otherCrewMember = crew("oiler@example.com", "Nikos Pappas", Rank.ABLE_SEAMAN, ship);
        rice = item("Basmati rice", "kg", ItemCategory.PROVISIONS, true);
        oil = item("Olive oil", "lt", ItemCategory.PROVISIONS, true);
        sugar = item("Sugar", "kg", ItemCategory.PROVISIONS, true);
        showerGel = item("Shower gel", "pcs", ItemCategory.PERSONAL_ITEMS, true);
        retired = item("Retired spice", "g", ItemCategory.PROVISIONS, false);
        stock(rice, "40");
        stock(oil, "4");
        // Sugar has no stock row: Out of stock.
    }

    @Test
    void theCookSendsAnItemFromTheListAndANewItem() throws Exception {
        String form = page("/ship/requests/new", cook);
        assertThat(form).contains("<title>New request · Nereonet</title>", "Running low",
                "Add an item that is not on the list", "Send to the master")
                .doesNotContain("Retired spice");
        // Running low first: Out of stock (Sugar), then Low (Olive oil) with what is on board, then the rest.
        assertThat(form.indexOf(">Sugar<")).isPositive().isLessThan(form.indexOf(">Olive oil<"));
        assertThat(form.indexOf(">Olive oil<")).isLessThan(form.indexOf(">Basmati rice<"));
        assertThat(form.indexOf(">Basmati rice<")).isLessThan(form.indexOf(">Shower gel<"));
        assertThat(form).containsPattern("(?s)>Olive oil<.*?<span class=\"num\">4</span> <span>lt</span>.*?Low");

        // The new item is still in its fields when the cook presses Send: it is added with the rest.
        String location = mockMvc.perform(as(post("/ship/requests/new"), cook)
                        .param("lines[0].itemId", rice.getId().toString()).param("lines[0].quantity", "25")
                        .param("lines[1].itemId", oil.getId().toString()).param("lines[1].quantity", "")
                        .param("newName", " Saffron ").param("newUnit", "kg").param("newQuantity", "0,25"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("successMessage", "request.sent"))
                .andReturn().getResponse().getRedirectedUrl();

        ItemRequest request = requestRepository.findAllByRequestedByIdOrderByCreatedAtDescIdDesc(cook.getId())
                .getFirst();
        assertThat(location).isEqualTo("/ship/requests/" + request.getId());
        assertThat(request.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(request.getLines()).hasSize(2);
        RequestLine fromList = request.getLines().stream().filter(l -> !l.isNewItem()).findFirst().orElseThrow();
        assertThat(fromList.getItem().getId()).isEqualTo(rice.getId());
        assertThat(fromList.getQuantity()).isEqualByComparingTo("25");
        RequestLine newItem = request.getLines().stream().filter(RequestLine::isNewItem).findFirst().orElseThrow();
        assertThat(newItem.getNewItemName()).isEqualTo("Saffron");
        assertThat(newItem.getNewItemUnit()).isEqualTo("kg");
        assertThat(newItem.getQuantity()).isEqualByComparingTo("0.25");

        List<Notification> notifications = notificationRepository.findAllByUserEmailOrderByCreatedAtDescIdDesc(
                master.getEmail());
        assertThat(notifications).singleElement().satisfies(n -> {
            assertThat(n.getType()).isEqualTo(NotificationType.REQUEST);
            assertThat(n.getLink()).isEqualTo("/ship/approvals/" + request.getId());
        });

        assertThat(page("/ship/requests/" + request.getId(), cook))
                .contains("Request #" + request.getId(), "Waiting for the master", "Saffron", "New item",
                        "Cancel request", "/ship/requests/" + request.getId() + "/edit");
    }

    @Test
    void addAndRemoveShowTheFormAgainWithWhatWasTyped() throws Exception {
        String added = mockMvc.perform(as(post("/ship/requests/new"), cook).param("add", "1")
                        .param("lines[0].itemId", rice.getId().toString()).param("lines[0].quantity", "3")
                        .param("newName", "Saffron").param("newQuantity", "50"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(added).contains("name=\"newItems[0].name\" value=\"Saffron\"",
                "name=\"newItems[0].quantity\" value=\"50\"")
                .containsPattern("name=\"lines\\[\\d+]\\.quantity\" value=\"3\"");
        assertThat(requestRepository.count()).isZero();

        // A name already on the list is picked there.
        mockMvc.perform(as(post("/ship/requests/new"), cook).param("add", "1")
                        .param("newName", "basmati RICE").param("newQuantity", "1"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("Basmati rice is on the list: enter its quantity there."));

        String removed = mockMvc.perform(as(post("/ship/requests/new"), cook).param("remove", "0")
                        .param("newItems[0].name", "Saffron").param("newItems[0].quantity", "50"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(removed).doesNotContain("newItems[0].name");
    }

    @Test
    void quantitiesFollowTheRuleOfTheItemsUnit() throws Exception {
        // Each field carries the limit of its item, and the page the script's texts; no limit is shown.
        String form = page("/ship/requests/new", cook);
        assertThat(form).doesNotContain("Up to 2000 kg", "Whole numbers, up to 5000 pcs", "kg, lt or pcs")
                .contains("data-quantity-texts",
                        "data-quantity-max=\"2000\"", "inputmode=\"numeric\"")
                // data-quantity is what js/quantity.js looks for.
                .containsPattern("<input type=\"text\"[^>]* data-quantity[\\s>][^>]*id=\"lines\\[\\d+]\\.quantity\"");

        // Letters, decimals for pcs and 1000000 kg are refused, each under its field and in the summary.
        String wrong = mockMvc.perform(as(post("/ship/requests/new"), cook)
                        .param("lines[0].itemId", rice.getId().toString()).param("lines[0].quantity", "abc")
                        .param("lines[1].itemId", showerGel.getId().toString()).param("lines[1].quantity", "1,5")
                        .param("lines[2].itemId", sugar.getId().toString()).param("lines[2].quantity", "1000000"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(wrong).contains("This form has 3 errors", "Enter a number.", "Use whole numbers for pcs.",
                        "Up to 2000 kg per line. For more, split it or ask the office.")
                .contains("value=\"abc\"", "value=\"1,5\"", "value=\"1000000\"");
        assertThat(requestRepository.findAllByRequestedByIdOrderByCreatedAtDescIdDesc(cook.getId())).isEmpty();

        // "1,5" kg is 1.5 kg.
        mockMvc.perform(as(post("/ship/requests/new"), cook)
                        .param("lines[0].itemId", sugar.getId().toString()).param("lines[0].quantity", "1,5"))
                .andExpect(status().is3xxRedirection());
        ItemRequest request = requestRepository.findAllByRequestedByIdOrderByCreatedAtDescIdDesc(cook.getId())
                .getFirst();
        assertThat(request.getLines()).singleElement()
                .satisfies(line -> assertThat(line.getQuantity()).isEqualByComparingTo("1.5"));
    }

    @Test
    void anItemsOwnMaximumAndAQuantityAlreadySavedAreKept() throws Exception {
        // The office allows 3000 kg of rice in one request.
        rice.setMaxPerRequest(new BigDecimal("3000"));
        itemRepository.save(rice);
        assertThat(page("/ship/requests/new", cook)).contains("data-quantity-max=\"3000\"");
        mockMvc.perform(as(post("/ship/requests/new"), cook)
                        .param("lines[0].itemId", rice.getId().toString()).param("lines[0].quantity", "2500"))
                .andExpect(status().is3xxRedirection());

        // A request saved with more than today's limit (before P60, when there was no limit) can still be edited and saved as it is,
        // but not changed to another value above the limit.
        ItemRequest old = submit(cook, line(sugar, "2500"));
        mockMvc.perform(as(post("/ship/requests/" + old.getId() + "/edit"), cook)
                        .param("lines[0].itemId", sugar.getId().toString()).param("lines[0].quantity", "2500"))
                .andExpect(status().is3xxRedirection());
        String changed = mockMvc.perform(as(post("/ship/requests/" + old.getId() + "/edit"), cook)
                        .param("lines[0].itemId", sugar.getId().toString()).param("lines[0].quantity", "2600"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(changed).contains("Up to 2000 kg per line.");
    }

    @Test
    void aNewItemsQuantityFollowsTheRuleOfItsUnit() throws Exception {
        String refused = mockMvc.perform(as(post("/ship/requests/new"), cook)
                        .param("newName", "Saffron").param("newUnit", "g").param("newQuantity", "2,5").param("add", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(refused).contains("Use whole numbers for g.").doesNotContain("newItems[0].name");
        // Without a unit: whole numbers, up to the countable maximum.
        String noUnit = mockMvc.perform(as(post("/ship/requests/new"), cook)
                        .param("newName", "Saffron").param("newQuantity", "6000").param("add", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(noUnit).contains("Up to 5000 units per line.");
    }

    @Test
    void aRequestWithoutLinesOrWithAWrongQuantityIsAFormError() throws Exception {
        String empty = mockMvc.perform(as(post("/ship/requests/new"), cook)
                        .param("lines[0].itemId", rice.getId().toString()).param("lines[0].quantity", ""))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(empty).contains("Pick at least one item: enter its quantity.");

        String wrong = mockMvc.perform(as(post("/ship/requests/new"), cook)
                        .param("lines[0].itemId", rice.getId().toString()).param("lines[0].quantity", "-2"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(wrong).contains("Enter a quantity above zero.")
                .containsPattern("value=\"-2\"");

        // An item the office made inactive meanwhile cannot be asked for.
        String gone = mockMvc.perform(as(post("/ship/requests/new"), cook)
                        .param("lines[0].itemId", retired.getId().toString()).param("lines[0].quantity", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(gone).contains("no longer on the vessel");
        assertThat(requestRepository.count()).isZero();
    }

    @Test
    void crewMembersNeverSeeStockAndCannotAddNewItems() throws Exception {
        String form = page("/ship/requests/new", crewMember);
        assertThat(form).contains(">Basmati rice<", ">Shower gel<")
                .doesNotContain("Running low", "In stock", "Add an item that is not on the list", "newName");

        mockMvc.perform(as(post("/ship/requests/new"), crewMember)
                        .param("lines[0].itemId", showerGel.getId().toString()).param("lines[0].quantity", "2")
                        .param("newName", "Saffron").param("newQuantity", "1")
                        .param("newItems[0].name", "Caviar").param("newItems[0].quantity", "1"))
                .andExpect(status().is3xxRedirection());
        ItemRequest request = requestRepository.findAllByRequestedByIdOrderByCreatedAtDescIdDesc(crewMember.getId())
                .getFirst();
        assertThat(request.getLines()).singleElement().satisfies(l -> assertThat(l.isNewItem()).isFalse());
    }

    @Test
    void theCookCancelsAPendingRequest() throws Exception {
        ItemRequest request = submit(cook, line(rice, "10"));

        mockMvc.perform(as(post("/ship/requests/" + request.getId() + "/cancel"), cook))
                .andExpect(redirectedUrl("/ship/requests/" + request.getId()))
                .andExpect(flash().attribute("successMessage", "request.cancelled"));

        assertThat(request.getStatus()).isEqualTo(RequestStatus.CANCELLED);
        assertThat(request.getDecidedAt()).isNull();
        assertThat(requestRepository.findAllByShipIdAndStatusOrderByCreatedAtAscIdAsc(ship.getId(),
                RequestStatus.PENDING)).isEmpty();
        assertThat(page("/ship/approvals", master)).doesNotContain("/ship/approvals/" + request.getId() + "\"");
        assertThat(auditRepository.findAll()).filteredOn(e -> e.getAction().equals("REQUEST_CANCELLED"))
                .singleElement().extracting(AuditEntry::getDetails).isEqualTo("Request #" + request.getId() + ", 1 lines");
        assertThat(page("/ship/requests/" + request.getId(), cook))
                .contains("Cancelled", "You cancelled this request. It can no longer be changed.")
                .doesNotContain("/ship/requests/" + request.getId() + "/edit");

        // A second time (another tab): nothing changes.
        mockMvc.perform(as(post("/ship/requests/" + request.getId() + "/cancel"), cook))
                .andExpect(redirectedUrl("/ship/requests/" + request.getId()))
                .andExpect(flash().attribute("errorMessage", "request.notPending"));
    }

    @Test
    void editingChangesTheLinesUntilTheMasterDecides() throws Exception {
        ItemRequest request = submit(cook, line(rice, "10"));

        String form = page("/ship/requests/" + request.getId() + "/edit", cook);
        assertThat(form).contains("Edit request #" + request.getId()).containsPattern("value=\"10\"");

        mockMvc.perform(as(post("/ship/requests/" + request.getId() + "/edit"), cook)
                        .param("lines[0].itemId", rice.getId().toString()).param("lines[0].quantity", "12")
                        .param("lines[1].itemId", showerGel.getId().toString()).param("lines[1].quantity", "2"))
                .andExpect(redirectedUrl("/ship/requests/" + request.getId()))
                .andExpect(flash().attribute("successMessage", "request.saved"));
        ItemRequest saved = requestRepository.findById(request.getId()).orElseThrow();
        assertThat(saved.getLines()).extracting(l -> l.getItem().getName(), l -> l.getQuantity().stripTrailingZeros())
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("Basmati rice", new BigDecimal("12")),
                        org.assertj.core.groups.Tuple.tuple("Shower gel", new BigDecimal("2")));
        assertThat(auditRepository.findAll()).filteredOn(e -> e.getAction().equals("REQUEST_CHANGED")).hasSize(1);

        // Removing every line is the same error as sending none; nothing changes.
        mockMvc.perform(as(post("/ship/requests/" + request.getId() + "/edit"), cook)
                        .param("lines[0].itemId", rice.getId().toString()).param("lines[0].quantity", ""))
                .andExpect(status().isOk());
        assertThat(requestRepository.findById(request.getId()).orElseThrow().getLines()).hasSize(2);

        requestService.approve(master, request.getId());
        mockMvc.perform(as(get("/ship/requests/" + request.getId() + "/edit"), cook))
                .andExpect(redirectedUrl("/ship/requests/" + request.getId()))
                .andExpect(flash().attribute("errorMessage", "request.notPending"));
        mockMvc.perform(as(post("/ship/requests/" + request.getId() + "/edit"), cook)
                        .param("lines[0].itemId", rice.getId().toString()).param("lines[0].quantity", "1"))
                .andExpect(flash().attribute("errorMessage", "request.notPending"));
        assertThat(page("/ship/requests/" + request.getId(), cook))
                .contains("Approved", "Ship Master", "The master approved this request");
    }

    @Test
    void onlyThePersonWhoAskedOpensIt() throws Exception {
        ItemRequest request = submit(crewMember, line(showerGel, "2"));

        mockMvc.perform(as(get("/ship/requests/" + request.getId()), otherCrewMember)).andExpect(status().isNotFound());
        mockMvc.perform(as(get("/ship/requests/" + request.getId()), cook)).andExpect(status().isNotFound());
        mockMvc.perform(as(get("/ship/requests/" + request.getId() + "/edit"), otherCrewMember))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(post("/ship/requests/" + request.getId() + "/cancel"), otherCrewMember))
                .andExpect(status().isNotFound());
        assertThat(request.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(page("/ship/requests", otherCrewMember)).doesNotContain("/ship/requests/" + request.getId() + "\"");
    }

    @Test
    void theMasterGets403() throws Exception {
        mockMvc.perform(as(get("/ship/requests/new"), master)).andExpect(status().isForbidden());
        mockMvc.perform(as(get("/ship/requests"), master)).andExpect(status().isForbidden());
    }

    @Test
    void theListTheCountersAndTheCards() throws Exception {
        ItemRequest waiting = submit(cook, line(rice, "10"));
        ItemRequest cancelled = submit(cook, line(oil, "5"));
        requestService.cancel(cook, cancelled.getId());

        String all = page("/ship/requests", cook);
        assertThat(all).contains("href=\"/ship/requests/" + waiting.getId() + "\"",
                "href=\"/ship/requests/" + cancelled.getId() + "\"", "Waiting for the master", "Cancelled",
                "New request");
        String onlyWaiting = page("/ship/requests?status=waiting", cook);
        assertThat(onlyWaiting).contains("href=\"/ship/requests/" + waiting.getId() + "\"")
                .doesNotContain("href=\"/ship/requests/" + cancelled.getId() + "\"");
        // A cancelled request is under All only; an unknown status shows All.
        assertThat(page("/ship/requests?status=approved", cook))
                .doesNotContain("href=\"/ship/requests/" + cancelled.getId() + "\"");
        assertThat(page("/ship/requests?status=declined", cook))
                .doesNotContain("href=\"/ship/requests/" + cancelled.getId() + "\"");
        assertThat(page("/ship/requests?status=decided", cook))
                .contains("href=\"/ship/requests/" + cancelled.getId() + "\"");

        // The sidebar counts the one still waiting; so do Stores and Home.
        assertThat(all).containsPattern("<span[^>]*>Requests</span>\\s*<span class=\"nav-count num\""
                + " aria-hidden=\"true\">1</span>");
        assertThat(page("/ship/stores", cook)).containsPattern(
                "(?s)href=\"/ship/requests\\?status=waiting\">\\s*<span class=\"stores-fact-label\">Requests waiting</span>"
                        + "\\s*<span class=\"stores-fact-value num\">1</span>");
        // The Chief cook's Requests is a tab with the number (no card on Home); crew members keep the card.
        assertThat(page("/ship", cook)).doesNotContain("data-card=\"requests\"")
                .containsPattern("(?s)<a class=\"tab-bar-link\" href=\"/ship/requests\">.*?"
                        + "<span class=\"tab-bar-count num\" aria-hidden=\"true\">1</span>");
        assertThat(page("/ship", crewMember)).contains("data-card=\"requests\"").doesNotContain("data-card=\"stores\"");
        assertThat(page("/ship/more", crewMember)).contains("href=\"/ship/requests\"");
        assertThat(page("/ship/more", master)).doesNotContain("href=\"/ship/requests\"");

        // Nothing yet: the empty state carries New request.
        assertThat(page("/ship/requests", crewMember)).contains("No requests yet", "/ship/requests/new");
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

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, User u) {
        return request.with(user(new CurrentUser(u))).with(csrf());
    }

    private String page(String url, User u) throws Exception {
        return mockMvc.perform(get(url).with(user(new CurrentUser(u)))).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private User crew(String email, String name, Rank rank, Ship s) {
        return userService.createUser(email, name, "secret123", rank.role(), s, rank);
    }

    private Item item(String name, String unit, ItemCategory category, boolean active) {
        Item item = new Item();
        item.setShip(ship);
        item.setName(name);
        item.setUnit(unit);
        item.setCategory(category);
        item.setActive(active);
        item.setCreatedAt(Instant.now());
        return itemRepository.save(item);
    }

    private void stock(Item item, String quantity) {
        StockLevel level = new StockLevel();
        level.setItem(item);
        level.setQuantity(new BigDecimal(quantity));
        level.setUpdatedAt(Instant.now());
        levelRepository.save(level);
    }

    private Ship ship(String imo, String name) {
        Ship s = new Ship();
        s.setImoNumber(imo);
        s.setName(name);
        s.setCreatedAt(Instant.now());
        return shipRepository.save(s);
    }
}
