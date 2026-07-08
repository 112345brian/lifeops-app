#!/usr/bin/env python3
"""Coursework load-watcher (advisory; warn-only). Flags at-risk deadlines and
overextension. Pure logic over gathered assignment facts."""

WEEK_CAPACITY_H = 25    # rough realistic study hours/week
DAILY_CAPACITY_H = 3.5  # realistic discretionary work-hours/day across everything

def _n(v, default):
    """Missing/None-tolerant numeric read — a malformed assignment must not
    crash the watcher (it's advisory)."""
    return default if v is None else v


def deadline_risk(items, daily_capacity_h=DAILY_CAPACITY_H):
    """Motion-style 'this won't fit' check over ALL deadline-bearing tasks, not
    just coursework. Walk deadlines in order; if the total remaining work due on
    or before a deadline exceeds the work-hours realistically available before
    it (days_until x daily capacity), that deadline is at risk. Report the
    EARLIEST binding deadline — the real constraint to act on now — rather than
    every downstream one it also blows. Deterministic, advisory."""
    alerts = []
    dated = sorted((i for i in items if _n(i.get("remaining_min"), 0) > 0),
                   key=lambda i: _n(i.get("due_in_days"), 10**9))
    cum_h = 0.0
    for i in dated:
        d = max(0.25, _n(i.get("due_in_days"), 10**9))
        cum_h += _n(i.get("remaining_min"), 0) / 60.0
        available = d * daily_capacity_h
        if cum_h > available:
            over = cum_h - available
            alerts.append((f"Deadline crunch: ~{cum_h:.0f}h of work due by "
                           f"\"{(i.get('title') or '?')[:34]}\" (~{int(round(d))}d out) but only "
                           f"~{available:.0f}h realistically free — ~{over:.0f}h over. "
                           f"Start early or cut scope.", "high"))
            break
    return {"alerts": alerts}

def plan(assignments):
    alerts = []
    for a in assignments:
        due_h = _n(a.get("due_in_h"), 10**9)
        rem   = _n(a.get("remaining_min"), 0)
        if due_h <= 48 and _n(a.get("progress"), 0) == 0 and rem >= 120:
            alerts.append((f"Heavy + soon: {(a.get('title') or '?')[:42]} (~{rem//60}h) "
                           f"due in {int(due_h)}h.", "high"))
    week_h = sum(_n(a.get("remaining_min"), 0) for a in assignments
                 if _n(a.get("due_in_days"), 99) <= 7) / 60.0
    if week_h > WEEK_CAPACITY_H:
        alerts.append((f"Overbooked: ~{week_h:.0f}h of coursework due in 7 days "
                       f"(> ~{WEEK_CAPACITY_H}h you realistically have). Start early or cut something.",
                       "high"))
    return {"alerts": alerts}
