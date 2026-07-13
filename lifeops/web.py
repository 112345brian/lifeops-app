"""LifeOps control panel — FastAPI web UI.

Run:   uvicorn lifeops.web:app --host 0.0.0.0 --port 8765
Reach: tailscale serve 8765  →  https://<your-pc>.<tailnet>.ts.net
"""
import os, re, sys, json, subprocess, datetime, tempfile, hmac, logging
from contextlib import contextmanager
from pathlib import Path
from urllib.parse import quote
from fastapi import FastAPI, Form, Request, HTTPException
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse, FileResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from markupsafe import Markup, escape
from . import config, history, gather, actions, lock
from .flowsavvy import FlowSavvy

STATIC_DIR = Path(__file__).parent / "static"

app = FastAPI()
TEMPLATES = Jinja2Templates(directory=str(Path(__file__).parent / "templates"))
app.mount("/static", StaticFiles(directory=str(STATIC_DIR)), name="static")


@app.get("/sw.js")
def service_worker():
    """Served at the root (not /static/sw.js) so its scope covers the whole
    app -- a service worker can only control paths at or below where it's
    registered from."""
    return FileResponse(STATIC_DIR / "sw.js", media_type="application/javascript",
                        headers={"Service-Worker-Allowed": "/"})


@app.middleware("http")
async def _auth(request: Request, call_next):
    """Optional shared-secret gate for Tailscale/funnel exposure. With WEB_TOKEN
    set, open the panel once as /?token=<secret>; a cookie keeps you in."""
    if config.WEB_TOKEN:
        query_token = request.query_params.get("token")
        supplied = query_token or request.cookies.get("lifeops_auth") or ""
        # Constant-time compare -- cheap defense-in-depth against a timing
        # side-channel, even though exploiting it would need tailnet access
        # to begin with under this threat model.
        if not hmac.compare_digest(supplied, config.WEB_TOKEN):
            return JSONResponse({"error": "unauthorized"}, status_code=401)
        # Behind a TLS-terminating proxy (Tailscale funnel etc.) Uvicorn sees
        # a plain-http request even though the real connection is https --
        # check X-Forwarded-Proto too, consistently, wherever secure= is set.
        forwarded = request.headers.get("x-forwarded-proto", "").split(",", 1)[0]
        secure = request.url.scheme == "https" or forwarded == "https"
        # Lax (not Strict): tapping an ntfy notification's Click link is a
        # top-level GET navigation from outside the app, and Strict cookies
        # are withheld on exactly that kind of navigation on some
        # OS/browser notification plumbing -- Lax still blocks the
        # cross-site POST/embed cases Strict exists to guard against.
        # Gate the redirect/cookie dance on CLIENT CAPABILITY (does this
        # look like a browser navigation?), not a URL-prefix convention --
        # scoping it to "/api/*" meant the identical bug (bare HTTP clients
        # with no CookieHandler redirect-then-401 forever, confirmed via
        # web.log: next-tasks requests 303'd, then immediately 401'd, and
        # NEXT_TASKS_JSON never once populated) would resurface the moment
        # any future non-/api/ integration used a bare client, or a future
        # browser-facing route happened to live under /api/. A real browser
        # top-level navigation sends "text/html" in Accept; the widget's
        # bare HttpURLConnection and any similar client don't.
        accepts_html = "text/html" in request.headers.get("accept", "")
        if query_token and request.method in ("GET", "HEAD") and accepts_html:
            clean_url = request.url.remove_query_params("token")
            resp = RedirectResponse(clean_url, status_code=303)
            resp.set_cookie("lifeops_auth", config.WEB_TOKEN,
                            max_age=90 * 24 * 3600, httponly=True,
                            secure=secure, samesite="lax")
            return resp
        resp = await call_next(request)
        if accepts_html and query_token == config.WEB_TOKEN:
            resp.set_cookie("lifeops_auth", config.WEB_TOKEN,
                            max_age=90 * 24 * 3600, httponly=True,
                            secure=secure, samesite="lax")
        return resp
    return await call_next(request)

ROOT              = history.ROOT
DOMAINS_FILE      = os.path.join(ROOT, "logs", "domains.json")
# Shared with gather.py's engine-feed reader — must be the SAME path so the
# writer (this UI) and reader (gather.gym_input) can never silently diverge.
GYM_BLOCKS_FILE   = gather.GYM_BLOCKS_FILE
GYM_STATE_FILE    = os.path.join(ROOT, "logs", "gym_state.json")
SCHED_BLOCKS_FILE = os.path.join(ROOT, "logs", "schedule_blocks.json")
ALERT_STATE_FILE  = os.path.join(ROOT, "logs", "alert_state.json")
ENV               = str(config.ENV_FILE)

