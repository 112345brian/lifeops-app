"""LifeOps control panel — a tiny FastAPI app you reach privately over Tailscale.

Does the things FlowSavvy/Claude can't: create/edit completion-relative recurring
tasks (the [cycle:Nd] kind), see status + history, edit config knobs, toggle
domains on/off, and fire manual actions — all without querying Claude.

Run:   uvicorn lifeops.web:app --host 0.0.0.0 --port 8765
Reach: tailscale serve 8765   ->  https://<your-pc>.<tailnet>.ts.net
"""
import os, re, sys, json, subprocess, datetime
from fastapi import FastAPI, Form
from fastapi.responses import HTMLResponse, RedirectResponse
from . import config, history
from .flowsavvy import FlowSavvy

app = FastAPI()
ROOT = history.ROOT
DOMAINS_FILE = os.path.join(ROOT, "logs", "domains.json")
ENV = os.path.join(ROOT, ".env")
ALL_DOMAINS = ["gym", "ynab", "chore", "catchup", "homework", "spend", "social", "meal"]
EDITABLE = ["PARTNER_NAME", "PARTNER_SIGNAL", "PROPOSE_AHEAD_DAYS", "PLAN_LEAD_DAYS",
            "DISCRETIONARY", "OUTING_COSTS", "YNAB_COVER_ORDER", "YNAB_NO_ASSIGN",
            "EVENT_CALS", "SOCIAL_CAL"]   # never secrets/tokens

def _domains():
    try: return json.load(open(DOMAINS_FILE, encoding="utf-8"))
    except Exception: return {}

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
    try: lines = open(ENV, encoding="utf-8").read().splitlines()
    except FileNotFoundError: pass
    for i, l in enumerate(lines):
        if l.strip().startswith(key + "="):
            lines[i] = f"{key}={val}"; break
    else:
        lines.append(f"{key}={val}")
    open(ENV, "w", encoding="utf-8").write("\n".join(lines) + "\n")

def _cycle_tasks(fs):
    out = []
    for t in fs.list_items(itemType="task", completed=False).get("items", []):
        m = re.search(r"\[cycle:(\d+)d\]", t.get("notes") or "")
        if m:
            out.append({"id": t["id"], "title": t.get("title") or "", "days": int(m.group(1)),
                        "due": (t.get("dueDateTime") or "")[:10],
                        "dur": t.get("durationMinutes"), "list": t.get("listId"),
                        "sh": t.get("schedulingHoursId")})
    return sorted(out, key=lambda x: x["title"].lower())

def _run_domain(name):
    subprocess.Popen([sys.executable, "-m", "lifeops.runner", name], cwd=ROOT,
                     creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))

def _esc(s): return str(s).replace("<", "&lt;").replace(">", "&gt;")

