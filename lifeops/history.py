"""Append-only completion history — the durable record of WHEN things happened.

This is the system's memory. ntfy signals expire (~12h cache) and FlowSavvy
task-state is lossy, so completions are logged here permanently. Every engine
reads cadence ("when was X last done", "how many gyms this week") from here, not
from ephemeral sources.

Backed by the `history_events` SQLite table (see lifeops/db.py) rather than a
flat history.jsonl -- same durable-forever semantics, but `last`/`days_with`
are now indexed queries instead of a full-file scan on every call.
"""
import json, os, datetime, sqlite3

from . import db

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def private_logs_dir():
    """Where durable app state lives — inside the `private` submodule
    (tracked, backed up out-of-git by runner.py's periodic snapshot step)
    rather than the top-level `logs/`, which stays gitignored for
    ephemeral/local-only files (fcm_token.json, weather_grid_cache.json,
    lifeops.lock, web.log).

    A function, not a module constant: tests monkeypatch history.ROOT to
    redirect state into tmp_path (see tests/test_*.py), and a frozen
    constant computed once at import time would silently ignore that and
    keep writing into the real private/logs/ during test runs.
    """
    return os.path.join(ROOT, "private", "logs")


def append(action, ts=None, source="", meta=None):
    rec_ts = ts or datetime.datetime.now().isoformat(timespec="seconds")
    conn = db.state_conn()
    try:
        conn.execute(
            "INSERT INTO history_events(action, ts, source, meta) VALUES (?, ?, ?, ?)",
            (action, rec_ts, source, json.dumps(meta) if meta else None),
        )
        conn.commit()
    finally:
        conn.close()
    rec = {"action": action, "ts": rec_ts, "source": source}
    if meta:
        rec["meta"] = meta
    return rec


def _row_to_event(row):
    action, ts, source, meta = row
    rec = {"action": action, "ts": ts, "source": source}
    if meta:
        rec["meta"] = json.loads(meta)
    return rec


def events(action=None):
    # Degrades to [] on a locked/corrupt database rather than raising --
    # same "any I/O hiccup is a soft miss" contract state_store.load_json
    # restores (see its docstring); every engine in this app reads cadence
    # from here, so a transient lock must not crash a whole domain run.
    try:
        conn = db.state_conn()
        try:
            if action is None:
                rows = conn.execute(
                    "SELECT action, ts, source, meta FROM history_events ORDER BY id"
                ).fetchall()
            else:
                rows = conn.execute(
                    "SELECT action, ts, source, meta FROM history_events WHERE action=? ORDER BY id",
                    (action,),
                ).fetchall()
        finally:
            conn.close()
    except sqlite3.Error:
        return []
    return [_row_to_event(r) for r in rows]


def last(action):
    try:
        conn = db.state_conn()
        try:
            row = conn.execute(
                "SELECT MAX(ts) FROM history_events WHERE action=?", (action,)
            ).fetchone()
        finally:
            conn.close()
    except sqlite3.Error:
        return None
    return row[0]


def days_with(action, start_date, end_date):
    """Distinct dates (YYYY-MM-DD) an action happened within [start,end] inclusive."""
    try:
        conn = db.state_conn()
        try:
            rows = conn.execute(
                "SELECT DISTINCT substr(ts, 1, 10) FROM history_events "
                "WHERE action=? AND substr(ts, 1, 10) BETWEEN ? AND ?",
                (action, start_date, end_date),
            ).fetchall()
        finally:
            conn.close()
    except sqlite3.Error:
        return set()
    return {r[0] for r in rows}


def remove_day(action, date):
    """Delete every entry for `action` on `date` (YYYY-MM-DD), regardless of
    source. Used to undo a manual log/unlog toggle from the UI."""
    conn = db.state_conn()
    try:
        conn.execute(
            "DELETE FROM history_events WHERE action=? AND substr(ts, 1, 10)=?",
            (action, date),
        )
        conn.commit()
    finally:
        conn.close()


def remove_at(index, expect_ts=None, expect_action=None):
    """Delete a single event by its position in insertion order (0-based,
    oldest first). Used by the History page's undo button to strike a
    specific entry (e.g. a duplicate) without touching every other entry
    for that action/date the way remove_day would.

    If expect_ts/expect_action are given, the record at that position must
    match both or nothing is deleted (returns False) -- protects against
    acting on a stale index: another tab/tick logging or undoing something
    else in between the page load and the click can shift every later
    record's position, so the position alone isn't a safe identifier."""
    if index < 0:
        return False
    conn = db.state_conn()
    try:
        row = conn.execute(
            "SELECT id, action, ts FROM history_events ORDER BY id LIMIT 1 OFFSET ?",
            (index,),
        ).fetchone()
        if row is None:
            return False
        target_id, target_action, target_ts = row
        if expect_ts is not None and target_ts != expect_ts:
            return False
        if expect_action is not None and target_action != expect_action:
            return False
        conn.execute("DELETE FROM history_events WHERE id=?", (target_id,))
        conn.commit()
    finally:
        conn.close()
    return True
