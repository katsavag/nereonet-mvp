package com.nereonix.nereonet_mvp.shiprequest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.Errors;
import org.springframework.web.server.ResponseStatusException;

import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemCategory;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.form.ItemUnits;
import com.nereonix.nereonet_mvp.core.form.QuantityRules;
import com.nereonix.nereonet_mvp.core.form.QuantityRules.Use;
import com.nereonix.nereonet_mvp.core.list.ListQueries;
import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.request.InvalidRequestException;
import com.nereonix.nereonet_mvp.core.request.ItemRequest;
import com.nereonix.nereonet_mvp.core.request.ItemRequestRepository;
import com.nereonix.nereonet_mvp.core.request.RequestLine;
import com.nereonix.nereonet_mvp.core.request.RequestService;
import com.nereonix.nereonet_mvp.core.request.RequestService.Outcome;
import com.nereonix.nereonet_mvp.core.request.RequestStatus;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.stock.StockService;
import com.nereonix.nereonet_mvp.core.stock.StockService.Level;
import com.nereonix.nereonet_mvp.core.stock.StockStatus;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

/**
 * The Chief cook's and crew's own requests to the Master. Only the person who asked sees a request: anyone
 * else's is a 404, never a 403. A request is not an order: the Master approves it (approval) and only then does
 * it go into the ship order. Submitting, changing and cancelling are RequestService's; this builds the pages and the
 * form, reads what was typed, and shows what RequestService refuses as form errors.
 *
 * The form lists the ship's active items (both categories) the person can pick; editing a request also keeps the
 * items already on it, even if the office has since made them inactive. The Chief cook sees the provisions that are
 * Out of stock or Low first, under "Running low", with what is on board; crew members never see stock.
 */
@Service
public class ShipRequestService {

    /** The group keys of the form: running low (the cook only), then the two categories. */
    public static final String RUNNING_LOW = "low";

    /** One page of the person's requests the filter leaves, newest first, and the number under each filter. */
    public record Requests(Ship ship, RequestFilter filter, Page<ItemRequest> requests, long all, long waiting,
                           long approved, long declined) {
    }

    /** One category's lines of a request, in the request's order. */
    public record LineGroup(ItemCategory category, List<RequestLine> lines) {
    }

    public record Detail(ItemRequest request, List<LineGroup> groups) {

        public boolean isPending() {
            return request.getStatus() == RequestStatus.PENDING;
        }
    }

    /** One item of the form: its index in RequestForm.lines and, on the cook's Running low rows, its stock. */
    public record Row(int index, Item item, Level level, QuantityRules.Rule rule, BigDecimal kept) {
    }

    /** key: RUNNING_LOW or an ItemCategory name. */
    public record Group(String key, List<Row> rows) {

        public boolean isRunningLow() {
            return RUNNING_LOW.equals(key);
        }
    }

    /** What the form shows: the item groups, whether the person is the Chief cook (new items, stock), and the
     *  request being edited (null for a new one). */
    public record Picker(Ship ship, boolean cook, List<Group> groups, ItemRequest request) {

        public boolean isEmpty() {
            return groups.isEmpty();
        }
    }

    /** The form as posted, read against the items the person can pick (see read). */
    public record Read(RequestForm form, boolean itemGone) {
    }

    private final UserRepository userRepository;
    private final ItemRepository itemRepository;
    private final ItemRequestRepository requestRepository;
    private final RequestService requestService;
    private final StockService stockService;
    private final QuantityRules quantityRules;

    public ShipRequestService(UserRepository userRepository, ItemRepository itemRepository,
                              ItemRequestRepository requestRepository, RequestService requestService,
                              StockService stockService, QuantityRules quantityRules) {
        this.userRepository = userRepository;
        this.itemRepository = itemRepository;
        this.requestRepository = requestRepository;
        this.requestService = requestService;
        this.stockService = stockService;
        this.quantityRules = quantityRules;
    }

