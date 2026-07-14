"""Turn live FlowSavvy data + history into the structured inputs engines expect.
All personal identifiers come from config (.env) — none hardcoded here.
"""
import datetime, json, os, re
from . import history, config, adherence

# Canonical path — web.py imports this instead of re-deriving it, so the
# writer (web UI "block this day") and reader (this module's engine feed)
# can never silently diverge onto two different files.
GYM_BLOCKS_FILE = os.path.join(history.ROOT, "logs", "gym_blocks.json")

def _is_friend_hangout(title, notes):
    """A task counts as a friend hangout if its title/notes say so directly
    ("friend"/"friends" — word-boundary, so both singular and plural match),
    or the title names someone in FRIEND_NAMES (config) — the same idea as
    PARTNER_NAME, but for hangouts that aren't literally titled "Friends".
    Lives here (not runner.py) so social_input's has_friend check and
    runner._classify's history-logging check share one definition instead
    of silently drifting apart."""
    text = f"{title or ''} {notes or ''}".lower()
    if re.search(r"\bfriends?\b", text):
        return True
    return any(re.search(rf"\b{re.escape(n.lower())}\b", text) for n in config.FRIEND_NAMES)

_NOTE_OVERRIDE_RE = re.compile(r"(?im)^\s*(type|cost)\s*:\s*(.+?)\s*$")
_TYPE_ALIASES = {"concerts": "concert"}

def _normalize_note_type(value):
    value = value.strip().lower()
    return _TYPE_ALIASES.get(value, value)

def _parse_note_overrides(notes):
    """Sweeps an event/task's notes for "type: <name>[, <name>...]" /
    "cost: <dollars>" lines (case-insensitive, one per line, $ optional) -- lets a one-off
    event declare its own spend classification without needing its calendar
    pre-mapped in EVENT_CALS, e.g. a hangout with Chloe on your everyday
    calendar noted "type: friends\\ncost: 30". An explicit cost always wins
    over the type's projected default (config.COSTS); a type with no cost
    still gets that default. Multiple types are exposed as `types`, while
    `type` remains the first entry for older single-type callers. A malformed
    cost line (non-numeric) is ignored rather than raising, same spirit as
    this module's other best-effort parsing."""
    out = {}
    if not notes:
        return out
    for key, val in _NOTE_OVERRIDE_RE.findall(notes):
        key = key.lower()
        if key == "type":
            types = [_normalize_note_type(p) for p in val.split(",") if p.strip()]
            if types:
                out["type"] = types[0]
                out["types"] = types
        elif key == "cost":
            try:
                parsed = float(val.strip().lstrip("$"))
            except ValueError:
                continue
            # A negative cost (typo, or an attempt to log a credit) would
            # silently INFLATE net_fun_money instead of reducing it --
            # treat the same as a malformed value rather than let a stray
            # "-" flip the sign of everything downstream.
            if parsed >= 0:
                out["cost"] = parsed
    return out

def _note_types(notes):
    overrides = _parse_note_overrides(notes)
    if "types" in overrides:
        return overrides["types"]
    typ = overrides.get("type")
    return [typ] if typ else []

def _d(iso):  return (iso or "")[:10]
def _hm(iso): return (iso or "")[11:16]
def _h(iso):
    try: return int((iso or "")[11:13])
    except ValueError: return 0

def _sleep_ok(now):
    """False if last night's sleep was genuinely short. Prefers the watch's REAL
    duration (Health Connect -> ntfy 'sleep:<minutes>'); only falls back to the
    unreliable phone-sensor heuristic if there's no real data."""
    win_start = (now - datetime.timedelta(hours=18)).isoformat(timespec="seconds")
    durs = [e for e in history.events("sleep_dur") if e["ts"] >= win_start]
    if durs:   # real watch data wins
        return (durs[-1].get("meta") or {}).get("minutes", 0) >= config.SLEEP_OK_MIN
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

