"""Turn live FlowSavvy data + history into the structured inputs engines expect.
All personal identifiers come from config (.env) — none hardcoded here.
"""
import datetime
from . import history, config

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
        return True
    segs, start = [], None
    for e in evs:
        if e["action"] == "sleep":
            start = e["ts"]
        elif e["action"] == "wake" and start:
            a = datetime.datetime.fromisoformat(start)
            b = datetime.datetime.fromisoformat(e["ts"])
            segs.append((b - a).total_seconds() / 3600.0); start = None
    total = sum(s for s in segs if s > 0)
    if len(segs) >= 3:
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
    # count gym days, excluding any you flagged "don't count" (gym-nocount)
    gym_days = history.days_with("gym", monday.isoformat(), sunday.isoformat())
    skip_days = history.days_with("gym_skip", monday.isoformat(), sunday.isoformat())
    completed_count = len(gym_days - skip_days)

    scheduled = []
    for t in gym_open:
        st = t.get("startDateTime")
        if st and today.isoformat() <= _d(st) <= sunday.isoformat():
            scheduled.append({"id": t["id"], "date": _d(st), "start": _hm(st),
                              "end": _hm(t.get("endDateTime")), "manual": False,
                              "started": _d(st) == today.isoformat() and _h(st) <= now.hour + 2})

    # An event only BLOCKS the gym if it overlaps the 18:00-21:00 evening window;
    # any social event still marks the day as a "show" (for recovery + late-night).
    blocked, shows = set(), set()
    def _consider(st, en):
        if not st or _d(st) not in hset:
            return
        sh, eh = _h(st), (_h(en) if en else _h(st) + 2)
        shows.add(_d(st))
        if sh < 21 and eh > 18:
            blocked.add(_d(st))
    for cid in config.EVENT_CALS:
        try:
            evs = fs.list_items(itemType="event", calendarId=cid).get("items", [])
        except Exception:
            evs = []
        for e in evs:
            _consider(e.get("startDateTime"), e.get("endDateTime"))
    for t in gym_open:
        if (t.get("title") or "") in (config.PARTNER_TASK, config.FRIENDS_TASK):
            _consider(t.get("startDateTime"), t.get("endDateTime"))

    # coursework deadline pressure: remaining work due on/around each day
    load = {}
    try:
        course = fs.list_items(itemType="task", listId=config.LIST_COURSE, completed=False).get("items", [])
    except Exception:
        course = []
    for t in course:
        due = t.get("dueDateTime")
        if not due:
            continue
        rem = max(0, (t.get("durationMinutes") or 0) - (t.get("progressMinutes") or 0))
        load[_d(due)] = load.get(_d(due), 0) + rem
    def _heavy(ds_):  # >=3h of coursework due that day or the next → evening goes to it
        nxt = (datetime.date.fromisoformat(ds_) + datetime.timedelta(days=1)).isoformat()
        return (load.get(ds_, 0) + load.get(nxt, 0)) >= 180

    sleep_ok = _sleep_ok(now)
    days = []
    for d in horizon:
        ds, prev = d.isoformat(), (d - datetime.timedelta(days=1)).isoformat()
        near = (d - today).days <= 1   # last night's sleep only gates today/tomorrow
        days.append({"date": ds, "weekday": d.strftime("%a"),
                     "evening_blocked": ds in blocked,
                     "day_after_show": prev in shows,
                     "prior_night_blocked": prev in blocked,
                     "deadline_heavy": _heavy(ds),
                     "sleep_ok": sleep_ok if near else True})

    return {"today": today.isoformat(), "now": now.isoformat(timespec="seconds"),
            "sick_until": sick_until, "completed_count": completed_count,
            "scheduled": scheduled, "days": days}

def homework_input(fs, now):
    out = []
    for t in fs.list_items(itemType="task", listId=config.LIST_COURSE, completed=False).get("items", []):
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
    caltype = config.EVENT_CALS
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
                events.append({"date": _d(st), "type": typ, "cost": config.COSTS.get(typ, 40),
                               "label": e.get("title") or typ, "days_until": du})
    for t in fs.list_items(itemType="task", completed=False).get("items", []):
        title = t.get("title") or ""; st = t.get("startDateTime") or t.get("dueDateTime")
        if title in (config.PARTNER_TASK, config.FRIENDS_TASK) and st and start <= _d(st) <= end:
            typ = "date" if title == config.PARTNER_TASK else "friends"
            du = (datetime.date.fromisoformat(_d(st)) - now.date()).days
            events.append({"date": _d(st), "type": typ, "cost": config.COSTS.get(typ, 40),
                           "label": title, "days_until": du})
    disc = set(config.DISCRETIONARY)
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
    def _has(base):  # proposed / planning / locked all count as "has a plan"
        return any((t.get("title") or "") in (base, f"{base} (proposed)", f"Plan {base}")
                   for t in open_tasks)
    has_partner = _has(config.PARTNER_TASK)
    has_friend = _has(config.FRIENDS_TASK)
    if config.SOCIAL_CAL:
        try:
            for e in fs.list_items(itemType="event", calendarId=config.SOCIAL_CAL).get("items", []):
                st = e.get("startDateTime")
                if st and start <= _d(st) <= weekend:
                    has_partner = True
        except Exception:
            pass
    lo = max(1, config.PROPOSE_AHEAD_DAYS - 3); hi = config.PROPOSE_AHEAD_DAYS + 4
    days = [now.date() + datetime.timedelta(days=i) for i in range(lo, hi)]
    days.sort(key=lambda d: (d.weekday() < 5, d))   # weekends first, ~3 weeks out
    return {"partner_days": ago(history.last("partner")), "friend_days": ago(history.last("friends")),
            "has_partner": has_partner, "has_friend": has_friend,
            "good_days": [d.isoformat() for d in days],
            "is_protect_day": now.strftime("%a") in ("Sun", "Thu")}
