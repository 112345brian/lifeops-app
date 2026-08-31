"""History page: raw event log, undo of a single log entry, and undo of a
reversible action (currently just "created a task")."""
from urllib.parse import quote
from fastapi import APIRouter, Form, Request
from fastapi.responses import HTMLResponse, RedirectResponse
from .. import actions, history
from ..flowsavvy import FlowSavvy
from . import core

router = APIRouter()


@router.get("/history", response_class=HTMLResponse)
def history_page(request: Request):
    return core._page(request, "history.html", "history")

@router.post("/history/undo")
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
    with core._exclusive():
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

@router.post("/action/undo")
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