def sleep_minutes_last_night(now):
    """Real sleep duration in minutes for the last night (Health Connect
    watch data only, via the same "sleep_dur" history events _sleep_ok
    prefers) -- None if no real watch data landed in the trailing 18h
    window. The phone-sensor sleep/wake heuristic _sleep_ok falls back to
    has no reliable duration, only a rested/not-rested guess, so it's not
    reused here -- a stat tile showing a heuristic-derived "duration" would
    be misleading in a way a rested/not-rested badge isn't."""
    win_start = (now - datetime.timedelta(hours=18)).isoformat(timespec="seconds")
    durs = [e for e in history.events("sleep_dur") if e["ts"] >= win_start]
    if not durs:
        return None
    return (durs[-1].get("meta") or {}).get("minutes", 0)

def _gym_blocked_dates():
    """Dates manually marked 'no gym' via the web UI."""
    try:
        return set(json.load(open(GYM_BLOCKS_FILE, encoding="utf-8")))
    except Exception:
        return set()

def gym_ring(gym_last_7d, gym_target, today_needed, today_done):
    """Deterministic {fill, color} for the widget's gym ring.

    `fill` is purely the trailing-7-day adherence ratio (gym_last_7d /
    gym_target) -- it only grows as real sessions accumulate in the window,
    and completing today's session does NOT artificially inflate it.

    `color` is a separate, same-day ACTION signal, genuinely decoupled from
    fill -- it is never derived from the gym_last_7d/gym_target ratio itself
    (a `gym_last_7d >= gym_target` fallback used to live here and silently
    just re-derived whether fill>=1.0, which is NOT independent of fill, it's
    the same number relabeled; confirmed 2026-07-14 after a "went two days in
    a row, why is this yellow" report traced to exactly that fallback firing
    on a legitimate rest day gym_engine.plan() had already decided not to
    schedule anything for):
      red    - zero sessions in the trailing 7 days (a total drought)
      yellow - today has a scheduled-not-done session: the engine (which
               already accounts for rest days via max_consecutive, blocked
               evenings, deadline-heavy days, etc. -- see gym_engine.plan())
               decided today is a go-day, per `today_needed`
      green  - nothing needed right now: today's session is already done,
               or the engine didn't schedule anything for today at all
               (whether that's because target's met, or because today's a
               rest/blocked/deadline day the engine chose to skip -- the ring
               trusts the engine's own decision rather than re-deriving a
               cruder verdict from the raw count)

    Completing today's session turns color green immediately without
    waiting for fill to catch up -- the two are intentionally independent,
    not two views of the same number."""
    fill = max(0.0, min(1.0, gym_last_7d / gym_target)) if gym_target else 0.0
    if gym_last_7d <= 0:
        color = "red"
    elif today_done:
        color = "green"
    elif today_needed:
        color = "yellow"
    else:
        color = "green"
    return {"fill": round(fill, 3), "color": color}

def _gym_scheduled_today(fs, now):
    """True if there's an open (not-yet-completed) Gym-titled task
    scheduled for today -- i.e. the scheduling engine already decided
    today is a go-day. Deliberately doesn't reuse the full gym_input():
    that also does event-calendar/course-load/adherence work this one
    boolean doesn't need."""
    today = now.date().isoformat()
    try:
        items = fs.list_items(itemType="task", query="Gym", completed=False).get("items", [])
    except Exception:
        return False
    return any((t.get("title") or "").startswith("Gym") and _d(t.get("startDateTime")) == today
               for t in items)

def gym_ring_now(fs, now):
    """Live gym ring state computed fresh from current FlowSavvy + history
    data -- used by both the daily briefing (once/day) and the direct
    task-completion API / next-tasks push (every ~10 min or on-demand), so
    a checkbox tap can return an up-to-the-second ring instead of waiting
    for tomorrow's briefing recompute."""
    today = now.date()
    trail_start = (today - datetime.timedelta(days=6)).isoformat()
    trail_end = today.isoformat()
    gym_dates = history.days_with("gym", trail_start, trail_end)
    gym_last_7d = len(gym_dates - history.days_with("gym_skip", trail_start, trail_end))
    today_done = today.isoformat() in gym_dates
    today_needed = _gym_scheduled_today(fs, now)
    target = 4
    ring = gym_ring(gym_last_7d, target, today_needed, today_done)
    return {**ring, "gym_last_7d": gym_last_7d, "gym_target": target, "today_done": today_done}

