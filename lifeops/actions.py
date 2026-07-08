"""Audit log of agent-caused changes — the "what did LifeOps do?" feed, plus
enough info to undo the reversible ones.

One JSON object per line in logs/actions.jsonl:
  {"ts","domain","op","title","item_id","undoable","meta"?}

This is distinct from history.jsonl (durable *completions* — when you did things)
and runs.jsonl (per-run summaries). actions.jsonl is "mutations LifeOps made to
your calendar," so a surprising task appearing/vanishing is a grep, and the
control panel can offer a one-tap undo. Undone item ids are tracked in
logs/actions_undone.json so the feed greys them out and refuses a double-undo.
"""
import os, json, datetime
from . import history


def _path(name):
    return os.path.join(history.ROOT, "logs", name)


def log(domain, op, title, item_id=None, undoable=False, meta=None):
    """Record one mutation. `undoable` is only honored when there's an item_id to
    reverse (a created task → delete it). Best-effort: never raise into a domain."""
    rec = {"ts": datetime.datetime.now().isoformat(timespec="seconds"),
           "domain": domain, "op": op, "title": title,
           "item_id": item_id, "undoable": bool(undoable and item_id)}
    if meta:
        rec["meta"] = meta
    try:
        p = _path("actions.jsonl")
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "a", encoding="utf-8") as f:
            f.write(json.dumps(rec) + "\n")
    except Exception:
        pass
    return rec


def recent(n=20):
    """Last n actions, newest first, each tagged with an `undone` flag."""
    try:
        with open(_path("actions.jsonl"), encoding="utf-8") as f:
            lines = [l for l in f if l.strip()]
    except FileNotFoundError:
        return []
    undone = _undone_ids()
    out = []
    for l in reversed(lines[-n:]):
        try:
            r = json.loads(l)
        except Exception:
            continue
        r["undone"] = bool(r.get("item_id")) and r["item_id"] in undone
        out.append(r)
    return out


def _undone_ids():
    try:
        return set(json.load(open(_path("actions_undone.json"), encoding="utf-8")))
    except Exception:
        return set()


def mark_undone(item_id):
    """Record that an action's item was reversed, so the feed won't offer undo
    again. Atomic write (temp + replace) like the other state files."""
    ids = _undone_ids()
    ids.add(item_id)
    p = _path("actions_undone.json")
    os.makedirs(os.path.dirname(p), exist_ok=True)
    tmp = p + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(sorted(ids), f)
    os.replace(tmp, p)
