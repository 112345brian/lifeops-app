"""LifeOps control panel — FastAPI web UI.

Run:   uvicorn lifeops.web:app --host 0.0.0.0 --port 8765
Reach: tailscale serve 8765  →  https://<your-pc>.<tailnet>.ts.net
"""
import os, re, sys, json, subprocess, datetime
from pathlib import Path
from urllib.parse import quote
from fastapi import FastAPI, Form, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse, FileResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from . import config, history, gather
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
        supplied = request.query_params.get("token") or request.cookies.get("lifeops_auth")
        if supplied != config.WEB_TOKEN:
            return JSONResponse({"error": "unauthorized"}, status_code=401)
        resp = await call_next(request)
        if request.query_params.get("token") == config.WEB_TOKEN:
            resp.set_cookie("lifeops_auth", config.WEB_TOKEN,
                            max_age=90 * 24 * 3600, httponly=True)
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
ENV               = os.path.join(ROOT, ".env")

ALL_DOMAINS  = ["gym", "ynab", "chore", "catchup", "homework", "spend", "social", "meal", "canvas"]
DOMAIN_ICON  = {"gym": "🏋️", "ynab": "💰", "chore": "🧹", "catchup": "⚡",
                "homework": "📚", "spend": "💸", "social": "👫", "meal": "🍽️", "canvas": "🎓"}
EDITABLE     = ["PARTNER_NAME", "PARTNER_SIGNAL", "PROPOSE_AHEAD_DAYS", "PLAN_LEAD_DAYS",
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
    open(ENV, "w", encoding="utf-8").write("\n".join(lines) + "\n")

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

def _gym_stats():
    cutoff     = (datetime.date.today() - datetime.timedelta(weeks=4)).isoformat()
    evts       = [e for e in history.events("gym")      if e["ts"][:10] >= cutoff]
    skip_dates = {e["ts"][:10] for e in history.events("gym_skip")}
    real       = [e for e in evts if e["ts"][:10] not in skip_dates]
    morning    = sum(1 for e in real
                     if datetime.datetime.fromisoformat(e["ts"]).hour < 12)
    week_start = (datetime.date.today()
                  - datetime.timedelta(days=datetime.date.today().weekday())).isoformat()
    this_week  = sum(1 for e in real if e["ts"][:10] >= week_start)
    return {"total_4w": len(real), "morning": morning,
            "evening": len(real) - morning, "this_week": this_week}

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
    json.dump(pruned, open(GYM_BLOCKS_FILE, "w", encoding="utf-8"))

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
    json.dump(state, open(GYM_STATE_FILE, "w", encoding="utf-8"))

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
    json.dump(pruned, open(SCHED_BLOCKS_FILE, "w", encoding="utf-8"))

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

def _relogin_canvas():
    subprocess.Popen([sys.executable, os.path.join(ROOT, "scripts", "canvas_relogin.py")],
                     cwd=ROOT, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))

def _restart_server():
    """Restarts the LifeOps-web scheduled task from a detached helper process,
    since stopping the task kills this process before it could run the restart
    itself. Falls back to just exiting if it's not running as that service
    (e.g. a manual `uvicorn` dev run) — nothing else will bring it back up."""
    check = subprocess.run(["schtasks", "/query", "/tn", "LifeOps-web"],
                           capture_output=True,
                           creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    if check.returncode == 0:
        script = ('Start-Sleep -Milliseconds 800; '
                  'schtasks /end /tn "LifeOps-web"; '
                  'Start-Sleep -Seconds 2; '
                  'schtasks /run /tn "LifeOps-web"')
        subprocess.Popen(["powershell", "-NoProfile", "-Command", script],
                         creationflags=getattr(subprocess, "DETACHED_PROCESS", 0)
                                     | getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0))
    else:
        os._exit(0)