def gym_input(fs, now, sick_until=None, gym_open=None):
    """gym_open: pre-fetched "Gym"-titled open tasks, if the caller already has
    them (run_gym does, for its own cleanup pass) — avoids re-issuing the same
    FlowSavvy query a second time on every ~10-min tick. Fetches fresh if None."""
    today = now.date()
    # ROLLING 7-day windows, not the calendar week. The target is "≈N sessions in
    # any trailing 7 days" — so count workouts done in the last 7 days and blocks
    # scheduled in the next 7. Calendar-week counting reset every Monday (ignoring a
    # workout done two days ago) and let the count be gamed across the boundary.
    # The dedup path already went rolling in 9a5d8ea; this aligns the completion count.
    horizon = [today + datetime.timedelta(days=i) for i in range(7)]
    hset = {d.isoformat() for d in horizon}
    trail_start = (today - datetime.timedelta(days=6)).isoformat()
    trail_end = today.isoformat()
    gym_blocked = _gym_blocked_dates()

    if gym_open is None:
        gym_open = [t for t in fs.list_items(itemType="task", query="Gym", completed=False).get("items", [])
                    if (t.get("title") or "").startswith("Gym")]

    scheduled, sched_dates = [], set()
    for t in gym_open:
        st = t.get("startDateTime")
        if st and _d(st) in hset:
            sched_dates.add(_d(st))
            scheduled.append({"id": t["id"], "date": _d(st), "start": _hm(st),
                              "end": _hm(t.get("endDateTime")), "manual": False,
                              "started": _d(st) == today.isoformat() and _h(st) <= now.hour + 2})

    # workouts actually done in the trailing 7 days, minus any flagged "don't count"
    # (gym-nocount). A day that's both done and still on the calendar counts once, so
    # subtract scheduled dates here to avoid double-counting the today overlap.
    # history.days_with() re-parses the whole history.jsonl per call, so query
    # it once for "gym" and derive both completed_count and completed_dates
    # from that single result instead of scanning the file twice.
    gym_dates = history.days_with("gym", trail_start, trail_end)
    done_dates = gym_dates - history.days_with("gym_skip", trail_start, trail_end)
    completed_count = len(done_dates - sched_dates)
    # days he PHYSICALLY trained in the trailing week (skips included — his
    # muscles don't care about scorekeeping) so the engine's consecutive-day
    # cap sees them, same window as completed_count above.
    completed_dates = sorted(gym_dates)

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
                     "sleep_ok": sleep_ok if near else True,
                     "gym_blocked": ds in gym_blocked})

    # adherence: stop scheduling slots he doesn't honor; use his real evening time
    adh = adherence.gym(now)
    allow_morning = not (adh["morning_rate"] is not None and adh["morning_rate"] < 0.3)
    eh = adh["pref_evening_hour"]
    es = f"{eh:02d}:00" if eh and 17 <= eh <= 20 else "19:00"
    rules = {"allow_morning": allow_morning, "evening_start": es,
             "evening_end": f"{int(es[:2]) + 1:02d}:00"}
    return {"today": today.isoformat(), "now": now.isoformat(timespec="seconds"),
            "sick_until": sick_until, "completed_count": completed_count,
            "completed_dates": completed_dates,
            "scheduled": scheduled, "days": days, "rules": rules}

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
        # No short lower bound: an incomplete coursework item doesn't stop
        # being owed just because its due date passed -- Canvas readings
        # routinely sit overdue-but-pending for a week+ while still needing
        # to get done, and dropping them at 24h made them invisible to both
        # the hours-next-7d total and the at-risk check, not just the
        # briefing text (confirmed 2026-07-12: 9-days-overdue readings
        # silently excluded from a "0.5h due this week" figure).
        # A real upper bound still applies (~1 semester): LIST_COURSE isn't
        # necessarily cleared out between terms, and an item abandoned
        # months ago (a dropped assignment, a past semester's leftover)
        # would otherwise inflate "hours due this week"/at-risk forever with
        # no cleanup mechanism to ever age it out.
        if h < -24 * 120:
            continue
        dur = t.get("durationMinutes") or 0; prog = t.get("progressMinutes") or 0
        out.append({"title": t.get("title") or "", "due_in_h": h, "due_in_days": h / 24,
                    "remaining_min": max(0, dur - prog), "progress": prog})
    return out