def _status():
    try:
        lr = json.load(open(os.path.join(ROOT, "logs", "last_run.json"), encoding="utf-8"))
    except Exception:
        return "<i>never run yet</i>"
    ts = lr.get("ts", "?")
    try:
        age = datetime.datetime.now() - datetime.datetime.fromisoformat(ts)
        mins = int(age.total_seconds() // 60)
        fresh = "🟢" if mins < 20 else ("🟡" if mins < 120 else "🔴")
        when = f"{fresh} {mins} min ago"
    except Exception:
        when = ts
    errs = lr.get("errors") or {}
    estr = (" — <b style='color:#b00'>errors: " + _esc(", ".join(errs)) + "</b>") if errs else " — ok"
    return f"last run {when} ({_esc(', '.join(lr.get('ran', [])) or '—')}){estr}"

def render():
    fs = FlowSavvy()
    cyc = _cycle_tasks(fs)
    dom = _domains()
    recent = history.events()[-12:][::-1]

    rows = "".join(
        f"<tr><td>{_esc(c['title'])}</td><td>every {c['days']}d</td>"
        f"<td>{c['dur'] or ''}m</td><td>next {c['due']}</td>"
        f"<td><form method=post action=/cycle/edit style='display:inline'>"
        f"<input type=hidden name=id value='{c['id']}'>"
        f"<input name=days value='{c['days']}' size=3> "
        f"<button>save</button></form> "
        f"<form method=post action=/cycle/del style='display:inline'>"
        f"<input type=hidden name=id value='{c['id']}'><button>x</button></form></td></tr>"
        for c in cyc) or "<tr><td colspan=5><i>none yet</i></td></tr>"

    toggles = "".join(
        f"<form method=post action=/domain style='display:inline-block;margin:2px'>"
        f"<input type=hidden name=name value='{d}'>"
        f"<button name=on value='{0 if dom.get(d, True) else 1}'>"
        f"{d}: {'ON' if dom.get(d, True) else 'off'}</button></form>"
        for d in ALL_DOMAINS)

    runs = "".join(f"<form method=post action=/run style='display:inline-block;margin:2px'>"
                   f"<input type=hidden name=name value='{d}'><button>run {d}</button></form>"
                   for d in ALL_DOMAINS)

    cfg = "".join(
        f"<tr><td>{k}</td><td><form method=post action=/config>"
        f"<input type=hidden name=key value='{k}'>"
        f"<input name=value value='{_esc(_env_value(k))}' size=40><button>save</button>"
        f"</form></td></tr>" for k in EDITABLE)

    hist = "".join(f"<li>{_esc(e['ts'])} — <b>{_esc(e['action'])}</b> ({_esc(e.get('source',''))})</li>"
                   for e in recent) or "<li><i>nothing logged</i></li>"

    return f"""<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>
<title>LifeOps</title><style>
body{{font-family:system-ui;max-width:760px;margin:1em auto;padding:0 .6em}}
h2{{margin-top:1.4em;border-bottom:1px solid #ccc}}
table{{width:100%;border-collapse:collapse}} td{{padding:3px;border-bottom:1px solid #eee;font-size:14px}}
button{{padding:4px 8px}} input{{padding:3px}}
</style>
<h1>LifeOps</h1>
<p style='background:#f4f4f4;padding:.5em;border-radius:6px'>{_status()}</p>

<h2>Recurring tasks (repeat N days after you complete them)</h2>
<table><tr><th>task</th><th>cycle</th><th>dur</th><th>next</th><th></th></tr>{rows}</table>
<form method=post action=/cycle/new style='margin-top:.6em'>
 <input name=title placeholder=title required>
 every <input name=days value=7 size=3>d ·
 <input name=duration value=30 size=4>min ·
 list <input name=listId value='{config.LIST_PERSONAL}' size=8>
 <button>add</button>
</form>

<h2>Domains</h2><div>{toggles}</div>
<h3>Run now</h3><div>{runs}</div>
<div style='margin-top:.5em'>
 <form method=post action=/gym-nocount style='display:inline'><button>gym: don't count today</button></form>
 <form method=post action=/recalc style='display:inline'><button>recalculate</button></form>
</div>

<h2>Config <small>(restart the app to apply)</small></h2>
<table>{cfg}</table>

<h2>Recent history</h2><ul>{hist}</ul>
"""

@app.get("/", response_class=HTMLResponse)
def home():
    return render()

@app.post("/cycle/new")
def cycle_new(title: str = Form(...), days: int = Form(7), duration: int = Form(30),
              listId: str = Form("")):
    fs = FlowSavvy()
    d = (datetime.date.today() + datetime.timedelta(days=days)).isoformat()
    fs.create_task(title=title, listId=listId or config.LIST_PERSONAL,
                   durationMinutes=duration, minLengthMinutes=duration,
                   schedulingHoursId=config.SH_PERSONAL, isAutoIgnored=False,
                   dueDateTime=f"{d}T20:00:00",
                   canBeStartedAt=f"{datetime.date.today().isoformat()}T08:00:00",
                   notes=f"<p>Recurring task.</p><p>[cycle:{days}d]</p>")
    fs.recalculate()
    return RedirectResponse("/", 303)

@app.post("/cycle/del")
def cycle_del(id: str = Form(...)):
    fs = FlowSavvy(); fs.delete_item(id); fs.recalculate()
    return RedirectResponse("/", 303)

@app.post("/cycle/edit")
def cycle_edit(id: str = Form(...), days: int = Form(...)):
    fs = FlowSavvy()
    for c in _cycle_tasks(fs):
        if c["id"] == id:
            d = (datetime.date.today() + datetime.timedelta(days=days)).isoformat()
            fs.create_task(title=c["title"], listId=c["list"] or config.LIST_PERSONAL,
                           durationMinutes=c["dur"], minLengthMinutes=c["dur"],
                           schedulingHoursId=c["sh"] or config.SH_PERSONAL, isAutoIgnored=False,
                           dueDateTime=f"{d}T20:00:00",
                           canBeStartedAt=f"{datetime.date.today().isoformat()}T08:00:00",
                           notes=f"<p>Recurring task.</p><p>[cycle:{days}d]</p>")
            fs.delete_item(id); fs.recalculate()
            break
    return RedirectResponse("/", 303)

@app.post("/domain")
def domain(name: str = Form(...), on: int = Form(...)):
    d = _domains(); d[name] = bool(on)
    os.makedirs(os.path.dirname(DOMAINS_FILE), exist_ok=True)
    json.dump(d, open(DOMAINS_FILE, "w", encoding="utf-8"))
    return RedirectResponse("/", 303)

@app.post("/run")
def run(name: str = Form(...)):
    _run_domain(name); return RedirectResponse("/", 303)

@app.post("/config")
def set_config(key: str = Form(...), value: str = Form("")):
    if key in EDITABLE:
        _set_env(key, value)
    return RedirectResponse("/", 303)

@app.post("/gym-nocount")
def gym_nocount():
    history.append("gym_skip", source="ui"); return RedirectResponse("/", 303)

@app.post("/recalc")
def recalc():
    FlowSavvy().recalculate(); return RedirectResponse("/", 303)


def main():
    """Windowless-safe entry point: `pythonw -m lifeops.web`.
    pythonw has no console, so sys.stdout/stderr are None and uvicorn's default
    logging crashes on startup — point them at a logfile before serving. Run via
    the `uvicorn` CLI instead for interactive/console use (see module docstring)."""
    import uvicorn
    if sys.stdout is None or sys.stderr is None:   # running under pythonw
        log = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                           "logs", "web.log")
        os.makedirs(os.path.dirname(log), exist_ok=True)
        f = open(log, "a", buffering=1, encoding="utf-8")
        sys.stdout = sys.stderr = f
    uvicorn.run("lifeops.web:app", host="127.0.0.1", port=8765)


if __name__ == "__main__":
    main()
