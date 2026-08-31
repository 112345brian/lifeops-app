"""Schedule page: general "block this day" (all domains) controls and a
manual FlowSavvy recalculate button."""
import datetime
from urllib.parse import quote
from fastapi import APIRouter, Form, Request
from fastapi.responses import HTMLResponse, RedirectResponse
from ..flowsavvy import FlowSavvy
from . import core

router = APIRouter()


@router.get("/schedule", response_class=HTMLResponse)
def schedule_page(request: Request):
    return core._page(request, "schedule.html", "schedule")

@router.post("/schedule/block-day")
def schedule_block_day(date: str = Form(...)):
    try:
        datetime.date.fromisoformat(date)
    except ValueError:
        return RedirectResponse(f"/schedule?msg={quote('invalid date')}", 303)
    warn = core._do_block_day(date)
    return RedirectResponse(f"/schedule?msg={quote(warn)}" if warn else "/schedule", 303)

@router.post("/schedule/unblock-day")
def schedule_unblock_day(date: str = Form(...)):
    with core._exclusive():
        warn = core._unblock_day(date)
    core._run_domain("gym")
    return RedirectResponse(f"/schedule?msg={quote(warn)}" if warn else "/schedule", 303)

@router.post("/recalc")
def recalc():
    with core._exclusive():
        FlowSavvy().recalculate()
    return RedirectResponse("/schedule", 303)
