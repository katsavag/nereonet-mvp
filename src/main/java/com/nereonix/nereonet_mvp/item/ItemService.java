package com.nereonix.nereonet_mvp.item;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemCategory;
import com.nereonix.nereonet_mvp.core.item.ItemNameTakenException;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.item.StarterItems;
import com.nereonix.nereonet_mvp.core.list.ListQueries;
import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;

import jakarta.persistence.criteria.Predicate;

// Galley items of a ship. Items are never deleted, only deactivated.
@Service
public class ItemService {

    private final ItemRepository itemRepository;
    private final ShipRepository shipRepository;
    private final AuditService auditService;
    private final ListQueries listQueries;
    private final StarterItems starterItems;

    public ItemService(ItemRepository itemRepository, ShipRepository shipRepository, AuditService auditService,
                       ListQueries listQueries, StarterItems starterItems) {
        this.starterItems = starterItems;
        this.itemRepository = itemRepository;
        this.shipRepository = shipRepository;
        this.auditService = auditService;
        this.listQueries = listQueries;
    }

    // 404 if there is no such ship.
    @Transactional(readOnly = true)
    public Ship getShip(Long shipId) {
        return shipRepository.findById(shipId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    // 404 if there is no such item on this ship.
    @Transactional(readOnly = true)
    public Item getItem(Long shipId, Long itemId) {
        return itemRepository.findByIdAndShipId(itemId, shipId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * One page of the ship's Items tab, paginated in the database. active: true, false or null (both);
     * category: one category, or null for both; the search is the name or the place. Sort keys: name (default),
     * unit, category (by enum name: Personal items before Provisions), place, status (active first).
     */
    @Transactional(readOnly = true)
    public Page<Item> findItems(Long shipId, Boolean active, ItemCategory category, ListQuery list) {
        return listQueries.page(Item.class, list, (root, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("ship").get("id"), shipId));
            if (active != null) {
                where.add(cb.equal(root.get("active"), active));
            }
            if (category != null) {
                where.add(cb.equal(root.get("category"), category));
            }
            String q = list.getContains();
            if (q != null) {
                where.add(cb.or(ListQueries.contains(cb, root.get("name"), q),
                        ListQueries.contains(cb, root.get("place"), q)));
            }
            return where;
        }, (root, cb) -> switch (list.getSort()) {
            case "unit" -> List.of(cb.lower(root.get("unit")), cb.lower(root.get("name")));
            case "category" -> List.of(root.get("category"), cb.lower(root.get("name")));
            case "place" -> List.of(cb.lower(root.get("place")), cb.lower(root.get("name")));
            // Active before inactive when ascending.
            case "status" -> List.of(cb.<Integer>selectCase().when(cb.isTrue(root.get("active")), 0).otherwise(1),
                    cb.lower(root.get("name")));
            default -> List.of(cb.lower(root.get("name")));
        }, null);
    }

    /** Saves a new, active item. Throws ItemNameTakenException if the ship has an item with this name. */
    @Transactional
    public Item create(Long shipId, String name, String unit, ItemCategory category, String place,
                       BigDecimal maxPerRequest) {
        Ship ship = getShip(shipId);
        if (itemRepository.existsByShipIdAndNameIgnoreCase(shipId, name.strip())) {
            throw new ItemNameTakenException();
        }
        Item item = newItem(ship, name, unit, category, place);
        item.setMaxPerRequest(maxPerRequest);
        itemRepository.save(item);
        auditService.record("ITEM_CREATED", ship, describe(item));
        return item;
    }

    /**
     * "Add the standard list": gives a vessel that has no item at all the standard galley list (StarterItems).
     * Returns the number of items added; none when the vessel has an item already.
     */
    @Transactional
    public int addStarterItems(Long shipId) {
        Ship ship = getShip(shipId);
        return itemRepository.countByShipId(shipId) > 0 ? 0 : starterItems.addTo(ship);
    }

    /** Throws ItemNameTakenException if another item of the ship has this name. */
    @Transactional
    public void update(Long shipId, Long itemId, String name, String unit, ItemCategory category, String place,
                       BigDecimal maxPerRequest) {
        Item item = getItem(shipId, itemId);
        if (itemRepository.existsByShipIdAndNameIgnoreCaseAndIdNot(shipId, name.strip(), itemId)) {
            throw new ItemNameTakenException();
        }
        apply(item, name, unit, category, place);
        item.setMaxPerRequest(maxPerRequest);
        auditService.record("ITEM_UPDATED", item.getShip(), describe(item));
    }

    @Transactional
    public void setActive(Long shipId, Long itemId, boolean active) {
        Item item = getItem(shipId, itemId);
        item.setActive(active);
        auditService.record(active ? "ITEM_ACTIVATED" : "ITEM_DEACTIVATED", item.getShip(), item.getName());
    }

    // A new, active item, not saved and not audited. Also used by the import.
    static Item newItem(Ship ship, String name, String unit, ItemCategory category, String place) {
        Item item = new Item();
        item.setShip(ship);
        apply(item, name, unit, category, place);
        item.setActive(true);
        item.setCreatedAt(Instant.now());
        return item;
    }

    // For the audit log, e.g. "Rice, kg, Provisions, Dry store" (and ", max 200" when the item has its own maximum)
    private static String describe(Item item) {
        return item.getName() + ", " + item.getUnit() + ", " + item.getCategory().fileValue() + ", "
                + (item.getPlace() == null ? "-" : item.getPlace())
                + (item.getMaxPerRequest() == null ? "" : ", max " + item.getMaxPerRequest().stripTrailingZeros().toPlainString());
    }

    private static void apply(Item item, String name, String unit, ItemCategory category, String place) {
        item.setName(name.strip());
        item.setUnit(unit.strip());
        item.setCategory(category);
        item.setPlace(place(category, place));
    }

    /** The place as an item of this category stores it: null when blank, and always null for personal items. */
    static String place(ItemCategory category, String place) {
        return category != ItemCategory.PROVISIONS || place == null || place.isBlank() ? null : place.strip();
    }
}
