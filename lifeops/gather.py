"""Turn live FlowSavvy data into the structured inputs the engines expect.

This is the 'read the fuzzy world' layer. Pure data-shaping — the decisions
happen in the engines.
"""
import datetime

def _d(iso):  return (iso or "")[:10]
def _hm(iso): return (iso or "")[11:16]
def _h(iso):
    try: return int((iso or "")[11:13])
    except ValueError: return 0

def gym_input(fs, now: datetime.datetime, sick_until=None):
    today = now.date()
    monday = today - datetime.timedelta(days=today.weekday())
    sunday = monday + datetime.timedelta(days=6)
    horizon = [today + datetime.timedelta(days=i) for i in range(7)]

    sched = fs.get_schedule(today.isoformat(), horizon[-1].isoformat()).get("scheduleItems", [])
    gym_open = fs.list_items(itemType="task", query="Gym", completed=False).get("items", [])
    gym_done = fs.list_items(itemType="task", query="Gym", completed=True).get("items", [])

    def in_week(s): return s and monday.isoformat() <= _d(s) <= sunday.isoformat()

    completed_count = sum(
        1 for t in gym_done
        if "✅" in (t.get("title") or "")
        and in_week(t.get("dueDateTime") or t.get("startDateTime"))
    )

    scheduled = []
    for t in gym_open:
        st = t.get("startDateTime")
        if st and today.isoformat() <= _d(st) <= sunday.isoformat():
            scheduled.append({"id": t["id"], "date": _d(st), "start": _hm(st),
                              "end": _hm(t.get("endDateTime")), "manual": False,
                              "started": _d(st) == today.isoformat() and _h(st) <= now.hour + 2})

    # Evening blockers: any synced calendar EVENT in 17:00-23:00, plus Reina/Friends blocks
    blocked, shows = set(), set()
    for it in sched:
        st = it.get("startTime") or it.get("startDateTime")
        if not st:
            continue
        d, hh, title = _d(st), _h(st), (it.get("title") or "")
        if it.get("itemType") == "event" and 17 <= hh < 23:
            blocked.add(d); shows.add(d)
        if title in ("Reina time", "Friends") and 17 <= hh < 23:
            blocked.add(d)

    days = []
    for d in horizon:
        ds, prev = d.isoformat(), (d - datetime.timedelta(days=1)).isoformat()
        days.append({"date": ds, "weekday": d.strftime("%a"),
                     "evening_blocked": ds in blocked,
                     "day_after_show": prev in shows,
                     "prior_night_blocked": prev in blocked,
                     "sleep_ok": True})   # TODO: wire ntfy sleep feed

    return {"today": today.isoformat(), "now": now.isoformat(timespec="seconds"),
            "sick_until": sick_until, "completed_count": completed_count,
            "scheduled": scheduled, "days": days}
