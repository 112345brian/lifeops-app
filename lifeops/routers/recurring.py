"""Recurring page: cycle-tasks CRUD (FlowSavvy tasks tagged [cycle:Nd])."""
import datetime
from fastapi import APIRouter, Form, Request
from fastapi.responses import HTMLResponse, RedirectResponse
from .. import config
from ..flowsavvy import FlowSavvy
from . import core

router = APIRouter()


@router.get("/recurring", response_class=HTMLResponse)
def recurring_page(request: Request):
    return core._page(request, "recurring.html", "recurring", include_cycle=True)

@router.post("/cycle/new")
def cycle_new(title: str = Form(...), days: int = Form(7), duration: int = Form(30),
              listId: str = Form("")):
    with core._exclusive():
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

@router.post("/cycle/del")
def cycle_del(id: str = Form(...)):
    with core._exclusive():
        fs = FlowSavvy()
        fs.delete_item(id)
        fs.recalculate()
    return RedirectResponse("/recurring", 303)

@router.post("/cycle/edit")
def cycle_edit(id: str = Form(...), days: int = Form(...)):
    with core._exclusive():
        fs = FlowSavvy()
        for c in core._cycle_tasks(fs):
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
