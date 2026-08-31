"""Gym page: went/skip logging, the gym-only block calendar, sick-until, and
the calendar cell's click-to-cycle state machine."""
import datetime
from urllib.parse import quote
from fastapi import APIRouter, Form, Request
from fastapi.responses import HTMLResponse, RedirectResponse
from .. import history
from . import core

router = APIRouter()


@router.get("/gym", response_class=HTMLResponse)
def gym_page(request: Request):
    return core._page(request, "gym.html", "gym")

@router.post("/gym-nocount")
def gym_nocount():
    core._log_gym_skip()
    core._run_domain("gym")     # re-plan immediately, consistent with the other gym controls
    return RedirectResponse("/gym", 303)

@router.post("/gym/block-date")
def gym_block_date(date: str = Form(...)):
    try:
        datetime.date.fromisoformat(date)  # validate
    except ValueError:
        return RedirectResponse(f"/gym?msg={quote('invalid date')}", 303)
    dates = core._gym_blocks()
    if date not in dates:
        dates.append(date)
    core._save_gym_blocks(dates)
    core._run_domain("gym")
    return RedirectResponse("/gym", 303)

@router.post("/gym/unblock-date")
def gym_unblock_date(date: str = Form(...)):
    dates = [d for d in core._gym_blocks() if d != date]
    core._save_gym_blocks(dates)
    core._run_domain("gym")
    return RedirectResponse("/gym", 303)

@router.post("/gym/cycle-date")
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
    if date in core._gym_blocks():
        warn = core._unblock_day(date)
    elif d > today:
        warn = core._block_day(date)
    elif any(e["ts"][:10] == date for e in history.events("gym")):
        history.remove_day("gym", date)
        history.append("gym_skip", ts=f"{date}T12:00:00", source="ui")
    elif any(e["ts"][:10] == date for e in history.events("gym_skip")):
        history.remove_day("gym_skip", date)
    else:
        core._log_gym_went(date)
    core._run_domain("gym")
    return RedirectResponse(f"/?msg={quote(warn)}#calendar" if warn else "/#calendar", 303)

@router.post("/gym/sick-until")
def gym_sick_until(date: str = Form("")):
    if date:
        try:
            datetime.date.fromisoformat(date)
        except ValueError:
            return RedirectResponse(f"/gym?msg={quote('invalid date')}", 303)
    core._save_gym_sick_until(date)
    core._run_domain("gym")
    return RedirectResponse("/gym", 303)
