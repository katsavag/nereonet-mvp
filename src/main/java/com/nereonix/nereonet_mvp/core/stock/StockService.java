package com.nereonix.nereonet_mvp.core.stock;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemCategory;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrderService;
import com.nereonix.nereonet_mvp.core.ship.Ship;

/**
 * The stock of a ship's provisions (P52), shared by the Chief cook's Stores pages and, later, the request page, the
 * receipt on board and the office. Only PROVISIONS items have stock; personal items never appear. An item without a
 * StockLevel row has quantity 0. Read only for now: counting and receipts come with their own pages.
 * app.stock.low-threshold (default 10, one threshold for every item, in the item's unit) splits LOW from OK.
 */
@Service
public class StockService {

    /** One item of the store with its quantity on board and its status. */
    public record Level(Item item, BigDecimal quantity, StockStatus status) {
    }

    private final ItemRepository itemRepository;
    private final StockLevelRepository levelRepository;
    private final StockMovementRepository movementRepository;
    private final BigDecimal lowThreshold;

    public StockService(ItemRepository itemRepository, StockLevelRepository levelRepository,
                        StockMovementRepository movementRepository,
                        @Value("${app.stock.low-threshold:10}") BigDecimal lowThreshold) {
        this.itemRepository = itemRepository;
        this.levelRepository = levelRepository;
        this.movementRepository = movementRepository;
        this.lowThreshold = lowThreshold;
    }

    /** True for a quantity the store can hold: zero or more, with the precision of an order line
     *  (ShipOrderService.isValidQuantity: at most 3 decimals and 9 whole digits). */
    public static boolean isValidQuantity(BigDecimal quantity) {
        return quantity != null && (quantity.signum() == 0 || ShipOrderService.isValidQuantity(quantity));
    }

    /** OUT at 0, LOW above 0 and below the threshold, otherwise OK. */
    public StockStatus status(BigDecimal quantity) {
        if (quantity.signum() <= 0) {
            return StockStatus.OUT;
        }
        return quantity.compareTo(lowThreshold) < 0 ? StockStatus.LOW : StockStatus.OK;
    }

    /** Every active PROVISIONS item of the ship, by name, with its quantity and status. Two queries. */
    @Transactional(readOnly = true)
    public List<Level> levels(Ship ship) {
        Map<Long, BigDecimal> quantities = levelRepository.findAllByItemShipId(ship.getId()).stream()
                .collect(Collectors.toMap(level -> level.getItem().getId(), StockLevel::getQuantity));
        return itemRepository.findAllByShipIdAndCategoryAndActiveTrueOrderByNameAsc(ship.getId(),
                        ItemCategory.PROVISIONS).stream()
                .map(item -> level(item, quantities.getOrDefault(item.getId(), BigDecimal.ZERO)))
                .toList();
    }

    /** One item's quantity and status (0 without a row). IllegalArgumentException for an item that is not
     *  PROVISIONS: personal items have no stock. */
    @Transactional(readOnly = true)
    public Level level(Item item) {
        if (item.getCategory() != ItemCategory.PROVISIONS) {
            throw new IllegalArgumentException("Only provisions have stock");
        }
        return level(item, levelRepository.findByItemId(item.getId()).map(StockLevel::getQuantity)
                .orElse(BigDecimal.ZERO));
    }

    private Level level(Item item, BigDecimal quantity) {
        return new Level(item, quantity, status(quantity));
    }

    /** The item's movements, newest first. */
    @Transactional(readOnly = true)
    public List<StockMovement> history(Item item) {
        return movementRepository.findAllByItemIdOrderByAtDescIdDesc(item.getId());
    }

    /** When the ship's provisions were last counted (the latest COUNT movement), or null when never. */
    @Transactional(readOnly = true)
    public Instant lastCount(Ship ship) {
        return movementRepository.lastAt(ship.getId(), StockMovementType.COUNT);
    }

    /** Low plus Out of stock on the ship of the user with this email: the Chief cook's Stores counter. One query. */
    @Transactional(readOnly = true)
    public long belowThresholdOnShipOf(String email) {
        return levelRepository.countBelowOnShipOf(ItemCategory.PROVISIONS, lowThreshold, email);
    }
}
