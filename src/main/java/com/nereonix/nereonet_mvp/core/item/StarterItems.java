package com.nereonix.nereonet_mvp.core.item;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.form.ItemUnits;
import com.nereonix.nereonet_mvp.core.ship.Ship;

/**
 * The standard galley list a vessel starts with: the rows of starter/galley-items.csv (name;unit;place, after the
 * heading row), each an active PROVISIONS item. The file is read and checked once, when the application starts: a
 * wrong row (not three columns, no name, a name or place too long, a unit that is not one of ItemUnits, a name
 * twice) stops the startup with a message that names the row, so adding the list to a vessel cannot fail on the file.
 */
@Component
public class StarterItems {

    static final String FILE = "starter/galley-items.csv";

    private record Row(String name, String unit, String place) {
    }

    private final ItemRepository itemRepository;
    private final AuditService auditService;
    private final List<Row> rows;

    public StarterItems(ItemRepository itemRepository, AuditService auditService) {
        this.itemRepository = itemRepository;
        this.auditService = auditService;
        this.rows = read();
    }

    /**
     * Adds the standard list to the vessel and records one ITEMS_STARTED on it ("62 items"). A name the vessel
     * already has, ignoring case, is skipped, so a second run adds nothing (and records nothing). No stock levels:
     * the quantities start empty. Returns the number of items added.
     */
    @Transactional
    public int addTo(Ship ship) {
        Set<String> taken = new HashSet<>();
        for (Item item : itemRepository.findAllByShipIdOrderByNameAsc(ship.getId())) {
            taken.add(item.getName().toLowerCase(Locale.ROOT));
        }
        List<Item> items = new ArrayList<>();
        Instant now = Instant.now();
        for (Row row : rows) {
            if (!taken.add(row.name().toLowerCase(Locale.ROOT))) {
                continue;
            }
            Item item = new Item();
            item.setShip(ship);
            item.setName(row.name());
            item.setUnit(row.unit());
            item.setCategory(ItemCategory.PROVISIONS);
            item.setPlace(row.place());
            item.setActive(true);
            item.setCreatedAt(now);
            items.add(item);
        }
        if (!items.isEmpty()) {
            itemRepository.saveAll(items);
            auditService.record("ITEMS_STARTED", ship, items.size() + " items");
        }
        return items.size();
    }

    private static List<Row> read() {
        List<Row> rows = new ArrayList<>();
        Set<String> names = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ClassPathResource(FILE).getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int number = 0;
            while ((line = reader.readLine()) != null) {
                number++;
                // Row 1 is the heading; an empty line is skipped.
                if (number == 1 || line.isBlank()) {
                    continue;
                }
                String[] cells = line.split(";", -1);
                if (cells.length != 3) {
                    throw wrong(number, line, "expected name;unit;place");
                }
                String name = cells[0].strip();
                String unit = cells[1].strip();
                String place = cells[2].strip();
                if (name.isEmpty() || name.length() > Item.NAME_MAX_LENGTH) {
                    throw wrong(number, line, "the name must have 1 to " + Item.NAME_MAX_LENGTH + " characters");
                }
                if (!ItemUnits.listed(unit)) {
                    throw wrong(number, line, "unknown unit \"" + unit + "\", use one of " + ItemUnits.ALL);
                }
                if (place.length() > Item.PLACE_MAX_LENGTH) {
                    throw wrong(number, line, "the place must have at most " + Item.PLACE_MAX_LENGTH + " characters");
                }
                if (!names.add(name.toLowerCase(Locale.ROOT))) {
                    throw wrong(number, line, "the name is in the file twice");
                }
                rows.add(new Row(name, unit, place.isEmpty() ? null : place));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + FILE, e);
        }
        return List.copyOf(rows);
    }

    private static IllegalStateException wrong(int number, String line, String why) {
        return new IllegalStateException(FILE + ", row " + number + " (\"" + line + "\"): " + why);
    }
}
