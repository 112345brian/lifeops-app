"""Append-only completion history — the durable record of WHEN things happened.

This is the system's memory. ntfy signals expire (~12h cache) and FlowSavvy
task-state is lossy, so completions are logged here permanently. Every engine
reads cadence ("when was X last done", "how many gyms this week") from here, not
from ephemeral sources.

One JSON object per line: {"action": "gym", "ts": "2026-06-27T18:00:00", "source": "ntfy"}
"""
import os, json, datetime

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

def events(action=None):
    out = []
    try:
        with open(HIST, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                r = json.loads(line)
                if action is None or r.get("action") == action:
                    out.append(r)
    except FileNotFoundError:
        pass
    return out

def last(action):
    es = events(action)
    return max((e["ts"] for e in es), default=None)

def days_with(action, start_date, end_date):
    """Distinct dates (YYYY-MM-DD) an action happened within [start,end] inclusive."""
    return {e["ts"][:10] for e in events(action)
            if start_date <= e["ts"][:10] <= end_date}
