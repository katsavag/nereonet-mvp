package com.nereonix.nereonet_mvp.stores;

import java.math.BigDecimal;

import org.springframework.context.MessageSource;
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

import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.list.ListRequest;
import com.nereonix.nereonet_mvp.core.list.ListSort;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.stock.StockMovementType;
import com.nereonix.nereonet_mvp.core.stock.StockService;
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

    /** A count the server refused (P53), shown next to its row on the count page: the item and the value sent. */
    public record CountError(Long itemId, String value) {
    }

    /** A count saved, for js/stores-count.js (P53): the new quantity, its status (key, badge tone and words) and
     *  "Saved"; or, refused, only the error. */
    public record Counted(String quantity, String status, String tone, String statusLabel, String message,
                          String error) {
    }

    private final StoresService storesService;
    private final VoiceCountService voiceCountService;
    private final MessageSource messageSource;

    public StoresController(StoresService storesService, VoiceCountService voiceCountService,
                            MessageSource messageSource) {
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

    // One row of the count page, without JavaScript: saved at once, then back to the same row. Blank, not a number,
    // below zero or more than 3 decimals: nothing saved, and the row shows the error with the value sent. An item that
    // is not an active provisions item of the cook's ship: 404.
    @PostMapping(PATH + "/items/{id}/count")
    public String count(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id,
                        @RequestParam(required = false) String quantity, RedirectAttributes redirectAttributes) {
        BigDecimal value = parse(quantity);
        if (!StockService.isValidQuantity(value)) {
            redirectAttributes.addFlashAttribute("countError", new CountError(id, quantity));
        } else {
            storesService.count(currentUser.getUsername(), id, value);
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
        BigDecimal value = parse(quantity);
        if (!StockService.isValidQuantity(value)) {
            return ResponseEntity.unprocessableEntity()
                    .body(new Counted(null, null, null, null, null, message("stores.count.error")));
        }
        Level level = storesService.count(currentUser.getUsername(), id, value);
        return ResponseEntity.ok(new Counted(level.quantity().stripTrailingZeros().toPlainString(),
                level.status().key(), level.status().tone(), message(level.status().labelKey()),
                message("stores.count.saved"), null));
    }

    // P53: every movement of the ship, newest first, 50 a page; filtered by type and UTC days. A day that is not a
    // date or outside the sensible range shows its error under the field and filters nothing.
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
        return "stores/history";
    }

    private static String historyUrl(StoresHistoryFilter filter, int page) {
        UriComponentsBuilder url = UriComponentsBuilder.fromPath(PATH + "/history");
        if (filter.movementType() != null) {
            url.queryParam("type", filter.movementType().name());
        }
        if (filter.getFrom() != null) {
            url.queryParam("from", filter.getFrom());
        }
        if (filter.getTo() != null) {
            url.queryParam("to", filter.getTo());
        }
        if (page > 0) {
            url.queryParam("page", page);
        }
        return url.build().toUriString();
    }

    private String message(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    // "6", "2.5" or "2,5"; null for anything else.
    static BigDecimal parse(String quantity) {
        if (quantity == null || quantity.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(quantity.strip().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
