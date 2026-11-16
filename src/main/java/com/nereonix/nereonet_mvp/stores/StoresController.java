package com.nereonix.nereonet_mvp.stores;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import com.nereonix.nereonet_mvp.core.form.ItemUnits;
import com.nereonix.nereonet_mvp.core.form.QuantityRules;
import com.nereonix.nereonet_mvp.core.form.QuantityRules.Use;
import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemNameTakenException;
import com.nereonix.nereonet_mvp.core.list.DatePreset;
import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.list.ListRequest;
import com.nereonix.nereonet_mvp.core.list.ListSort;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.stock.StockMovementType;
import com.nereonix.nereonet_mvp.core.stock.StockService.Level;
import com.nereonix.nereonet_mvp.core.stock.StockStatus;
import com.nereonix.nereonet_mvp.stores.StoresService.NewItem;
import com.nereonix.nereonet_mvp.stores.StoresService.PlaceGroup;
import com.nereonix.nereonet_mvp.stores.StoresService.Stores;

import jakarta.servlet.http.HttpSession;

// The Chief cook's store: the stock of their own ship's provisions, and one item with its history. P53: counting
// by hand (/ship/stores/count, one form with one "Save count") and the ship's history (/ship/stores/history). P57: counting
// by voice from the Count page (VoiceCountController).
// SecurityConfig limits /ship/stores/** to CHIEF_COOK (the Master and crew members get 403). English only, as every
// crew screen.
@Controller
public class StoresController {

    static final String PATH = "/ship/stores";

    // Grouped by place and by name within a place: there is nothing to sort, but ListQuery needs its default.
    private static final ListSort[] SORTS = {ListSort.asc("name")};

    private final StoresService storesService;
    private final VoiceCountService voiceCountService;
    private final MessageSource messageSource;
    private final QuantityRules quantityRules;

    public StoresController(StoresService storesService, VoiceCountService voiceCountService,
                            MessageSource messageSource, QuantityRules quantityRules) {
        this.quantityRules = quantityRules;
        this.storesService = storesService;
        this.voiceCountService = voiceCountService;
        this.messageSource = messageSource;
    }

    // status: low or out (anything else: all); listRequest: q, the search by part of the name. No paging: the
    // whole store, grouped by place.
    @GetMapping(PATH)
    public String list(@AuthenticationPrincipal CurrentUser currentUser,
                       @RequestParam(required = false) String status, @ModelAttribute ListRequest listRequest,
                       Model model) {
        StockStatus chosen = StockStatus.fromKey(status);
        if (chosen == StockStatus.OK) {
            chosen = null;
        }
        ListQuery list = ListQuery.of(PATH, listRequest, SORTS)
                .filterKey("status", chosen == null ? null : chosen.key(), "stores.filter.status",
                        chosen == null ? null : chosen.labelKey());
        model.addAttribute("list", list);
        model.addAttribute("status", chosen == null ? null : chosen.key());
        model.addAttribute("stores", storesService.load(currentUser.getUsername(), chosen, list.getQ()));
        return "stores/list";
    }

    @GetMapping(PATH + "/items/{id}")
    public String item(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id, Model model) {
        model.addAttribute("detail", storesService.item(currentUser.getUsername(), id));
        return "stores/item";
    }

    // Every active provisions item by place, each row with the quantity the inventory has now, in one form with one
    // "Save count". Model: stores, form (CountForm), rowErrors (item id: the message of its refused quantity),
    // voiceEnabled (both providers configured) and voicePending (a proposal waits in the session for its review).
    @GetMapping(PATH + "/count")
    public String countPage(@AuthenticationPrincipal CurrentUser currentUser, HttpSession session, Model model) {
        model.addAttribute("form", new CountForm());
        return countPage(storesService.load(currentUser.getUsername(), null, null), Map.of(), session, model);
    }

    private String countPage(Stores stores, Map<Long, String> rowErrors, HttpSession session, Model model) {
        model.addAttribute("stores", stores);
        model.addAttribute("rowErrors", rowErrors);
        model.addAttribute("voiceEnabled", voiceCountService.configured());
        model.addAttribute("voicePending", session.getAttribute(VoiceCountController.SESSION_KEY) != null);
        return "stores/count";
    }

