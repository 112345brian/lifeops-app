"""Canvas panel: approve/dismiss syncs the flood guard (runner.py) held for
review. Renders inline on the settings page (settings.html#canvas), but the
mutations are their own concern -- separate from general env-var config --
so they get their own router."""
import datetime, os
from urllib.parse import quote
from fastapi import APIRouter, Form
from fastapi.responses import RedirectResponse
from .. import state_store
from . import core

router = APIRouter()


@router.post("/canvas/approve-sync")
def canvas_approve_sync(course_id: str = Form(...)):
    """Approve a Canvas sync the flood guard held for one course. Sets a
    one-shot `flood_ack` (today) on that course's bucket in canvas_state.json,
    then re-runs canvas — the guard bypasses for the day and creates the held
    tasks through the normal path (no replay). Other courses' holds, if any,
    are untouched."""
    sp = os.path.join(core.ROOT, "private", "logs", "canvas_state.json")
    st_root = state_store.load_json(sp, default={}) or {}
    st_root.setdefault("courses", {}).setdefault(course_id, {})["flood_ack"] = \
        datetime.date.today().isoformat()
    state_store.save_json_atomic(sp, st_root)
    core._run_domain("canvas")
    return RedirectResponse(f"/settings?msg={quote('Approved — creating the held Canvas tasks.')}#canvas", 303)

@router.post("/canvas/dismiss-pending")
def canvas_dismiss_pending(course_id: str = Form(...)):
    """Discard a held Canvas sync for one course without creating anything
    (e.g. you restored canvas_state.json instead). Removes just that course's
    entry from the pending file."""
    pp = state_store.logs_path("canvas_pending.json")
    pending_all = state_store.load_json(pp, default={}) or {}
    pending_all.pop(course_id, None)
    if pending_all:
        state_store.save_json_atomic(pp, pending_all)
    else:
        state_store.delete_key(pp)
    return RedirectResponse(f"/settings?msg={quote('Dismissed the held Canvas sync.')}#canvas", 303)
