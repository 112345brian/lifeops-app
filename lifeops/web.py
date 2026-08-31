"""LifeOps control panel — FastAPI web UI.

Run:   uvicorn lifeops.web:app --host 0.0.0.0 --port 8765
Reach: tailscale serve 8765  →  https://<your-pc>.<tailnet>.ts.net

Routes are split by page/section under lifeops/routers/ (home, gym,
schedule, recurring, settings, canvas, history, api) -- this module just
wires up the app itself: auth, static assets, and route registration.
"""
import os, re, sys, datetime, hmac, logging
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, RedirectResponse, FileResponse
from fastapi.staticfiles import StaticFiles
from . import config
from .routers import core, home, gym, schedule, recurring, settings, canvas, history, api

STATIC_DIR = core.STATIC_DIR

app = FastAPI()
app.mount("/static", StaticFiles(directory=str(STATIC_DIR)), name="static")

for router in (home.router, gym.router, schedule.router, recurring.router,
               settings.router, canvas.router, history.router, api.router):
    app.include_router(router)


@app.get("/sw.js")
def service_worker():
    """Served at the root (not /static/sw.js) so its scope covers the whole
    app -- a service worker can only control paths at or below where it's
    registered from."""
    return FileResponse(STATIC_DIR / "sw.js", media_type="application/javascript",
                        headers={"Service-Worker-Allowed": "/"})


@app.middleware("http")
async def _auth(request: Request, call_next):
    """Optional shared-secret gate for Tailscale/funnel exposure. With WEB_TOKEN
    set, open the panel once as /?token=<secret>; a cookie keeps you in."""
    if config.WEB_TOKEN:
        query_token = request.query_params.get("token")
        supplied = query_token or request.cookies.get("lifeops_auth") or ""
        # Constant-time compare -- cheap defense-in-depth against a timing
        # side-channel, even though exploiting it would need tailnet access
        # to begin with under this threat model.
        if not hmac.compare_digest(supplied, config.WEB_TOKEN):
            return JSONResponse({"error": "unauthorized"}, status_code=401)
        # Behind a TLS-terminating proxy (Tailscale funnel etc.) Uvicorn sees
        # a plain-http request even though the real connection is https --
        # check X-Forwarded-Proto too, consistently, wherever secure= is set.
        forwarded = request.headers.get("x-forwarded-proto", "").split(",", 1)[0]
        secure = request.url.scheme == "https" or forwarded == "https"
        # Lax (not Strict): tapping an ntfy notification's Click link is a
        # top-level GET navigation from outside the app, and Strict cookies
        # are withheld on exactly that kind of navigation on some
        # OS/browser notification plumbing -- Lax still blocks the
        # cross-site POST/embed cases Strict exists to guard against.
        # Gate the redirect/cookie dance on CLIENT CAPABILITY (does this
        # look like a browser navigation?), not a URL-prefix convention --
        # scoping it to "/api/*" meant the identical bug (bare HTTP clients
        # with no CookieHandler redirect-then-401 forever, confirmed via
        # web.log: next-tasks requests 303'd, then immediately 401'd, and
        # NEXT_TASKS_JSON never once populated) would resurface the moment
        # any future non-/api/ integration used a bare client, or a future
        # browser-facing route happened to live under /api/. A real browser
        # top-level navigation sends "text/html" in Accept; the widget's
        # bare HttpURLConnection and any similar client don't.
        accepts_html = "text/html" in request.headers.get("accept", "")
        if query_token and request.method in ("GET", "HEAD") and accepts_html:
            clean_url = request.url.remove_query_params("token")
            resp = RedirectResponse(clean_url, status_code=303)
            resp.set_cookie("lifeops_auth", config.WEB_TOKEN,
                            max_age=90 * 24 * 3600, httponly=True,
                            secure=secure, samesite="lax")
            return resp
        resp = await call_next(request)
        if accepts_html and query_token == config.WEB_TOKEN:
            resp.set_cookie("lifeops_auth", config.WEB_TOKEN,
                            max_age=90 * 24 * 3600, httponly=True,
                            secure=secure, samesite="lax")
        return resp
    return await call_next(request)


class _RedactTokenFilter(logging.Filter):
    """Strips `token=<value>` out of uvicorn access-log records so WEB_TOKEN
    never lands in logs/web.log in cleartext (see main())."""
    _pat = re.compile(r"token=[^&\s\"]+")

    def filter(self, record):
        if isinstance(record.args, tuple):
            record.args = tuple(
                self._pat.sub("token=REDACTED", a) if isinstance(a, str) else a
                for a in record.args)
        elif isinstance(record.msg, str):
            record.msg = self._pat.sub("token=REDACTED", record.msg)
        return True


def main():
    """Windowless-safe entry point: `pythonw -m lifeops.web`.
    pythonw has no console, so sys.stdout/stderr are None and uvicorn's default
    logging crashes on startup — point them at a logfile before serving. Run via
    the `uvicorn` CLI instead for interactive/console use (see module docstring)."""
    import copy
    import uvicorn

    # Uvicorn's default formatters have no timestamp, which makes root-causing
    # a silent death (no exception, process just stops) impossible after the
    # fact. Prefix every log line with one.
    log_config = copy.deepcopy(uvicorn.config.LOGGING_CONFIG)
    for formatter in log_config["formatters"].values():
        formatter["fmt"] = "%(asctime)s " + formatter["fmt"]

    # The access logger includes the full request path -- e.g.
    # "GET /api/next-tasks?token=<WEB_TOKEN> HTTP/1.1" -- so once /api/*
    # clients started being served directly on every call (no cookie
    # bootstrap), WEB_TOKEN ended up in logs/web.log in cleartext on every
    # single poll, forever, rather than a one-time bootstrap (caught
    # 2026-07-12). Redact it at the logging layer rather than relying on
    # every call site to build token-free URLs for logging.
    logging.getLogger("uvicorn.access").addFilter(_RedactTokenFilter())

    if sys.stdout is None or sys.stderr is None:   # running under pythonw
        log = os.path.join(core.ROOT, "logs", "web.log")
        os.makedirs(os.path.dirname(log), exist_ok=True)
        f = open(log, "a", buffering=1, encoding="utf-8")
        sys.stdout = sys.stderr = f

    print(f"=== starting (pid {os.getpid()}) {datetime.datetime.now().isoformat()} ===", flush=True)
    try:
        uvicorn.run("lifeops.web:app", host="127.0.0.1", port=8765, log_config=log_config)
    finally:
        # If this doesn't print, the process was killed rather than exiting cleanly.
        print(f"=== exiting (pid {os.getpid()}) {datetime.datetime.now().isoformat()} ===", flush=True)


if __name__ == "__main__":
    main()
