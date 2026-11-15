package com.nereonix.nereonet_mvp.stores;

import java.time.LocalDate;
import java.time.ZoneOffset;

import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import com.nereonix.nereonet_mvp.core.form.QuantityRules;
import com.nereonix.nereonet_mvp.core.form.QuantityRules.Use;
import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.list.DatePreset;
import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.list.ListRequest;
import com.nereonix.nereonet_mvp.core.list.ListSort;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.stock.StockMovementType;
import com.nereonix.nereonet_mvp.core.stock.StockService.Level;
import com.nereonix.nereonet_mvp.core.stock.StockStatus;

import jakarta.servlet.http.HttpSession;

// The Chief cook's store (P52): the stock of their own ship's provisions, and one item with its history. P53: counting
// by hand (/ship/stores/count, each row saved on its own) and the ship's history (/ship/stores/history). P57: counting
// by voice from the Count page (VoiceCountController).
// SecurityConfig limits /ship/stores/** to CHIEF_COOK (the Master and crew members get 403). English only, as every
// crew screen.
@Controller
public class StoresController {

    static final String PATH = "/ship/stores";

    // Grouped by place and by name within a place: there is nothing to sort, but ListQuery needs its default.
    private static final ListSort[] SORTS = {ListSort.asc("name")};

    /** A count the server refused (P53), shown next to its row on the count page: the item, the value sent and (P60)
     *  the error of the quantity rule. */
    public record CountError(Long itemId, String value, MessageSourceResolvable message) {
    }

    /** A count saved, for js/stores-count.js (P53): the new quantity, its status (key, badge tone and words) and
     *  "Saved"; or, refused, only the error. */
    public record Counted(String quantity, String status, String tone, String statusLabel, String message,
                          String error) {
    }

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

    // status: low or out (anything else: all); listRequest: q, the search by part of the name (P19). No paging: the
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

    // P53: every active provisions item by place, each with its expected quantity in its own small form. Flash
    // attributes after a row's form was posted without JavaScript: countSaved (the item's id) or countError.
    // P57: voiceEnabled (both providers configured: the Count by voice card) and voicePending (a proposal waits in the
    // session for its review).
    @GetMapping(PATH + "/count")
    public String countPage(@AuthenticationPrincipal CurrentUser currentUser, HttpSession session, Model model) {
        model.addAttribute("stores", storesService.load(currentUser.getUsername(), null, null));
        model.addAttribute("voiceEnabled", voiceCountService.configured());
        model.addAttribute("voicePending", session.getAttribute(VoiceCountController.SESSION_KEY) != null);
        return "stores/count";
    }

    // One row of the count page, without JavaScript: saved at once, then back to the same row. P60: the rule of the
    // item's unit for a store count (core/form/QuantityRules: blank, not a number, below zero, too many decimals or
    // above the store maximum): nothing saved, and the row shows the error with the value sent. An item that is not an
    // active provisions item of the cook's ship: 404.
    @PostMapping(PATH + "/items/{id}/count")
    public String count(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                        @RequestParam(required = false) String quantity, RedirectAttributes redirectAttributes) {
        QuantityRules.Check check = check(currentUser, id, quantity);
        if (!check.ok()) {
            redirectAttributes.addFlashAttribute("countError", new CountError(id, quantity, check.message()));
        } else {
            storesService.count(currentUser.getUsername(), id, check.value());
            redirectAttributes.addFlashAttribute("countSaved", id);
        }
        return "redirect:" + PATH + "/count#item-" + id;
    }

    // The same with JavaScript (Accept: application/json, js/stores-count.js): the row is updated in place. A refused
    // value: 422 with the error.
    @PostMapping(value = PATH + "/items/{id}/count", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Counted> countInPlace(@AuthenticationPrincipal CurrentUser currentUser,
                                                @PathVariable Long id,
                                                @RequestParam(required = false) String quantity) {
        QuantityRules.Check check = check(currentUser, id, quantity);
        if (!check.ok()) {
            return ResponseEntity.unprocessableEntity().body(new Counted(null, null, null, null, null,
                    messageSource.getMessage(check.message(), LocaleContextHolder.getLocale())));
        }
        Level level = storesService.count(currentUser.getUsername(), id, check.value());
        return ResponseEntity.ok(new Counted(level.quantity().stripTrailingZeros().toPlainString(),
                level.status().key(), level.status().tone(), message(level.status().labelKey()),
                message("stores.count.saved"), null));
    }

    // P53: every movement of the ship, newest first, 50 a page; filtered by type and UTC days. A day that is not a
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

    private String message(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    // P60: a count of this item as typed, by the rule of its unit for the store.
    private QuantityRules.Check check(CurrentUser currentUser, Long id, String quantity) {
        Item item = storesService.countable(currentUser.getUsername(), id);
        return quantityRules.forItem(item, Use.COUNT).check(quantity);
    }
}