    // "Save count": every row whose number is not the one the page showed is saved as a count, a row left as it was
    // writes nothing, and the item typed under "Add an item that is not on the list" joins the list and is counted;
    // all in one transaction (StoresService.saveCount). Each quantity follows the rule of its item's unit for a count
    // (core/form/QuantityRules: blank, not a number, below zero, too many decimals or above the inventory maximum).
    // Anything wrong: nothing is saved and the page comes back with every typed value and the message next to each
    // wrong row or field. Otherwise back to the Inventory with "Count saved: 3 items changed."
    @PostMapping(PATH + "/count")
    public String saveCount(@AuthenticationPrincipal CurrentUser currentUser, @ModelAttribute("form") CountForm form,
                            BindingResult errors, HttpSession session, Model model,
                            RedirectAttributes redirectAttributes) {
        Stores stores = storesService.load(currentUser.getUsername(), null, null);
        Map<Long, String> rowErrors = new HashMap<>();
        Map<Long, BigDecimal> counts = new LinkedHashMap<>();
        for (PlaceGroup group : stores.groups()) {
            for (Level level : group.levels()) {
                Long id = level.item().getId();
                String typed = form.getQuantities().get(id);
                if (typed == null) {
                    continue;   // added to the list after the page was opened
                }
                QuantityRules.Check check = quantityRules.forItem(level.item(), Use.COUNT)
                        .check(typed, level.quantity());
                if (!check.ok()) {
                    rowErrors.put(id, messageSource.getMessage(check.message(), LocaleContextHolder.getLocale()));
                } else if (check.value().compareTo(shown(form, level)) != 0) {
                    counts.put(id, check.value());
                }
            }
        }
        NewItem newItem = form.hasNewItem() ? newItem(form, errors) : null;
        if (!rowErrors.isEmpty() || errors.hasErrors()) {
            return countPage(stores, rowErrors, session, model);
        }
        try {
            storesService.saveCount(currentUser.getUsername(), counts, newItem);
        } catch (ItemNameTakenException e) {
            errors.rejectValue("newName", "stores.count.add.error.taken");
            return countPage(stores, rowErrors, session, model);
        }
        int changed = counts.size() + (newItem == null ? 0 : 1);
        if (changed == 0) {
            redirectAttributes.addFlashAttribute("successMessage", "stores.count.nothing");
        } else {
            redirectAttributes.addFlashAttribute("successMessage", "stores.count.changed.saved");
            redirectAttributes.addFlashAttribute("successArgs", new Object[] {changed});
        }
        return "redirect:" + PATH;
    }

    // The quantity the page showed in this row when it was opened; the inventory's own when the field is missing.
    private static BigDecimal shown(CountForm form, Level level) {
        try {
            return new BigDecimal(form.getShown().get(level.item().getId()));
        } catch (NullPointerException | NumberFormatException e) {
            return level.quantity();
        }
    }

    // The new item as typed, checked: name, unit (one of ItemUnits) and quantity required, the lengths of Item, the
    // quantity by the rule of the chosen unit for a count. Null when a field is wrong (the errors are on the fields).
    private NewItem newItem(CountForm form, BindingResult errors) {
        if (form.getNewName() == null) {
            errors.rejectValue("newName", "request.form.error.nameRequired");
        } else if (form.getNewName().length() > Item.NAME_MAX_LENGTH) {
            errors.rejectValue("newName", "request.form.error.nameTooLong", new Object[] {Item.NAME_MAX_LENGTH}, null);
        }
        if (!ItemUnits.listed(form.getNewUnit())) {
            errors.rejectValue("newUnit", "unit.error.notListed");
        }
        if (form.getNewPlace() != null && form.getNewPlace().length() > Item.PLACE_MAX_LENGTH) {
            errors.rejectValue("newPlace", "item.error.tooLong");
        }
        QuantityRules.Check check = quantityRules.forUnit(form.getNewUnit(), Use.COUNT).check(form.getNewQuantity());
        check.reject(errors, "newQuantity");
        return errors.hasErrors() ? null
                : new NewItem(form.getNewName(), form.getNewUnit(), form.getNewPlace(), check.value());
    }

    // Every movement of the ship, newest first, 50 a page; filtered by type and UTC days. A day that is not a
    // date or outside the sensible range shows its error under the field and filters nothing. P58: the quick choices
    // of layout/parts :: dateRange (the last 30, 60 and 90 days, links that keep the type).
    @GetMapping(PATH + "/history")
    public String history(@AuthenticationPrincipal CurrentUser currentUser,
                          @Validated @ModelAttribute("filter") StoresHistoryFilter filter, BindingResult bindingResult,
                          Model model) {
        if (bindingResult.hasFieldErrors("from")) {
            filter.setFrom(null);
        }
        if (bindingResult.hasFieldErrors("to")) {
            filter.setTo(null);
        }
        StoresService.History history = storesService.history(currentUser.getUsername(), filter.movementType(),
                filter.getFrom(), filter.getTo(), filter.pageIndex());
        model.addAttribute("history", history);
        model.addAttribute("types", StockMovementType.values());
        int page = history.movements().getNumber();
        model.addAttribute("newerUrl", history.movements().hasPrevious() ? historyUrl(filter, page - 1) : null);
        model.addAttribute("olderUrl", history.movements().hasNext() ? historyUrl(filter, page + 1) : null);
        model.addAttribute("datePresets", DatePreset.of(DatePreset.LAST_DAYS, LocalDate.now(ZoneOffset.UTC),
                filter.getFrom(), filter.getTo(),
                (from, to) -> historyUrl(filter.movementType(), from, to, 0)));
        return "stores/history";
    }

    private static String historyUrl(StoresHistoryFilter filter, int page) {
        return historyUrl(filter.movementType(), filter.getFrom(), filter.getTo(), page);
    }

    private static String historyUrl(StockMovementType type, LocalDate from, LocalDate to, int page) {
        UriComponentsBuilder url = UriComponentsBuilder.fromPath(PATH + "/history");
        if (type != null) {
            url.queryParam("type", type.name());
        }
        if (from != null) {
            url.queryParam("from", from);
        }
        if (to != null) {
            url.queryParam("to", to);
        }
        if (page > 0) {
            url.queryParam("page", page);
        }
        return url.build().toUriString();
    }
}
