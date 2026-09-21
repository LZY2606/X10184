package com.regforge.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
public class ModelRepository {

    private final JdbcTemplate jdbc;

    public ModelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ModelRecord> MAPPER = (rs, n) -> new ModelRecord(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("version"),
            rs.getString("content_hash"),
            rs.getString("canonical"),
            rs.getString("yaml"),
            rs.getString("created_at"));

    public ModelRecord insert(String name, String version, String hash, String canonical, String yaml) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO models(name, version, content_hash, canonical, yaml) VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, version);
            ps.setString(3, hash);
            ps.setString(4, canonical);
            ps.setString(5, yaml);
            return ps;
        }, kh);
        long id = kh.getKey() == null ? -1L : kh.getKey().longValue();
        return findById(id).orElseThrow();
    }

    public Optional<ModelRecord> findByHash(String hash) {
        return jdbc.query("SELECT * FROM models WHERE content_hash = ? ORDER BY id LIMIT 1", MAPPER, hash)
                .stream().findFirst();
    }

    public Optional<ModelRecord> findById(long id) {
        return jdbc.query("SELECT * FROM models WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public List<ModelRecord> findAll() {
        return jdbc.query("SELECT * FROM models ORDER BY id", MAPPER);
    }
}
