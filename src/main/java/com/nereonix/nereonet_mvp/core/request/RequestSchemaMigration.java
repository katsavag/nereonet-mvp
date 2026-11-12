package com.nereonix.nereonet_mvp.core.request;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.notification.NotificationType;

/**
 * P33: Hibernate (ddl-auto: update) never changes a column it created. On a database from before P33 that leaves
 * request_line.item_id not null (a line for a new item has no item), and the check constraints PostgreSQL got for
 * item_request.status and notification.type without CANCELLED and REQUEST. On startup this drops the not null and
 * re-creates both constraints from the enums. Each statement gives the same result when run again, so it runs on
 * every startup; on a new database it changes nothing. Plain SQL, before anything saves a request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestSchemaMigration implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    public RequestSchemaMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        migrate();
    }

    @Transactional
    public void migrate() {
        jdbc.execute("alter table request_line alter column item_id drop not null");
        recreateCheck("item_request", "status", names(RequestStatus.values()));
        recreateCheck("notification", "type", names(NotificationType.values()));
    }

    // The constraint PostgreSQL names <table>_<column>_check for Hibernate's inline check.
    private void recreateCheck(String table, String column, String values) {
        String name = table + "_" + column + "_check";
        jdbc.execute("alter table " + table + " drop constraint if exists " + name);
        jdbc.execute("alter table " + table + " add constraint " + name + " check (" + column + " in (" + values + "))");
    }

    private static String names(Enum<?>[] values) {
        return Arrays.stream(values).map(value -> "'" + value.name() + "'").collect(Collectors.joining(","));
    }
}
