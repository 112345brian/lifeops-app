"""Settings page: env-var config editing and server restart. Canvas-account
controls live in routers.canvas even though they render on this same page --
see that module for why they're split out."""
import os, sys, subprocess, datetime, tempfile
from urllib.parse import quote
from fastapi import APIRouter, Form, Request
from fastapi.responses import HTMLResponse, RedirectResponse
from . import core

router = APIRouter()


def _set_env(key, val):
    lines = []
    try:
        lines = open(core.ENV, encoding="utf-8").read().splitlines()
    except FileNotFoundError:
        pass
    for i, l in enumerate(lines):
        if l.strip().startswith(key + "="):
            lines[i] = f"{key}={val}"
            break
    else:
        lines.append(f"{key}={val}")
    os.makedirs(os.path.dirname(core.ENV), exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix="env-", dir=os.path.dirname(core.ENV))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, core.ENV)
    finally:
        try:
            os.remove(tmp)
        except FileNotFoundError:
            pass

def _relogin_canvas():
    subprocess.Popen([sys.executable, os.path.join(core.ROOT, "scripts", "canvas_relogin.py")],
                     cwd=core.ROOT, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))

def _restart_server():
    """Restarts the LifeOps-web scheduled task from a detached helper process,
    since stopping the task kills this process before it could run the restart
    itself. Falls back to just exiting if it's not running as that service
    (e.g. a manual `uvicorn` dev run) — nothing else will bring it back up.

    Uses CREATE_NO_WINDOW rather than DETACHED_PROCESS for the helper: with no
    console at all (DETACHED_PROCESS), powershell.exe silently exits without
    running the script — CREATE_NO_WINDOW gives it a real (hidden) console so
    it actually executes.

    The delay before `schtasks /end` has to outlast this request's own
    303 redirect actually reaching the browser -- 800ms was too tight over
    a real network hop (confirmed 2026-07-14: the caller's browser was
    still sitting on the bare POST URL when the process died mid-response,
    so a reload/retry hit it as a GET and 405'd, reading as "the button
    doesn't work" when the restart itself had actually already fired)."""
    check = subprocess.run(["schtasks", "/query", "/tn", "LifeOps-web"],
                           capture_output=True,
                           creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    if check.returncode == 0:
        script = ('Start-Sleep -Milliseconds 3000; '
                  'schtasks /end /tn "LifeOps-web"; '
                  'Start-Sleep -Seconds 2; '
                  'schtasks /run /tn "LifeOps-web"')
        subprocess.Popen(["powershell", "-NoProfile", "-Command", script],
                         creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    else:
        os._exit(0)


@router.get("/settings", response_class=HTMLResponse)
def settings_page(request: Request):
    return core._page(request, "settings.html", "settings")

@router.post("/config")
async def set_config_bulk(request: Request):
    """Saves every EDITABLE field from the settings form in one submit --
    the page used to have a separate form + save button per row (14 of
    them), which was both visually noisy and meant 14 individual clicks to
    change more than one setting at once. Only keys already in EDITABLE are
    ever written; anything else in the POST body is ignored (an unexpected
    key can't smuggle in a new env var this way)."""
    form = await request.form()
    changed = False
    for key in core.EDITABLE:
        if key not in form:
            continue
        # newlines would inject extra lines into .env — flatten them
        value = str(form[key]).replace("\r", " ").replace("\n", " ").strip()
        if value != core._env_value(key):
            _set_env(key, value)
            changed = True
    msg = quote("Saved." if changed else "No changes.")
    return RedirectResponse(f"/settings?msg={msg}#config", 303)

@router.post("/account/canvas/relogin")
def account_canvas_relogin():
    """Opens a real, visible Chrome window on this machine (the same
    persistent profile the Canvas sync uses) for the user to sign back in —
    triggered from the control panel after a 'Canvas session expired' ntfy
    alert, since that alert can't itself open a browser on the PC."""
    _relogin_canvas()
    return RedirectResponse(f"/settings?msg={quote('Opening Chrome for Canvas — sign in, then it saves automatically.')}#accounts", 303)

@router.post("/system/restart")
def system_restart():
    """Restarts the control panel itself. Triggered from the Config card
    after editing a setting, since env-var changes only take effect on
    process start."""
    _restart_server()
    return RedirectResponse(f"/settings?msg={quote('Restarting server — give it a few seconds, then refresh.')}#config", 303)
