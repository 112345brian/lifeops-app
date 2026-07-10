"""Append-only completion history — the durable record of WHEN things happened.

This is the system's memory. ntfy signals expire (~12h cache) and FlowSavvy
task-state is lossy, so completions are logged here permanently. Every engine
reads cadence ("when was X last done", "how many gyms this week") from here, not
from ephemeral sources.

One JSON object per line: {"action": "gym", "ts": "2026-06-27T18:00:00", "source": "ntfy"}
"""
import os, json, datetime, tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HIST = os.path.join(ROOT, "logs", "history.jsonl")

def append(action, ts=None, source="", meta=None):
    rec = {"action": action,
           "ts": ts or datetime.datetime.now().isoformat(timespec="seconds"),
           "source": source}
    if meta:
        rec["meta"] = meta
    os.makedirs(os.path.dirname(HIST), exist_ok=True)
    with open(HIST, "a", encoding="utf-8") as f:
        f.write(json.dumps(rec) + "\n")
    return rec

def _lines():
    """Return raw lines paired with parsed records. Corrupt lines are retained
    during edits so one partial write cannot hide or erase the valid history."""
    out = []
    try:
        with open(HIST, encoding="utf-8") as f:
            for raw in f:
                if not raw.strip():
                    out.append((raw, None))
                    continue
                try:
                    rec = json.loads(raw)
                except (json.JSONDecodeError, TypeError):
                    rec = None
                out.append((raw, rec))
    except FileNotFoundError:
        pass
    return out

def _rewrite(lines):
    os.makedirs(os.path.dirname(HIST), exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix="history-", suffix=".tmp", dir=os.path.dirname(HIST))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.writelines(lines)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, HIST)
    finally:
        try:
            os.remove(tmp)
        except FileNotFoundError:
            pass

def events(action=None):
    return [r for _, r in _lines()
            if r is not None and (action is None or r.get("action") == action)]

def last(action):
    es = events(action)
    return max((e["ts"] for e in es), default=None)

def days_with(action, start_date, end_date):
    """Distinct dates (YYYY-MM-DD) an action happened within [start,end] inclusive."""
    return {e["ts"][:10] for e in events(action)
            if start_date <= e["ts"][:10] <= end_date}

def remove_day(action, date):
    """Delete every entry for `action` on `date` (YYYY-MM-DD), regardless of
    source. Used to undo a manual log/unlog toggle from the UI."""
    lines = _lines()
    if not lines:
        return
    kept = [raw for raw, r in lines
            if r is None or not (r.get("action") == action and r.get("ts", "")[:10] == date)]
    _rewrite(kept)

def remove_at(index, expect_ts=None, expect_action=None):
    """Delete a single event by its position in the full file (0-based,
    file order — oldest first). Used by the History page's undo button to
    strike a specific entry (e.g. a duplicate) without touching every other
    entry for that action/date the way remove_day would.

    If expect_ts/expect_action are given, the record at that position must
    match both or nothing is deleted (returns False) -- protects against
    acting on a stale index: another tab/tick logging or undoing something
    else in between the page load and the click can shift every later
    record's position, so the position alone isn't a safe identifier."""
    lines = _lines()
    if not lines:
        return False
    valid_index = -1
    target = None
    kept = []
    for raw, rec in lines:
        if rec is not None:
            valid_index += 1
        if rec is not None and valid_index == index:
            target = rec
            continue
        kept.append(raw)
    if target is None:
        return False
    if expect_ts is not None and target.get("ts") != expect_ts:
        return False
    if expect_action is not None and target.get("action") != expect_action:
        return False
    _rewrite(kept)
    return True
