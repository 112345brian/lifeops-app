"""LifeOps control panel — FastAPI web UI.

Run:   uvicorn lifeops.web:app --host 0.0.0.0 --port 8765
Reach: tailscale serve 8765  →  https://<your-pc>.<tailnet>.ts.net
"""
import os, re, sys, json, subprocess, datetime
from pathlib import Path
from fastapi import FastAPI, Form, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from fastapi.templating import Jinja2Templates
from . import config, history
from .flowsavvy import FlowSavvy

app = FastAPI()
TEMPLATES = Jinja2Templates(directory=str(Path(__file__).parent / "templates"))

ROOT         = history.ROOT
DOMAINS_FILE = os.path.join(ROOT, "logs", "domains.json")
ENV          = os.path.join(ROOT, ".env")

ALL_DOMAINS  = ["gym", "ynab", "chore", "catchup", "homework", "spend", "social", "meal"]
DOMAIN_ICON  = {"gym": "🏋️", "ynab": "💰", "chore": "🧹", "catchup": "⚡",
                "homework": "📚", "spend": "💸", "social": "👫", "meal": "🍽️"}
EDITABLE     = ["PARTNER_NAME", "PARTNER_SIGNAL", "PROPOSE_AHEAD_DAYS", "PLAN_LEAD_DAYS",
                "DISCRETIONARY", "OUTING_COSTS", "YNAB_COVER_ORDER", "YNAB_NO_ASSIGN",
                "EVENT_CALS", "SOCIAL_CAL"]
ACTION_COLOR = {"gym": "#4ade80", "gym_skip": "#6b7280", "chore_done": "#60a5fa",
                "social": "#c084fc", "meal": "#fb923c", "ynab": "#fbbf24",
                "homework": "#38bdf8", "digest": "#a78bfa", "sleep": "#818cf8"}


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
    return {"ts": ts, "ran": lr.get("ran", []), "errors": lr.get("errors") or {}, "age_mins": age_mins}

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
    _run_domain(name)
    return RedirectResponse("/", 303)

@app.post("/config")
def set_config(key: str = Form(...), value: str = Form("")):
    if key in EDITABLE:
        _set_env(key, value)
    return RedirectResponse("/", 303)

@app.post("/gym-nocount")
def gym_nocount():
    history.append("gym_skip", source="ui")
    return RedirectResponse("/", 303)

@app.post("/recalc")
def recalc():
    FlowSavvy().recalculate()
    return RedirectResponse("/", 303)


# ── main page ──────────────────────────────────────────────────────────────────

@app.get("/", response_class=HTMLResponse)
def home(request: Request):
    fs  = FlowSavvy()
    ctx = _build_context(fs)
    return TEMPLATES.TemplateResponse("index.html", {"request": request, **ctx})
