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

_COSTS = {"concert": 40, "party": 35, "date": 50, "friends": 35}

def homework_input(fs, now):
    out = []
    for t in fs.list_items(itemType="task", listId="147765", completed=False).get("items", []):
        due = t.get("dueDateTime")
        if not due:
            continue
        try:
            h = (datetime.datetime.fromisoformat(due) - now).total_seconds() / 3600
        except ValueError:
            continue
        if h < -24:
            continue
        dur = t.get("durationMinutes") or 0; prog = t.get("progressMinutes") or 0
        out.append({"title": t.get("title") or "", "due_in_h": h, "due_in_days": h / 24,
                    "remaining_min": max(0, dur - prog), "progress": prog})
    return out

def spend_input(fs, yn, now):
    caltype = {"201561": "concert", "310291": "concert", "201563": "party",
               "236685": "party", "201560": "date"}
    start = now.date().isoformat(); end = (now.date() + datetime.timedelta(days=21)).isoformat()
    events = []
    for cid, typ in caltype.items():
        try:
            evs = fs.list_items(itemType="event", calendarId=cid).get("items", [])
        except Exception:
            evs = []
        for e in evs:
            st = e.get("startDateTime")
            if st and start <= _d(st) <= end:
                du = (datetime.date.fromisoformat(_d(st)) - now.date()).days
                events.append({"date": _d(st), "type": typ, "cost": _COSTS[typ],
                               "label": e.get("title") or typ, "days_until": du})
    for t in fs.list_items(itemType="task", completed=False).get("items", []):
        title = t.get("title") or ""; st = t.get("startDateTime") or t.get("dueDateTime")
        if title in ("Reina time", "Friends") and st and start <= _d(st) <= end:
            typ = "date" if title == "Reina time" else "friends"
            du = (datetime.date.fromisoformat(_d(st)) - now.date()).days
            events.append({"date": _d(st), "type": typ, "cost": _COSTS[typ],
                           "label": title, "days_until": du})
    disc = {"shopping", "entertainment", "eating out", "shows", "splurge"}
    try:
        month = yn.month()
    except Exception:
        month = {"categories": []}
    fun = sum(c.get("balance", 0) for c in month.get("categories", [])
              if c["name"].lower() in disc) / 1000.0
    return {"events": events, "fun_money": fun}

def social_input(fs, now):
    def ago(ts):
        return (now - datetime.datetime.fromisoformat(ts)).days if ts else None
    start = now.date().isoformat(); weekend = (now.date() + datetime.timedelta(days=7)).isoformat()
    open_tasks = fs.list_items(itemType="task", completed=False).get("items", [])
    has_reina = any(t.get("title") == "Reina time" for t in open_tasks)
    has_friend = any(t.get("title") == "Friends" for t in open_tasks)
    try:
        for e in fs.list_items(itemType="event", calendarId="201560").get("items", []):
            st = e.get("startDateTime")
            if st and start <= _d(st) <= weekend:
                has_reina = True
    except Exception:
        pass
    days = [now.date() + datetime.timedelta(days=i) for i in range(1, 8)]
    days.sort(key=lambda d: (d.weekday() < 5, d))   # weekends first
    return {"reina_days": ago(history.last("reina")), "friend_days": ago(history.last("friends")),
            "has_reina": has_reina, "has_friend": has_friend,
            "good_days": [d.isoformat() for d in days],
            "is_protect_day": now.strftime("%a") in ("Sun", "Thu")}
