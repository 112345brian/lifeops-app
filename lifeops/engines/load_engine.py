#!/usr/bin/env python3
"""Coursework load-watcher (advisory; warn-only). Flags at-risk deadlines and
overextension. Pure logic over gathered assignment facts."""

WEEK_CAPACITY_H = 25  # rough realistic study hours/week

def _n(v, default):
    """Missing/None-tolerant numeric read — a malformed assignment must not
    crash the watcher (it's advisory)."""
    return default if v is None else v

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