def _build_context(fs):
    lr  = _last_run()
    dom = _domains()
    gs  = _gym_stats()

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

    # history entries
    raw_history = history.events()[-50:][::-1]
    hist = []
    for e in raw_history:
        ts = e.get("ts", "")
        try:
            display_ts = datetime.datetime.fromisoformat(ts).strftime("%m-%d %H:%M")
        except Exception:
            display_ts = ts[:16]
        meta = e.get("meta") or {}
        meta_str = (", ".join(f"{k}={v}" for k, v in meta.items())
                    if isinstance(meta, dict) else str(meta))
        hist.append({
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

    return {
        "status_dot":       dot,
        "status_text":      text,
        "gym_stats":        gs,
        "domains":          domains,
        "cycle_tasks":      _cycle_tasks(fs),
        "config_items":     [{"key": k, "value": _env_value(k)} for k in EDITABLE],
        "history":          hist,
        "list_personal":    config.LIST_PERSONAL,
        "last_run_domains": ", ".join(lr["ran"]) if lr and lr["ran"] else "",
        "last_run_errors":  str(lr["errors"]) if lr and lr["errors"] else "",
        "gym_blocks":       gym_block_display,
        "gym_sick_until":   sick_until,
        "today":            today.isoformat(),
        "tomorrow":         (today + datetime.timedelta(days=1)).isoformat(),
        "sched_blocks":     sched_block_display,
        "block_cal_set":    bool(config.BLOCK_CAL),
        "canvas_status":    _canvas_status(),
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


# ── action endpoints ────────────────────────────────────────────────────────────

@app.post("/cycle/new")
def cycle_new(title: str = Form(...), days: int = Form(7), duration: int = Form(30),
              listId: str = Form("")):
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
    return RedirectResponse("/", 303)

@app.post("/cycle/del")
def cycle_del(id: str = Form(...)):
    fs = FlowSavvy()
    fs.delete_item(id)
    fs.recalculate()
    return RedirectResponse("/", 303)

@app.post("/cycle/edit")
def cycle_edit(id: str = Form(...), days: int = Form(...)):
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
    return RedirectResponse("/", 303)

@app.post("/domain")
def domain_toggle(name: str = Form(...), on: int = Form(...)):
    d = _domains()
    d[name] = bool(on)
    os.makedirs(os.path.dirname(DOMAINS_FILE), exist_ok=True)
    json.dump(d, open(DOMAINS_FILE, "w", encoding="utf-8"))
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
    return RedirectResponse("/", 303)

@app.post("/gym-nocount")
def gym_nocount():
    history.append("gym_skip", source="ui")
    _run_domain("gym")     # re-plan immediately, consistent with the other gym controls
    return RedirectResponse("/", 303)

@app.post("/schedule/block-day")
def schedule_block_day(date: str = Form(...)):
    try:
        datetime.date.fromisoformat(date)
    except ValueError:
        return RedirectResponse(f"/?msg={quote('invalid date')}", 303)
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
    # also block gym engine for the same day
    gym_dates = _gym_blocks()
    if date not in gym_dates:
        gym_dates.append(date)
    _save_gym_blocks(gym_dates)
    _run_domain("gym")
    return RedirectResponse(f"/?msg={quote(warn)}" if warn else "/", 303)

@app.post("/schedule/unblock-day")
def schedule_unblock_day(date: str = Form(...)):
    entries = _sched_blocks()
    to_remove = [e for e in entries if e["date"] == date]
    entries = [e for e in entries if e["date"] != date]
    _save_sched_blocks(entries)
    warn = ""
    # delete FlowSavvy event if we stored one
    if config.BLOCK_CAL:
        try:
            fs = FlowSavvy()
            for e in to_remove:
                if e.get("event_id"):
                    fs.delete_item(e["event_id"])
            fs.recalculate()
        except Exception as e:
            warn = f"⚠️ Couldn't delete the FlowSavvy busy event ({str(e)[:80]}) — remove it manually."
    # also unblock gym engine
    _save_gym_blocks([d for d in _gym_blocks() if d != date])
    _run_domain("gym")
    return RedirectResponse(f"/?msg={quote(warn)}" if warn else "/", 303)

@app.post("/gym/block-date")
def gym_block_date(date: str = Form(...)):
    try:
        datetime.date.fromisoformat(date)  # validate
    except ValueError:
        return RedirectResponse(f"/?msg={quote('invalid date')}", 303)
    dates = _gym_blocks()
    if date not in dates:
        dates.append(date)
    _save_gym_blocks(dates)
    _run_domain("gym")
    return RedirectResponse("/", 303)

@app.post("/gym/unblock-date")
def gym_unblock_date(date: str = Form(...)):
    dates = [d for d in _gym_blocks() if d != date]
    _save_gym_blocks(dates)
    _run_domain("gym")
    return RedirectResponse("/", 303)

@app.post("/gym/sick-until")
def gym_sick_until(date: str = Form("")):
    if date:
        try:
            datetime.date.fromisoformat(date)
        except ValueError:
            return RedirectResponse(f"/?msg={quote('invalid date')}", 303)
    _save_gym_sick_until(date)
    _run_domain("gym")
    return RedirectResponse("/", 303)

@app.post("/recalc")
def recalc():
    FlowSavvy().recalculate()
    return RedirectResponse("/", 303)

@app.post("/account/canvas/relogin")
def account_canvas_relogin():
    """Opens a real, visible Chrome window on this machine (the same
    persistent profile the Canvas sync uses) for the user to sign back in —
    triggered from the control panel after a 'Canvas session expired' ntfy
    alert, since that alert can't itself open a browser on the PC."""
    _relogin_canvas()
    return RedirectResponse(f"/?msg={quote('Opening Chrome for Canvas — sign in, then it saves automatically.')}#accounts", 303)

@app.post("/system/restart")
def system_restart():
    """Restarts the control panel itself. Triggered from the Config card
    after editing a setting, since env-var changes only take effect on
    process start."""
    _restart_server()
    return RedirectResponse(f"/?msg={quote('Restarting server — give it a few seconds, then refresh.')}#config", 303)


# ── main page ──────────────────────────────────────────────────────────────────

@app.get("/", response_class=HTMLResponse)
def home(request: Request):
    fs  = FlowSavvy()
    ctx = _build_context(fs)
    ctx["flash"] = (request.query_params.get("msg") or "")[:200]
    return TEMPLATES.TemplateResponse(request, "index.html", ctx)


def main():
    """Windowless-safe entry point: `pythonw -m lifeops.web`.
    pythonw has no console, so sys.stdout/stderr are None and uvicorn's default
    logging crashes on startup — point them at a logfile before serving. Run via
    the `uvicorn` CLI instead for interactive/console use (see module docstring)."""
    import uvicorn
    if sys.stdout is None or sys.stderr is None:   # running under pythonw
        log = os.path.join(ROOT, "logs", "web.log")
        os.makedirs(os.path.dirname(log), exist_ok=True)
        f = open(log, "a", buffering=1, encoding="utf-8")
        sys.stdout = sys.stderr = f
    uvicorn.run("lifeops.web:app", host="127.0.0.1", port=8765)


if __name__ == "__main__":
    main()
