package com.nereonix.nereonet_mvp.stores;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.list.ListRequest;
import com.nereonix.nereonet_mvp.core.list.ListSort;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.stock.StockStatus;

// The Chief cook's store (P52): the stock of their own ship's provisions, and one item with its history.
// SecurityConfig limits /ship/stores/** to CHIEF_COOK (the Master and crew members get 403). English only, as every
// crew screen.
@Controller
public class StoresController {

    static final String PATH = "/ship/stores";

    // Grouped by place and by name within a place: there is nothing to sort, but ListQuery needs its default.
    private static final ListSort[] SORTS = {ListSort.asc("name")};

    private final StoresService storesService;

    public StoresController(StoresService storesService) {
        this.storesService = storesService;
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
}
