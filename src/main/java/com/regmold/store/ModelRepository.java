package com.regmold.store;

import com.regmold.domain.Model;
import com.regmold.io.ModelCanonicalizer;
import com.regmold.io.YamlModelParser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
public class ModelRepository {

    private final JdbcTemplate jdbc;

    public ModelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        init();
    }

    private void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS model_revision (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    version TEXT NOT NULL,
                    hash TEXT NOT NULL,
                    source_yaml TEXT NOT NULL,
                    canonical_yaml TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS model_generation (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    revision_id INTEGER NOT NULL,
                    target_dir TEXT,
                    created_at TEXT NOT NULL,
                    FOREIGN KEY (revision_id) REFERENCES model_revision(id)
                )
                """);
    }

    public long insert(Model model, String canonical) {
        String hash = ModelCanonicalizer.hash(canonical);
        jdbc.update("INSERT INTO model_revision(name, version, hash, source_yaml, canonical_yaml, created_at)"
                        + " VALUES (?,?,?,?,?,?)",
                model.name(), model.version(), hash, model.sourceYaml(), canonical,
                Timestamp.from(Instant.now()).toString());
        Long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        return id == null ? 0L : id;
    }

    public long findIdByHash(String hash) {
        List<Long> ids = jdbc.query("SELECT id FROM model_revision WHERE hash = ? ORDER BY id LIMIT 1",
                (rs, i) -> rs.getLong(1), hash);
        return ids.isEmpty() ? -1L : ids.get(0);
    }

    public Model loadRevision(long id) {
        List<Model> models = jdbc.query(
                "SELECT * FROM model_revision WHERE id = ?", mapper(), id);
        return models.isEmpty() ? null : models.get(0);
    }

    public List<Map<String, Object>> listRevisions() {
        return jdbc.queryForList(
                "SELECT id, name, version, hash, created_at FROM model_revision ORDER BY id");
    }

    public String canonical(long id) {
        List<String> v = jdbc.query("SELECT canonical_yaml FROM model_revision WHERE id = ?",
                (rs, i) -> rs.getString(1), id);
        return v.isEmpty() ? null : v.get(0);
    }

    public void recordGeneration(long revisionId, String targetDir) {
        jdbc.update("INSERT INTO model_generation(revision_id, target_dir, created_at) VALUES (?,?,?)",
                revisionId, targetDir, Timestamp.from(Instant.now()).toString());
    }

    private RowMapper<Model> mapper() {
        return (rs, n) -> {
            Model parsed = YamlModelParser.parse(rs.getString("canonical_yaml"));
            Model normalized = ModelCanonicalizer.normalize(parsed);
            long id = rs.getLong("id");
            return normalized.withRevision(id, rs.getString("canonical_yaml"));
        };
    }
}
