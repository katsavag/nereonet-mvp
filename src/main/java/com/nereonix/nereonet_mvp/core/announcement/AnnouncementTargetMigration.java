package com.nereonix.nereonet_mvp.core.announcement;

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
 * P28: before, an announcement had one nullable ship_id (null: all ships). Hibernate adds the new fleet_wide column
 * (false) and the announcement_ship table but never drops a column, so on startup, while announcement.ship_id still
 * exists, this moves each row to the new model in one transaction: ship_id set gives one announcement_ship row,
 * ship_id null gives fleet_wide = true; then ship_id is dropped, so it runs once. Nothing to do on a new database.
 * Runs before the other startup runners.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AnnouncementTargetMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementTargetMigration.class);

    private final JdbcTemplate jdbc;

    public AnnouncementTargetMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        migrate();
    }

    /** Moves the rows saved with announcement.ship_id, if that column exists. Returns the number of rows moved. */
    @Transactional
    public int migrate() {
        Integer columns = jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where lower(table_schema) = lower(current_schema())
                  and lower(table_name) = 'announcement' and lower(column_name) = 'ship_id'""", Integer.class);
        if (columns == null || columns == 0) {
            return 0;
        }
        // Both skip rows that already have targets, so a run that stopped half way can simply run again.
        int toShips = jdbc.update("""
                insert into announcement_ship (announcement_id, ship_id)
                select a.id, a.ship_id from announcement a
                where a.ship_id is not null
                  and not exists (select 1 from announcement_ship t where t.announcement_id = a.id)""");
        int toFleet = jdbc.update("""
                update announcement set fleet_wide = true
                where ship_id is null
                  and not exists (select 1 from announcement_ship t where t.announcement_id = announcement.id)""");
        jdbc.execute("alter table announcement drop column ship_id");
        log.info("Announcement targets migrated: {} to one ship, {} to all ships; announcement.ship_id dropped",
                toShips, toFleet);
        return toShips + toFleet;
    }
}
