"""One-time migration: port existing private/logs/*.json(l) state files into
private/logs/state.db, and the local-only logs/fcm_token.json +
logs/weather_grid_cache.json into logs/local.db (see lifeops/db.py,
lifeops/state_store.py, lifeops/history.py).

Run once, by hand, AFTER pulling the SQLite-backed state_store/history code
and BEFORE restarting the live web.py server or runner.py's scheduled tasks --
once that code is running it reads/writes state.db exclusively, so the old
JSON files' data must be migrated in first or it's effectively orphaned
(still on disk, just never read again).

    python scripts\\migrate_state_to_sqlite.py

In practice runner.py re-imports fresh on every invocation, so it's easy for
a scheduled tick to reach this SQLite-backed code (and start writing real
state.db rows) before this script ever runs. This script is written to be
safe regardless:

- kv_state keys: never overwritten if state.db already has the key -- that
  live value is newer/more authoritative than the old JSON snapshot, so the
  key is left alone and reported as skipped. Only keys state.db has never
  seen get backfilled from the old JSON files.
- append-log data (history.jsonl, actions.jsonl, gym_log.jsonl,
  llm_usage.jsonl, runs.jsonl): safe to run more than once AND safe even if
  the live system already wrote some rows in the meantime. For each log,
  any rows already in state.db are treated as "live, written after cutover"
  -- they're temporarily lifted out, the old file's historical lines are
  inserted first (so they get lower ids / sort earlier), then the live rows
  are reinserted after them, preserving correct chronological id-ordering
  (old history first, live activity after) without losing or duplicating a
  single row. A kv_state marker (_migration_log_status) records which logs
  have already been backfilled this way, so re-running the script is a
  no-op for logs already migrated -- it will NOT re-prepend the file's
  content a second time.

Does NOT delete the original JSON/JSONL files -- left on disk (git history
has them regardless) as a rollback safety net for one release. Delete them
in a follow-up commit once the new system has run cleanly for a few days.
"""
import json, os, sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from lifeops import db, history, state_store

# Dict- or list-shaped files that map 1:1 onto a kv_state key (the file's
# basename, minus extension -- see state_store._key_from_path).
KV_FILES = [
    "event_frequency.json", "deadline_alerts_sent.json",
    "push_ack_briefing.json", "push_ack_next_tasks.json",
    "panel_health_state.json", "ingest_state.json", "alert_state.json",
    "gym_state.json", "chore_state.json", "catchup_state.json",
    "social_state.json", "meal_state.json", "briefing.json", "cashflow.json",
    "canvas_state.json", "canvas_pending.json", "domains.json",
    "gym_blocks.json", "schedule_blocks.json", "phone_location.json",
    "last_run.json", "actions_undone.json",
]

# Append-only files that map onto state_store's generic log_lines table.
LOG_FILES = ["actions.jsonl", "gym_log.jsonl", "llm_usage.jsonl", "runs.jsonl"]

# Local-only, never-backed-up files under the top-level (gitignored) logs/
# dir -- these map onto logs/local.db (see lifeops/db.py's local_conn),
# separate from the tracked private/logs/state.db.
LOCAL_KV_FILES = ["fcm_token.json", "weather_grid_cache.json"]

_MARKER_KEY = "_migration_log_status"


def _read_lines(path):
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8") as f:
        return [line.rstrip("\n") for line in f if line.strip()]


def _migrate_kv(logs_dir):
    conn = db.state_conn()
    try:
        existing_keys = {row[0] for row in conn.execute("SELECT key FROM kv_state")}
    finally:
        conn.close()

    migrated, skipped = 0, 0
    for name in KV_FILES:
        path = os.path.join(logs_dir, name)
        if not os.path.exists(path):
            continue
        key = state_store._key_from_path(name)
        if key in existing_keys:
            print(f"  skip kv: {name} (state.db already has newer live data for this key)")
            skipped += 1
            continue
        with open(path, encoding="utf-8") as f:
            try:
                data = json.load(f)
            except json.JSONDecodeError:
                print(f"  skip kv: {name} (not valid JSON)")
                continue
        state_store.save_json_atomic(state_store.logs_path(name), data)
        migrated += 1
        print(f"  kv:      {name}")
    return migrated, skipped


