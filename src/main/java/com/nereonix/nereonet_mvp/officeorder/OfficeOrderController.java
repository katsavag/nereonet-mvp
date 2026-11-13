package com.nereonix.nereonet_mvp.officeorder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.springframework.http.HttpStatus;
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
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nereonix.nereonet_mvp.core.item.ItemCategory;
import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.list.ListRequest;
import com.nereonix.nereonet_mvp.core.list.ListSort;
import com.nereonix.nereonet_mvp.core.order.ShipOrder;
import com.nereonix.nereonet_mvp.core.order.ShipOrderLine;
import com.nereonix.nereonet_mvp.core.order.ShipOrderService;
import com.nereonix.nereonet_mvp.core.order.ShipOrderService.Confirmation;
import com.nereonix.nereonet_mvp.core.export.XlsExport;
import com.nereonix.nereonet_mvp.core.order.ShipOrderStatus;
import com.nereonix.nereonet_mvp.core.security.CurrentUser;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

// The orders the ships have sent, as the office sees them (P36): the list, one order, confirming its port and
// delivery date, and the export (an Excel workbook since P39). The logic is ShipOrderService's; the office never changes lines or quantities.
// /office/** is limited to OFFICE_USER in SecurityConfig. An OPEN order (still being put together on board) is a 404.
@Controller
public class OfficeOrderController {

    static final String LIST = "/office/orders";

    // The list has one order: newest sent first.
    private static final ListSort[] SORTS = {ListSort.desc("sentAt")};

    // The "Export status" filter's value for "Not exported yet" (?exported=no), as the Overview links to it.
    static final String NOT_EXPORTED = "no";

    /** One category's lines, by item name. */
    public record LineGroup(ItemCategory category, List<ShipOrderLine> lines) {
    }

    private final ShipOrderService orderService;
    private final ShipRepository shipRepository;
    private final UserRepository userRepository;

    public OfficeOrderController(ShipOrderService orderService, ShipRepository shipRepository,
                                 UserRepository userRepository) {
        this.orderService = orderService;
        this.shipRepository = shipRepository;
        this.userRepository = userRepository;
    }

    // shipId, status (sent, confirmed, closed) and exported=no (not exported yet) filter the list; anything else in
    // status or exported filters nothing. listRequest: search and page (P19).
    @GetMapping(LIST)
    public String list(@RequestParam(required = false) Long shipId,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) String exported,
                       @ModelAttribute ListRequest listRequest, Model model) {
        List<Ship> ships = shipRepository.findAllByOrderByNameAsc();
        ShipOrderStatus chosen = status(status);
        boolean notExported = NOT_EXPORTED.equals(exported);
        ListQuery list = ListQuery.of(LIST, listRequest, SORTS).filterShip(shipId, ships);
        if (chosen != null) {
            list.filterKey("status", param(chosen), "officeorder.filter.status", "officeorder.status." + chosen.name());
        }
        if (notExported) {
            list.filterKey("exported", NOT_EXPORTED, "officeorder.filter.exported", "officeorder.filter.exported.no");
        }
        model.addAttribute("list", list);
        model.addAttribute("orders", orderService.findSent(shipId, chosen, notExported, list));
        model.addAttribute("ships", ships);
        model.addAttribute("shipId", shipId);
        model.addAttribute("status", chosen == null ? null : param(chosen));
        model.addAttribute("statuses", ShipOrderService.SENT_TO_OFFICE);
        model.addAttribute("exported", notExported ? NOT_EXPORTED : null);
        return "officeorder/list";
    }

    @GetMapping(LIST + "/{id}")
    public String detail(@PathVariable Long id, Model model) {
        ShipOrder order = sent(id);
        return showDetail(order, ConfirmOrderForm.of(order), model);
    }

    /**
     * Confirms the order's port and delivery date, or corrects them, while it is SENT or CONFIRMED. Errors: the order
     * page again with them, nothing saved. Closed meanwhile: back with a message, nothing changed.
     */
    @PostMapping(LIST + "/{id}/confirm")
    public String confirm(@PathVariable Long id, @AuthenticationPrincipal CurrentUser currentUser,
                          @Validated @ModelAttribute("confirmForm") ConfirmOrderForm confirmForm,
                          BindingResult bindingResult, Model model, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            return showDetail(sent(id), confirmForm, model);
        }
        ShipOrderService.Confirm result = orderService.confirm(user(currentUser), id,
                new Confirmation(confirmForm.getConfirmedPort(), confirmForm.getDeliveryDate()));
        switch (result) {
            case DONE -> redirectAttributes.addFlashAttribute("successMessage", "officeorder.confirmed");
            case CLOSED -> redirectAttributes.addFlashAttribute("errorMessage", "officeorder.closed");
            case NOT_FOUND -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return "redirect:" + LIST + "/" + id;
    }

    /**
     * The workbook of the checked orders (the list), or of one order (its page): a .xls download, one row per order
     * line (P40; .xlsx in P39, CSV before). It marks them exported now. Too many lines for one .xls sheet: back to the
     * list with "Too many rows for one file", nothing marked. Nothing checked, or none of them an order the ships sent:
     * back to the list with a message.
     */
    @PostMapping(LIST + "/export")
    public Object export(@RequestParam(required = false) List<Long> ids,
                         @AuthenticationPrincipal CurrentUser currentUser, RedirectAttributes redirectAttributes) {
        List<Long> chosen = ids == null ? List.of() : ids.stream().filter(Objects::nonNull).toList();
        if (chosen.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "officeorder.export.noneSelected");
            return "redirect:" + LIST;
        }
        XlsExport.File file;
        try {
            file = orderService.export(user(currentUser), chosen).orElse(null);
        } catch (XlsExport.TooManyRowsException e) {
            redirectAttributes.addFlashAttribute("errorMessage", XlsExport.TooManyRowsException.MESSAGE);
            return "redirect:" + LIST;
        }
        if (file == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "officeorder.export.nothing");
            return "redirect:" + LIST;
        }
        return file.response();
    }

    private String showDetail(ShipOrder order, ConfirmOrderForm confirmForm, Model model) {
        Map<ItemCategory, List<ShipOrderLine>> byCategory = new EnumMap<>(ItemCategory.class);
        for (ShipOrderLine line : ShipOrderService.byCategoryAndName(order.getLines())) {
            byCategory.computeIfAbsent(line.getItem().getCategory(), category -> new ArrayList<>()).add(line);
        }
        model.addAttribute("order", order);
        model.addAttribute("groups", byCategory.entrySet().stream()
                .map(entry -> new LineGroup(entry.getKey(), List.copyOf(entry.getValue()))).toList());
        model.addAttribute("canConfirm", order.getStatus() != ShipOrderStatus.CLOSED);
        model.addAttribute("confirmForm", confirmForm);
        return "officeorder/detail";
    }

    private ShipOrder sent(Long id) {
        return orderService.findSent(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private User user(CurrentUser currentUser) {
        return userRepository.findById(currentUser.getId()).orElseThrow();
    }

    // "sent", "confirmed", "closed"; null for anything else (and OPEN, which the office never sees).
    private static ShipOrderStatus status(String value) {
        return Arrays.stream(ShipOrderStatus.values())
                .filter(ShipOrderService.SENT_TO_OFFICE::contains)
                .filter(status -> param(status).equals(value))
                .findFirst().orElse(null);
    }

    static String param(ShipOrderStatus status) {
        return status.name().toLowerCase(Locale.ROOT);
    }
}
