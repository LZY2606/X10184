package com.regforge.store;

import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class SchemaInitializer implements CommandLineRunner {

    private final JdbcTemplate jdbc;

    public SchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS models (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    version TEXT NOT NULL,
                    content_hash TEXT NOT NULL,
                    canonical TEXT NOT NULL,
                    yaml TEXT NOT NULL,
                    created_at TEXT NOT NULL DEFAULT (datetime('now'))
                )
                """);
    }
}