    /** The rule of a new item's quantity: by its unit as typed; none or unknown: whole numbers. */
    public QuantityRules.Rule newItemRule(String unit) {
        return quantityRules.forUnit(unit, Use.REQUEST);
    }

    // ---- The list and one request ----

    @Transactional(readOnly = true)
    public Requests list(String email, RequestFilter filter, ListQuery list) {
        User user = user(email);
        Page<ItemRequest> requests = ListQueries.page(list, page -> requestRepository
                .findAllByRequestedByIdAndStatusInOrderByCreatedAtDescIdDesc(user.getId(), filter.statuses(), page));
        // The rows show the number of lines: load them here (in batches, see ItemRequest.lines).
        requests.forEach(request -> request.getLines().size());
        return new Requests(user.getShip(), filter, requests, count(user, RequestFilter.ALL),
                count(user, RequestFilter.WAITING), count(user, RequestFilter.APPROVED),
                count(user, RequestFilter.DECLINED));
    }

    private long count(User user, RequestFilter filter) {
        return requestRepository.countByRequestedByIdAndStatusIn(user.getId(), filter.statuses());
    }

    /** The person's own request with its lines by category (Provisions, then Personal items); otherwise 404. */
    @Transactional(readOnly = true)
    public Detail detail(String email, Long id) {
        ItemRequest request = own(user(email), id);
        Map<ItemCategory, List<RequestLine>> byCategory = new EnumMap<>(ItemCategory.class);
        for (RequestLine line : request.getLines()) {
            byCategory.computeIfAbsent(line.getCategory(), category -> new ArrayList<>()).add(line);
        }
        return new Detail(request, byCategory.entrySet().stream()
                .map(entry -> new LineGroup(entry.getKey(), List.copyOf(entry.getValue())))
                .toList());
    }

    /** True while the person's own request is PENDING; 404 when it is not theirs. */
    @Transactional(readOnly = true)
    public boolean isPending(String email, Long id) {
        return own(user(email), id).getStatus() == RequestStatus.PENDING;
    }

    // ---- The form ----

    /** A new request: every item the person can pick, nothing picked. */
    @Transactional(readOnly = true)
    public RequestForm newForm(String email) {
        User user = user(email);
        RequestForm form = new RequestForm();
        for (Item item : pickable(user, null)) {
            form.getLines().add(new RequestForm.Line(item.getId(), null));
        }
        return form;
    }

    /** Editing a request: as a new one, with the request's quantities and its new items. 404 when not theirs. */
    @Transactional(readOnly = true)
    public RequestForm editForm(String email, Long id) {
        User user = user(email);
        ItemRequest request = own(user, id);
        Map<Long, String> quantities = new HashMap<>();
        RequestForm form = new RequestForm();
        for (RequestLine line : request.getLines()) {
            if (line.isNewItem()) {
                form.getNewItems().add(new RequestForm.NewLine(line.getNewItemName(), line.getNewItemUnit(),
                        line.getNewItemCategory(), plain(line.getQuantity())));
            } else {
                quantities.merge(line.getItem().getId(), plain(line.getQuantity()), (a, b) -> a);
            }
        }
        for (Item item : pickable(user, request)) {
            form.getLines().add(new RequestForm.Line(item.getId(), quantities.get(item.getId())));
        }
        return form;
    }

    /**
     * The form as posted, in the order of the page as it is now: each item the person can pick with the quantity
     * posted for it (the first one, if posted twice), so the rows and their errors always match the items.
     * itemGone: a quantity was posted for an item that can no longer be picked (made inactive meanwhile). Crew members
     * cannot ask for new items, so theirs are dropped. requestId: the request being edited, or null.
     */
    @Transactional(readOnly = true)
    public Read read(String email, RequestForm posted, Long requestId) {
        User user = user(email);
        ItemRequest request = requestId == null ? null : own(user, requestId);
        Map<Long, String> quantities = new HashMap<>();
        for (RequestForm.Line line : posted.getLines()) {
            if (line.getItemId() != null && line.isPicked()) {
                quantities.putIfAbsent(line.getItemId(), line.getQuantity());
            }
        }
        RequestForm form = new RequestForm();
        for (Item item : pickable(user, request)) {
            form.getLines().add(new RequestForm.Line(item.getId(), quantities.remove(item.getId())));
        }
        if (user.getRole() == Role.CHIEF_COOK) {
            form.setNewItems(posted.getNewItems());
            form.setNewName(posted.getNewName());
            form.setNewUnit(posted.getNewUnit());
            form.setNewCategory(posted.getNewCategory());
            form.setNewQuantity(posted.getNewQuantity());
        }
        return new Read(form, !quantities.isEmpty());
    }

