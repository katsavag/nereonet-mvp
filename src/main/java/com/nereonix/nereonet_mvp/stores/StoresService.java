package com.nereonix.nereonet_mvp.stores;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemCategory;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.stock.StockMovement;
import com.nereonix.nereonet_mvp.core.stock.StockService;
import com.nereonix.nereonet_mvp.core.stock.StockService.Level;
import com.nereonix.nereonet_mvp.core.stock.StockStatus;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

/**
 * The Chief cook's Stores pages (P52): the provisions of the signed-in cook's own ship with their stock. An item of
 * another ship, or one that is not PROVISIONS, is a 404, never a 403. Read only.
 */
@Service
public class StoresService {

    /** The two counts of the summary (and of the Stores card on the cook's home page). */
    public record Counts(long low, long out) {

        public boolean any() {
            return low + out > 0;
        }
    }

    /** The items of one storage place, by name. place: null for the items without one ("Other", always last). */
    public record PlaceGroup(String place, List<Level> levels) {
    }

    /**
     * counts: Low and Out of stock over every item, whatever the filter. lastCount: the latest COUNT movement, or null
     * ("Not counted yet"). total: the number of items in the store; groups: the items the filter and search leave.
     */
    public record Stores(Ship ship, Counts counts, Instant lastCount, int total, List<PlaceGroup> groups) {

        public boolean isEmpty() {
            return groups.isEmpty();
        }
    }

    public record ItemDetail(Ship ship, Level level, List<StockMovement> history) {
    }

    private final UserRepository userRepository;
    private final ItemRepository itemRepository;
    private final StockService stockService;

    public StoresService(UserRepository userRepository, ItemRepository itemRepository, StockService stockService) {
        this.userRepository = userRepository;
        this.itemRepository = itemRepository;
        this.stockService = stockService;
    }

    /**
     * The store of the cook's ship. status: only the items with this status, or all when null. search: only the
     * items whose name contains it, ignoring case, or all when null. Filtered here, not in the database: a ship has
     * at most a few hundred provisions, all loaded for the counts anyway.
     */
    @Transactional(readOnly = true)
    public Stores load(String email, StockStatus status, String search) {
        Ship ship = ship(email);
        List<Level> levels = stockService.levels(ship);
        Counts counts = counts(levels);
        String needle = search == null ? null : search.toLowerCase(Locale.ROOT);
        List<Level> shown = levels.stream()
                .filter(level -> status == null || level.status() == status)
                .filter(level -> needle == null || level.item().getName().toLowerCase(Locale.ROOT).contains(needle))
                .toList();
        return new Stores(ship, counts, stockService.lastCount(ship), levels.size(), byPlace(shown));
    }

    /** Low and Out of stock on the cook's ship, for the cook's home page. */
    @Transactional(readOnly = true)
    public Counts counts(String email) {
        return counts(stockService.levels(ship(email)));
    }

    private static Counts counts(List<Level> levels) {
        return new Counts(levels.stream().filter(level -> level.status() == StockStatus.LOW).count(),
                levels.stream().filter(level -> level.status() == StockStatus.OUT).count());
    }

    // Places in alphabetical order ignoring case (the same place written "Cold room" and "cold room" is one group,
    // under the first spelling met), the items without a place last; the levels keep their order (by name).
    private static List<PlaceGroup> byPlace(List<Level> levels) {
        Map<String, String> names = new TreeMap<>();
        Map<String, List<Level>> byKey = new TreeMap<>();
        List<Level> other = new ArrayList<>();
        for (Level level : levels) {
            String place = level.item().getPlace();
            if (place == null || place.isBlank()) {
                other.add(level);
                continue;
            }
            String key = place.strip().toLowerCase(Locale.ROOT);
            names.putIfAbsent(key, place.strip());
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(level);
        }
        List<PlaceGroup> groups = new ArrayList<>();
        byKey.forEach((key, group) -> groups.add(new PlaceGroup(names.get(key), List.copyOf(group))));
        if (!other.isEmpty()) {
            groups.add(new PlaceGroup(null, List.copyOf(other)));
        }
        return groups;
    }

    /** A PROVISIONS item of the cook's ship (active or not) with its stock and history, newest first; otherwise 404. */
    @Transactional(readOnly = true)
    public ItemDetail item(String email, Long id) {
        Ship ship = ship(email);
        Item item = itemRepository.findByIdAndShipId(id, ship.getId())
                .filter(found -> found.getCategory() == ItemCategory.PROVISIONS)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return new ItemDetail(ship, stockService.level(item), stockService.history(item));
    }

    private Ship ship(String email) {
        User cook = userRepository.findByEmail(email).orElseThrow();
        if (cook.getShip() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return cook.getShip();
    }
}