def deadline_input(fs, now):
    """All incomplete tasks with a real due date, across every list — the input
    for the generalized deadline-risk watchdog (homework_input is coursework-only).
    Bounded to the actionable window (skip long-overdue and far-future)."""
    out = []
    for t in fs.list_items(itemType="task", completed=False).get("items", []):
        due = t.get("dueDateTime")
        if not due:
            continue
        try:
            h = (datetime.datetime.fromisoformat(due) - now).total_seconds() / 3600
        except ValueError:
            continue
        if h < -24 or h > 24 * 21:          # skip long-overdue and >3-weeks-out
            continue
        dur = t.get("durationMinutes") or 0
        prog = t.get("progressMinutes") or 0
        out.append({"title": t.get("title") or "", "due_in_h": h, "due_in_days": h / 24,
                    "remaining_min": max(0, dur - prog), "listId": t.get("listId")})
    return out

def _upcoming_schedule(fs, now):
    """Shared fetch for next_tasks_input/today_events_input -- both read from
    the same get_schedule window (it fully contains "today"), so calling
    this once and passing the result to both avoids a redundant FlowSavvy
    round-trip on every single /api/next-tasks request (which itself fires
    every 15 min per widget instance, plus once per widget placement).

    21 days, not 7: a light week with nothing auto-scheduled/fixed-time in
    the next 7 days would otherwise make next_tasks_input wrongly report
    "nothing next" even though a real next task exists just past that
    boundary (matches spend_input's existing 21-day window elsewhere in
    this file, for consistency).

    Deliberately does NOT swallow exceptions here -- a genuine FlowSavvy
    fetch failure must be distinguishable from "genuinely nothing
    scheduled." Swallowing to an empty list made a transient outage
    indistinguishable from real emptiness and silently overwrote good
    previously-fetched state with "nothing to show." Callers (web.py's
    /api/next-tasks) should catch this and fail the request rather than
    return a false-empty 200."""
    start = now.date().isoformat()
    end = (now.date() + datetime.timedelta(days=21)).isoformat()
    return fs.get_schedule(start, end).get("scheduleItems", [])

def next_tasks_input(fs, now, n=3, schedule_items=None):
    """The next n incomplete tasks by REAL start time, across every list --
    feeds the widget's "what's next" section. Unlike deadline_input (due-date
    based, for risk surfacing), this is start-time based: whatever FlowSavvy
    has queued up next, regardless of list or due date.

    Uses get_schedule (the actual computed placement), not list_items'
    startDateTime field -- that field is null for every auto-scheduled task
    (i.e. most coursework, which FlowSavvy places into time blocks itself)
    and only ever populated for fixed-time tasks like "Gym". Reading
    startDateTime alone meant a fixed-time task always won "next up" even
    when an auto-scheduled reading was genuinely scheduled sooner (confirmed
    2026-07-12: "Gym" at 6pm showed as next while several unread M07
    readings were auto-scheduled for that same morning).

    Pass a pre-fetched `schedule_items` (from _upcoming_schedule) to avoid
    re-fetching when the caller also needs today_events_input's data."""
    if schedule_items is None:
        schedule_items = _upcoming_schedule(fs, now)
    tasks = []
    for i in schedule_items:
        if i.get("itemType") != "task" or i.get("allDay"):
            continue
        if i.get("completed") or i.get("thisPartCompleted"):
            continue
        st = i.get("startTime")
        if not st or st < now.isoformat(timespec="seconds"):
            continue
        tasks.append({"id": i.get("itemId"), "title": i.get("title") or "", "start": st})
    tasks.sort(key=lambda t: t["start"])
    return tasks[:n]

