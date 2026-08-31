"""JSON API surface consumed by the Android widget: status/health polling,
next-tasks + completion, quick-action gym/schedule mutations, and
push-token/location registration."""
import datetime
from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import JSONResponse
from .. import fcm, gather, history, location, weather
from ..flowsavvy import FlowSavvy
from . import core

router = APIRouter()


def _tasks_and_events(fs, now, n):
    """Shared next-tasks + today's-events fetch: one FlowSavvy schedule
    round trip, reused by both /api/next-tasks and the completion endpoint.
    Deliberately does NOT catch exceptions -- see gather._upcoming_schedule's
    own docstring on why a genuine fetch failure must propagate rather than
    return a false-empty result. Callers decide how to surface that.

    weather is included here (not just in the once-daily briefing facts) so
    the widget's temperature refreshes on the same ~15-min cadence as
    gym_ring, via NextTasksRefreshWorker's existing periodic pull -- before
    this, weather.current() was only ever called once/day inside
    run_briefing, so a widget checked mid-afternoon still showed whatever
    NOAA said that morning (confirmed 2026-07-15: "that would only happen
    with server inconsistency" -- it wasn't inconsistency, it was staleness).
    weather.current() is already best-effort (returns None on any failure),
    so no extra try/except needed here."""
    schedule_items = gather._upcoming_schedule(fs, now)
    return {"tasks": gather.next_tasks_input(fs, now, n, schedule_items=schedule_items),
            "events": gather.today_events_input(fs, now, schedule_items=schedule_items),
            "gym_ring": gather.gym_ring_now(fs, now),
            "weather": weather.current(now)}


@router.get("/api/status")
def api_status():
    lr  = core._last_run()
    dom = core._domains()
    return JSONResponse({
        "last_run":  lr,
        "domains":   {d: dom.get(d, True) for d in core.ALL_DOMAINS},
        "gym_stats": core._gym_stats(),
    })

@router.get("/api/health")
def api_health():
    return JSONResponse({**core._fcm_health(), "last_run": core._last_run()})

@router.get("/api/history")
def api_history(n: int = 50):
    return JSONResponse(history.events()[-n:][::-1])

@router.get("/api/briefing")
def api_briefing():
    b = core._today_briefing_raw()
    if b is None:
        return JSONResponse({"briefing": None}, status_code=404)
    return JSONResponse(b)

@router.get("/api/next-tasks")
def api_next_tasks(n: int = 8):
    fs = FlowSavvy()
    now = datetime.datetime.now()
    try:
        return JSONResponse(_tasks_and_events(fs, now, n))
    except Exception as e:
        # A real FlowSavvy fetch failure must fail this request, not
        # silently return {"tasks": [], "events": []} -- that used to be
        # indistinguishable from genuine emptiness and would overwrite the
        # widget's perfectly good existing state with "nothing to show."
        # The Android client already treats any non-200 as "leave state
        # alone, retry later" (NextTasksRefreshWorker), so failing loudly
        # here is strictly safer than succeeding with a false empty result.
        raise HTTPException(502, f"FlowSavvy fetch failed: {e}")

@router.post("/api/tasks/{task_id}/complete")
def api_task_complete(task_id: str, n: int = 8):
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
    try:
        fresh = _tasks_and_events(fs, now, n)
    except Exception as e:
        # The completion itself already succeeded above -- only the
        # post-completion state refresh failed. Still a 502 (matching
        # /api/next-tasks): the client needs to know the returned
        # tasks/events aren't trustworthy, even though the task IS done.
        raise HTTPException(502, f"task completed, but refreshing state failed: {e}")
    return JSONResponse({"completed_id": task_id, **fresh})

@router.post("/api/gym/log")
def api_gym_log():
    """Logs a same-day gym session, same as ticking today on the home
    calendar. Quick-action equivalent of the calendar click-through, scoped
    to "today" since that's the only case a one-tap action needs."""
    today = datetime.date.today().isoformat()
    core._log_gym_went(today)
    core._run_domain("gym")
    return JSONResponse({"ok": True, "attention": core._current_attention()})

@router.post("/api/gym/skip")
def api_gym_skip():
    """JSON equivalent of the /gym-nocount form action: logs today as a
    deliberate skip (not a missed session) and re-plans immediately."""
    core._log_gym_skip()
    core._run_domain("gym")
    return JSONResponse({"ok": True, "attention": core._current_attention()})

@router.post("/api/schedule/block-day")
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
    warn = core._do_block_day(date)
    return JSONResponse({"ok": True, "warning": warn or None, "attention": core._current_attention()})

@router.post("/api/domains/{name}/run")
def api_domain_run(name: str):
    """JSON equivalent of the /run form action -- triggers one domain
    out-of-cycle (e.g. after a manual schedule change)."""
    if not core._validate_domain(name):
        raise HTTPException(404, f"unknown domain: {name[:24]}")
    core._run_domain(name)
    return JSONResponse({"ok": True, "domain": name})

@router.post("/api/register-fcm-token")
async def api_register_fcm_token(request: Request):
    """The widget calls this once per token (install, or whenever Firebase
    rotates it) so run_briefing knows where to push. Direct, Tailscale-gated
    path; runner.py's ntfy `token:<value>` signal handler is the relay
    fallback for when the phone isn't on the tailnet at registration time.
    Single-user app -- one token on file, last write wins."""
    try:
        body = await request.json()
    except Exception:
        raise HTTPException(400, "malformed JSON body")
    if not isinstance(body, dict):
        raise HTTPException(400, "expected a JSON object")
    if not fcm.register_token(body.get("fcm_token")):
        raise HTTPException(400, "fcm_token required (string, 10-4096 chars)")
    return JSONResponse({"ok": True})

@router.post("/api/location")
async def api_location(request: Request):
    """The widget POSTs the phone's last GPS fix here a few times a day
    (piggybacked on NextTasksRefreshWorker's existing periodic cycle, not a
    continuous background track) so weather.py can forecast for wherever
    the user actually is instead of a fixed WEATHER_LAT/WEATHER_LON.
    Direct, Tailscale-gated path only -- unlike the FCM token, a stale
    location just means weather.py falls back to the static config value
    (see location.get_location), so there's no ntfy relay fallback needed
    here. Single-user app -- one location on file, last write wins."""
    try:
        body = await request.json()
    except Exception:
        raise HTTPException(400, "malformed JSON body")
    if not isinstance(body, dict):
        raise HTTPException(400, "expected a JSON object")
    if not location.set_location(body.get("lat"), body.get("lon")):
        raise HTTPException(400, "lat/lon required (numeric, valid coordinates)")
    return JSONResponse({"ok": True})
