"""Shared infrastructure for the control-panel routers: constants, the
page-context builder, and helpers used by more than one router. Anything
used by only a single router lives as a private helper in that router's
own module instead of here.
"""
import os, re, sys, subprocess, datetime, tempfile
from contextlib import contextmanager
from pathlib import Path
from fastapi import HTTPException, Request
from fastapi.templating import Jinja2Templates
from markupsafe import Markup, escape
from .. import config, history, gather, lock, state_store, db, actions
from ..flowsavvy import FlowSavvy

STATIC_DIR = Path(__file__).parent.parent / "static"
TEMPLATES = Jinja2Templates(directory=str(Path(__file__).parent.parent / "templates"))

ROOT              = history.ROOT
DOMAINS_FILE      = os.path.join(ROOT, "private", "logs", "domains.json")
# Shared with gather.py's engine-feed reader — must be the SAME path so the
# writer (this UI) and reader (gather.gym_input) can never silently diverge.
GYM_BLOCKS_FILE   = gather.GYM_BLOCKS_FILE
GYM_STATE_FILE    = os.path.join(ROOT, "private", "logs", "gym_state.json")
SCHED_BLOCKS_FILE = os.path.join(ROOT, "private", "logs", "schedule_blocks.json")
ENV               = str(config.ENV_FILE)

ALL_DOMAINS  = ["gym", "ynab", "chore", "catchup", "homework", "spend", "social", "meal", "digest",
                "canvas", "briefing", "deadlines", "cashflow"]
DOMAIN_ICON  = {"gym": "🏋️", "ynab": "💰", "chore": "🧹", "catchup": "⚡",
                "homework": "📚", "spend": "💸", "social": "👫", "meal": "🍽️", "digest": "📝",
                "canvas": "🎓", "briefing": "☀️", "deadlines": "⏰", "cashflow": "📈"}
EDITABLE     = ["PARTNER_NAME", "PARTNER_TASK", "PARTNER_SIGNAL", "FRIENDS_TASK", "FRIEND_NAMES",
                "DISCRETIONARY", "OUTING_COSTS", "YNAB_COVER_ORDER", "YNAB_NO_ASSIGN",
                "EVENT_CALS", "SOCIAL_CAL", "BLOCK_CAL"]
# Human-readable label + one-line help per EDITABLE key, grouped for
# display -- the settings page used to just print the raw .env variable
# name, which meant reading source to know what e.g. YNAB_COVER_ORDER even
# was. Value: (group, label, help).
CONFIG_META = {
    "PARTNER_NAME":       ("Partner", "Partner's name", "Shown in briefings and hangout tracking."),
    "PARTNER_TASK":       ("Partner", "Partner-time task title", "FlowSavvy task title that counts as partner time."),
    "PARTNER_SIGNAL":     ("Partner", "Partner-seen signal phrase", "ntfy signal body that marks partner time done."),
    "FRIENDS_TASK":       ("Friends", "Friends task title", "FlowSavvy task title that counts as a friend hangout."),
    "FRIEND_NAMES":       ("Friends", "Friend names", "Comma-separated names that also count as a hangout."),
    "DISCRETIONARY":      ("Money", "Discretionary categories", "YNAB category names counted as fun money."),
    "OUTING_COSTS":       ("Money", "Per-outing cost estimates", "type:dollars pairs, e.g. concert:40,date:50."),
    "YNAB_COVER_ORDER":   ("Money", "Overspend cover order", "Category drain order when something overspends."),
    "YNAB_NO_ASSIGN":     ("Money", "Never auto-assign to", "Categories the categorizer must never touch."),
    "EVENT_CALS":         ("Calendars", "Paid-event calendars", "calendarId:type pairs feeding the spend projection."),
    "SOCIAL_CAL":         ("Calendars", "Partner's calendar ID", "Used to detect partner time from real events."),
    "BLOCK_CAL":          ("Calendars", "Busy-block calendar ID", "Calendar the panel's \"block this day\" writes to."),
}
ACTION_COLOR = {"gym": "#4ade80", "gym_skip": "#6b7280", "chore_done": "#60a5fa",
                "social": "#c084fc", "meal": "#fb923c", "ynab": "#fbbf24",
                "homework": "#38bdf8", "digest": "#a78bfa", "sleep": "#818cf8",
                "course": "#34d399", "course_task": "#22d3ee"}


