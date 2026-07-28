"""Shared SQLite connection + schema management for LifeOps runtime state.

Two databases, mirroring the boundary the old flat-file design already drew:

- ``state.db`` under ``private/logs/`` -- durable, tracked state (everything
  that used to be an individual JSON/JSONL file in the private submodule).
  Backed up out-of-git by runner.py's periodic snapshot step (see
  ``runner._backup_state_db``) rather than committed to git, since a
  binary SQLite file doesn't diff/compact the way the old JSONL appends did.
- ``local.db`` under the top-level (gitignored) ``logs/`` -- purely
  ephemeral, never-backed-up state (FCM device token, weather grid cache),
  matching what already lived in the gitignored ``logs/`` dir before this
  migration.

Both are opened in WAL mode so the long-lived web.py server process and
runner.py's short-lived periodic subprocess invocations can read/write
concurrently without the old file-replace design's reliance on atomic
rename -- WAL allows any number of concurrent readers alongside a single
writer, and ``timeout=30`` makes a transient writer-lock collision retry
instead of immediately raising ``sqlite3.OperationalError: database is
locked``.
"""
import datetime, json, os, sqlite3

from . import history

_SCHEMA = """
CREATE TABLE IF NOT EXISTS kv_state (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS log_lines (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    log_name TEXT NOT NULL,
    line TEXT NOT NULL,
    inserted_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_log_lines_name ON log_lines(log_name, id);
CREATE TABLE IF NOT EXISTS history_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    action TEXT NOT NULL,
    ts TEXT NOT NULL,
    source TEXT,
    meta TEXT
);
CREATE INDEX IF NOT EXISTS idx_history_action_ts ON history_events(action, ts);
"""


def _connect(path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    conn = sqlite3.connect(path, timeout=30)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=NORMAL")
    conn.executescript(_SCHEMA)
    return conn


def state_conn():
    """The tracked/backed-up DB -- private/logs/state.db. A function, not a
    cached connection, computed at call time via history.private_logs_dir()
    (itself call-time, not import-time) so tests that monkeypatch
    history.ROOT to a sandbox tmp_path get a fresh, isolated DB automatically
    -- the same seam the old per-file design relied on."""
    return _connect(os.path.join(history.private_logs_dir(), "state.db"))


def local_conn():
    """The untracked/local-only DB -- logs/local.db. Mirrors the old
    top-level (gitignored) logs/ dir's ephemeral files (fcm_token.json,
    weather_grid_cache.json) that were deliberately excluded from the
    private-submodule backup."""
    return _connect(os.path.join(history.ROOT, "logs", "local.db"))


def local_get(key, default=None):
    """kv_state read against the local (never-backed-up) DB -- for the
    handful of ephemeral values (FCM device token, weather grid cache) that
    intentionally live outside the tracked state.db. Degrades to `default`
    on any read/decode failure -- the original file-based readers this
    replaced (fcm._device_token, weather._load_grid_cache) each caught
    their own I/O errors just as broadly."""
    try:
        conn = local_conn()
        try:
            row = conn.execute("SELECT value FROM kv_state WHERE key=?", (key,)).fetchone()
        finally:
            conn.close()
    except sqlite3.Error:
        return default
    if row is None:
        return default
    try:
        return json.loads(row[0])
    except json.JSONDecodeError:
        return default


def local_set(key, value):
    # Python-side timestamp (local time), not SQL's datetime('now') (UTC) --
    # matches state_store's kv_state writes so updated_at means the same
    # thing in both databases.
    now = datetime.datetime.now().isoformat(timespec="seconds")
    conn = local_conn()
    try:
        conn.execute(
            "INSERT INTO kv_state(key, value, updated_at) VALUES (?, ?, ?) "
            "ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at",
            (key, json.dumps(value), now),
        )
        conn.commit()
    finally:
        conn.close()