ALL_DOMAINS  = ["gym", "ynab", "chore", "catchup", "homework", "spend", "social", "meal", "digest",
                "canvas", "briefing", "deadlines", "cashflow"]
DOMAIN_ICON  = {"gym": "🏋️", "ynab": "💰", "chore": "🧹", "catchup": "⚡",
                "homework": "📚", "spend": "💸", "social": "👫", "meal": "🍽️", "digest": "📝",
                "canvas": "🎓", "briefing": "☀️", "deadlines": "⏰", "cashflow": "📈"}
EDITABLE     = ["PARTNER_NAME", "PARTNER_TASK", "PARTNER_SIGNAL", "FRIENDS_TASK", "FRIEND_NAMES",
                "PROPOSE_AHEAD_DAYS", "PLAN_LEAD_DAYS",
                "DISCRETIONARY", "OUTING_COSTS", "YNAB_COVER_ORDER", "YNAB_NO_ASSIGN",
                "EVENT_CALS", "SOCIAL_CAL", "BLOCK_CAL"]
ACTION_COLOR = {"gym": "#4ade80", "gym_skip": "#6b7280", "chore_done": "#60a5fa",
                "social": "#c084fc", "meal": "#fb923c", "ynab": "#fbbf24",
                "homework": "#38bdf8", "digest": "#a78bfa", "sleep": "#818cf8",
                "course": "#34d399", "course_task": "#22d3ee"}


# ── helpers ───────────────────────────────────────────────────────────────────

def _domains():
    try:
        return json.load(open(DOMAINS_FILE, encoding="utf-8"))
    except Exception:
        return {}

def _write_json(path, value):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=Path(path).name + "-", dir=os.path.dirname(path))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(value, f)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
    finally:
        try:
            os.remove(tmp)
        except FileNotFoundError:
            pass

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

def _set_env(key, val):
    lines = []
    try:
        lines = open(ENV, encoding="utf-8").read().splitlines()
    except FileNotFoundError:
        pass
    for i, l in enumerate(lines):
        if l.strip().startswith(key + "="):
            lines[i] = f"{key}={val}"
            break
    else:
        lines.append(f"{key}={val}")
    os.makedirs(os.path.dirname(ENV), exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix="env-", dir=os.path.dirname(ENV))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, ENV)
    finally:
        try:
            os.remove(tmp)
        except FileNotFoundError:
            pass

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

def _last_run():
    try:
        lr = json.load(open(os.path.join(ROOT, "logs", "last_run.json"), encoding="utf-8"))
    except Exception:
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

def _gym_blocks():
    """Future-only blocked dates, sorted."""
    today = datetime.date.today().isoformat()
    try:
        dates = json.load(open(GYM_BLOCKS_FILE, encoding="utf-8"))
    except Exception:
        dates = []
    return sorted(d for d in dates if d >= today)

def _save_gym_blocks(dates):
    today = datetime.date.today().isoformat()
    pruned = sorted({d for d in dates if d >= today})
    os.makedirs(os.path.dirname(GYM_BLOCKS_FILE), exist_ok=True)
    _write_json(GYM_BLOCKS_FILE, pruned)

def _gym_sick_until():
    try:
        return json.load(open(GYM_STATE_FILE, encoding="utf-8")).get("sick_until") or ""
    except Exception:
        return ""

def _save_gym_sick_until(date_str):
    state = {}
    try:
        state = json.load(open(GYM_STATE_FILE, encoding="utf-8"))
    except Exception:
        pass
    if date_str:
        state["sick_until"] = date_str
    else:
        state.pop("sick_until", None)
    os.makedirs(os.path.dirname(GYM_STATE_FILE), exist_ok=True)
    _write_json(GYM_STATE_FILE, state)

def _sched_blocks():
    """General FlowSavvy busy-event blocks: [{date, event_id, label}]."""
    today = datetime.date.today().isoformat()
    try:
        entries = json.load(open(SCHED_BLOCKS_FILE, encoding="utf-8"))
    except Exception:
        entries = []
    return sorted((e for e in entries if e.get("date", "") >= today), key=lambda e: e["date"])

def _save_sched_blocks(entries):
    today = datetime.date.today().isoformat()
    pruned = [e for e in entries if e.get("date", "") >= today]
    os.makedirs(os.path.dirname(SCHED_BLOCKS_FILE), exist_ok=True)
    _write_json(SCHED_BLOCKS_FILE, pruned)

