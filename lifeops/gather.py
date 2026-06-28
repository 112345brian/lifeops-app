"""Turn live FlowSavvy data + history into the structured inputs engines expect.
The 'read the fuzzy world' layer. Decisions happen in the engines.
"""
import datetime
from . import history

# FlowSavvy calendar ids whose EVENINGS genuinely block a gym (shows/dates/parties)
BLOCKER_CALS = {"201560": "Breina", "201561": "Concerts", "201563": "Fun",
                "310291": "AXS", "236685": "Partiful"}

def _d(iso):  return (iso or "")[:10]
def _hm(iso): return (iso or "")[11:16]
def _h(iso):
    try: return int((iso or "")[11:13])
    except ValueError: return 0

def _sleep_ok(now):
    """False if last night's sleep was poor or looks corrupted (engine then
    won't pin a 5am morning). Uses the durable sleep/wake history."""
    win_start = (now - datetime.timedelta(hours=18)).isoformat(timespec="seconds")
    evs = sorted([e for e in history.events() if e["action"] in ("sleep", "wake")
                  and e["ts"] >= win_start], key=lambda e: e["ts"])
    if not evs:
        return True                                   # unknown -> don't penalize
    # pair sleep->wake, sum durations, flag fragmentation
    segs, start = [], None
    for e in evs:
        if e["action"] == "sleep":
            start = e["ts"]
        elif e["action"] == "wake" and start:
            a = datetime.datetime.fromisoformat(start)
            b = datetime.datetime.fromisoformat(e["ts"])
            segs.append((b - a).total_seconds() / 3600.0); start = None
    total = sum(s for s in segs if s > 0)
    if len(segs) >= 3:           # fragmented night
        return False
    if total and (total < 5.5 or total > 11):
        return False
    return True

def gym_input(fs, now, sick_until=None):
    today = now.date()
    monday = today - datetime.timedelta(days=today.weekday())
    sunday = monday + datetime.timedelta(days=6)
    horizon = [today + datetime.timedelta(days=i) for i in range(7)]
    hset = {d.isoformat() for d in horizon}

    gym_open = fs.list_items(itemType="task", query="Gym", completed=False).get("items", [])
    completed_count = len(history.days_with("gym", monday.isoformat(), sunday.isoformat()))

    scheduled = []
    for t in gym_open:
        st = t.get("startDateTime")
        if st and today.isoformat() <= _d(st) <= sunday.isoformat():
            scheduled.append({"id": t["id"], "date": _d(st), "start": _hm(st),
                              "end": _hm(t.get("endDateTime")), "manual": False,
                              "started": _d(st) == today.isoformat() and _h(st) <= now.hour + 2})

    # precise evening blockers: real events on the blocker calendars, 17:00-23:00
    blocked, shows = set(), set()
    for cid in BLOCKER_CALS:
        try:
            evs = fs.list_items(itemType="event", calendarId=cid).get("items", [])
        except Exception:
            evs = []
        for e in evs:
            st = e.get("startDateTime")
            if st and _d(st) in hset and 17 <= _h(st) < 23:
                blocked.add(_d(st)); shows.add(_d(st))
    # Reina time / Friends tasks also block the evening
    for t in gym_open:
        st = t.get("startDateTime"); title = (t.get("title") or "")
        if st and _d(st) in hset and 17 <= _h(st) < 23 and title in ("Reina time", "Friends"):
            blocked.add(_d(st))

    sleep_ok = _sleep_ok(now)
    days = []
    for d in horizon:
        ds, prev = d.isoformat(), (d - datetime.timedelta(days=1)).isoformat()
        days.append({"date": ds, "weekday": d.strftime("%a"),
                     "evening_blocked": ds in blocked,
                     "day_after_show": prev in shows,
                     "prior_night_blocked": prev in blocked,
                     "sleep_ok": sleep_ok})

    return {"today": today.isoformat(), "now": now.isoformat(timespec="seconds"),
            "sick_until": sick_until, "completed_count": completed_count,
            "scheduled": scheduled, "days": days}
