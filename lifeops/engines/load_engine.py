#!/usr/bin/env python3
"""Coursework load-watcher (advisory; warn-only). Flags at-risk deadlines and
overextension. Pure logic over gathered assignment facts."""

WEEK_CAPACITY_H = 25    # rough realistic study hours/week
DAILY_CAPACITY_H = 3.5  # realistic discretionary work-hours/day across everything

def _n(v, default):
    """Missing/None-tolerant numeric read — a malformed assignment must not
    crash the watcher (it's advisory)."""
    return default if v is None else v


def _deadline_crunch(items, daily_capacity_h):
    """Shared walk behind both deadline_crunch_item and deadline_risk, so
    the two can never disagree about what's at risk or how the cumulative
    hours were computed. Returns (item, cum_h, available) for the earliest
    binding deadline, or (None, None, None) if nothing's at risk."""
    dated = sorted((i for i in items if _n(i.get("remaining_min"), 0) > 0),
                   key=lambda i: _n(i.get("due_in_days"), 10**9))
    cum_h = 0.0
    for i in dated:
        d = max(0.25, _n(i.get("due_in_days"), 10**9))
        cum_h += _n(i.get("remaining_min"), 0) / 60.0
        available = d * daily_capacity_h
        if cum_h > available:
            return i, cum_h, available
    return None, None, None

def deadline_crunch_item(items, daily_capacity_h=DAILY_CAPACITY_H):
    """The raw item (not a formatted string) behind deadline_risk's "won't
    fit" alert, or None if nothing's at risk. Exposed separately so a
    caller that wants the real due date/title (e.g. run_briefing phrasing
    "finish X by Y" without an LLM) doesn't have to parse it back out of
    deadline_risk's prose alert string."""
    item, _cum_h, _available = _deadline_crunch(items, daily_capacity_h)
    return item

def deadline_risk(items, daily_capacity_h=DAILY_CAPACITY_H):
    """Motion-style 'this won't fit' check over ALL deadline-bearing tasks, not
    just coursework. Walk deadlines in order; if the total remaining work due on
    or before a deadline exceeds the work-hours realistically available before
    it (days_until x daily capacity), that deadline is at risk. Report the
    EARLIEST binding deadline — the real constraint to act on now — rather than
    every downstream one it also blows. Deterministic, advisory."""
    alerts = []
    i, cum_h, available = _deadline_crunch(items, daily_capacity_h)
    if i is not None:
        d = max(0.25, _n(i.get("due_in_days"), 10**9))
        over = cum_h - available
        alerts.append((f"Deadline crunch: ~{cum_h:.0f}h of work due by "
                       f"\"{(i.get('title') or '?')[:34]}\" (~{int(round(d))}d out) but only "
                       f"~{available:.0f}h realistically free — ~{over:.0f}h over. "
                       f"Start early or cut scope.", "high"))
    return {"alerts": alerts}

def at_risk_assignments(assignments):
    """The raw assignment dicts (not formatted strings) behind plan()'s
    "Heavy + soon" per-item alerts -- same criterion plan() uses internally,
    so a caller that wants the real title/due_in_h (e.g. run_briefing
    phrasing "finish X by Y" without an LLM) doesn't have to parse it back
    out of plan's prose alert string."""
    return [a for a in assignments
            if _n(a.get("due_in_h"), 10**9) <= 48
            and _n(a.get("progress"), 0) == 0
            and _n(a.get("remaining_min"), 0) >= 120]

def plan(assignments):
    alerts = []
    for a in at_risk_assignments(assignments):
        due_h = _n(a.get("due_in_h"), 10**9)
        rem   = _n(a.get("remaining_min"), 0)
        alerts.append((f"Heavy + soon: {(a.get('title') or '?')[:42]} (~{rem//60}h) "
                       f"due in {int(due_h)}h.", "high"))
    week_h = sum(_n(a.get("remaining_min"), 0) for a in assignments
                 if _n(a.get("due_in_days"), 99) <= 7) / 60.0
    if week_h > WEEK_CAPACITY_H:
        alerts.append((f"Overbooked: ~{week_h:.0f}h of coursework due in 7 days "
                       f"(> ~{WEEK_CAPACITY_H}h you realistically have). Start early or cut something.",
                       "high"))
    return {"alerts": alerts}
