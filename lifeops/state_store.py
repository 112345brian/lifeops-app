"""Durable LifeOps state, backed by SQLite (see lifeops/db.py for the
connection/schema layer). Preserves the original file-based public API
(load_json/save_json_atomic/append_line/logs_path) so existing callers
don't need to change just because the backend moved from individual JSON
files to one shared database -- each function still takes a `path`-shaped
string, and derives a stable storage key from its basename.
"""
import json, os, datetime, sqlite3

from . import db, history


def logs_path(filename):
    return os.path.join(history.private_logs_dir(), filename)


def _key_from_path(path):
    return os.path.splitext(os.path.basename(path))[0]


def load_json(path, default=None, require_type=None):
    """Reads never raise -- degrades to `default` on anything from a missing
    key to a locked/corrupt database, same broad safety net the old
    file-based load_json had (it caught FileNotFoundError/JSONDecodeError/
    OSError -- any I/O hiccup was a soft miss, never a crash). A shared
    state.db makes lock contention a real possibility across unrelated
    domains' writes in a way independent per-file writes never were, so
    this matters more now, not less."""
    key = _key_from_path(path)
    try:
        conn = db.state_conn()
        try:
            row = conn.execute("SELECT value FROM kv_state WHERE key=?", (key,)).fetchone()
        finally:
            conn.close()
    except sqlite3.Error:
        return default
    if row is None:
        return default
    try:
        data = json.loads(row[0])
    except json.JSONDecodeError:
        return default
    if require_type is not None and not isinstance(data, require_type):
        return default
    return data


def save_json_atomic(path, data):
    key = _key_from_path(path)
    now = datetime.datetime.now().isoformat(timespec="seconds")
    conn = db.state_conn()
    try:
        conn.execute(
            "INSERT INTO kv_state(key, value, updated_at) VALUES (?, ?, ?) "
            "ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at",
            (key, json.dumps(data), now),
        )
        conn.commit()
    finally:
        conn.close()


def delete_key(path):
    """Removes a kv_state entry entirely -- the SQL equivalent of deleting a
    state file outright (as opposed to save_json_atomic's overwrite), used
    where "no entry" and "an empty dict" are meaningfully different (e.g. a
    pending-approval file that must vanish once cleared, not just go empty)."""
    key = _key_from_path(path)
    conn = db.state_conn()
    try:
        conn.execute("DELETE FROM kv_state WHERE key=?", (key,))
        conn.commit()
    finally:
        conn.close()


def append_line(path, line):
    """Appends one opaque line under the log named by `path`'s basename.
    Kept string-in-string-out (not JSON-aware) to match every existing
    caller, which already passes a pre-serialized JSON line."""
    log_name = _key_from_path(path)
    now = datetime.datetime.now().isoformat(timespec="seconds")
    conn = db.state_conn()
    try:
        conn.execute(
            "INSERT INTO log_lines(log_name, line, inserted_at) VALUES (?, ?, ?)",
            (log_name, line, now),
        )
        conn.commit()
    finally:
        conn.close()


def recent_lines(path, n):
    """Up to the newest `n` lines previously appended via append_line under
    this same path's key, newest first."""
    log_name = _key_from_path(path)
    conn = db.state_conn()
    try:
        rows = conn.execute(
            "SELECT line FROM log_lines WHERE log_name=? ORDER BY id DESC LIMIT ?",
            (log_name, n),
        ).fetchall()
    finally:
        conn.close()
    return [r[0] for r in rows]


def trim_log(path, keep_newest):
    """Deletes everything but the newest `keep_newest` rows for this log --
    the SQL equivalent of a size-based rewrite-to-tail maintenance pass."""
    log_name = _key_from_path(path)
    conn = db.state_conn()
    try:
        conn.execute(
            "DELETE FROM log_lines WHERE log_name=? AND id NOT IN ("
            "SELECT id FROM log_lines WHERE log_name=? ORDER BY id DESC LIMIT ?)",
            (log_name, log_name, keep_newest),
        )
        conn.commit()
    finally:
        conn.close()