# ── shared low-level helpers ────────────────────────────────────────────────

def _domains():
    return state_store.load_json(DOMAINS_FILE, default={})

def _write_json(path, value):
    state_store.save_json_atomic(path, value)

@contextmanager
def _exclusive():
    try:
        lock.acquire()
    except lock.Locked:
        raise HTTPException(status_code=409, detail="LifeOps is already running; retry shortly.")
    try:
        yield
    finally:
        lock.release()

def _env_value(key):
    try:
        for line in open(ENV, encoding="utf-8"):
            if line.strip().startswith(key + "="):
                return line.split("=", 1)[1].strip()
    except FileNotFoundError:
        pass
    return ""

def _config_groups():
    """EDITABLE keys bucketed by CONFIG_META's group, in first-seen order --
    the settings page renders one form field per key (human label + help,
    not the raw env var name) inside a handful of labeled groups instead of
    a flat table of 14 anonymous rows."""
    groups = {}
    for key in EDITABLE:
        group, label, help_text = CONFIG_META.get(key, ("Other", key, ""))
        groups.setdefault(group, []).append(
            {"key": key, "label": label, "help": help_text, "value": _env_value(key)})
    # "fields", not "items" -- Jinja's `.` access tries getattr() before
    # dict lookup, and dicts already have a real .items() METHOD, so
    # `group.items` in the template would silently resolve to that bound
    # method instead of this list (confirmed 2026-07-13).
    return [{"name": name, "fields": fields} for name, fields in groups.items()]

def _cycle_tasks(fs):
    out = []
    for t in fs.list_items(itemType="task", completed=False).get("items", []):
        m = re.search(r"\[cycle:(\d+)d\]", t.get("notes") or "")
        if m:
            out.append({"id": t["id"], "title": t.get("title") or "",
                        "days": int(m.group(1)), "due": (t.get("dueDateTime") or "")[:10],
                        "dur": t.get("durationMinutes"), "list": t.get("listId"),
                        "sh": t.get("schedulingHoursId")})
    return sorted(out, key=lambda x: x["title"].lower())

def _run_domain(name):
    subprocess.Popen([sys.executable, "-m", "lifeops.runner", name], cwd=ROOT,
                     creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))

def _validate_domain(name):
    """True if `name` is a real, runnable domain -- never pass arbitrary
    argv through to _run_domain. Shared by the form and JSON /run routes."""
    return name in ALL_DOMAINS

