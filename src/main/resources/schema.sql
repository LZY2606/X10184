CREATE TABLE IF NOT EXISTS versions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    hash TEXT NOT NULL,
    yaml TEXT NOT NULL,
    canonical TEXT NOT NULL,
    diagnostics TEXT NOT NULL,
    created_at TEXT NOT NULL
);