def today_events_input(fs, now, n=5, schedule_items=None):
    """Today's real (timed, non-all-day) calendar events, across EVERY
    calendar -- feeds the widget's "today" line shown above the next-tasks
    list. Deliberately time-based and calendar-agnostic (via get_schedule,
    the same endpoint the calendar grid itself renders from) rather than
    scoped to EVENT_CALS/spend_input's tracked types, so something like a
    family BBQ shows up here even before/regardless of whether it's ever
    added to the paid-events tracking.

    Capped at n: the widget renders this list in a non-scrolling Glance
    Column, the same layout primitive that silently clipped ALL content
    once before (the width/height Spacer bug) -- an uncapped list on a
    busy-calendar day would reintroduce that exact "content silently
    vanishes past a point" failure class via overflow instead.

    Pass a pre-fetched `schedule_items` (from _upcoming_schedule, a superset
    that includes today) to avoid a redundant FlowSavvy round-trip when the
    caller also needs next_tasks_input's data."""
    if schedule_items is None:
        schedule_items = _upcoming_schedule(fs, now)
    today = now.date().isoformat()
    events = [i for i in schedule_items if i.get("itemType") == "event" and not i.get("allDay")
              and (i.get("startTime") or "").startswith(today)]
    events.sort(key=lambda i: i.get("startTime") or "")
    return [{"title": e.get("title") or "", "start": e.get("startTime")} for e in events[:n]]

# Process-lifetime cache for spend_input's "every calendar" sweep -- runner.py's
# _run() constructs one FlowSavvy() and passes it to every domain in the daily
# tier (spend, briefing, cashflow all call spend_input with the SAME fs/now
# within one process run, then the process exits), so a plain module-level
# cache is naturally fresh next run without needing an explicit TTL. Without
# this, that pass tripled every day once it was added (identical
# "fetch every event on every calendar" call from 3 independent call sites).
_ALL_EVENTS_CACHE = {}

def _all_events_cached(fs):
    if "events" not in _ALL_EVENTS_CACHE:
        try:
            _ALL_EVENTS_CACHE["events"] = fs.list_items(itemType="event").get("items", [])
        except Exception:
            _ALL_EVENTS_CACHE["events"] = []
    return _ALL_EVENTS_CACHE["events"]

