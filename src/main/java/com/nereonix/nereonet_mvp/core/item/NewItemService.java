package com.nereonix.nereonet_mvp.core.item;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.User;

/**
 * A new item of a ship's list made on board: the Chief cook adds an item found on the shelf while counting the
 * inventory (stores). The office makes its items in the item feature (ItemService).
 */
@Service
public class NewItemService {

    private final ItemRepository itemRepository;
    private final AuditService auditService;

    public NewItemService(ItemRepository itemRepository, AuditService auditService) {
        this.itemRepository = itemRepository;
        this.auditService = auditService;
    }

    /**
     * Saves a new, active PROVISIONS item of the ship and records ITEM_CREATED by this user ("Saffron, g, Provisions,
     * Dry store"). place: null or blank for none. ItemNameTakenException when the ship has an item with this name,
     * ignoring case.
     */
    @Transactional
    public Item createProvisions(Ship ship, String name, String unit, String place, User by) {
        if (itemRepository.existsByShipIdAndNameIgnoreCase(ship.getId(), name.strip())) {
            throw new ItemNameTakenException();
        }
        Item item = new Item();
        item.setShip(ship);
        item.setName(name.strip());
        item.setUnit(unit.strip());
        item.setCategory(ItemCategory.PROVISIONS);
        item.setPlace(place == null || place.isBlank() ? null : place.strip());
        item.setActive(true);
        item.setCreatedAt(Instant.now());
        itemRepository.save(item);
        auditService.record("ITEM_CREATED", ship, item.getName() + ", " + item.getUnit() + ", "
                + item.getCategory().fileValue() + ", " + (item.getPlace() == null ? "-" : item.getPlace()), by);
        return item;
    }
}
