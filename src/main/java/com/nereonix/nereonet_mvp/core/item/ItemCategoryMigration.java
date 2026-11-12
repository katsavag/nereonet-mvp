package com.nereonix.nereonet_mvp.core.item;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * P32: item.category was free text ("Dry goods", or null) and is now an ItemCategory. Hibernate (ddl-auto: update)
 * keeps the old column as it is, so on startup every item whose category is missing or not an ItemCategory name
 * becomes PROVISIONS (every item before P32 was a galley provision), and the column is made not null. Plain SQL, before
 * anything loads an Item. Changes nothing on a new database or once done, so it simply runs on every startup.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ItemCategoryMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ItemCategoryMigration.class);

    private final JdbcTemplate jdbc;

    public ItemCategoryMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        migrate();
    }

    /** Sets PROVISIONS where the category is missing or not valid. Returns the number of items changed. */
    @Transactional
    public int migrate() {
        int changed = jdbc.update("""
                update item set category = 'PROVISIONS'
                where category is null or category not in ('PROVISIONS', 'PERSONAL_ITEMS')""");
        if (changed > 0) {
            jdbc.execute("alter table item alter column category set not null");
            log.info("Item categories migrated: {} items set to PROVISIONS", changed);
        }
        return changed;
    }
}