def _canvas_status():
    """Cheap status for the Accounts card — reads the same dedup log runner.py
    writes to (logs/alert_state.json) instead of launching a browser on every
    page load. `needs_relogin` is only a same-day signal: it's set once the
    daily sync alerts that the session expired, and cleared the next day
    regardless of whether you actually re-logged in."""
    from . import canvas_browser
    today = datetime.date.today().isoformat()
    try:
        st = json.load(open(ALERT_STATE_FILE, encoding="utf-8"))
    except Exception:
        st = {}
    return {
        "profile_exists": canvas_browser.profile_exists(),
        "needs_relogin":  st.get("canvas:session:" + today) == today,
    }

def _canvas_pending():
    """The Canvas flood guard (runner.py) writes logs/canvas_pending.json and
    HOLDS creation when a sync would make an implausible number of tasks (the
    state-loss re-sync signature). Surface it so the panel can show what was
    held and offer a one-tap approve. Returns None when nothing is pending."""
    try:
        p = json.load(open(os.path.join(ROOT, "logs", "canvas_pending.json"), encoding="utf-8"))
    except Exception:
        return None
    return {"count": p.get("count"), "at": p.get("at"),
            "titles": (p.get("titles") or [])[:30]}

def _format_briefing_text(text):
    """Briefing text from the LLM uses **bold** and newlines as plain markup —
    escape it first (it's untrusted-ish free text), then turn those two markers
    into real HTML so bold renders and each line is its own line."""
    html = str(escape(text or ""))
    html = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", html)
    html = html.replace("\n", "<br>")
    return Markup(html)

def _today_briefing():
    """The daily briefing (run_briefing) writes logs/briefing.json. Show it only
    if it's from today — a stale briefing is worse than none."""
    try:
        b = json.load(open(os.path.join(ROOT, "logs", "briefing.json"), encoding="utf-8"))
    except Exception:
        return None
    if b.get("date") != datetime.date.today().isoformat():
        return None
    facts = b.get("facts") or {}
    return {"text": _format_briefing_text(b.get("text", "")), "facts": facts,
            "attention": facts.get("attention")}

def _today_briefing_raw():
    """Same staleness check as _today_briefing(), but without the HTML
    formatting step — for API clients (e.g. the Android widget) that want the
    raw **bold**/\\n markup to style themselves rather than <strong>/<br>."""
    try:
        b = json.load(open(os.path.join(ROOT, "logs", "briefing.json"), encoding="utf-8"))
    except Exception:
        return None
    if b.get("date") != datetime.date.today().isoformat():
        return None
    return {"date": b.get("date"), "text": b.get("text", ""), "facts": b.get("facts") or {}}

def _current_attention(briefing=None, lr=None):
    """Deterministic attention state for the given (or freshly-loaded)
    briefing facts + last-run health. Shared by _build_context (panel home)
    and the mutation endpoints below, so a POST that changes gym/schedule
    state can hand the caller a fresh read without a second round trip."""
    from . import attention
    briefing = _today_briefing() if briefing is None else briefing
    lr = _last_run() if lr is None else lr
    return attention.compute((briefing or {}).get("facts") or {}, lr or {})

def _cashflow():
    """Panel-only forward discretionary-balance projection (run_cashflow writes
    logs/cashflow.json; no notifications by design). Adds a `bar_pct` per week
    for a simple inline bar, scaled to the starting balance. Today's only."""
    try:
        c = json.load(open(os.path.join(ROOT, "logs", "cashflow.json"), encoding="utf-8"))
    except Exception:
        return None
    if c.get("date") != datetime.date.today().isoformat():
        return None
    peak = max([c.get("start_balance", 0)] + [w.get("balance", 0) for w in c.get("weeks", [])] + [1])
    for w in c.get("weeks", []):
        w["bar_pct"] = max(0, min(100, round(100 * w.get("balance", 0) / peak))) if peak else 0
        w["negative"] = w.get("balance", 0) < 0
    return c

def _relogin_canvas():
    subprocess.Popen([sys.executable, os.path.join(ROOT, "scripts", "canvas_relogin.py")],
                     cwd=ROOT, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))