def spend_input(fs, yn, now):
    """events: every upcoming (next 21d) spend-relevant event/task, cost
    either projected (config.COSTS[type]) or an explicit override swept from
    notes (see _parse_note_overrides). fun_money: RAW current discretionary
    balance -- unchanged meaning, since spend_engine.plan and run_cashflow's
    projection both re-derive "balance vs. upcoming cost" themselves and
    would double-subtract if this were already netted. net_fun_money: same
    balance minus every swept event's cost -- "what's actually free to
    assign right now" once known future spend is accounted for -- is what
    the daily briefing/widget shows instead of the raw balance."""
    caltype = config.EVENT_CALS
    start = now.date().isoformat(); end = (now.date() + datetime.timedelta(days=21)).isoformat()
    events = []
    seen_ids = set()

    def _event_key(e, st):
        # Prefer the real id; an event missing one (seen from some
        # FlowSavvy responses) still needs a stable dedup key, or the
        # broad sweep below re-adds it a second time and double-counts its
        # cost -- (startDateTime, title) is stable across the two fetches
        # for the same underlying event.
        eid = e.get("id")
        return eid if eid is not None else (st, e.get("title"))

    def _spend_event(st, typ, notes, label, default_typ_cost=None):
        """Builds one events[] entry: resolves the note overrides, the
        final cost (explicit override > this type's projected default >
        the ORIGINAL type's default, for when an override changes the type
        but not the cost), and days_until -- shared by all three passes
        below so they can't drift on how an event dict is shaped."""
        overrides = _parse_note_overrides(notes)
        typ_final = overrides.get("type", typ)
        fallback_cost = config.COSTS.get(typ_final, default_typ_cost if default_typ_cost is not None
                                          else config.COSTS.get(typ, 40))
        cost = overrides.get("cost", fallback_cost)
        du = (datetime.date.fromisoformat(_d(st)) - now.date()).days
        return {"date": _d(st), "type": typ_final, "cost": cost,
                "label": label or typ_final, "days_until": du}

    for cid, typ in caltype.items():
        try:
            evs = fs.list_items(itemType="event", calendarId=cid).get("items", [])
        except Exception:
            evs = []
        for e in evs:
            st = e.get("startDateTime")
            if not st or not (start <= _d(st) <= end):
                continue
            events.append(_spend_event(st, typ, e.get("notes"), e.get("title"),
                                        default_typ_cost=config.COSTS.get(typ, 40)))
            seen_ids.add(_event_key(e, st))
    # Sweep every OTHER calendar too, for events that declare their own
    # type/cost via notes -- e.g. a hangout with a friend that lives on your
    # everyday calendar, never mapped in EVENT_CALS, noted "type: friends" /
    # "cost: 30". Unlike the EVENT_CALS pass above, a bare event with no
    # "type:" note is skipped: without that, every appointment on every
    # calendar would get swept in.
    all_evs = _all_events_cached(fs)
    for e in all_evs:
        st = e.get("startDateTime")
        if _event_key(e, st) in seen_ids:
            continue
        if not st or not (start <= _d(st) <= end):
            continue
        notes = e.get("notes")
        if not _parse_note_overrides(notes).get("type"):
            continue
        events.append(_spend_event(st, None, notes, e.get("title")))
    for t in fs.list_items(itemType="task", completed=False).get("items", []):
        title = t.get("title") or ""; st = t.get("startDateTime") or t.get("dueDateTime")
        if title in (config.PARTNER_TASK, config.FRIENDS_TASK) and st and start <= _d(st) <= end:
            typ = "date" if title == config.PARTNER_TASK else "friends"
            events.append(_spend_event(st, typ, t.get("notes"), title))
    disc = set(config.DISCRETIONARY)
    try:
        month = yn.month()
    except Exception:
        month = {"categories": []}
    fun = sum(c.get("balance", 0) for c in month.get("categories", [])
              if c["name"].lower() in disc) / 1000.0
    net_fun = fun - sum(e.get("cost", 0) or 0 for e in events)
    return {"events": events, "fun_money": fun, "net_fun_money": net_fun}

