package com.regmold.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class ProjectStore {

    private final JdbcTemplate jdbc;

    public ProjectStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long createProject(String name) {
        jdbc.update("INSERT INTO project(name, created) VALUES (?, datetime('now'))", name);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    public List<Map<String, Object>> listProjects() {
        return jdbc.queryForList(
                "SELECT p.id, p.name, p.created, COUNT(r.id) AS revisions " +
                "FROM project p LEFT JOIN revision r ON r.project_id = p.id GROUP BY p.id ORDER BY p.id");
    }

    public Map<String, Object> project(long id) {
        return jdbc.queryForMap("SELECT * FROM project WHERE id = ?", id);
    }

    /** Store the original YAML verbatim plus its canonical fingerprint. */
    public int addRevision(long projectId, String yaml, String fingerprint, String message) {
        Integer next = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) + 1 FROM revision WHERE project_id = ?", Integer.class, projectId);
        jdbc.update("INSERT INTO revision(project_id, version, yaml, fingerprint, message, created) " +
                        "VALUES (?, ?, ?, ?, ?, datetime('now'))",
                projectId, next, yaml, fingerprint, message == null ? "" : message);
        return next;
    }

    public List<Map<String, Object>> listRevisions(long projectId) {
        return jdbc.queryForList(
                "SELECT id, version, fingerprint, message, created FROM revision WHERE project_id = ? ORDER BY version",
                projectId);
    }

    public Map<String, Object> revision(long projectId, int version) {
        return jdbc.queryForMap(
                "SELECT * FROM revision WHERE project_id = ? AND version = ?", projectId, version);
    }

    public Integer latestVersion(long projectId) {
        return jdbc.queryForObject("SELECT MAX(version) FROM revision WHERE project_id = ?",
                Integer.class, projectId);
    }
}