def _restart_server():
    """Restarts the LifeOps-web scheduled task from a detached helper process,
    since stopping the task kills this process before it could run the restart
    itself. Falls back to just exiting if it's not running as that service
    (e.g. a manual `uvicorn` dev run) — nothing else will bring it back up.

    Uses CREATE_NO_WINDOW rather than DETACHED_PROCESS for the helper: with no
    console at all (DETACHED_PROCESS), powershell.exe silently exits without
    running the script — CREATE_NO_WINDOW gives it a real (hidden) console so
    it actually executes."""
    check = subprocess.run(["schtasks", "/query", "/tn", "LifeOps-web"],
                           capture_output=True,
                           creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    if check.returncode == 0:
        script = ('Start-Sleep -Milliseconds 800; '
                  'schtasks /end /tn "LifeOps-web"; '
                  'Start-Sleep -Seconds 2; '
                  'schtasks /run /tn "LifeOps-web"')
        subprocess.Popen(["powershell", "-NoProfile", "-Command", script],
                         creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    else:
        os._exit(0)

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
    current_attention = _current_attention(briefing, lr)
    return {
        "status_dot":       dot,
        "status_text":      text,
        "gym_stats":        gs,
        "domains":          domains,
        "cycle_tasks":      cycle_tasks,
        "cycle_error":      cycle_error,
        "config_items":     [{"key": k, "value": _env_value(k)} for k in EDITABLE],
        "history":          hist,
        "list_personal":    config.LIST_PERSONAL,
        "last_run_domains": ", ".join(lr["ran"]) if lr and lr["ran"] else "",
        "last_run_errors":  str(lr["errors"]) if lr and lr["errors"] else "",
        "gym_calendar":     _gym_calendar(all_events),
        "partner_calendar": _social_calendar(all_events, "partner"),
        "friends_calendar": _social_calendar(all_events, "friends"),
        "partner_name":     config.PARTNER_NAME,
        "gym_blocks":       gym_block_display,
        "gym_sick_until":   sick_until,
        "today":            today.isoformat(),
        "tomorrow":         (today + datetime.timedelta(days=1)).isoformat(),
        "sched_blocks":     sched_block_display,
        "block_cal_set":    bool(config.BLOCK_CAL),
        "canvas_status":    _canvas_status(),
        "canvas_pending":   _canvas_pending(),
        "recent_actions":   actions.recent(15),
        "briefing":         briefing,
        "attention":        current_attention,
        "cashflow":         _cashflow(),
    }


# ── JSON endpoints ─────────────────────────────────────────────────────────────

@app.get("/api/status")
def api_status():
    lr  = _last_run()
    dom = _domains()
    return JSONResponse({
        "last_run":  lr,
        "domains":   {d: dom.get(d, True) for d in ALL_DOMAINS},
        "gym_stats": _gym_stats(),
    })

@app.get("/api/history")
def api_history(n: int = 50):
    return JSONResponse(history.events()[-n:][::-1])

@app.get("/api/briefing")
def api_briefing():
    b = _today_briefing_raw()
    if b is None:
        return JSONResponse({"briefing": None}, status_code=404)
    return JSONResponse(b)

@app.get("/api/next-tasks")
def api_next_tasks(n: int = 3):
    fs = FlowSavvy()
    now = datetime.datetime.now()
    try:
        schedule_items = gather._upcoming_schedule(fs, now)
    except Exception as e:
        # A real FlowSavvy fetch failure must fail this request, not
        # silently return {"tasks": [], "events": []} -- that used to be
        # indistinguishable from genuine emptiness and would overwrite the
        # widget's perfectly good existing state with "nothing to show."
        # The Android client already treats any non-200 as "leave state
        # alone, retry later" (NextTasksRefreshWorker), so failing loudly
        # here is strictly safer than succeeding with a false empty result.
        raise HTTPException(502, f"FlowSavvy fetch failed: {e}")
    return JSONResponse({"tasks": gather.next_tasks_input(fs, now, n, schedule_items=schedule_items),
                        "events": gather.today_events_input(fs, now, schedule_items=schedule_items)})

@app.post("/api/tasks/{task_id}/complete")
def api_task_complete(task_id: str, n: int = 3):
    """Completes a task straight from the widget's checkbox tap and returns
    the fresh next-tasks list (+ today's events) in the same response, so
    the widget updates immediately without a follow-up GET. This is the
    primary completion path when the phone is reachable on the tailnet; the
    ntfy `complete:<id>` signal handled by runner.py's ingest() cycle is the
    fallback for when it isn't (see notify.py's docstring)."""
    fs = FlowSavvy()
    fs.complete_task(task_id)
    fs.recalculate()
    now = datetime.datetime.now()
    schedule_items = gather._upcoming_schedule(fs, now)
    return JSONResponse({"completed_id": task_id,
                        "tasks": gather.next_tasks_input(fs, now, n, schedule_items=schedule_items),
                        "events": gather.today_events_input(fs, now, schedule_items=schedule_items)})

@app.post("/api/gym/log")
def api_gym_log():
    """Logs a same-day gym session, same as ticking today on the home
    calendar. Quick-action equivalent of the calendar click-through, scoped
    to "today" since that's the only case a one-tap action needs."""
    today = datetime.date.today().isoformat()
    history.remove_day("gym_missed", today)  # see /gym/cycle-date's own note on this
    history.append("gym", ts=f"{today}T12:00:00", source="ui")
    _run_domain("gym")
    return JSONResponse({"ok": True, "attention": _current_attention()})

@app.post("/api/gym/skip")
def api_gym_skip():
    """JSON equivalent of the /gym-nocount form action: logs today as a
    deliberate skip (not a missed session) and re-plans immediately."""
    history.append("gym_skip", source="ui")
    _run_domain("gym")
    return JSONResponse({"ok": True, "attention": _current_attention()})

@app.post("/api/schedule/block-day")
async def api_schedule_block_day(request: Request):
    """JSON equivalent of the /schedule/block-day form action. Body:
    {"date": "YYYY-MM-DD"}."""
    try:
        body = await request.json()
    except Exception:
        raise HTTPException(400, "malformed JSON body")
    date = (body or {}).get("date", "")
    try:
        datetime.date.fromisoformat(date)
    except ValueError:
        raise HTTPException(400, "date required (YYYY-MM-DD)")
    with _exclusive():
        warn = _block_day(date)
    _run_domain("gym")
    return JSONResponse({"ok": True, "warning": warn or None, "attention": _current_attention()})

@app.post("/api/domains/{name}/run")
def api_domain_run(name: str):
    """JSON equivalent of the /run form action -- triggers one domain
    out-of-cycle (e.g. after a manual schedule change)."""
    if name not in ALL_DOMAINS:               # never pass arbitrary argv through
        raise HTTPException(404, f"unknown domain: {name[:24]}")
    _run_domain(name)
    return JSONResponse({"ok": True, "domain": name})

FCM_TOKEN_FILE = os.path.join(ROOT, "logs", "fcm_token.json")

@app.post("/api/register-fcm-token")
async def api_register_fcm_token(request: Request):
    """The widget calls this once per token (install, or whenever Firebase
    rotates it) so run_briefing knows where to push. Single-user app -- one
    token on file, last write wins."""
    try:
        body = await request.json()
    except Exception:
        raise HTTPException(400, "malformed JSON body")
    if not isinstance(body, dict):
        raise HTTPException(400, "expected a JSON object")
    token = body.get("fcm_token")
    # FCM registration tokens are long opaque strings (typically 140-200+
    # chars); a generous sanity bound catches obvious garbage without
    # hardcoding Firebase's exact format.
    if not isinstance(token, str) or not (10 <= len(token) <= 4096):
        raise HTTPException(400, "fcm_token required (string, 10-4096 chars)")
    _write_json(FCM_TOKEN_FILE, {"token": token})
    return JSONResponse({"ok": True})

@app.post("/history/undo")
def history_undo(idx: int = Form(...), ts: str = Form(""), action: str = Form("")):
    """Strike a single history entry (e.g. a duplicate 'gym' log) by its
    file position. Most actions are completion RECORDS (you did the gym
    session, you completed the "Reina" task) with nothing else to reverse --
    removing the log line is the full undo. The exception is an entry whose
    meta carries creates_task=True: that log was written right when its
    action CREATED a FlowSavvy task (e.g. the Canvas sync's "course_task"),
    and meta.id is that task's id -- so undoing it also deletes the task.
    (Other actions carry an id too, e.g. flowsavvy-sourced completions, but
    that id is the task you completed, not something the log created --
    deleting it would erase a real completion, not reverse one. Any future
    task-creating log call just needs creates_task=True in its meta; this
    endpoint doesn't need to know about it by name.)

    ts/action are the record's own fields as rendered on the History page --
    idx alone isn't a safe identifier since another tab/tick can log or
    undo something else in between, shifting every later record's file
    position. remove_at() only deletes if the record at idx still matches
    both, otherwise this is a no-op (stale page, ask the user to refresh)."""
    with _exclusive():
        events = history.events()
        warn = ""
        if 0 <= idx < len(events) and events[idx].get("ts") == ts and events[idx].get("action") == action:
            e = events[idx]
            meta = e.get("meta") or {}
            if meta.get("creates_task") and meta.get("id"):
                try:
                    fs = FlowSavvy()
                    fs.delete_item(meta["id"])
                    fs.recalculate()
                except Exception as ex:
                    warn = (f"⚠️ log removed, but couldn't delete the FlowSavvy "
                           f"task ({str(ex)[:80]}) — remove it manually.")
            if not history.remove_at(idx, expect_ts=ts, expect_action=action):
                warn = "⚠️ that entry moved — refresh History and try again."
        else:
            warn = "⚠️ that entry moved — refresh History and try again."
    return RedirectResponse(f"/history?msg={quote(warn)}" if warn else "/history", 303)


# ── action endpoints ────────────────────────────────────────────────────────────

@app.post("/cycle/new")
def cycle_new(title: str = Form(...), days: int = Form(7), duration: int = Form(30),
              listId: str = Form("")):
    with _exclusive():
        fs = FlowSavvy()
        d  = (datetime.date.today() + datetime.timedelta(days=days)).isoformat()
        fs.create_task(
            title=title,
            listId=listId or config.LIST_PERSONAL,
            durationMinutes=duration,
            minLengthMinutes=duration,
            schedulingHoursId=config.SH_PERSONAL,
            isAutoIgnored=False,
            dueDateTime=f"{d}T20:00:00",
            canBeStartedAt=f"{datetime.date.today().isoformat()}T08:00:00",
            notes=f"<p>Recurring task.</p><p>[cycle:{days}d]</p>",
        )
        fs.recalculate()
    return RedirectResponse("/recurring", 303)

@app.post("/cycle/del")
def cycle_del(id: str = Form(...)):
    with _exclusive():
        fs = FlowSavvy()
        fs.delete_item(id)
        fs.recalculate()
    return RedirectResponse("/recurring", 303)

@app.post("/cycle/edit")
def cycle_edit(id: str = Form(...), days: int = Form(...)):
    with _exclusive():
        fs = FlowSavvy()
        for c in _cycle_tasks(fs):
            if c["id"] == id:
                d = (datetime.date.today() + datetime.timedelta(days=days)).isoformat()
                fs.create_task(
                    title=c["title"],
                    listId=c["list"] or config.LIST_PERSONAL,
                    durationMinutes=c["dur"],
                    minLengthMinutes=c["dur"],
                    schedulingHoursId=c["sh"] or config.SH_PERSONAL,
                    isAutoIgnored=False,
                    dueDateTime=f"{d}T20:00:00",
                    canBeStartedAt=f"{datetime.date.today().isoformat()}T08:00:00",
                    notes=f"<p>Recurring task.</p><p>[cycle:{days}d]</p>",
                )
                fs.delete_item(id)
                fs.recalculate()
                break
    return RedirectResponse("/recurring", 303)

@app.post("/domain")
def domain_toggle(name: str = Form(...), on: int = Form(...)):
    d = _domains()
    if name in ALL_DOMAINS:
        d[name] = bool(on)
        _write_json(DOMAINS_FILE, d)
    return RedirectResponse("/", 303)

@app.post("/run")
def run_domain(name: str = Form(...)):
    if name not in ALL_DOMAINS:               # never pass arbitrary argv through
        return RedirectResponse(f"/?msg={quote('unknown domain: ' + name[:24])}", 303)
    _run_domain(name)
    return RedirectResponse("/", 303)

@app.post("/config")
def set_config(key: str = Form(...), value: str = Form("")):
    # newlines would inject extra lines into .env — flatten them
    value = value.replace("\r", " ").replace("\n", " ").strip()
    if key in EDITABLE:
        _set_env(key, value)
    return RedirectResponse("/settings", 303)

@app.post("/gym-nocount")
def gym_nocount():
    history.append("gym_skip", source="ui")
    _run_domain("gym")     # re-plan immediately, consistent with the other gym controls
    return RedirectResponse("/gym", 303)

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

@app.post("/schedule/block-day")
def schedule_block_day(date: str = Form(...)):
    try:
        datetime.date.fromisoformat(date)
    except ValueError:
        return RedirectResponse(f"/schedule?msg={quote('invalid date')}", 303)
    with _exclusive():
        warn = _block_day(date)
    _run_domain("gym")
    return RedirectResponse(f"/schedule?msg={quote(warn)}" if warn else "/schedule", 303)

@app.post("/schedule/unblock-day")
def schedule_unblock_day(date: str = Form(...)):
    with _exclusive():
        warn = _unblock_day(date)
    _run_domain("gym")
    return RedirectResponse(f"/schedule?msg={quote(warn)}" if warn else "/schedule", 303)

@app.post("/gym/block-date")
def gym_block_date(date: str = Form(...)):
    try:
        datetime.date.fromisoformat(date)  # validate
    except ValueError:
        return RedirectResponse(f"/gym?msg={quote('invalid date')}", 303)
    dates = _gym_blocks()
    if date not in dates:
        dates.append(date)
    _save_gym_blocks(dates)
    _run_domain("gym")
    return RedirectResponse("/gym", 303)

@app.post("/gym/unblock-date")
def gym_unblock_date(date: str = Form(...)):
    dates = [d for d in _gym_blocks() if d != date]
    _save_gym_blocks(dates)
    _run_domain("gym")
    return RedirectResponse("/gym", 303)

@app.post("/gym/cycle-date")
def gym_cycle_date(date: str = Form(...)):
    """Advances one calendar cell through its state cycle on each click.
    The default action is the general 'all domains' block (same as
    /schedule/block-day) -- gym is just the one category with an extra,
    domain-specific went/didn't-go layer on top for days already in the past:
      past/today:    neutral -> went -> didn't go -> neutral
      today/future:  neutral -> don't schedule (everything) -> neutral
    """
    try:
        d = datetime.date.fromisoformat(date)
    except ValueError:
        return RedirectResponse(f"/?msg={quote('invalid date')}#calendar", 303)

    today = datetime.date.today()
    warn  = ""
    if date in _gym_blocks():
        warn = _unblock_day(date)
    elif d > today:
        warn = _block_day(date)
    elif any(e["ts"][:10] == date for e in history.events("gym")):
        history.remove_day("gym", date)
        history.append("gym_skip", ts=f"{date}T12:00:00", source="ui")
    elif any(e["ts"][:10] == date for e in history.events("gym_skip")):
        history.remove_day("gym_skip", date)
    else:
        # The calendar only shows gym/gym_skip, not the nightly cleanup's
        # separate "gym_missed" marker (runner.py) -- so a day the cleanup
        # already auto-logged missed still reads as neutral here. Clear it
        # before logging "went", or adherence.gym()'s rate() would double-count
        # this date as both done and missed.
        history.remove_day("gym_missed", date)
        history.append("gym", ts=f"{date}T12:00:00", source="ui")
    _run_domain("gym")
    return RedirectResponse(f"/?msg={quote(warn)}#calendar" if warn else "/#calendar", 303)

_LOGGABLE_ACTIVITIES = {"partner", "friends"}

@app.post("/log/cycle-date")
def log_cycle_date(action: str = Form(...), date: str = Form(...)):
    """Simple neutral <-> went toggle for social activities (partner,
    friends) on the same calendar grid gym uses. No skip/blocked states --
    those are gym-scheduling concepts, not applicable to logging a hangout."""
    if action not in _LOGGABLE_ACTIVITIES:
        return RedirectResponse(f"/?msg={quote('invalid activity')}", 303)
    try:
        datetime.date.fromisoformat(date)
    except ValueError:
        return RedirectResponse(f"/?msg={quote('invalid date')}#calendar", 303)
    if any(e["ts"][:10] == date for e in history.events(action)):
        history.remove_day(action, date)
    else:
        history.append(action, ts=f"{date}T12:00:00", source="ui")
    _run_domain("social")
    return RedirectResponse("/#calendar", 303)

@app.post("/gym/sick-until")
def gym_sick_until(date: str = Form("")):
    if date:
        try:
            datetime.date.fromisoformat(date)
        except ValueError:
            return RedirectResponse(f"/gym?msg={quote('invalid date')}", 303)
    _save_gym_sick_until(date)
    _run_domain("gym")
    return RedirectResponse("/gym", 303)

@app.post("/recalc")
def recalc():
    with _exclusive():
        FlowSavvy().recalculate()
    return RedirectResponse("/schedule", 303)

@app.post("/account/canvas/relogin")
def account_canvas_relogin():
    """Opens a real, visible Chrome window on this machine (the same
    persistent profile the Canvas sync uses) for the user to sign back in —
    triggered from the control panel after a 'Canvas session expired' ntfy
    alert, since that alert can't itself open a browser on the PC."""
    _relogin_canvas()
    return RedirectResponse(f"/settings?msg={quote('Opening Chrome for Canvas — sign in, then it saves automatically.')}#accounts", 303)

@app.post("/canvas/approve-sync")
def canvas_approve_sync():
    """Approve a Canvas sync the flood guard held. Sets a one-shot `flood_ack`
    (today) in canvas_state.json, then re-runs canvas — the guard bypasses for
    the day and creates the held tasks through the normal path (no replay)."""
    sp = os.path.join(ROOT, "logs", "canvas_state.json")
    try:
        st = json.load(open(sp, encoding="utf-8"))
    except Exception:
        st = {}
    st["flood_ack"] = datetime.date.today().isoformat()
    tmp = sp + ".tmp"
    os.makedirs(os.path.dirname(sp), exist_ok=True)
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(st, f)
    os.replace(tmp, sp)
    _run_domain("canvas")
    return RedirectResponse(f"/settings?msg={quote('Approved — creating the held Canvas tasks.')}#canvas", 303)

@app.post("/canvas/dismiss-pending")
def canvas_dismiss_pending():
    """Discard a held Canvas sync without creating anything (e.g. you restored
    canvas_state.json instead). Just removes the pending file."""
    try:
        os.remove(os.path.join(ROOT, "logs", "canvas_pending.json"))
    except Exception:
        pass
    return RedirectResponse(f"/settings?msg={quote('Dismissed the held Canvas sync.')}#canvas", 303)

@app.post("/action/undo")
def action_undo(item_id: str = Form(...)):
    """Undo a reversible LifeOps action — currently 'created a task' → delete it.
    Idempotent: marks the id undone so the feed won't offer it again, and a
    missing task (already gone) is treated as success."""
    try:
        FlowSavvy().delete_item(item_id)
    except Exception as e:
        return RedirectResponse(f"/history?msg={quote('Undo failed: ' + str(e)[:60])}#activity", 303)
    actions.mark_undone(item_id)
    actions.log("panel", "undid a creation", item_id, item_id=None, undoable=False)
    return RedirectResponse(f"/history?msg={quote('Undone — task removed.')}#activity", 303)

@app.post("/system/restart")
def system_restart():
    """Restarts the control panel itself. Triggered from the Config card
    after editing a setting, since env-var changes only take effect on
    process start."""
    _restart_server()
    return RedirectResponse(f"/settings?msg={quote('Restarting server — give it a few seconds, then refresh.')}#config", 303)


# ── pages ──────────────────────────────────────────────────────────────────────
# One card-heavy page got hard to navigate, so it's split by function; every
# page shares the same context build (cheap enough for a single-user app) and
# just renders the section(s) relevant to it.

def _page(request, template, active_page, include_cycle=False):
    fs = FlowSavvy() if include_cycle else None
    ctx = _build_context(fs, include_cycle=include_cycle)
    ctx["flash"] = (request.query_params.get("msg") or "")[:200]
    ctx["active_page"] = active_page
    return TEMPLATES.TemplateResponse(request, template, ctx)

@app.get("/", response_class=HTMLResponse)
def home(request: Request):
    return _page(request, "home.html", "home")

@app.get("/gym", response_class=HTMLResponse)
def gym_page(request: Request):
    return _page(request, "gym.html", "gym")

@app.get("/schedule", response_class=HTMLResponse)
def schedule_page(request: Request):
    return _page(request, "schedule.html", "schedule")

@app.get("/recurring", response_class=HTMLResponse)
def recurring_page(request: Request):
    return _page(request, "recurring.html", "recurring", include_cycle=True)

@app.get("/settings", response_class=HTMLResponse)
def settings_page(request: Request):
    return _page(request, "settings.html", "settings")

@app.get("/history", response_class=HTMLResponse)
def history_page(request: Request):
    return _page(request, "history.html", "history")


class _RedactTokenFilter(logging.Filter):
    """Strips `token=<value>` out of uvicorn access-log records so WEB_TOKEN
    never lands in logs/web.log in cleartext (see main())."""
    _pat = re.compile(r"token=[^&\s\"]+")

    def filter(self, record):
        if isinstance(record.args, tuple):
            record.args = tuple(
                self._pat.sub("token=REDACTED", a) if isinstance(a, str) else a
                for a in record.args)
        elif isinstance(record.msg, str):
            record.msg = self._pat.sub("token=REDACTED", record.msg)
        return True


def main():
    """Windowless-safe entry point: `pythonw -m lifeops.web`.
    pythonw has no console, so sys.stdout/stderr are None and uvicorn's default
    logging crashes on startup — point them at a logfile before serving. Run via
    the `uvicorn` CLI instead for interactive/console use (see module docstring)."""
    import copy
    import uvicorn

    # Uvicorn's default formatters have no timestamp, which makes root-causing
    # a silent death (no exception, process just stops) impossible after the
    # fact. Prefix every log line with one.
    log_config = copy.deepcopy(uvicorn.config.LOGGING_CONFIG)
    for formatter in log_config["formatters"].values():
        formatter["fmt"] = "%(asctime)s " + formatter["fmt"]

    # The access logger includes the full request path -- e.g.
    # "GET /api/next-tasks?token=<WEB_TOKEN> HTTP/1.1" -- so once /api/*
    # clients started being served directly on every call (no cookie
    # bootstrap), WEB_TOKEN ended up in logs/web.log in cleartext on every
    # single poll, forever, rather than a one-time bootstrap (caught
    # 2026-07-12). Redact it at the logging layer rather than relying on
    # every call site to build token-free URLs for logging.
    logging.getLogger("uvicorn.access").addFilter(_RedactTokenFilter())

    if sys.stdout is None or sys.stderr is None:   # running under pythonw
        log = os.path.join(ROOT, "logs", "web.log")
        os.makedirs(os.path.dirname(log), exist_ok=True)
        f = open(log, "a", buffering=1, encoding="utf-8")
        sys.stdout = sys.stderr = f

    print(f"=== starting (pid {os.getpid()}) {datetime.datetime.now().isoformat()} ===", flush=True)
    try:
        uvicorn.run("lifeops.web:app", host="127.0.0.1", port=8765, log_config=log_config)
    finally:
        # If this doesn't print, the process was killed rather than exiting cleanly.
        print(f"=== exiting (pid {os.getpid()}) {datetime.datetime.now().isoformat()} ===", flush=True)


if __name__ == "__main__":
    main()
