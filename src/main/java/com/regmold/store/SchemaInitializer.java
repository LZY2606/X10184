package com.regmold.store;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class SchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    public SchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) throws java.io.IOException {
        String url = null;
        if (jdbc.getDataSource() instanceof org.springframework.jdbc.datasource.AbstractDriverBasedDataSource ds) {
            url = ds.getUrl();
        }
        if (url != null && url.startsWith("jdbc:sqlite:")) {
            java.io.File file = new java.io.File(url.substring("jdbc:sqlite:".length()));
            if (file.getParentFile() != null) {
                java.nio.file.Files.createDirectories(file.getParentFile().toPath());
            }
        }
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS models (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  name TEXT NOT NULL UNIQUE,
                  created_at TEXT NOT NULL DEFAULT (datetime('now'))
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS revisions (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  model_id INTEGER NOT NULL,
                  revision INTEGER NOT NULL,
                  semantic_hash TEXT NOT NULL,
                  canonical_yaml TEXT NOT NULL,
                  original_yaml TEXT NOT NULL,
                  valid INTEGER NOT NULL,
                  created_at TEXT NOT NULL DEFAULT (datetime('now')),
                  UNIQUE(model_id, revision),
                  FOREIGN KEY(model_id) REFERENCES models(id)
                )
                """);
    }
}
