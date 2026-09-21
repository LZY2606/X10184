package com.regmold.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class ModelRepository {

    private final JdbcTemplate jdbc;

    public ModelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Long ensureModel(String name) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM models WHERE name = ?", Long.class, name);
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        jdbc.update("INSERT INTO models(name) VALUES(?)", name);
        return jdbc.queryForObject("SELECT id FROM models WHERE name = ?", Long.class, name);
    }

    public long addRevision(String name, String hash, String canonicalYaml, String originalYaml, boolean valid) {
        Long modelId = ensureModel(name);
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(revision), 0) FROM revisions WHERE model_id = ?",
                Integer.class, modelId);
        int revision = (max == null ? 0 : max) + 1;
        jdbc.update("""
                INSERT INTO revisions(model_id, revision, semantic_hash, canonical_yaml, original_yaml, valid)
                VALUES(?, ?, ?, ?, ?, ?)
                """, modelId, revision, hash, canonicalYaml, originalYaml, valid ? 1 : 0);
        return revision;
    }

    public List<Map<String, Object>> listModels() {
        return jdbc.queryForList("""
                SELECT m.id AS id, m.name AS name, m.created_at AS created_at,
                       COUNT(r.id) AS revisions,
                       (SELECT semantic_hash FROM revisions WHERE model_id = m.id
                        ORDER BY revision DESC LIMIT 1) AS latest_hash
                FROM models m LEFT JOIN revisions r ON r.model_id = m.id
                GROUP BY m.id ORDER BY m.name
                """);
    }

    public List<Map<String, Object>> listRevisions(String name) {
        return jdbc.queryForList("""
                SELECT r.revision AS revision, r.semantic_hash AS semantic_hash,
                       r.valid AS valid, r.created_at AS created_at
                FROM revisions r JOIN models m ON m.id = r.model_id
                WHERE m.name = ? ORDER BY r.revision DESC
                """, name);
    }

    public Map<String, Object> getRevision(String name, int revision) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.revision AS revision, r.semantic_hash AS semantic_hash,
                       r.canonical_yaml AS canonical_yaml, r.original_yaml AS original_yaml,
                       r.valid AS valid, r.created_at AS created_at
                FROM revisions r JOIN models m ON m.id = r.model_id
                WHERE m.name = ? AND r.revision = ?
                """, name, revision);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
