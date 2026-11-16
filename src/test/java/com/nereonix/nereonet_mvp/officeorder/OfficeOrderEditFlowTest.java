package com.nereonix.nereonet_mvp.officeorder;

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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
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

import com.nereonix.nereonet_mvp.XlsSheet;
import com.nereonix.nereonet_mvp.core.audit.AuditEntry;
import com.nereonix.nereonet_mvp.core.audit.AuditRepository;
import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemCategory;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.notification.Notification;
import com.nereonix.nereonet_mvp.core.notification.NotificationRepository;
import com.nereonix.nereonet_mvp.core.order.OfficeOrderEditService;
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
 * P50, the office changes the items of a pending order: quantity, remove (asks first), add an item or add to its line,
 * each with an optional note and audited; the line keeps what the ship sent, and the office's changes show on the
 * office's and the Master's order pages and in the export. The Master and the Chief cook get one notification per
 * editing session: "Done editing", 10 minutes without changes, or confirming the delivery, after which the lines are
 * read-only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails("office@example.com") // seeded by BootstrapData
class OfficeOrderEditFlowTest {

    @Autowired MockMvc mockMvc;
    @Autowired ShipRepository shipRepository;
    @Autowired UserService userService;
    @Autowired ItemRepository itemRepository;
    @Autowired ShipOrderRepository orderRepository;
    @Autowired ShipOrderLineRepository lineRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired AuditRepository auditRepository;
    @Autowired OfficeOrderEditService editService;

    Ship ship;
    User master;
    User cook;
    User crewMember;
    Item rice;
    Item oil;
    Item soap;
    ShipOrder order;
    ShipOrderLine riceLine;
    ShipOrderLine soapLine;

    @BeforeEach
    void setUp() {
        ship = ship("9074729", "MV Demo");
        master = crew("master@example.com", "Ship Master", Rank.MASTER);
        cook = crew("cook@example.com", "Demo Cook", Rank.CHIEF_COOK);
        crewMember = crew("ab@example.com", "Ana Santos", Rank.ABLE_SEAMAN);
        rice = item(ship, "Basmati rice", "kg", ItemCategory.PROVISIONS);
        oil = item(ship, "Olive oil", "lt", ItemCategory.PROVISIONS);
        soap = item(ship, "Shower gel", "pcs", ItemCategory.PERSONAL_ITEMS);
        order = new ShipOrder();
        order.setShip(ship);
        order.setStatus(ShipOrderStatus.SENT);
        order.setSentBy(master);
        order.setSentAt(Instant.parse("2026-11-10T09:30:00Z"));
        order.setRequestedPort("Rotterdam");
        order.setNeededBy(LocalDate.of(2026, 11, 25));
        order.setCreatedAt(Instant.parse("2026-11-09T09:30:00Z"));
        orderRepository.save(order);
        riceLine = line(rice, "50");
        soapLine = line(soap, "6");
    }