def social_input(fs, now):
    def ago(ts):
        return (now - datetime.datetime.fromisoformat(ts)).days if ts else None
    start = now.date().isoformat(); weekend = (now.date() + datetime.timedelta(days=7)).isoformat()
    open_tasks = fs.list_items(itemType="task", completed=False).get("items", [])
    def _has_hold(base):
        """A real task or a LifeOps placeholder counts as a scheduling hold
        for the social engine, so it does not create duplicate holds. The
        widget's "next" date is computed separately from real commitments
        only."""
        return any((t.get("title") or "") in (base, f"{base} (proposed)", f"Plan {base}")
                   for t in open_tasks)
    def _is_lifeops_social_placeholder(title):
        return title in {
            f"{config.PARTNER_TASK} (proposed)", f"Plan {config.PARTNER_TASK}",
            f"{config.FRIENDS_TASK} (proposed)", f"Plan {config.FRIENDS_TASK}",
        }
    def _is_lifeops_generated_social_task(t):
        title = t.get("title") or ""
        notes = t.get("notes") or ""
        return _is_lifeops_social_placeholder(title) or "Locked in (LifeOps)" in notes
    def _is_friend_commitment(t):
        return (
            (t.get("title") or "") == config.FRIENDS_TASK or
            _is_friend_hangout(t.get("title"), t.get("notes")) or
            "friends" in _note_types(t.get("notes"))
        )
    def _next_task_date(match):
        """Earliest scheduled date (today or later) among open tasks matching
        a real commitment predicate, using startDateTime or, for auto-
        scheduled tasks, dueDateTime."""
        dates = []
        for t in open_tasks:
            if not match(t):
                continue
            st = t.get("startDateTime") or t.get("dueDateTime")
            if st and _d(st) >= start:
                dates.append(datetime.date.fromisoformat(_d(st)))
        return min(dates) if dates else None
    def _next_event_date(events, match):
        dates = []
        for e in events:
            st = e.get("startDateTime")
            if st and start <= _d(st) <= weekend and match(e):
                dates.append(datetime.date.fromisoformat(_d(st)))
        return min(dates) if dates else None
    def _min_date(*dates):
        present = [d for d in dates if d is not None]
        return min(present) if present else None

    has_partner = _has_hold(config.PARTNER_TASK)
    # A task titled "Friends" always counts, same as before -- but so does
    # any task that _is_friend_hangout would also log as a friend hangout
    # (a FRIEND_NAMES match, or "friend(s)" in title/notes), so a hangout
    # scheduled under someone's actual name doesn't go unrecognized here and
    # get double-proposed/nagged about.
    has_friend = _has_hold(config.FRIENDS_TASK) or any(
        not _is_lifeops_social_placeholder(t.get("title") or "") and
        _is_friend_commitment(t) for t in open_tasks)
    partner_next = _next_task_date(lambda t: (t.get("title") or "") == config.PARTNER_TASK
                                   and not _is_lifeops_generated_social_task(t))
    friend_next = _next_task_date(lambda t: (
        not _is_lifeops_generated_social_task(t) and _is_friend_commitment(t)
    ))

    try:
        friend_event_next = _next_event_date(fs.list_items(itemType="event").get("items", []),
                                             lambda e: _is_friend_hangout(e.get("title"), e.get("notes")) or
                                             "friends" in _note_types(e.get("notes")))
        friend_next = _min_date(friend_next, friend_event_next)
        if friend_event_next is not None:
            has_friend = True
    except Exception:
        pass
    for cid, typ in config.EVENT_CALS.items():
        if typ != "friends":
            continue
        try:
            mapped_next = _next_event_date(fs.list_items(itemType="event", calendarId=cid).get("items", []),
                                           lambda _e: True)
            friend_next = _min_date(friend_next, mapped_next)
            if mapped_next is not None:
                has_friend = True
        except Exception:
            pass
    if config.SOCIAL_CAL:
        try:
            for e in fs.list_items(itemType="event", calendarId=config.SOCIAL_CAL).get("items", []):
                st = e.get("startDateTime")
                if st and start <= _d(st) <= weekend:
                    has_partner = True
                    ed = datetime.date.fromisoformat(_d(st))
                    if partner_next is None or ed < partner_next:
                        partner_next = ed
        except Exception:
            pass
    # Never propose the next weekly social hold inside the current 7-day
    # cadence window; it should reserve capacity for the next cycle.
    lo = max(7, config.PROPOSE_AHEAD_DAYS - 3); hi = config.PROPOSE_AHEAD_DAYS + 4
    days = [now.date() + datetime.timedelta(days=i) for i in range(lo, hi)]
    days.sort(key=lambda d: (d.weekday() < 5, d))   # weekends first, ~3 weeks out
    def _until(d):
        return (d - now.date()).days if d else None
    return {"partner_days": ago(history.last("partner")), "friend_days": ago(history.last("friends")),
            "has_partner": has_partner, "has_friend": has_friend,
            "partner_days_until": _until(partner_next), "friend_days_until": _until(friend_next),
            "good_days": [d.isoformat() for d in days],
            "is_protect_day": now.strftime("%a") in ("Sun", "Thu")}