    /** The groups of the form, from the lines of the form (see read): for the Chief cook Running low first (Out of
     *  stock, then Low, each by name), then Provisions and Personal items, each by name. */
    @Transactional(readOnly = true)
    public Picker picker(String email, RequestForm form, Long requestId) {
        User user = user(email);
        ItemRequest request = requestId == null ? null : own(user, requestId);
        boolean cook = user.getRole() == Role.CHIEF_COOK;
        Map<Long, Item> items = pickableById(user, request);
        Map<Long, Level> low = lowLevels(user);
        Map<Long, BigDecimal> saved = savedQuantities(request);
        Map<String, List<Row>> groups = new LinkedHashMap<>();
        groups.put(RUNNING_LOW, new ArrayList<>());
        for (ItemCategory category : ItemCategory.values()) {
            groups.put(category.name(), new ArrayList<>());
        }
        for (int i = 0; i < form.getLines().size(); i++) {
            Item item = items.get(form.getLines().get(i).getItemId());
            if (item == null) {
                continue;
            }
            Level level = low.get(item.getId());
            groups.get(level != null ? RUNNING_LOW : item.getCategory().name()).add(new Row(i, item, level,
                    quantityRules.forItem(item, Use.REQUEST), saved.get(item.getId())));
        }
        List<Group> shown = groups.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .map(entry -> new Group(entry.getKey(), List.copyOf(entry.getValue())))
                .toList();
        return new Picker(user.getShip(), cook, shown, request);
    }

    /**
     * "Add to request" (the Chief cook only): the typed new item becomes a new-item line of the form, its fields are
     * emptied. A name is required (at most Item.NAME_MAX_LENGTH), the unit optional (one of ItemUnits), the category
     * Provisions unless Personal items is chosen,
     * the quantity above zero (the order's quantity rule); a name already on the list or already added is refused,
     * so the person picks it there. False, with the errors, when refused.
     */
    @Transactional(readOnly = true)
    public boolean addNewItem(String email, RequestForm form, Long requestId, Errors errors) {
        User user = user(email);
        if (user.getRole() != Role.CHIEF_COOK) {
            return false;
        }
        String name = form.getNewName();
        if (name == null || name.isEmpty()) {
            errors.rejectValue("newName", "request.form.error.nameRequired");
        } else if (name.length() > Item.NAME_MAX_LENGTH) {
            errors.rejectValue("newName", "request.form.error.nameTooLong", new Object[] {Item.NAME_MAX_LENGTH}, null);
        } else {
            ItemRequest request = requestId == null ? null : own(user, requestId);
            String lower = name.toLowerCase(Locale.ROOT);
            pickableById(user, request).values().stream()
                    .filter(item -> item.getName().toLowerCase(Locale.ROOT).equals(lower))
                    .findFirst()
                    .ifPresent(item -> errors.rejectValue("newName", "request.form.error.onList",
                            new Object[] {item.getName()}, null));
            if (!errors.hasFieldErrors("newName") && form.getNewItems().stream()
                    .anyMatch(line -> line.getName() != null && line.getName().equalsIgnoreCase(name))) {
                errors.rejectValue("newName", "request.form.error.alreadyAdded", new Object[] {name}, null);
            }
        }
        if (form.getNewUnit() != null && !ItemUnits.listed(form.getNewUnit())) {
            errors.rejectValue("newUnit", "unit.error.notListed");
        }
        newItemRule(form.getNewUnit()).check(form.getNewQuantity()).reject(errors, "newQuantity");
        if (errors.hasErrors()) {
            return false;
        }
        form.getNewItems().add(new RequestForm.NewLine(name, form.getNewUnit(), form.getNewCategory(),
                form.getNewQuantity()));
        form.clearNewItemFields();
        return true;
    }

