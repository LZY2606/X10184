package com.regforge.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public class ModelStore {

    private static final RowMapper<VersionRow> ROW_MAPPER = (rs, n) -> new VersionRow(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("hash"),
            rs.getString("yaml"),
            rs.getString("canonical"),
            rs.getString("diagnostics"),
            Instant.parse(rs.getString("created_at")));

    private final JdbcTemplate jdbc;

    public ModelStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public VersionRow insert(String name, String hash, String yaml, String canonical,
                             String diagnostics) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO versions(name, hash, yaml, canonical, diagnostics, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, name, hash, yaml, canonical, diagnostics, now.toString());
        Long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        return new VersionRow(id == null ? -1 : id, name, hash, yaml, canonical, diagnostics, now);
    }

    public List<VersionRow> list() {
        return jdbc.query(
                "SELECT id, name, hash, yaml, canonical, diagnostics, created_at "
                        + "FROM versions ORDER BY id", ROW_MAPPER);
    }

    public VersionRow get(long id) {
        List<VersionRow> rows = jdbc.query(
                "SELECT id, name, hash, yaml, canonical, diagnostics, created_at "
                        + "FROM versions WHERE id = ?", ROW_MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long count() {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM versions", Long.class);
        return value == null ? 0 : value;
    }
}
