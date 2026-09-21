CREATE TABLE IF NOT EXISTS project (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    created TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS revision (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id INTEGER NOT NULL REFERENCES project(id),
    version INTEGER NOT NULL,
    yaml TEXT NOT NULL,
    fingerprint TEXT NOT NULL,
    message TEXT NOT NULL DEFAULT '',
    created TEXT NOT NULL,
    UNIQUE(project_id, version)
);
