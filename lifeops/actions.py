"""Audit log of agent-caused changes — the "what did LifeOps do?" feed, plus
enough info to undo the reversible ones.

One row per mutation in the `actions_log` bucket of state_store's log_lines
table: {"ts","domain","op","title","item_id","undoable","meta"?}, serialized
as a JSON line same as before (see state_store.append_line/recent_lines).

This is distinct from history.jsonl (durable *completions* — when you did things)
and runs.jsonl (per-run summaries). The actions log is "mutations LifeOps made to
your calendar," so a surprising task appearing/vanishing is a grep away, and the
control panel can offer a one-tap undo. Undone item ids are tracked in the
actions_undone kv_state key so the feed greys them out and refuses a double-undo.
"""
import json, datetime
from . import state_store


def _path(name):
    return state_store.logs_path(name)


def log(domain, op, title, item_id=None, undoable=False, meta=None):
    """Record one mutation. `undoable` is only honored when there's an item_id to
    reverse (a created task → delete it). Best-effort: never raise into a domain."""
    rec = {"ts": datetime.datetime.now().isoformat(timespec="seconds"),
           "domain": domain, "op": op, "title": title,
           "item_id": item_id, "undoable": bool(undoable and item_id)}
    if meta:
        rec["meta"] = meta
    try:
        state_store.append_line(_path("actions.jsonl"), json.dumps(rec))
    except Exception:
        pass
    return rec


def recent(n=20):
    """Last n actions, newest first, each tagged with an `undone` flag."""
    undone = _undone_ids()
    out = []
    for line in state_store.recent_lines(_path("actions.jsonl"), n):
        try:
            r = json.loads(line)
        except Exception:
            continue
        r["undone"] = bool(r.get("item_id")) and r["item_id"] in undone
        out.append(r)
    return out


def _undone_ids():
    return set(state_store.load_json(_path("actions_undone.json"), default=[]))


def mark_undone(item_id):
    """Record that an action's item was reversed, so the feed won't offer undo
    again."""
    ids = _undone_ids()
    ids.add(item_id)
    state_store.save_json_atomic(_path("actions_undone.json"), sorted(ids))