def _last_run():
    lr = state_store.load_json(os.path.join(ROOT, "private", "logs", "last_run.json"))
    if lr is None:
        return None
    ts = lr.get("ts")
    age_mins = None
    if ts:
        try:
            delta = datetime.datetime.now() - datetime.datetime.fromisoformat(ts)
            age_mins = int(delta.total_seconds() // 60)
        except Exception:
            pass
    return {"ts": ts, "ran": lr.get("ran", []), "errors": lr.get("errors") or {},
            "details": lr.get("details") or {}, "age_mins": age_mins}


# ── gym / activity calendars ────────────────────────────────────────────────

def _gym_stats(events=None):
    """events: a pre-loaded history.events() list to filter in memory
    instead of re-reading/re-parsing history.jsonl from disk (callers that
    already have the list nearby, e.g. _build_context, should pass it in).
    Defaults to loading it here for standalone callers like /api/status."""
    events = history.events() if events is None else events
    cutoff     = (datetime.date.today() - datetime.timedelta(weeks=4)).isoformat()
    evts       = [e for e in events if e.get("action") == "gym" and e["ts"][:10] >= cutoff]
    skip_dates = {e["ts"][:10] for e in events if e.get("action") == "gym_skip"}
    real       = [e for e in evts if e["ts"][:10] not in skip_dates]
    morning    = sum(1 for e in real
                     if datetime.datetime.fromisoformat(e["ts"]).hour < 12)
    week_start = (datetime.date.today()
                  - datetime.timedelta(days=datetime.date.today().weekday())).isoformat()
    this_week  = sum(1 for e in real if e["ts"][:10] >= week_start)
    return {"total_4w": len(real), "morning": morning,
            "evening": len(real) - morning, "this_week": this_week}

def _dates_with(events, action, start, end):
    """In-memory equivalent of history.days_with(), filtering an
    already-loaded events list instead of re-reading history.jsonl."""
    return {e["ts"][:10] for e in events
            if e.get("action") == action and start <= e["ts"][:10] <= end}

def _cal_range():
    """Mon-aligned 2-week window: last week + this week (which runs a few
    days into the future). Shared by every activity calendar so they all
    line up on the same grid."""
    today          = datetime.date.today()
    monday_this_wk = today - datetime.timedelta(days=today.weekday())
    start          = monday_this_wk - datetime.timedelta(weeks=1)
    end            = monday_this_wk + datetime.timedelta(days=6)
    return today, start, end

def _calendar_days(went, skipped=None, blocked=None):
    """Builds the day-cell list for one activity's calendar grid, given the
    sets of dates (YYYY-MM-DD) in each state."""
    today, start, end = _cal_range()
    skipped = skipped or set()
    blocked = blocked or set()
    days = []
    for i in range((end - start).days + 1):
        d  = start + datetime.timedelta(days=i)
        ds = d.isoformat()
        if ds in blocked:
            state = "blocked"
        elif ds in went:
            state = "went"
        elif ds in skipped:
            state = "skip"
        else:
            state = "neutral"
        days.append({"date": ds, "day": d.day, "state": state,
                     "today": d == today, "future": d > today})
    return days

def _gym_calendar(events):
    """Past/today cells cycle went -> didn't-go -> blank; today/future cells
    cycle blank -> don't-schedule -> blank. Backed by the same history
    actions and gym_blocks list the other gym controls use. `events`: a
    pre-loaded history.events() list (see _gym_stats)."""
    _, start, end = _cal_range()
    went    = _dates_with(events, "gym",      start.isoformat(), end.isoformat())
    skipped = _dates_with(events, "gym_skip", start.isoformat(), end.isoformat())
    return _calendar_days(went, skipped, set(_gym_blocks()))

def _social_calendar(events, action):
    """Same grid as gym, but for a plain went/didn't-go social activity
    (partner, friends) — no skip or block states, those are gym-scheduling
    concepts that don't apply here."""
    _, start, end = _cal_range()
    went = _dates_with(events, action, start.isoformat(), end.isoformat())
    return _calendar_days(went)

def _social_status(label, days_since, days_until):
    """Small display model for the home-page social tracker. It keeps the
    log calendar anchored to the current cadence question instead of making
    the user inspect a grid before knowing whether anything needs action."""
    if days_since is None:
        primary = "No history"
        secondary = "Log the last hangout when you know it."
        tone = "empty"
    else:
        primary = f"{days_since}d ago"
        if days_until is not None:
            secondary = f"Next in {days_until}d"
            tone = "planned"
        elif days_since >= 7:
            secondary = "No next plan"
            tone = "watch"
        else:
            secondary = "No next plan"
            tone = "ok"
    return {"label": label, "primary": primary, "secondary": secondary, "tone": tone}

def _days_since_event(events, action, today):
    dates = []
    for e in events:
        if e.get("action") != action:
            continue
        try:
            d = datetime.date.fromisoformat(e.get("ts", "")[:10])
        except ValueError:
            continue
        if d <= today:
            dates.append(d)
    return (today - max(dates)).days if dates else None

def _gym_blocks():
    """Future-only blocked dates, sorted."""
    today = datetime.date.today().isoformat()
    dates = state_store.load_json(GYM_BLOCKS_FILE, default=[])
    return sorted(d for d in dates if d >= today)

def _save_gym_blocks(dates):
    today = datetime.date.today().isoformat()
    pruned = sorted({d for d in dates if d >= today})
    _write_json(GYM_BLOCKS_FILE, pruned)

def _gym_sick_until():
    return state_store.load_json(GYM_STATE_FILE, default={}).get("sick_until") or ""

def _save_gym_sick_until(date_str):
    state = state_store.load_json(GYM_STATE_FILE, default={})
    if date_str:
        state["sick_until"] = date_str
    else:
        state.pop("sick_until", None)
    _write_json(GYM_STATE_FILE, state)

def _log_gym_went(date):
    """Logs a gym session for `date`, e.g. from the calendar click-through or
    the quick-action API. The calendar only shows gym/gym_skip, not the
    nightly cleanup's separate "gym_missed" marker (runner.py) -- so a day
    the cleanup already auto-logged missed still reads as neutral here.
    Clear it before logging "went", or adherence.gym()'s rate() would
    double-count this date as both done and missed."""
    history.remove_day("gym_missed", date)
    history.append("gym", ts=f"{date}T12:00:00", source="ui")

def _log_gym_skip():
    """Logs a deliberate same-day gym skip (not a missed session)."""
    history.append("gym_skip", source="ui")


# ── schedule blocks (all-domains "block this day") ─────────────────────────

def _sched_blocks():
    """General FlowSavvy busy-event blocks: [{date, event_id, label}]."""
    today = datetime.date.today().isoformat()
    entries = state_store.load_json(SCHED_BLOCKS_FILE, default=[])
    return sorted((e for e in entries if e.get("date", "") >= today), key=lambda e: e["date"])

def _save_sched_blocks(entries):
    today = datetime.date.today().isoformat()
    pruned = [e for e in entries if e.get("date", "") >= today]
    _write_json(SCHED_BLOCKS_FILE, pruned)

def _block_day(date):
    """Blocks a day for EVERYTHING: a FlowSavvy busy event (if BLOCK_CAL is
    configured) plus entries in both sched_blocks and gym_blocks. This is the
    'all domains' block -- gym's own narrower /gym/block-date only touches
    gym_blocks. Returns a warning string (possibly empty)."""
    event_id, warn = None, ""
    if config.BLOCK_CAL:
        try:
            fs = FlowSavvy()
            r = fs.create_event(
                title="Blocked",
                calendarId=config.BLOCK_CAL,
                startDateTime=f"{date}T07:00:00",
                endDateTime=f"{date}T22:00:00",
            )
            event_id = r.get("id") or r.get("item", {}).get("id")
            fs.recalculate()
        except Exception as e:
            # do NOT swallow this: without the event, FlowSavvy is NOT blocked
            warn = f"⚠️ FlowSavvy busy event failed ({str(e)[:80]}) — only gym is blocked for {date}."
    entries = _sched_blocks()
    if not any(e["date"] == date for e in entries):
        entries.append({"date": date, "event_id": event_id})
    _save_sched_blocks(entries)
    gym_dates = _gym_blocks()
    if date not in gym_dates:
        gym_dates.append(date)
    _save_gym_blocks(gym_dates)
    return warn

def _unblock_day(date):
    """Inverse of _block_day: clears sched_blocks + gym_blocks and deletes any
    tracked FlowSavvy busy event. Safe to call even if the day was only ever
    gym-blocked (no sched_blocks entry, nothing to delete)."""
    entries = _sched_blocks()
    to_remove = [e for e in entries if e["date"] == date]
    entries = [e for e in entries if e["date"] != date]
    _save_sched_blocks(entries)
    warn = ""
    if config.BLOCK_CAL:
        try:
            fs = FlowSavvy()
            for e in to_remove:
                if e.get("event_id"):
                    fs.delete_item(e["event_id"])
            fs.recalculate()
        except Exception as e:
            warn = f"⚠️ Couldn't delete the FlowSavvy busy event ({str(e)[:80]}) — remove it manually."
    _save_gym_blocks([d for d in _gym_blocks() if d != date])
    return warn

def _do_block_day(date):
    """Core of blocking a day: runs _block_day under the exclusive lock and
    re-plans gym. Caller must validate `date` first. Shared by the form and
    JSON /schedule/block-day routes. Returns the warning string (possibly
    empty)."""
    with _exclusive():
        warn = _block_day(date)
    _run_domain("gym")
    return warn


# ── canvas panel status (surfaced on the settings page) ────────────────────

def _canvas_status():
    """Cheap status for the Accounts card — asks notify.alerted_today the
    same question runner.py's _alert_once/notify.alert(dedup_key=...)
    answers when deciding whether to (re-)send, instead of launching a
    browser on every page load. `needs_relogin` is only a same-day signal:
    it's set once the daily sync alerts that the session expired, and
    cleared the next day regardless of whether you actually re-logged in."""
    from .. import canvas_browser, notify
    today = datetime.date.today().isoformat()
    return {
        "profile_exists": canvas_browser.profile_exists(),
        "needs_relogin":  notify.alerted_today("canvas:session:" + today),
    }

def _canvas_pending():
    """The Canvas flood guard (runner.py) writes logs/canvas_pending.json,
    keyed by course_id, and HOLDS creation when a sync would make an
    implausible number of tasks (the state-loss re-sync signature). Surface
    it so the panel can show what was held per course and offer a one-tap
    approve. Returns a list (possibly empty) — one entry per course with a
    held sync."""
    p = state_store.load_json(state_store.logs_path("canvas_pending.json"))
    if not p:
        return []
    return [{"course_id": course_id, "count": info.get("count"), "at": info.get("at"),
             "titles": (info.get("titles") or [])[:30]}
            for course_id, info in p.items()]


# ── briefing / attention / cashflow ─────────────────────────────────────────

def _format_briefing_text(text):
    """Briefing text from the LLM uses **bold** and newlines as plain markup —
    escape it first (it's untrusted-ish free text), then turn those two markers
    into real HTML so bold renders and each line is its own line."""
    html = str(escape(text or ""))
    html = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", html)
    html = html.replace("\n", "<br>")
    return Markup(html)

def _today_briefing():
    """The daily briefing (run_briefing) writes private/logs/briefing.json.
    Show it only if it's from today — a stale briefing is worse than none."""
    b = state_store.load_json(os.path.join(ROOT, "private", "logs", "briefing.json"))
    if not b or b.get("date") != datetime.date.today().isoformat():
        return None
    facts = b.get("facts") or {}
    return {"text": _format_briefing_text(b.get("text", "")), "facts": facts,
            "attention": facts.get("attention")}

def _today_briefing_raw():
    """Same staleness check as _today_briefing(), but without the HTML
    formatting step — for API clients (e.g. the Android widget) that want the
    raw **bold**/\\n markup to style themselves rather than <strong>/<br>."""
    b = state_store.load_json(os.path.join(ROOT, "private", "logs", "briefing.json"))
    if not b or b.get("date") != datetime.date.today().isoformat():
        return None
    return {"date": b.get("date"), "text": b.get("text", ""), "facts": b.get("facts") or {}}

def _current_attention(briefing=None, lr=None):
    """Deterministic attention state for the given (or freshly-loaded)
    briefing facts + last-run health. Shared by _build_context (panel home)
    and the mutation endpoints below, so a POST can hand the caller a fresh
    read of system-health reasons (panel stale/erroring) without a second
    round trip. NOTE: this is NOT fresh for domain facts like gym_last_7d --
    those only get recomputed by the once-daily run_briefing tier, so a
    gym/coursework/money-related reason can still reflect this morning's
    numbers even right after a mutation that would change them.

    `lr` must stay None (not {}) when there's genuinely no last-run data --
    attention.compute distinguishes "no system data" (system=None) from
    "system data present but everything empty" (system={}), so coercing
    with `lr or {}` here previously turned a fresh install's missing
    logs/last_run.json into a false 'risk: LifeOps data is stale' reading."""
    from .. import attention
    briefing = _today_briefing() if briefing is None else briefing
    lr = _last_run() if lr is None else lr
    return attention.compute((briefing or {}).get("facts") or {}, lr)

def _cashflow():
    """Panel-only forward discretionary-balance projection (run_cashflow writes
    private/logs/cashflow.json; no notifications by design). Adds a `bar_pct`
    per week for a simple inline bar, scaled to the starting balance. Today's
    only."""
    c = state_store.load_json(os.path.join(ROOT, "private", "logs", "cashflow.json"))
    if not c or c.get("date") != datetime.date.today().isoformat():
        return None
    peak = max([c.get("start_balance", 0)] + [w.get("balance", 0) for w in c.get("weeks", [])] + [1])
    for w in c.get("weeks", []):
        w["bar_pct"] = max(0, min(100, round(100 * w.get("balance", 0) / peak))) if peak else 0
        w["negative"] = w.get("balance", 0) < 0
    return c


# ── widget push health ──────────────────────────────────────────────────────

def _fcm_health():
    """Cheap widget-push health summary -- token presence/age plus the
    outcome of the most recent send attempt (fcm._send persists both via
    db.local_set). Not delivery confirmation, just enough to notice "no
    token registered" or "sends have been no-oping" from the panel instead
    of only discovering it from a silent widget."""
    tok = db.local_get("fcm_token", default={}) or {}
    registered_at = tok.get("registered_at")
    age_hours = None
    if registered_at:
        try:
            delta = datetime.datetime.now() - datetime.datetime.fromisoformat(registered_at)
            # max(0, ...): naive-local-time subtraction can go slightly
            # negative across a DST fall-back rather than reflect a
            # meaningful negative age.
            age_hours = max(0.0, round(delta.total_seconds() / 3600, 1))
        except Exception:
            pass
    return {
        "fcm_token_registered": bool(tok.get("token")),
        "fcm_token_age_hours":  age_hours,
        "fcm_last_send":        db.local_get("fcm_last_send", default=None),
    }


# ── page context builder ────────────────────────────────────────────────────

def _build_context(fs=None, include_cycle=False):
    lr  = _last_run()
    dom = _domains()
    # Loaded once and threaded through every history-derived section below
    # (stats, both calendars, the history list) instead of each one
    # independently re-reading and re-parsing history.jsonl from disk.
    all_events = history.events()
    gs  = _gym_stats(all_events)
    cycle_tasks = []
    cycle_error = ""
    if include_cycle and fs:
        try:
            cycle_tasks = _cycle_tasks(fs)
        except Exception as e:
            cycle_error = f"FlowSavvy is unavailable ({str(e)[:100]})."

    # status bar
    if lr is None:
        dot, text = "⚫", "never run"
    else:
        mins = lr["age_mins"]
        if mins is None:
            dot, text = "⚫", lr["ts"] or "?"
        elif mins < 20:
            dot, text = "🟢", f"{mins}m ago"
        elif mins < 120:
            dot, text = "🟡", f"{mins}m ago"
        else:
            dot, text = "🔴", f"{mins // 60}h ago"
        if lr["errors"]:
            text += " · errors: " + ", ".join(lr["errors"])
        if lr["ran"]:
            text += " · ran: " + ", ".join(lr["ran"])

    # domains list
    domains = [{"name": d, "icon": DOMAIN_ICON.get(d, "•"), "enabled": dom.get(d, True)}
               for d in ALL_DOMAINS]

    # history entries — idx is the event's position in the full file (file
    # order, oldest first), not its position in this trimmed/reversed list;
    # the undo button posts idx/ts/action back so history.remove_at() can
    # verify it's still striking the exact record the page showed, not
    # whatever now sits at that position.
    raw_history = list(enumerate(all_events))[-50:][::-1]
    hist = []
    for idx, e in raw_history:
        ts = e.get("ts", "")
        try:
            display_ts = datetime.datetime.fromisoformat(ts).strftime("%m-%d %H:%M")
        except Exception:
            display_ts = ts[:16]
        meta = e.get("meta") or {}
        meta_str = (", ".join(f"{k}={v}" for k, v in meta.items())
                    if isinstance(meta, dict) else str(meta))
        hist.append({
            "idx":        idx,
            "ts":         ts,
            "display_ts": display_ts,
            "action":     e.get("action", "?"),
            "source":     e.get("source", ""),
            "color":      ACTION_COLOR.get(e.get("action", ""), "#9ca3af"),
            "meta_str":   meta_str,
        })

    # gym controls state
    gym_blocks = _gym_blocks()
    sick_until = _gym_sick_until()
    today = datetime.date.today()
    gym_block_display = [
        {"date": d, "label": datetime.date.fromisoformat(d).strftime("%a %b %d").replace(" 0", " ")}
        for d in gym_blocks
    ]

    # general schedule blocks
    sched_blocks_raw = _sched_blocks()
    sched_block_display = [
        {**b, "label": datetime.date.fromisoformat(b["date"]).strftime("%a %b %d").replace(" 0", " ")}
        for b in sched_blocks_raw
    ]

    briefing = _today_briefing()
    briefing_facts = (briefing or {}).get("facts") or {}
    partner_days_since = briefing_facts.get("partner_days_since")
    friend_days_since = briefing_facts.get("friend_days_since")
    if partner_days_since is None:
        partner_days_since = _days_since_event(all_events, "partner", today)
    if friend_days_since is None:
        friend_days_since = _days_since_event(all_events, "friends", today)
    social_status = [
        _social_status(config.PARTNER_NAME or "Partner",
                       partner_days_since,
                       briefing_facts.get("partner_days_until")),
        _social_status("Friends",
                       friend_days_since,
                       briefing_facts.get("friend_days_until")),
    ]
    current_attention = _current_attention(briefing, lr)
    return {
        "status_dot":       dot,
        "status_text":      text,
        "gym_stats":        gs,
        "domains":          domains,
        "cycle_tasks":      cycle_tasks,
        "cycle_error":      cycle_error,
        "config_groups":    _config_groups(),
        "history":          hist,
        "list_personal":    config.LIST_PERSONAL,
        "last_run_domains": ", ".join(lr["ran"]) if lr and lr["ran"] else "",
        "last_run_errors":  str(lr["errors"]) if lr and lr["errors"] else "",
        "gym_calendar":     _gym_calendar(all_events),
        "partner_calendar": _social_calendar(all_events, "partner"),
        "friends_calendar": _social_calendar(all_events, "friends"),
        "social_status":    social_status,
        "partner_name":     config.PARTNER_NAME,
        "gym_blocks":       gym_block_display,
        "gym_sick_until":   sick_until,
        "today":            today.isoformat(),
        "tomorrow":         (today + datetime.timedelta(days=1)).isoformat(),
        "sched_blocks":     sched_block_display,
        "block_cal_set":    bool(config.BLOCK_CAL),
        "canvas_status":    _canvas_status(),
        "canvas_pending":   _canvas_pending(),
        "fcm_health":       _fcm_health(),
        "recent_actions":   actions.recent(15),
        "briefing":         briefing,
        "attention":        current_attention,
        "cashflow":         _cashflow(),
    }


def _page(request: Request, template, active_page, include_cycle=False):
    """Renders a full panel page: builds the shared context (cheap enough for
    a single-user app to rebuild on every request) and hands it to the given
    template, tagging which nav item is active and pulling any one-shot flash
    message off the query string."""
    fs = FlowSavvy() if include_cycle else None
    ctx = _build_context(fs, include_cycle=include_cycle)
    ctx["flash"] = (request.query_params.get("msg") or "")[:200]
    ctx["active_page"] = active_page
    return TEMPLATES.TemplateResponse(request, template, ctx)