    @Test
    void theOfficeChangesRemovesAndAddsAndTheShipIsToldOnce() throws Exception {
        // While pending: each line has its quantity and note, Remove asks first (with a note), and Add an item offers
        // the vessel's active items not on the order (P59).
        String page = page("/office/orders/" + order.getId());
        assertThat(page).contains("action=\"/office/orders/" + order.getId() + "/lines/" + riceLine.getId() + "/quantity\"",
                        "name=\"note\"", "data-confirm-title=\"Remove Shower gel from the order?\"",
                        "data-confirm-note=\"Note (optional)\"", "Add an item", "action=\"/office/orders/" + order.getId() + "/lines/add\"",
                        "data-item-search")
                .doesNotContain("/edits/done\"");

        change(riceLine, "40", "Out of season, reduced")
                .andExpect(redirectedUrl("/office/orders/" + order.getId()))
                .andExpect(flash().attribute("successMessage", "officeorder.edit.changed"));
        remove(soapLine, "Not available").andExpect(flash().attribute("successMessage", "officeorder.edit.removed"));
        add(oil, "12", "").andExpect(flash().attribute("successMessage", "officeorder.edit.added"));

        assertThat(riceLine.getQuantity()).isEqualByComparingTo("40");
        assertThat(riceLine.getOriginalQuantity()).isEqualByComparingTo("50");
        assertThat(riceLine.getChangeNote()).isEqualTo("Out of season, reduced");
        assertThat(soapLine.isRemoved()).isTrue();
        ShipOrderLine oilLine = lineRepository.findByOrderIdAndItemId(order.getId(), oil.getId()).orElseThrow();
        assertThat(oilLine.isAddedByOffice()).isTrue();
        assertThat(oilLine.getQuantity()).isEqualByComparingTo("12");
        assertThat(oilLine.getChangeNote()).isNull();
        // Audited per change: line, before, after, note.
        assertThat(audit("ORDER_LINE_CHANGED")).singleElement().extracting(AuditEntry::getDetails)
                .isEqualTo("Order #" + order.getId() + ", Basmati rice: 50 kg -> 40 kg, note: Out of season, reduced");
        assertThat(audit("ORDER_LINE_REMOVED")).singleElement().extracting(AuditEntry::getDetails)
                .isEqualTo("Order #" + order.getId() + ", Shower gel: 6 pcs -> removed, note: Not available");
        assertThat(audit("ORDER_LINE_ADDED")).singleElement().extracting(AuditEntry::getDetails)
                .isEqualTo("Order #" + order.getId() + ", Olive oil: added 12 lt");
        // Nobody is told per click.
        assertThat(notifications(master)).isEmpty();
        assertThat(order.getOfficeEditedAt()).isNotNull();

        // The office's page: the changes under the names, the removed line struck through without controls, and
        // "Done editing" while the ship has not been told.
        page = page("/office/orders/" + order.getId());
        assertThat(page).contains("Changed by office: 50 kg → 40 kg", "Out of season, reduced", "Removed by office",
                        "Not available", "Added by office", "Done editing", "class=\"order-line-removed\"")
                .doesNotContain("/lines/" + soapLine.getId() + "/quantity", "removeLine" + soapLine.getId());
        // Greek on the office's page.
        assertThat(mockMvc.perform(get("/office/orders/{id}", order.getId()).param("lang", "el")).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("Αλλαγή από το γραφείο: 50 kg → 40 kg", "Αφαιρέθηκε από το γραφείο", "Τέλος αλλαγών");

        // Done editing: one notification for the Master and the Chief cook (both with the link, P55), nobody else.
        mockMvc.perform(post("/office/orders/{id}/edits/done", order.getId()).with(csrf()))
                .andExpect(redirectedUrl("/office/orders/" + order.getId()))
                .andExpect(flash().attribute("successMessage", "officeorder.edit.done"));
        String text = "The office changed order " + order.getId() + ": 1 line changed, 1 removed, 1 added";
        assertThat(notifications(master)).singleElement().satisfies(n -> {
            assertThat(n.getText()).isEqualTo(text);
            assertThat(n.getSubject()).isEqualTo("MV Demo");
            assertThat(n.getLink()).isEqualTo("/ship/orders/" + order.getId());
        });
        assertThat(notifications(cook)).singleElement().satisfies(n -> {
            assertThat(n.getText()).isEqualTo(text);
            assertThat(n.getLink()).isEqualTo("/ship/orders/" + order.getId());
        });
        assertThat(notifications(crewMember)).isEmpty();
        assertThat(order.getOfficeEditedAt()).isNull();
        assertThat(page("/office/orders/" + order.getId())).doesNotContain("/edits/done\"");
        // Nothing new: nobody is told again.
        mockMvc.perform(post("/office/orders/{id}/edits/done", order.getId()).with(csrf()))
                .andExpect(flash().attribute("successMessage", "officeorder.edit.doneNothing"));
        assertThat(notifications(master)).hasSize(1);

        // The Master's order page shows the same.
        String shipPage = mockMvc.perform(get("/ship/orders/{id}", order.getId()).with(user(new CurrentUser(master))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(shipPage).contains("Changed by office: 50 kg → 40 kg", "Out of season, reduced", "Removed by office",
                "Added by office", "Olive oil", "order-line-removed");

        // The export: what to deliver (0 for the removed line), what the ship sent, the change and the note.
        XlsSheet sheet = XlsSheet.of(mockMvc.perform(post("/office/orders/export").param("ids", order.getId().toString())
                .with(csrf())).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(sheet.body()).extracting(row -> row.subList(10, 16)).containsExactly(
                Arrays.asList("Basmati rice", "kg", 40.0, 50.0, "changed", "Out of season, reduced"),
                Arrays.asList("Olive oil", "lt", 12.0, null, "added", null),
                Arrays.asList("Shower gel", "pcs", 0.0, 6.0, "removed", "Not available"));
    }

    // P59: two panels. "Not on the order": the active items not on it, by category, each a radio with its name and
    // unit (no JavaScript needed); "On the order": its lines, read only. A removed line's item is back on the left.
    @Test
    void addAnItemOffersOnlyTheItemsNotOnTheOrder() throws Exception {
        Item flour = item(ship, "Flour", "kg", ItemCategory.PROVISIONS);
        Item razor = item(ship, "Razor", "pcs", ItemCategory.PERSONAL_ITEMS);
        Item inactive = item(ship, "Old flour", "kg", ItemCategory.PROVISIONS);
        inactive.setActive(false);
        Ship other = ship("9321483", "MV Other");
        item(other, "Coffee", "kg", ItemCategory.PROVISIONS);

        String form = addItemForm(page("/office/orders/" + order.getId()));
        String notOnOrder = form.substring(form.indexOf(">Not on the order<"), form.indexOf(">On the order<"));
        String onOrder = form.substring(form.indexOf(">On the order<"));
        assertThat(radios(notOnOrder)).containsExactly(flour.getId(), oil.getId(), razor.getId());
        assertThat(notOnOrder).containsSubsequence(">Provisions<", ">Flour (kg)<", ">Olive oil (lt)<",
                        ">Personal items<", ">Razor (pcs)<")
                .contains(">Not on the order</legend>",
                        "<label class=\"item-picker-option\" data-item-option>",
                        "data-label=\"Olive oil (lt)\"", "data-unit=\"lt\"")
                .doesNotContain("Basmati rice", "Shower gel", "Old flour", "Coffee", "<select");
        assertThat(onOrder).containsSubsequence(">Provisions<", "Basmati rice", ">50</span>", "kg",
                ">Personal items<", "Shower gel", ">6</span>", "pcs").doesNotContain("type=\"radio\"");
        // The summary and the unit in the quantity's label come from the script; the page has their texts.
        assertThat(form).contains("data-text=\"Adding: {0}\"", "data-empty=\"No item chosen yet.\"",
                "data-text=\"Quantity ({0})\"", "class=\"form-row-wide\"");
        assertThat(page("/office/orders/" + order.getId())).contains("card form-box form-box-full mb-4\" aria-labelledby=\"addItemTitle\"",
                "card form-box form-box-full\" aria-labelledby=\"confirmTitle\"");

        // Posting one adds a line; it leaves the list and joins On the order.
        add(flour, "5", "").andExpect(flash().attribute("successMessage", "officeorder.edit.added"));
        assertThat(lineRepository.findByOrderIdAndItemId(order.getId(), flour.getId())).get()
                .satisfies(line -> assertThat(line.getQuantity()).isEqualByComparingTo("5"));
        form = addItemForm(page("/office/orders/" + order.getId()));
        assertThat(radios(form.substring(0, form.indexOf(">On the order<")))).containsExactly(oil.getId(), razor.getId());
        assertThat(form.substring(form.indexOf(">On the order<"))).contains("Flour", ">5</span>");

        // A removed line's item can be added again.
        remove(soapLine, "");
        form = addItemForm(page("/office/orders/" + order.getId()));
        assertThat(radios(form.substring(0, form.indexOf(">On the order<"))))
                .containsExactly(oil.getId(), razor.getId(), soap.getId());
        assertThat(form.substring(form.indexOf(">On the order<"))).doesNotContain("Shower gel");

        // Greek.
        String greek = mockMvc.perform(get("/office/orders/{id}", order.getId()).param("lang", "el")).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(greek).contains(">Εκτός παραγγελίας<", ">Στην παραγγελία<", "data-text=\"Προσθήκη: {0}\"");
    }

    @Test
    void everyItemOnTheOrderLeavesNothingToAdd() throws Exception {
        add(oil, "2", "");

        String form = addItemForm(page("/office/orders/" + order.getId()));
        assertThat(form).contains("Every active item of the vessel is on the order already.", ">On the order<")
                .doesNotContain("type=\"radio\"", "Add to order");
    }

    @Test
    void anItemAlreadyOnTheOrderAddsToItsLineAndARemovedLineComesBack() throws Exception {
        // P59: no longer offered on the page, but a stale page may still post it.
        add(rice, "10", "Extra for the crossing").andExpect(flash().attribute("successMessage", "officeorder.edit.added"));
        assertThat(riceLine.getQuantity()).isEqualByComparingTo("60");
        assertThat(riceLine.isAddedByOffice()).isFalse();
        assertThat(lineRepository.findAll().stream().filter(l -> l.getOrder().getId().equals(order.getId()))).hasSize(2);
        assertThat(audit("ORDER_LINE_CHANGED")).singleElement().extracting(AuditEntry::getDetails)
                .isEqualTo("Order #" + order.getId() + ", Basmati rice: 50 kg -> 60 kg (added 10 kg), note: Extra for the crossing");

        remove(soapLine, "");
        add(soap, "3", "");
        assertThat(soapLine.isRemoved()).isFalse();
        assertThat(soapLine.getQuantity()).isEqualByComparingTo("3");
        assertThat(soapLine.getOriginalQuantity()).isEqualByComparingTo("6");
        assertThat(page("/office/orders/" + order.getId())).contains("Changed by office: 6 pcs → 3 pcs",
                "Changed by office: 50 kg → 60 kg");

        // Back to what the ship sent: not counted as a change.
        change(soapLine, "6", "");
        mockMvc.perform(post("/office/orders/{id}/edits/done", order.getId()).with(csrf()));
        assertThat(notifications(master)).singleElement().extracting(Notification::getText)
                .isEqualTo("The office changed order " + order.getId() + ": 1 line changed");
    }

    @Test
    void addAnItemAndTheLinesFollowTheRuleOfTheItemsUnit() throws Exception {
        // P60: each item of Add an item carries its limit for the script; the hint says it depends on the unit.
        String page = page("/office/orders/" + order.getId());
        assertThat(page).contains("id=\"addQuantityHint\" data-quantity-hint></span>", "data-quantity-texts")
                .containsPattern("value=\"" + oil.getId() + "\"[^>]*data-quantity-max=\"2000\" data-quantity-decimals=\"3\"")
                .containsPattern("id=\"addQuantity\"[^>]*type=\"text\"|type=\"text\"[^>]*id=\"addQuantity\"")
                // No limit is shown before a value breaks it.
                .doesNotContain("Up to 2000 kg", "Whole numbers, up to 5000 pcs");

        // Too much, letters, decimals for pcs: nothing added, the error with the item's name, the item still chosen
        // and the value typed back in the field.
        add(oil, "1000000", "").andExpect(redirectedUrl("/office/orders/" + order.getId() + "#addItemTitle"))
                .andExpect(flash().attribute("errorMessage", "quantity.error.line"));
        MvcResult refused = add(oil, "1000000", "").andReturn();
        String shown = mockMvc.perform(get("/office/orders/" + order.getId())
                        .flashAttr("errorMessage", "quantity.error.line")
                        .flashAttr("errorArgs", refused.getFlashMap().get("errorArgs"))
                        .flashAttr("addQuantityError", refused.getFlashMap().get("addQuantityError")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(shown).contains("Not saved: Olive oil: Up to 2000 lt per line.", "value=\"1000000\"")
                .containsPattern("value=\"" + oil.getId() + "\"[^>]*checked");
        add(oil, "abc", "").andExpect(flash().attribute("errorMessage", "quantity.error.line"));
        add(soap, "1,5", "").andExpect(flash().attribute("errorMessage", "quantity.error.line"));
        assertThat(order.getLines()).extracting(line -> line.getItem().getName())
                .containsExactlyInAnyOrder("Basmati rice", "Shower gel");

        // "1,5" lt is 1.5 lt.
        add(oil, "1,5", "").andExpect(flash().attribute("successMessage", "officeorder.edit.added"));
        assertThat(order.getLines()).filteredOn(line -> line.getItem().getId().equals(oil.getId())).singleElement()
                .satisfies(line -> assertThat(line.getQuantity()).isEqualByComparingTo("1.5"));

        // A line's quantity: the same rule; the error is shown under that line with what was typed.
        change(riceLine, "2500", "").andExpect(flash().attribute("errorMessage", "quantity.error.line"))
                .andExpect(redirectedUrl("/office/orders/" + order.getId() + "#line-" + riceLine.getId()));
        assertThat(riceLine.getQuantity()).isEqualByComparingTo("50");
    }

    @Test
    void wrongInputChangesNothing() throws Exception {
        for (String quantity : List.of("", "0", "-1", "abc", "1.2345")) {
            change(riceLine, quantity, "").andExpect(flash().attribute("errorMessage", "quantity.error.line"));
        }
        change(riceLine, "40", "x".repeat(201))
                .andExpect(flash().attribute("errorMessage", "officeorder.edit.noteTooLong"));
        mockMvc.perform(post("/office/orders/{id}/lines/add", order.getId()).param("quantity", "2").with(csrf()))
                .andExpect(flash().attribute("errorMessage", "officeorder.edit.add.chooseItem"));
        // The same quantity and note: nothing to change, nothing recorded.
        change(riceLine, "50.000", "").andExpect(flash().attribute("successMessage", "officeorder.edit.unchanged"));
        assertThat(riceLine.getQuantity()).isEqualByComparingTo("50");
        assertThat(order.getOfficeEditedAt()).isNull();

        // Not an active item of this ship, or not a line of this order: 404.
        Item inactive = item(ship, "Old flour", "kg", ItemCategory.PROVISIONS);
        inactive.setActive(false);
        Ship other = ship("9321483", "MV Other");
        Item otherItem = item(other, "Coffee", "kg", ItemCategory.PROVISIONS);
        add(inactive, "1", "").andExpect(status().isNotFound());
        add(otherItem, "1", "").andExpect(status().isNotFound());
        mockMvc.perform(post("/office/orders/{id}/lines/{l}/quantity", order.getId(), 999_999).param("quantity", "1")
                .with(csrf())).andExpect(status().isNotFound());
        assertThat(page("/office/orders/" + order.getId())).doesNotContain("Old flour", "Coffee");
        assertThat(audit("ORDER_LINE_CHANGED")).isEmpty();
        assertThat(audit("ORDER_LINE_ADDED")).isEmpty();

        // Only the office.
        mockMvc.perform(post("/office/orders/{id}/lines/{l}/quantity", order.getId(), riceLine.getId())
                        .param("quantity", "1").with(csrf()).with(user(new CurrentUser(master))))
                .andExpect(status().isForbidden());
        assertThat(riceLine.getQuantity()).isEqualByComparingTo("50");
    }

    @Test
    void withoutJavaScriptRemoveAsksOnAPlainPageWithTheNote() throws Exception {
        String confirmPage = page("/office/orders/" + order.getId() + "/lines/" + soapLine.getId() + "/remove");
        assertThat(confirmPage).contains("Remove Shower gel from the order?", "struck through",
                "action=\"/office/orders/" + order.getId() + "/lines/" + soapLine.getId() + "/remove\"",
                "<textarea id=\"confirmNote\"", "name=\"note\"", "maxlength=\"200\"");
        assertThat(soapLine.isRemoved()).isFalse();
        remove(soapLine, "Out of stock");
        // Removed already: the confirmation page goes back to the order.
        mockMvc.perform(get("/office/orders/{id}/lines/{l}/remove", order.getId(), soapLine.getId()))
                .andExpect(redirectedUrl("/office/orders/" + order.getId()));
        // A change of a removed line (another tab) says so.
        change(soapLine, "2", "").andExpect(flash().attribute("errorMessage", "officeorder.edit.lineRemoved"));
    }

    @Test
    void schedulingTheDeliveryTellsTheShipFirstAndLocksTheLines() throws Exception {
        change(riceLine, "45", "");
        mockMvc.perform(post("/office/orders/{id}/confirm", order.getId()).param("confirmedPort", "Antwerp")
                .param("deliveryDate", "2026-11-20").with(csrf()));

        assertThat(notifications(master)).extracting(Notification::getText).containsExactly(
                "Delivery scheduled: Antwerp, 20 Nov 2026",
                "The office changed order " + order.getId() + ": 1 line changed");
        assertThat(order.getOfficeEditedAt()).isNull();

        String page = page("/office/orders/" + order.getId());
        assertThat(page).contains("Changed by office: 50 kg → 45 kg")
                .doesNotContain("name=\"quantity\"", "id=\"addItemTitle\"", "/edits/done\"", "data-confirm-note=\"");
        change(riceLine, "30", "").andExpect(flash().attribute("errorMessage", "officeorder.edit.notEditable"));
        add(oil, "1", "").andExpect(flash().attribute("errorMessage", "officeorder.edit.notEditable"));
        remove(riceLine, "").andExpect(flash().attribute("errorMessage", "officeorder.edit.notEditable"));
        assertThat(riceLine.getQuantity()).isEqualByComparingTo("45");
        assertThat(riceLine.isRemoved()).isFalse();
    }

    // P65: one primary button per state, the page's next step. Pending: Schedule delivery (Done editing secondary, Add
    // to order an outline primary). Delivery scheduled: none until a field of Save delivery changes changes (js/forms.js
    // promotes it); posted with errors, it is the primary.
    @Test
    void theOrderPageHasOnePrimaryButtonPerState() throws Exception {
        change(riceLine, "45", "");
        String pending = page("/office/orders/" + order.getId());
        assertThat(primaries(pending)).containsExactly("Schedule delivery");
        assertThat(pending).contains("class=\"btn btn-outline-secondary\">Done editing</button>",
                "class=\"btn btn-outline-primary\">Add to order</button>");

        mockMvc.perform(post("/office/orders/{id}/confirm", order.getId()).param("confirmedPort", "Antwerp")
                .param("deliveryDate", "2026-11-20").with(csrf()));
        String scheduled = page("/office/orders/" + order.getId());
        assertThat(primaries(scheduled)).isEmpty();
        assertThat(scheduled).contains(">Delivery scheduled</span>")
                .containsPattern("class=\"btn btn-outline-secondary\"\\s+data-primary-on-change=\"true\">Save delivery changes</button>");

        String errors = mockMvc.perform(post("/office/orders/{id}/confirm", order.getId()).param("confirmedPort", " ")
                        .param("deliveryDate", "2026-11-20").with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(primaries(errors)).containsExactly("Save delivery changes");
    }

    @Test
    void tenMinutesWithoutChangesTellTheShip() throws Exception {
        change(riceLine, "40", "");
        Instant now = Instant.now();

        // Changed 5 minutes ago: still editing.
        order.setOfficeEditedAt(now.minus(5, ChronoUnit.MINUTES));
        assertThat(editService.notifyIdle(now)).isZero();
        assertThat(notifications(master)).isEmpty();

        order.setOfficeEditedAt(now.minus(Duration.ofMinutes(10)).minusSeconds(1));
        assertThat(editService.notifyIdle(now)).isEqualTo(1);
        assertThat(notifications(master)).singleElement().extracting(Notification::getText)
                .isEqualTo("The office changed order " + order.getId() + ": 1 line changed");
        assertThat(order.getOfficeEditedAt()).isNull();
        assertThat(editService.notifyIdle(now.plus(1, ChronoUnit.HOURS))).isZero();
    }

    @Test
    void sendingAnOrderKeepsWhatTheShipSent() throws Exception {
        ShipOrder open = new ShipOrder();
        open.setShip(ship);
        open.setStatus(ShipOrderStatus.OPEN);
        open.setCreatedAt(Instant.now());
        orderRepository.save(open);
        ShipOrderLine line = new ShipOrderLine();
        line.setOrder(open);
        line.setItem(oil);
        line.setQuantity(new BigDecimal("7.5"));
        open.getLines().add(lineRepository.save(line));
        order.setStatus(ShipOrderStatus.CLOSED);

        mockMvc.perform(post("/ship/order/send").param("requestedPort", "Piraeus")
                        .param("neededBy", LocalDate.now().plusDays(5).toString())
                        .with(csrf()).with(user(new CurrentUser(master))))
                .andExpect(redirectedUrl("/ship/orders/" + open.getId()));
        assertThat(line.getOriginalQuantity()).isEqualByComparingTo("7.5");
        assertThat(line.isChangedByOffice()).isFalse();
    }

    // ---------------------------------------------------------------------------------------------------------------

    // The Add an item form (P59).
    private static String addItemForm(String html) {
        int start = html.indexOf("data-item-picker");
        assertThat(start).as("the Add an item form").isPositive();
        return html.substring(start, html.indexOf("</form>", start));
    }

    // P65: the texts of the filled primary buttons in the page's main content.
    static List<String> primaries(String html) {
        String main = html.substring(html.indexOf("<main"), html.indexOf("</main>"));
        return java.util.regex.Pattern.compile("class=\"btn btn-primary\"[^>]*>([^<]*)<").matcher(main).results()
                .map(m -> m.group(1).strip()).toList();
    }

    // The item ids of the radios, in page order.
    private static List<Long> radios(String html) {
        return java.util.regex.Pattern.compile("type=\"radio\" name=\"itemId\" class=\"form-check-input\" value=\"(\\d+)\"")
                .matcher(html).results().map(m -> Long.valueOf(m.group(1))).toList();
    }

    private ResultActions change(ShipOrderLine line, String quantity, String note) throws Exception {
        return mockMvc.perform(post("/office/orders/{id}/lines/{l}/quantity", order.getId(), line.getId())
                .param("quantity", quantity).param("note", note).with(csrf()));
    }

    private ResultActions remove(ShipOrderLine line, String note) throws Exception {
        return mockMvc.perform(post("/office/orders/{id}/lines/{l}/remove", order.getId(), line.getId())
                .param("note", note).with(csrf()));
    }

    private ResultActions add(Item item, String quantity, String note) throws Exception {
        return mockMvc.perform(post("/office/orders/{id}/lines/add", order.getId())
                .param("itemId", item.getId().toString()).param("quantity", quantity).param("note", note).with(csrf()));
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

    private ShipOrderLine line(Item item, String quantity) {
        ShipOrderLine line = new ShipOrderLine();
        line.setOrder(order);
        line.setItem(item);
        line.setQuantity(new BigDecimal(quantity));
        line.setOriginalQuantity(new BigDecimal(quantity));
        order.getLines().add(lineRepository.save(line));
        return line;
    }

    private Ship ship(String imo, String name) {
        Ship s = new Ship();
        s.setImoNumber(imo);
        s.setName(name);
        s.setCreatedAt(Instant.now());
        return shipRepository.save(s);
    }

    private User crew(String email, String name, Rank rank) {
        return userService.createUser(email, name, "secret123", rank.role(), ship, rank);
    }

    private Item item(Ship owner, String name, String unit, ItemCategory category) {
        Item item = new Item();
        item.setShip(owner);
        item.setName(name);
        item.setUnit(unit);
        item.setCategory(category);
        item.setCreatedAt(Instant.now());
        return itemRepository.save(item);
    }
}
