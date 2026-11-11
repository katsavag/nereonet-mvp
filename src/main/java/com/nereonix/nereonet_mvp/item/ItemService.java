package com.nereonix.nereonet_mvp.item;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;

// Galley items of a ship. Items are never deleted, only deactivated.
@Service
public class ItemService {

    private final ItemRepository itemRepository;
    private final ShipRepository shipRepository;
    private final AuditService auditService;

    public ItemService(ItemRepository itemRepository, ShipRepository shipRepository, AuditService auditService) {
        this.itemRepository = itemRepository;
        this.shipRepository = shipRepository;
        this.auditService = auditService;
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

    @Transactional(readOnly = true)
    public List<Item> findItems(Long shipId) {
        return itemRepository.findAllByShipIdOrderByNameAsc(shipId);
    }

    /** Saves a new, active item. Throws ItemNameTakenException if the ship has an item with this name. */
    @Transactional
    public Item create(Long shipId, String name, String unit, String category) {
        Ship ship = getShip(shipId);
        if (itemRepository.existsByShipIdAndNameIgnoreCase(shipId, name.strip())) {
            throw new ItemNameTakenException();
        }
        Item item = newItem(ship, name, unit, category);
        itemRepository.save(item);
        auditService.record("ITEM_CREATED", ship, describe(item));
        return item;
    }

    /** Throws ItemNameTakenException if another item of the ship has this name. */
    @Transactional
    public void update(Long shipId, Long itemId, String name, String unit, String category) {
        Item item = getItem(shipId, itemId);
        if (itemRepository.existsByShipIdAndNameIgnoreCaseAndIdNot(shipId, name.strip(), itemId)) {
            throw new ItemNameTakenException();
        }
        apply(item, name, unit, category);
        auditService.record("ITEM_UPDATED", item.getShip(), describe(item));
    }

    @Transactional
    public void setActive(Long shipId, Long itemId, boolean active) {
        Item item = getItem(shipId, itemId);
        item.setActive(active);
        auditService.record(active ? "ITEM_ACTIVATED" : "ITEM_DEACTIVATED", item.getShip(), item.getName());
    }

    // A new, active item, not saved and not audited. Also used by the import.
    static Item newItem(Ship ship, String name, String unit, String category) {
        Item item = new Item();
        item.setShip(ship);
        apply(item, name, unit, category);
        item.setActive(true);
        item.setCreatedAt(Instant.now());
        return item;
    }

    // For the audit log, e.g. "Rice, kg, Dry goods"
    private static String describe(Item item) {
        return item.getName() + ", " + item.getUnit() + ", " + (item.getCategory() == null ? "-" : item.getCategory());
    }

    private static void apply(Item item, String name, String unit, String category) {
        item.setName(name.strip());
        item.setUnit(unit.strip());
        item.setCategory(category == null || category.isBlank() ? null : category.strip());
    }
}
