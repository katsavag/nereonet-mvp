package com.nereonix.nereonet_mvp.announcement;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.nereonix.nereonet_mvp.core.announcement.Announcement;
import com.nereonix.nereonet_mvp.core.announcement.AnnouncementRepository;
import com.nereonix.nereonet_mvp.core.announcement.AnnouncementTargetMigration;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;

// P28: rows saved before targets were a set keep the target they had. A database of its own (the schema is changed
// and the rows are committed), closed afterwards.
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:announcementMigration;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@DirtiesContext
class AnnouncementTargetMigrationTest {

    @Autowired
    AnnouncementTargetMigration migration;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ShipRepository shipRepository;

    @Autowired
    AnnouncementRepository announcementRepository;

    @Autowired
    TransactionTemplate transaction;

    @Test
    void existingAnnouncementsKeepTheirTarget() {
        // Nothing to do on a database created with the new model.
        assertThat(migration.migrate()).isZero();

        Ship demo = new Ship();
        demo.setImoNumber("9074729");
        demo.setName("MV Demo");
        demo.setCreatedAt(Instant.now());
        shipRepository.save(demo);

        // The schema and two rows as they were before: ship_id set (one ship) and null (all ships).
        jdbc.execute("alter table announcement add column ship_id bigint");
        Long toShip = insertOld("To MV Demo", demo.getId());
        Long toFleet = insertOld("To all ships", null);

        assertThat(migration.migrate()).isEqualTo(2);

        transaction.executeWithoutResult(status -> {
            Announcement ship = announcementRepository.findById(toShip).orElseThrow();
            assertThat(ship.isFleetWide()).isFalse();
            assertThat(ship.getTargetShips()).extracting(Ship::getId).containsExactly(demo.getId());
            assertThat(ship.getOnlyShip().getName()).isEqualTo("MV Demo");
            Announcement fleet = announcementRepository.findById(toFleet).orElseThrow();
            assertThat(fleet.isFleetWide()).isTrue();
            assertThat(fleet.getTargetShips()).isEmpty();
        });
        assertThat(announcementRepository.countForShip(demo.getId())).isEqualTo(2);
        // The old column is gone, so it runs once.
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where lower(table_name) = 'announcement' and lower(column_name) = 'ship_id'""", Integer.class)).isZero();
        assertThat(migration.migrate()).isZero();
    }

    private Long insertOld(String title, Long shipId) {
        jdbc.update("insert into announcement (title, body, created_at, ship_id) values (?, ?, ?, ?)",
                title, "Please read.", Timestamp.from(Instant.now()), shipId);
        return jdbc.queryForObject("select id from announcement where title = ?", Long.class, title);
    }
}
