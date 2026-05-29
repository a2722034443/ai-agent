package com.localagent.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseCompatibilityRunner implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;

    public DatabaseCompatibilityRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbcTemplate.execute("alter table plan_session modify column status varchar(32) not null");
        } catch (Exception ignored) {
            // H2 tests and freshly created schemas may not need this MySQL compatibility migration.
        }
    }
}