    /** "Remove" on a new-item line (the cook only): it leaves the form. An index out of range changes nothing. */
    public void removeNewItem(RequestForm form, int index) {
        if (index >= 0 && index < form.getNewItems().size()) {
            form.getNewItems().remove(index);
        }
    }

    /**
     * "Send to the master": a new request with the picked lines (RequestService.submit, which records it and notifies
     * the Master). Something typed in "Add an item that is not on the list" is added first. The id of the new request,
     * or null with the errors: a quantity that is not above zero (the order's rule), an item no longer on the list, or
     * whatever submit refuses (no lines at all, ...).
     */
    public Long submit(String email, Read read, Errors errors) {
        List<RequestLine> lines = linesToSave(email, read, null, errors);
        if (lines == null) {
            return null;
        }
        User user = user(email);
        ItemRequest request = new ItemRequest();
        request.setShip(user.getShip());
        request.setRequestedBy(user);
        request.getLines().addAll(lines);
        try {
            return requestService.submit(request).getId();
        } catch (InvalidRequestException e) {
            errors.reject(errorKey(e), null);
            return null;
        }
    }

    /**
     * "Save" on Edit request: the request's lines become the picked ones (RequestService.update, locked as the Master's
     * decision is). Null with the errors as submit (nothing changed); otherwise what update did: DONE, NOT_PENDING
     * once the Master decided or it was cancelled (nothing changed), NOT_FOUND.
     */
    public Outcome update(String email, Long id, Read read, Errors errors) {
        List<RequestLine> lines = linesToSave(email, read, id, errors);
        if (lines == null) {
            return null;
        }
        try {
            return requestService.update(user(email), id, lines);
        } catch (InvalidRequestException e) {
            errors.reject(errorKey(e), null);
            return null;
        }
    }

    /** Cancel: RequestService.cancel. */
    public Outcome cancel(String email, Long id) {
        return requestService.cancel(user(email), id);
    }

    // The request lines of the form, or null with the errors of the form's own checks.
    private List<RequestLine> linesToSave(String email, Read read, Long requestId, Errors errors) {
        RequestForm form = read.form();
        if (form.hasNewItemTyped()) {
            addNewItem(email, form, requestId, errors);
        }
        User user = user(email);
        ItemRequest request = requestId == null ? null : own(user, requestId);
        Map<Long, Item> items = pickableById(user, request);
        // A quantity already on the request (editing) may stay above the maximum; a new or changed one may not.
        Map<Long, BigDecimal> saved = savedQuantities(request);
        Map<String, BigDecimal> savedNew = new HashMap<>();
        if (request != null) {
            request.getLines().stream().filter(RequestLine::isNewItem).forEach(line -> savedNew.putIfAbsent(
                    line.getNewItemName().toLowerCase(Locale.ROOT), line.getQuantity()));
        }
        List<RequestLine> lines = new ArrayList<>();
        for (int i = 0; i < form.getLines().size(); i++) {
            RequestForm.Line line = form.getLines().get(i);
            if (!line.isPicked()) {
                continue;
            }
            Item item = items.get(line.getItemId());
            QuantityRules.Check check = quantityRules.forItem(item, Use.REQUEST)
                    .check(line.getQuantity(), saved.get(item.getId()));
            if (!check.ok()) {
                check.reject(errors, "lines[" + i + "].quantity");
                continue;
            }
            RequestLine requestLine = new RequestLine();
            requestLine.setItem(item);
            requestLine.setQuantity(check.value());
            lines.add(requestLine);
        }
        for (int i = 0; i < form.getNewItems().size(); i++) {
            RequestForm.NewLine line = form.getNewItems().get(i);
            if (line.getName() == null || line.getName().isEmpty() || line.getName().length() > Item.NAME_MAX_LENGTH
                    || (line.getUnit() != null && line.getUnit().length() > Item.UNIT_MAX_LENGTH)) {
                errors.rejectValue("newItems[" + i + "].quantity", "request.form.error.newItemInvalid");
                continue;
            }
            QuantityRules.Check check = newItemRule(line.getUnit())
                    .check(line.getQuantity(), savedNew.get(line.getName().toLowerCase(Locale.ROOT)));
            if (!check.ok()) {
                check.reject(errors, "newItems[" + i + "].quantity");
                continue;
            }
            RequestLine requestLine = new RequestLine();
            requestLine.setNewItemName(line.getName());
            requestLine.setNewItemUnit(line.getUnit());
            requestLine.setNewItemCategory(line.getCategory());
            requestLine.setQuantity(check.value());
            lines.add(requestLine);
        }
        if (read.itemGone()) {
            errors.reject("request.form.error.itemGone", null);
        }
        return errors.hasErrors() ? null : lines;
    }

