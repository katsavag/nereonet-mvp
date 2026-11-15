package com.nereonix.nereonet_mvp.stores;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditEntry;
import com.nereonix.nereonet_mvp.core.audit.AuditRepository;
import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemCategory;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrder;
import com.nereonix.nereonet_mvp.core.order.ShipOrderRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrderStatus;
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
import com.nereonix.nereonet_mvp.core.time.Dates;
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
    @Autowired AuditRepository auditRepository;
    @Autowired Dates dates;
    @Autowired ShipOrderRepository orderRepository;

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

    // ---- P53: counting by hand ----------------------------------------------------------------------------------------

    @Test
    void theCookCountsSevenWhereTheStoreExpectedTen() throws Exception {
        Item flour = item(ship, "Flour", "kg", ItemCategory.PROVISIONS, "Dry store");
        stock(flour, "10");

        mockMvc.perform(post("/ship/stores/items/{id}/count", flour.getId()).param("quantity", "7")
                        .with(user(new CurrentUser(cook))).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ship/stores/count#item-" + flour.getId()))
                .andExpect(flash().attribute("countSaved", flour.getId()));

        assertThat(levelRepository.findByItemId(flour.getId())).get()
                .satisfies(level -> assertThat(level.getQuantity()).isEqualByComparingTo("7"));
        assertThat(stockService.history(flour)).singleElement().satisfies(m -> {
            assertThat(m.getType()).isEqualTo(StockMovementType.COUNT);
            assertThat(m.getQuantityBefore()).isEqualByComparingTo("10");
            assertThat(m.getQuantityAfter()).isEqualByComparingTo("7");
            assertThat(m.getBy().getId()).isEqualTo(cook.getId());
            assertThat(m.getAt()).isBetween(Instant.now().minusSeconds(60), Instant.now());
            assertThat(m.getOrder()).isNull();
        });
        assertThat(page("/ship/stores", cook)).containsPattern("(?s)href=\"/ship/stores/items/" + flour.getId()
                + "\">Flour</a>\\s*<span class=\"stores-qty\"><span class=\"num\">7</span> <span>kg</span></span>\\s*"
                + "<span class=\"badge status-badge status-warning\">Low</span>");
        assertThat(audit("STOCK_COUNTED")).singleElement().satisfies(e -> {
            assertThat(e.getDetails()).isEqualTo("Flour: 10 -> 7 kg");
            assertThat(e.getActor().getId()).isEqualTo(cook.getId());
            assertThat(e.getShip().getId()).isEqualTo(ship.getId());
        });
        // The item's history: before, after and the difference.
        assertThat(page("/ship/stores/items/" + flour.getId(), cook))
                .containsPattern("(?s)>10</td>\\s*<td[^>]*>7</td>\\s*<td[^>]*><span class=\"num\">-3</span></td>");
    }

    @Test
    void withJavaScriptTheRowComesBackAsJson() throws Exception {
        mockMvc.perform(post("/ship/stores/items/{id}/count", rice.getId()).param("quantity", "2,5")
                        .with(user(new CurrentUser(cook))).with(csrf()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value("2.5"))
                .andExpect(jsonPath("$.status").value("low"))
                .andExpect(jsonPath("$.tone").value("warning"))
                .andExpect(jsonPath("$.statusLabel").value("Low"))
                .andExpect(jsonPath("$.message").value("Saved"));
        assertThat(stockService.level(rice).quantity()).isEqualByComparingTo("2.5");

        mockMvc.perform(post("/ship/stores/items/{id}/count", rice.getId()).param("quantity", "-1")
                        .with(user(new CurrentUser(cook))).with(csrf()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("Enter a quantity of zero or more, with at most 3 decimals."));
        assertThat(stockService.level(rice).quantity()).isEqualByComparingTo("2.5");
        assertThat(stockService.history(rice)).hasSize(1);
    }

    @Test
    void aCountEqualToTheStoreAndAFirstCountAreSavedToo() throws Exception {
        count(rice, "40");
        count(sugar, "0");   // no row yet
        count(sugar, "12");

        assertThat(stockService.history(rice)).singleElement().satisfies(m -> {
            assertThat(m.getQuantityBefore()).isEqualByComparingTo("40");
            assertThat(m.getQuantityAfter()).isEqualByComparingTo("40");
        });
        assertThat(stockService.history(sugar)).extracting(StockMovement::getQuantityBefore)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(levelRepository.findByItemId(sugar.getId())).get()
                .satisfies(level -> assertThat(level.getQuantity()).isEqualByComparingTo("12"));
        // The confirmed count shows a difference of 0.
        assertThat(page("/ship/stores/items/" + rice.getId(), cook)).contains("<span class=\"num\">0</span></td>");
    }

    @Test
    void aWrongValueShowsItsErrorNextToThatRowOnly() throws Exception {
        for (String wrong : List.of("", "abc", "-1", "1.0001", "1234567890")) {
            mockMvc.perform(post("/ship/stores/items/{id}/count", oil.getId()).param("quantity", wrong)
                            .with(user(new CurrentUser(cook))).with(csrf()))
                    .andExpect(redirectedUrl("/ship/stores/count#item-" + oil.getId()))
                    .andExpect(flash().attribute("countError", new StoresController.CountError(oil.getId(), wrong)));
        }
        assertThat(stockService.history(oil)).isEmpty();
        assertThat(stockService.level(oil).quantity()).isEqualByComparingTo("4");

        String html = mockMvc.perform(get("/ship/stores/count").with(user(new CurrentUser(cook)))
                        .flashAttr("countError", new StoresController.CountError(oil.getId(), "-1")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).containsOnlyOnce("Enter a quantity of zero or more, with at most 3 decimals.")
                .containsOnlyOnce("is-invalid")
                .containsPattern("<input(?=[^>]*id=\"count-" + oil.getId() + "\")(?=[^>]*is-invalid)"
                        + "(?=[^>]*aria-invalid=\"true\")(?=[^>]*value=\"-1\")")
                .containsPattern("id=\"count-" + oil.getId() + "-error\" data-count-error>Enter a quantity");
    }

    @Test
    void theCountPageListsEveryItemWithItsExpectedQuantity() throws Exception {
        assertThat(page("/ship/stores", cook)).contains("href=\"/ship/stores/count\"", "href=\"/ship/stores/history\"");

        String html = page("/ship/stores/count", cook);
        assertThat(html).contains("<title>Count the store · Nereonet</title>", "js/stores-count", "data-autosave")
                .doesNotContain("Shower gel", "Jasmine rice")
                .containsPattern("<input type=\"number\" inputmode=\"decimal\"[^>]*id=\"count-" + rice.getId()
                        + "\"[^>]*value=\"40\"")
                .containsPattern("id=\"count-" + salt.getId() + "\"[^>]*value=\"12.5\"")
                .containsPattern("id=\"count-" + sugar.getId() + "\"[^>]*value=\"0\"")
                .contains("action=\"/ship/stores/items/" + rice.getId() + "/count\"")
                .doesNotContain("is-invalid");
        // By place, "Other" last; no Save for the whole page.
        assertThat(html.indexOf(">Dry store</h2>")).isPositive().isLessThan(html.indexOf(">Other</h2>"));
        // Saved without JavaScript: "Saved" on that row.
        String saved = mockMvc.perform(get("/ship/stores/count").with(user(new CurrentUser(cook)))
                        .flashAttr("countSaved", rice.getId()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(saved).containsOnlyOnce("data-count-saved>Saved</span>");
    }

    @Test
    void onlyActiveProvisionsOfTheCooksOwnShip() throws Exception {
        Item retired = item(ship, "Old tea", "kg", ItemCategory.PROVISIONS, null);
        retired.setActive(false);
        itemRepository.save(retired);
        for (Item item : List.of(otherShipsRice, showerGel, retired)) {
            mockMvc.perform(post("/ship/stores/items/{id}/count", item.getId()).param("quantity", "1")
                            .with(user(new CurrentUser(cook))).with(csrf()))
                    .andExpect(status().isNotFound());
        }
        assertThat(stockService.level(otherShipsRice).quantity()).isEqualByComparingTo("3");
        for (User other : List.of(master, crewMember)) {
            mockMvc.perform(post("/ship/stores/items/{id}/count", rice.getId()).param("quantity", "1")
                            .with(user(new CurrentUser(other))).with(csrf()))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/ship/stores/count").with(user(new CurrentUser(other))))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/ship/stores/history").with(user(new CurrentUser(other))))
                    .andExpect(status().isForbidden());
            assertThatThrownBy(() -> stockService.count(other, rice.getId(), BigDecimal.ONE, null))
                    .isInstanceOf(AccessDeniedException.class);
        }
        cook.setEnabled(false);
        assertThatThrownBy(() -> stockService.count(cook, rice.getId(), BigDecimal.ONE, null))
                .isInstanceOf(AccessDeniedException.class);
        cook.setEnabled(true);
        assertThatThrownBy(() -> stockService.count(cook, rice.getId(), new BigDecimal("-1"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stockService.count(cook, rice.getId(), BigDecimal.ONE, "x".repeat(201)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(stockService.history(rice)).isEmpty();
    }

    @Test
    void theLastCountIsTheRealOne() throws Exception {
        assertThat(page("/ship/stores", cook)).contains("Not counted yet");
        count(rice, "38");
        assertThat(page("/ship/stores", cook)).doesNotContain("Not counted yet")
                .contains(dates.utcDate(Instant.now()));
    }

    @Test
    void theHistoryOfTheShipNewestFirstFilteredAndFiftyAPage() throws Exception {
        Instant day = Instant.parse("2026-11-01T10:00:00Z");
        for (int i = 0; i < 55; i++) {
            movementRepository.save(StockMovement.of(rice, StockMovementType.COUNT, new BigDecimal(i),
                    new BigDecimal(i + 1), cook, day.plus(Duration.ofMinutes(i)), null, null));
        }
        movementRepository.save(StockMovement.of(oil, StockMovementType.RECEIPT, new BigDecimal("4"),
                new BigDecimal("24"), cook, day.plus(Duration.ofDays(3)), null, null));
        movementRepository.save(StockMovement.of(otherShipsRice, StockMovementType.COUNT, BigDecimal.ZERO,
                new BigDecimal("3"), cook, day, null, null));

        String first = page("/ship/stores/history", cook);
        assertThat(first).contains("<title>History · Nereonet</title>", "Page 1 of 2 · 56 movements",
                        "href=\"/ship/stores/history?page=1\"")
                .doesNotContain("Jasmine rice");
        assertThat(rows(first)).isEqualTo(50);
        // Newest first: the delivery, then the latest count.
        assertThat(first.indexOf(">Delivery received</span>")).isPositive()
                .isLessThan(first.indexOf(">Count</span>"));
        assertThat(first).containsPattern("(?s)>Olive oil</a>.*?>4</td>\\s*<td[^>]*>24</td>\\s*"
                + "<td[^>]*><span class=\"num\">\\+20</span></td>\\s*<td[^>]*>Demo Cook</td>");
        assertThat(rows(page("/ship/stores/history?page=1", cook))).isEqualTo(6);

        assertThat(rows(page("/ship/stores/history?type=RECEIPT", cook))).isEqualTo(1);
        String counts = page("/ship/stores/history?type=COUNT", cook);
        assertThat(counts).contains("href=\"/ship/stores/history?type=COUNT&amp;page=1\"")
                .contains("<option value=\"COUNT\" selected=\"selected\">Count</option>")
                .doesNotContain(">Delivery received</span>");
        assertThat(rows(page("/ship/stores/history?from=2026-11-02&to=2026-11-04", cook))).isEqualTo(1);
        assertThat(rows(page("/ship/stores/history?to=2026-11-01", cook))).isEqualTo(50);
        assertThat(page("/ship/stores/history?from=2026-12-01", cook)).contains("No movements for these filters.");
        // Not a date: its error, and nothing filtered.
        assertThat(page("/ship/stores/history?from=nonsense", cook)).contains("is-invalid", "56 movements");
    }

    // P58: the shared date range (layout/parts :: dateRange): Last 30 / 60 / 90 days above From and To. Each choice is
    // a plain link (so it works without JavaScript) that keeps the type and sets From = today minus N days, To = today
    // (UTC); the one shown is marked; dates typed by hand match none.
    @Test
    void theHistoryHasFromAndToOnOneRowAndQuickChoices() throws Exception {
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        for (int daysAgo : List.of(10, 45, 75, 120)) {
            movementRepository.save(StockMovement.of(rice, StockMovementType.COUNT, BigDecimal.ONE, BigDecimal.TWO, cook,
                    today.minusDays(daysAgo).atTime(12, 0).toInstant(java.time.ZoneOffset.UTC), null, null));
        }
        String all = page("/ship/stores/history", cook);
        assertThat(all).containsSubsequence("data-date-range", "class=\"view-switch date-range-presets\"",
                        "aria-label=\"Quick dates\"", "Last 30 days", "Last 60 days", "Last 90 days",
                        "class=\"date-range-row\"", "<label for=\"from\" class=\"form-label\">From (UTC)</label>",
                        "type=\"date\"", "<label for=\"to\" class=\"form-label\">To (UTC)</label>",
                        "class=\"date-range-actions\"", ">Filter</button>", ">Clear</a>")
                .contains("href=\"/ship/stores/history?from=" + today.minusDays(60) + "&amp;to=" + today + "\"")
                .doesNotContain("aria-current=\"true\"");
        assertThat(rows(all)).isEqualTo(4);

        String sixty = page("/ship/stores/history?from=" + today.minusDays(60) + "&to=" + today, cook);
        assertThat(rows(sixty)).isEqualTo(2);
        assertThat(sixty).containsPattern("href=\"/ship/stores/history\\?from=" + today.minusDays(60) + "&amp;to="
                        + today + "\"\\s+aria-current=\"true\"")
                .contains("value=\"" + today.minusDays(60) + "\"", "value=\"" + today + "\"");
        assertThat(sixty.split("aria-current=\"true\"", -1)).hasSize(2);
        assertThat(rows(page("/ship/stores/history?from=" + today.minusDays(30) + "&to=" + today, cook))).isEqualTo(1);
        assertThat(rows(page("/ship/stores/history?from=" + today.minusDays(90) + "&to=" + today, cook))).isEqualTo(3);
        // The type is kept by the quick choices.
        assertThat(page("/ship/stores/history?type=COUNT", cook))
                .contains("href=\"/ship/stores/history?type=COUNT&amp;from=" + today.minusDays(30) + "&amp;to=" + today + "\"");
        // Dates typed by hand: no choice is marked.
        assertThat(page("/ship/stores/history?from=" + today.minusDays(59) + "&to=" + today, cook))
                .doesNotContain("aria-current=\"true\"");
    }

    private static int rows(String html) {
        return html.split("data-movement", -1).length - 1;
    }

    @Test
    void theNextDeliveryLinksToItsOrder() throws Exception {
        assertThat(page("/ship/stores", cook)).containsPattern("(?s)Next delivery</span>\\s*"
                + "<span class=\"stores-fact-value stores-fact-none\">None scheduled</span>");

        ShipOrder later = order(LocalDate.of(2026, 12, 3), "Piraeus");
        ShipOrder next = order(LocalDate.of(2026, 11, 20), "Antwerp");

        String html = page("/ship/stores", cook);
        assertThat(html).containsPattern("(?s)<a class=\"stores-fact\" href=\"/ship/orders/" + next.getId() + "\">\\s*"
                        + "<span class=\"stores-fact-label\">Next delivery</span>\\s*"
                        + "<span class=\"stores-fact-value stores-fact-date\">Antwerp, 20 Nov 2026</span>")
                .doesNotContain("None scheduled", "/ship/orders/" + later.getId());
        // The link opens the order, read only for the cook.
        assertThat(page("/ship/orders/" + next.getId(), cook)).contains("Only the Master can change or send the order.");
    }

    // A CONFIRMED order of the cook's ship: the office scheduled its delivery.
    private ShipOrder order(LocalDate deliveryDate, String port) {
        ShipOrder order = new ShipOrder();
        order.setShip(ship);
        order.setStatus(ShipOrderStatus.CONFIRMED);
        order.setCreatedAt(Instant.now());
        order.setSentAt(Instant.now());
        order.setSentBy(master);
        order.setRequestedPort(port);
        order.setNeededBy(deliveryDate);
        order.setConfirmedPort(port);
        order.setDeliveryDate(deliveryDate);
        return orderRepository.save(order);
    }

    private void count(Item item, String quantity) throws Exception {
        mockMvc.perform(post("/ship/stores/items/{id}/count", item.getId()).param("quantity", quantity)
                        .with(user(new CurrentUser(cook))).with(csrf()))
                .andExpect(flash().attribute("countSaved", item.getId()));
    }

    private List<AuditEntry> audit(String action) {
        return auditRepository.findAll().stream().filter(e -> e.getAction().equals(action)).toList();
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
