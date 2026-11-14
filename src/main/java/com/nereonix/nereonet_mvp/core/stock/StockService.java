package com.nereonix.nereonet_mvp.core.stock;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemCategory;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrderService;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/**
 * The stock of a ship's provisions (P52), shared by the Chief cook's Stores pages and, later, the request page, the
 * receipt on board and the office. Only PROVISIONS items have stock; personal items never appear. An item without a
 * StockLevel row has quantity 0. P53: the Chief cook counts an item by hand (count); receipts come with their own page.
 * Every change of an item's stock locks the item's row first, so two changes of one item never interleave.
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
    private final AuditService auditService;
    private final EntityManager entityManager;
    private final BigDecimal lowThreshold;

    /** The ship's history (P53) shows this many movements a page. */
    public static final int HISTORY_PAGE_SIZE = 50;

    public StockService(ItemRepository itemRepository, StockLevelRepository levelRepository,
                        StockMovementRepository movementRepository, AuditService auditService,
                        EntityManager entityManager, @Value("${app.stock.low-threshold:10}") BigDecimal lowThreshold) {
        this.itemRepository = itemRepository;
        this.levelRepository = levelRepository;
        this.movementRepository = movementRepository;
        this.auditService = auditService;
        this.entityManager = entityManager;
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

    /**
     * The Chief cook counts an item by hand (P53): its stock becomes the counted quantity at once, and a COUNT movement
     * keeps what the store expected (before) and what was counted (after), by the cook, now. A count equal to the
     * expected quantity is saved too: it confirms the count. Recorded in the audit log ("Basmati rice: 10 -> 7 kg").
     * No approval, no draft. The item's row is locked first (as ShipOrderService locks the ship's row): two tabs
     * counting the same item are saved one after the other, each "before" the quantity the other left. The lock is on
     * the item, not its StockLevel, because an item never counted has no StockLevel row to lock yet.
     * Empty (nothing changed) when the item is not an active PROVISIONS item of the cook's ship.
     * IllegalArgumentException for a quantity that is not valid (isValidQuantity) or a note longer than
     * StockMovement.NOTE_MAX_LENGTH; AccessDeniedException when the user is not an enabled Chief cook with a ship.
     */
    @Transactional
    public Optional<Level> count(User cook, Long itemId, BigDecimal counted, String note) {
        if (cook == null || cook.getRole() != Role.CHIEF_COOK || !cook.isEnabled() || cook.getShip() == null) {
            throw new AccessDeniedException("Only the Chief cook of the ship counts its stores");
        }
        if (!isValidQuantity(counted)) {
            throw new IllegalArgumentException("A stock quantity must be zero or more, with at most 3 decimals");
        }
        if (note != null && note.strip().length() > StockMovement.NOTE_MAX_LENGTH) {
            throw new IllegalArgumentException("A note has at most " + StockMovement.NOTE_MAX_LENGTH + " characters");
        }
        Item item = itemRepository.findByIdAndShipId(itemId, cook.getShip().getId()).orElse(null);
        if (item == null) {
            return Optional.empty();
        }
        // Locked, then read again: what another tab changed before the lock is seen here.
        entityManager.lock(item, LockModeType.PESSIMISTIC_WRITE);
        entityManager.refresh(item);
        if (item.getCategory() != ItemCategory.PROVISIONS || !item.isActive()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        StockLevel level = levelRepository.findByItemId(item.getId()).orElseGet(() -> {
            StockLevel created = new StockLevel();
            created.setItem(item);
            return created;
        });
        BigDecimal before = level.getQuantity();
        level.setQuantity(counted);
        level.setUpdatedAt(now);
        levelRepository.save(level);
        movementRepository.save(StockMovement.of(item, StockMovementType.COUNT, before, counted, cook, now, note,
                null));
        auditService.record("STOCK_COUNTED", item.getShip(),
                item.getName() + ": " + plain(before) + " -> " + plain(counted) + " " + item.getUnit(), cook);
        return Optional.of(level(item, counted));
    }

    private static String plain(BigDecimal quantity) {
        return quantity.stripTrailingZeros().toPlainString();
    }

    /**
     * Every movement of the ship's provisions (P53), newest first, HISTORY_PAGE_SIZE a page (page from 0). type: only
     * that type, or all when null; from and to: UTC days, both included, or open when null.
     */
    @Transactional(readOnly = true)
    public Page<StockMovement> history(Ship ship, StockMovementType type, LocalDate from, LocalDate to, int page) {
        List<StockMovementType> types = type == null ? List.of(StockMovementType.values()) : List.of(type);
        Instant start = from == null ? Instant.EPOCH : from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = to == null ? Instant.parse("9999-12-31T00:00:00Z")
                : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return movementRepository.findOnShip(ship.getId(), types, start, end,
                PageRequest.of(Math.max(page, 0), HISTORY_PAGE_SIZE));
    }

    /** Low plus Out of stock on the ship of the user with this email: the Chief cook's Stores counter. One query. */
    @Transactional(readOnly = true)
    public long belowThresholdOnShipOf(String email) {
        return levelRepository.countBelowOnShipOf(ItemCategory.PROVISIONS, lowThreshold, email);
    }
}