    private static String errorKey(InvalidRequestException e) {
        return switch (e.getProblem()) {
            case NO_LINES -> "request.form.error.empty";
            default -> "request.form.error.invalid";
        };
    }

    // The quantities on the request being edited, by item (the first line of an item); empty for a new request.
    private static Map<Long, BigDecimal> savedQuantities(ItemRequest request) {
        Map<Long, BigDecimal> saved = new HashMap<>();
        if (request != null) {
            request.getLines().stream().filter(line -> !line.isNewItem())
                    .forEach(line -> saved.putIfAbsent(line.getItem().getId(), line.getQuantity()));
        }
        return saved;
    }

    private static String plain(BigDecimal quantity) {
        return quantity.stripTrailingZeros().toPlainString();
    }

    // The items the person can pick, in the order of the form: for the Chief cook the provisions running low first
    // (Out of stock, then Low), then Provisions and Personal items, each by name. One query, two more for the cook's
    // stock.
    private List<Item> pickable(User user, ItemRequest request) {
        Map<Long, Level> low = lowLevels(user);
        Comparator<Item> order = Comparator
                .comparingInt((Item item) -> {
                    Level level = low.get(item.getId());
                    return level == null ? 2 : (level.status() == StockStatus.OUT ? 0 : 1);
                })
                .thenComparing(Item::getCategory)
                .thenComparing(item -> item.getName().toLowerCase(Locale.ROOT));
        return pickableById(user, request).values().stream().sorted(order).toList();
    }

    // The items the person can pick, by id: the ship's active items and, when editing, the items already on the
    // request (even if made inactive since). One query.
    private Map<Long, Item> pickableById(User user, ItemRequest request) {
        Map<Long, Item> items = new LinkedHashMap<>();
        for (Item item : itemRepository.findAllByShipIdOrderByNameAsc(user.getShip().getId())) {
            if (item.isActive()) {
                items.put(item.getId(), item);
            }
        }
        if (request != null) {
            for (RequestLine line : request.getLines()) {
                if (!line.isNewItem() && line.getItem().getShip().getId().equals(user.getShip().getId())) {
                    items.putIfAbsent(line.getItem().getId(), line.getItem());
                }
            }
        }
        return items;
    }

    // For the Chief cook, the ship's provisions that are Out of stock or Low, by item id; nothing for crew members.
    private Map<Long, Level> lowLevels(User user) {
        if (user.getRole() != Role.CHIEF_COOK) {
            return Map.of();
        }
        return stockService.levels(user.getShip()).stream()
                .filter(level -> level.status() != StockStatus.OK)
                .collect(Collectors.toMap(level -> level.item().getId(), Function.identity()));
    }

    private ItemRequest own(User user, Long id) {
        return requestRepository.findByIdAndRequestedById(id, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private User user(String email) {
        User user = userRepository.findByEmail(email).orElseThrow();
        if (user.getShip() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return user;
    }
}
