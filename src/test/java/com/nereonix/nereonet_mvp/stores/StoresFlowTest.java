package com.nereonix.nereonet_mvp.stores;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemCategory;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.stock.StockLevel;
import com.nereonix.nereonet_mvp.core.stock.StockLevelRepository;
import com.nereonix.nereonet_mvp.core.stock.StockMovement;
import com.nereonix.nereonet_mvp.core.stock.StockMovementRepository;
import com.nereonix.nereonet_mvp.core.stock.StockMovementType;
import com.nereonix.nereonet_mvp.core.stock.StockService;
import com.nereonix.nereonet_mvp.core.stock.StockStatus;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserService;

/**
 * P52, the Chief cook's store: /ship/stores lists the active provisions of the cook's own ship by place with their
 * quantity and status (OK, Low below the threshold, Out of stock at 0 or without a row), the summary and the last
 * count, filtered by status and searched by name on the server; an item's page shows its history, newest first. Only
 * the Chief cook, only their own ship, never personal items.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StoresFlowTest {

    @Autowired MockMvc mockMvc;
    @Autowired ShipRepository shipRepository;
    @Autowired UserService userService;
    @Autowired ItemRepository itemRepository;
    @Autowired StockLevelRepository levelRepository;
    @Autowired StockMovementRepository movementRepository;
    @Autowired StockService stockService;

    Ship ship;
    Ship otherShip;
    User cook;
    User master;
    User crewMember;
    Item rice;
    Item oil;
    Item sugar;
    Item salt;
    Item showerGel;
    Item otherShipsRice;

    @BeforeEach
    void setUp() {
        ship = ship("9074729", "MV Demo");
        otherShip = ship("9123456", "MV Other");
        cook = crew("cook@example.com", "Demo Cook", Rank.CHIEF_COOK, ship);
        master = crew("master@example.com", "Ship Master", Rank.MASTER, ship);
        crewMember = crew("ab@example.com", "Maria Santos", Rank.ABLE_SEAMAN, ship);
        rice = item(ship, "Basmati rice", "kg", ItemCategory.PROVISIONS, "Dry store");
        oil = item(ship, "Olive oil", "lt", ItemCategory.PROVISIONS, "Dry store");
        sugar = item(ship, "Sugar", "kg", ItemCategory.PROVISIONS, "Dry store");
        salt = item(ship, "Salt", "kg", ItemCategory.PROVISIONS, null);
        showerGel = item(ship, "Shower gel", "pcs", ItemCategory.PERSONAL_ITEMS, null);
        otherShipsRice = item(otherShip, "Jasmine rice", "kg", ItemCategory.PROVISIONS, "Dry store");
        stock(rice, "40");
        stock(oil, "4");
        stock(salt, "12.5");
        stock(otherShipsRice, "3");
        // Sugar has no row: Out of stock.
    }

    @Test
    void theCookSeesEachItemWithItsQuantityAndStatus() throws Exception {
        String html = page("/ship/stores", cook);

        assertThat(html).contains("<title>Stores · Nereonet</title>", "MV Demo · IMO 9074729")
                .containsPattern("(?s)href=\"/ship/stores/items/" + rice.getId() + "\">Basmati rice</a>\\s*"
                        + "<span class=\"stores-qty\"><span class=\"num\">40</span> <span>kg</span></span>\\s*"
                        + "<span class=\"badge status-badge status-ok\">OK</span>")
                .containsPattern("(?s)href=\"/ship/stores/items/" + oil.getId() + "\">Olive oil</a>\\s*"
                        + "<span class=\"stores-qty\"><span class=\"num\">4</span> <span>lt</span></span>\\s*"
                        + "<span class=\"badge status-badge status-warning\">Low</span>")
                .containsPattern("(?s)href=\"/ship/stores/items/" + sugar.getId() + "\">Sugar</a>\\s*"
                        + "<span class=\"stores-qty\"><span class=\"num\">0</span> <span>kg</span></span>\\s*"
                        + "<span class=\"badge status-badge status-danger\">Out of stock</span>")
                .contains(">12.5</span> <span>kg</span>")
                .doesNotContain("Shower gel", "Jasmine rice");
        // By place, the items without one last under "Other".
        assertThat(html.indexOf(">Dry store</h2>")).isPositive().isLessThan(html.indexOf(">Other</h2>"));
        assertThat(html.indexOf(">Other</h2>")).isLessThan(html.indexOf(">Salt</a>"));
        // The summary: 1 Low, 1 Out of stock, never counted.
        assertThat(html).containsPattern("(?s)<span class=\"stores-fact-label\">Low</span>\\s*"
                        + "<span class=\"stores-fact-value num\">1</span>")
                .containsPattern("(?s)<span class=\"stores-fact-label\">Out of stock</span>\\s*"
                        + "<span class=\"stores-fact-value num\">1</span>")
                .contains("Not counted yet");
        // The sidebar counter: Low plus Out of stock.
        assertThat(html).containsPattern("<span[^>]*>Stores</span>\\s*<span class=\"nav-count num\""
                + " aria-hidden=\"true\">2</span>");
    }

    @Test
    void filterByStatusAndSearchByName() throws Exception {
        String low = page("/ship/stores?status=low", cook);
        assertThat(low).contains(">Olive oil</a>").doesNotContain(">Basmati rice</a>", ">Sugar</a>")
                .contains("Status: Low");

        String out = page("/ship/stores?status=out", cook);
        assertThat(out).contains(">Sugar</a>").doesNotContain(">Olive oil</a>", ">Basmati rice</a>");

        String search = page("/ship/stores?q=RICE", cook);
        assertThat(search).contains(">Basmati rice</a>").doesNotContain(">Olive oil</a>", ">Salt</a>");

        assertThat(page("/ship/stores?q=caviar", cook)).contains("No results for these filters");
        // An unknown status shows everything.
        assertThat(page("/ship/stores?status=nonsense", cook)).contains(">Basmati rice</a>", ">Sugar</a>");
    }

    @Test
    void theLastCountAndAnItemsHistoryNewestFirst() throws Exception {
        Instant counted = Instant.parse("2026-11-10T08:00:00Z");
        movementRepository.save(StockMovement.of(rice, StockMovementType.COUNT, BigDecimal.ZERO, new BigDecimal("30"),
                cook, counted, null, null));
        movementRepository.save(StockMovement.of(rice, StockMovementType.COUNT, new BigDecimal("30"),
                new BigDecimal("40"), cook, counted.plus(Duration.ofDays(2)), "Found a sack in the freezer room", null));

        assertThat(page("/ship/stores", cook)).doesNotContain("Not counted yet").contains("12 Nov 2026");

        String html = page("/ship/stores/items/" + rice.getId(), cook);
        assertThat(html).contains("<title>Basmati rice · Nereonet</title>", "href=\"/ship/stores\"", "Dry store",
                "Found a sack in the freezer room", "Demo Cook");
        assertThat(html.indexOf("Found a sack")).isLessThan(html.lastIndexOf(">Count<"));
        assertThat(stockService.history(rice)).extracting(StockMovement::getQuantityAfter)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("40"), new BigDecimal("30"));
    }

    @Test
    void anItemOfAnotherShipOrAPersonalItemIsNotFound() throws Exception {
        for (Item item : List.of(otherShipsRice, showerGel)) {
            mockMvc.perform(get("/ship/stores/items/" + item.getId()).with(user(new CurrentUser(cook))))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void onlyTheChiefCook() throws Exception {
        for (User other : List.of(crewMember, master)) {
            mockMvc.perform(get("/ship/stores").with(user(new CurrentUser(other)))).andExpect(status().isForbidden());
            mockMvc.perform(get("/ship/stores/items/" + rice.getId()).with(user(new CurrentUser(other))))
                    .andExpect(status().isForbidden());
            assertThat(page("/ship", other)).doesNotContain("href=\"/ship/stores\"");
        }
    }

    @Test
    void theCooksHomeAndMoreShowStores() throws Exception {
        assertThat(page("/ship", cook)).contains("data-card=\"stores\"", "is-due")
                .containsPattern("<span class=\"num\">1</span> low</span>")
                .containsPattern("<span class=\"num\">1</span> out of stock</span>");
        assertThat(page("/ship/more", cook)).contains("href=\"/ship/stores\"", "2 low or out of stock");
    }

    @Test
    void statusAndQuantityRules() {
        assertThat(stockService.status(BigDecimal.ZERO)).isEqualTo(StockStatus.OUT);
        assertThat(stockService.status(new BigDecimal("0.001"))).isEqualTo(StockStatus.LOW);
        assertThat(stockService.status(new BigDecimal("9.999"))).isEqualTo(StockStatus.LOW);
        assertThat(stockService.status(BigDecimal.TEN)).isEqualTo(StockStatus.OK);
        assertThat(StockService.isValidQuantity(BigDecimal.ZERO)).isTrue();
        assertThat(StockService.isValidQuantity(new BigDecimal("1.250"))).isTrue();
        assertThat(StockService.isValidQuantity(new BigDecimal("-1"))).isFalse();
        assertThat(StockService.isValidQuantity(new BigDecimal("1.0001"))).isFalse();
        assertThat(StockService.isValidQuantity(new BigDecimal("1234567890"))).isFalse();
        assertThat(StockService.isValidQuantity(null)).isFalse();
        assertThatThrownBy(() -> new StockLevel().setQuantity(new BigDecimal("-0.5")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stockService.level(showerGel)).isInstanceOf(IllegalArgumentException.class);
        assertThat(stockService.levels(ship)).extracting(level -> level.item().getName())
                .containsExactly("Basmati rice", "Olive oil", "Salt", "Sugar");
    }

    private void stock(Item item, String quantity) {
        StockLevel level = new StockLevel();
        level.setItem(item);
        level.setQuantity(new BigDecimal(quantity));
        level.setUpdatedAt(Instant.now());
        levelRepository.save(level);
    }

    private String page(String url, User u) throws Exception {
        return mockMvc.perform(get(url).with(user(new CurrentUser(u)))).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private User crew(String email, String name, Rank rank, Ship s) {
        return userService.createUser(email, name, "secret123", rank.role(), s, rank);
    }

    private Item item(Ship s, String name, String unit, ItemCategory category, String place) {
        Item item = new Item();
        item.setShip(s);
        item.setName(name);
        item.setUnit(unit);
        item.setCategory(category);
        item.setPlace(place);
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
