"""Home page: domain toggles/manual runs, the upcoming-events cost overrides
feeding the cashflow projection, and the partner/friends hangout calendar."""
import datetime
from urllib.parse import quote
from fastapi import APIRouter, Form, HTTPException, Request
from fastapi.responses import HTMLResponse, RedirectResponse
from .. import config, gather, history
from ..flowsavvy import FlowSavvy
from . import core

router = APIRouter()

_LOGGABLE_ACTIVITIES = {"partner", "friends"}


def _find_fs_item(fs, item_type, item_id):
    """Locates a FlowSavvy item by id for the upcoming-events edit/delete
    controls. Tasks: the same small unfinished-tasks list gather.py's
    PARTNER_TASK/FRIENDS_TASK pass already fetches. Events: gather's
    paginated all-calendars sweep, since the item could be on any
    calendar, not just one in EVENT_CALS -- calls _fetch_all_events (NOT
    _all_events_cached) since that cache is process-lifetime and this is
    a long-running server, not runner.py's one-shot-per-run subprocess."""
    if item_type == "task":
        items = fs.list_items(itemType="task", completed=False).get("items", [])
    else:
        items = gather._fetch_all_events(fs)
    for it in items:
        if str(it.get("id")) == str(item_id):
            return it
    return None


@router.get("/", response_class=HTMLResponse)
def home(request: Request):
    return core._page(request, "home.html", "home")

@router.post("/domain")
def domain_toggle(name: str = Form(...), on: int = Form(...)):
    d = core._domains()
    if name in core.ALL_DOMAINS:
        d[name] = bool(on)
        core._write_json(core.DOMAINS_FILE, d)
    return RedirectResponse("/", 303)

@router.post("/run")
def run_domain(name: str = Form(...)):
    if not core._validate_domain(name):
        return RedirectResponse(f"/?msg={quote('unknown domain: ' + name[:24])}", 303)
    core._run_domain(name)
    return RedirectResponse("/", 303)

@router.post("/upcoming-events/edit")
def upcoming_event_edit(item_id: str = Form(...), item_type: str = Form(...),
                         cost: float = Form(...)):
    """Overrides (or, with cost=0, effectively deletes-from-the-forecast)
    a single upcoming event/task's projected cost, by rewriting its
    FlowSavvy notes -- the same "cost: <dollars>" override gather.py
    already understands -- rather than deleting the underlying
    appointment, which still needs to happen."""
    if item_type not in ("event", "task"):
        raise HTTPException(400, "unknown item_type")
    with core._exclusive():
        fs = FlowSavvy()
        item = _find_fs_item(fs, item_type, item_id)
        if not item:
            raise HTTPException(404, "That item wasn't found — it may have already changed.")
        # Send only the changed field, matching every other update_task call
        # site (runner.py's title=.../dueDateTime=... calls) -- spreading the
        # whole fetched item back as the body would round-trip whatever
        # read-only/computed fields list_items() happens to include into a
        # PUT whose real shape is inferred, not confirmed (flowsavvy.py's own
        # docstring), for no benefit over a single-field update.
        new_notes = gather.set_cost_override(item.get("notes"), cost)
        if item_type == "task":
            fs.update_task(item_id, notes=new_notes)
        else:
            fs.update_event(item_id, notes=new_notes)
    core._run_domain("cashflow")
    return RedirectResponse("/", 303)

@router.post("/upcoming-events/new")
def upcoming_event_new(label: str = Form(...), cost: float = Form(...), date: str = Form(...)):
    """Adds a brand-new manual future cost not tied to any existing
    calendar item -- a FlowSavvy calendar event carrying a "type: custom
    / cost: <dollars>" note, on any EVENT_CALS calendar (the note-swept
    type always wins over that calendar's own default, so which one it
    lands on doesn't matter)."""
    cal_id = next(iter(config.EVENT_CALS), "")
    if not cal_id:
        raise HTTPException(400, "No EVENT_CALS calendar configured to add a manual cost to.")
    with core._exclusive():
        fs = FlowSavvy()
        fs.create_event(
            title=label,
            calendarId=cal_id,
            startDateTime=f"{date}T12:00:00",
            endDateTime=f"{date}T13:00:00",
            notes=gather.set_cost_override("type: custom", cost),
        )
        fs.recalculate()
    core._run_domain("cashflow")
    return RedirectResponse("/", 303)

@router.post("/log/cycle-date")
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
    core._run_domain("social")
    return RedirectResponse("/#calendar", 303)
