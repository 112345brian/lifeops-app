"""Deterministic "what's newly at risk" for the daily briefing -- no LLM.

Tracks which deadline-risk items have already been surfaced (by title + due
datetime) so a lingering at-risk/overdue task doesn't get renarrated every single
day once you already know about it -- only genuinely NEW risk gets called
out, worded directly from the data ("Finish X by Thursday 5pm") rather than
run through an LLM that might phrase it vaguely or restate a number.
"""
import datetime
from . import state_store

# Prune surfaced-item records once their due date is this far in the past --
# bounds file growth. If the exact same title+due somehow recurs after that
# (due dates don't normally repeat), it just gets re-surfaced once more,
# which is harmless.
_STALE_DAYS = 30


def _alerted_file():
    return state_store.logs_path("deadline_alerts_sent.json")


def _load_alerted():
    return state_store.load_json(_alerted_file(), default={}, require_type=dict)


def _save_alerted(data):
    state_store.save_json_atomic(_alerted_file(), data)


def _key(title, due_dt):
    return f"{title}|{due_dt.isoformat()}"


def _format_due(due_dt):
    # %I (not %-I): %-I isn't portable to Windows, which is how this app
    # actually runs (Windows Task Scheduler -- see register_task.ps1). The
    # leading-zero strip is done manually instead so "9:00am" doesn't
    # render as "09:00am".
    time_part = due_dt.strftime("%I:%M%p").lstrip("0").lower()
    return f"{due_dt.strftime('%A')} {time_part}"


def newly_at_risk(items, now):
    """[items]: [{"title", "due_iso"}] -- e.g. load_engine.at_risk_assignments's
    output, or a single-item list built from deadline_crunch_item. Returns
    only the items never surfaced before (by title+due-datetime), each with a
    ready-to-display `phrase` ("Finish X by Thursday 5pm"), and marks them
    surfaced so tomorrow's run stays quiet about the same item -- an
    unresolved at-risk task doesn't need daily renarration once you know."""
    alerted = _load_alerted()
    cutoff = (now - datetime.timedelta(days=_STALE_DAYS)).date().isoformat()
    alerted = {k: v for k, v in alerted.items() if v >= cutoff}

    out = []
    for item in items:
        title, due_iso = item.get("title"), item.get("due_iso")
        if not title or not due_iso:
            continue
        try:
            due_dt = datetime.datetime.fromisoformat(due_iso)
        except ValueError:
            continue
        key = _key(title, due_dt)
        if key in alerted:
            continue
        out.append({"title": title, "due": due_dt.isoformat(),
                    "phrase": f'Finish "{title}" by {_format_due(due_dt)}'})
        alerted[key] = due_dt.date().isoformat()

    _save_alerted(alerted)
    return out