def _migrate_local_kv(local_logs_dir):
    conn = db.local_conn()
    try:
        existing_keys = {row[0] for row in conn.execute("SELECT key FROM kv_state")}
    finally:
        conn.close()

    migrated, skipped = 0, 0
    for name in LOCAL_KV_FILES:
        path = os.path.join(local_logs_dir, name)
        if not os.path.exists(path):
            continue
        key = state_store._key_from_path(name)
        if key in existing_keys:
            print(f"  skip local kv: {name} (local.db already has newer live data for this key)")
            skipped += 1
            continue
        with open(path, encoding="utf-8") as f:
            try:
                data = json.load(f)
            except json.JSONDecodeError:
                print(f"  skip local kv: {name} (not valid JSON)")
                continue
        db.local_set(key, data)
        migrated += 1
        print(f"  local kv: {name}")
    return migrated, skipped


def _migrate_history(logs_dir, marker):
    if marker.get("history_migrated"):
        print("  skip history.jsonl (already migrated by a previous run)")
        return 0
    lines = _read_lines(os.path.join(logs_dir, "history.jsonl"))

    conn = db.state_conn()
    try:
        live_rows = conn.execute(
            "SELECT action, ts, source, meta FROM history_events ORDER BY id"
        ).fetchall()
        conn.execute("DELETE FROM history_events")
        conn.commit()
    finally:
        conn.close()

    migrated = 0
    for line in lines:
        try:
            rec = json.loads(line)
        except json.JSONDecodeError:
            print(f"  skip corrupt history.jsonl line: {line[:80]}")
            continue
        history.append(rec.get("action"), ts=rec.get("ts"),
                       source=rec.get("source", ""), meta=rec.get("meta"))
        migrated += 1

    if live_rows:
        conn = db.state_conn()
        try:
            conn.executemany(
                "INSERT INTO history_events(action, ts, source, meta) VALUES (?, ?, ?, ?)",
                live_rows,
            )
            conn.commit()
        finally:
            conn.close()
        print(f"  history: restored {len(live_rows)} live row(s) written since cutover, after backfill")

    marker["history_migrated"] = True
    if migrated:
        print(f"  history: {migrated} event(s) from history.jsonl")
    return migrated


def _migrate_log(logs_dir, name, marker):
    done = set(marker.get("logs_migrated", []))
    if name in done:
        print(f"  skip {name} (already migrated by a previous run)")
        return 0
    lines = _read_lines(os.path.join(logs_dir, name))
    log_key = state_store._key_from_path(name)

    conn = db.state_conn()
    try:
        live_rows = conn.execute(
            "SELECT line, inserted_at FROM log_lines WHERE log_name=? ORDER BY id", (log_key,)
        ).fetchall()
        conn.execute("DELETE FROM log_lines WHERE log_name=?", (log_key,))
        conn.commit()
    finally:
        conn.close()

    for line in lines:
        state_store.append_line(state_store.logs_path(name), line)

    if live_rows:
        conn = db.state_conn()
        try:
            conn.executemany(
                "INSERT INTO log_lines(log_name, line, inserted_at) VALUES (?, ?, ?)",
                [(log_key, line, inserted_at) for line, inserted_at in live_rows],
            )
            conn.commit()
        finally:
            conn.close()
        print(f"  log:     restored {len(live_rows)} live row(s) of {name} written since cutover, after backfill")

    marker.setdefault("logs_migrated", []).append(name)
    if lines:
        print(f"  log:     {len(lines)} lines from {name}")
    return len(lines)


def migrate(logs_dir, local_logs_dir):
    marker = state_store.load_json(state_store.logs_path(f"{_MARKER_KEY}.json"), default={})

    kv_migrated, kv_skipped = _migrate_kv(logs_dir)
    local_kv_migrated, local_kv_skipped = _migrate_local_kv(local_logs_dir)
    history_migrated = _migrate_history(logs_dir, marker)
    logs_migrated = sum(_migrate_log(logs_dir, name, marker) for name in LOG_FILES)

    state_store.save_json_atomic(state_store.logs_path(f"{_MARKER_KEY}.json"), marker)

    print(f"\nDone. Migrated {kv_migrated} kv file(s) ({kv_skipped} skipped, already live), "
          f"{local_kv_migrated} local kv file(s) ({local_kv_skipped} skipped, already live), "
          f"{history_migrated} history event(s), {logs_migrated} log line(s).")
    print(f"Original files left on disk under {logs_dir} and {local_logs_dir} -- delete "
          f"them by hand once you've confirmed the new state.db-backed system is working.")
    return 0


def main():
    logs_dir = history.private_logs_dir()
    local_logs_dir = os.path.join(history.ROOT, "logs")
    print(f"Migrating state from {logs_dir} into {os.path.join(logs_dir, 'state.db')}")
    print(f"Migrating local-only state from {local_logs_dir} into "
          f"{os.path.join(local_logs_dir, 'local.db')}\n")
    return migrate(logs_dir, local_logs_dir)


if __name__ == "__main__":
    sys.exit(main())
