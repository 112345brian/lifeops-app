#!/usr/bin/env python3
"""Coursework load-watcher (advisory; warn-only). Flags at-risk deadlines and
overextension. Pure logic over gathered assignment facts."""

WEEK_CAPACITY_H = 25  # rough realistic study hours/week

def plan(assignments):
    alerts = []
    for a in assignments:
        if a["due_in_h"] <= 48 and a["progress"] == 0 and a["remaining_min"] >= 120:
            alerts.append((f"Heavy + soon: {a['title'][:42]} (~{a['remaining_min']//60}h) "
                           f"due in {int(a['due_in_h'])}h.", "high"))
    week_h = sum(a["remaining_min"] for a in assignments if a["due_in_days"] <= 7) / 60.0
    if week_h > WEEK_CAPACITY_H:
        alerts.append((f"Overbooked: ~{week_h:.0f}h of coursework due in 7 days "
                       f"(> ~{WEEK_CAPACITY_H}h you realistically have). Start early or cut something.",
                       "high"))
    return {"alerts": alerts}
