"""Canvas LMS access via an authenticated browser session, for when no
CANVAS_TOKEN is available (JHU disables self-service API tokens).

Same method surface as canvas.Canvas (modules/assignments/page/announcements)
so runner.py can use either interchangeably. The trick: Canvas exposes the
exact same JSON REST API its own web UI calls, so once a browser session is
authenticated we hit those endpoints through Playwright's request context —
same responses the token-based client gets, just cookie-authenticated instead
of Bearer-token-authenticated. No HTML scraping, no duplicate parsing logic.

Session persistence: a dedicated Chrome profile directory (NOT the user's
daily browsing profile) that Playwright launches via launch_persistent_context.
Cookies/localStorage persist to disk across runs exactly like normal Chrome —
nothing is "kept alive" artificially. One-time setup: run scripts/canvas_login.py
and log in by hand (JHU SSO + Duo can never be automated). When that session
eventually expires, logged_in() detects it and the runner alerts you to redo
the one-time login instead of silently failing.

The unauthenticated login redirect (jhu.instructure.com/login -> ... ->
canvas.jhu.edu) sits behind Cloudflare Bot Fight Mode, which hard-blocks any
page navigation with Chrome DevTools Protocol attached — even a real Chrome
binary, even with anti-detection patches (verified: vanilla Playwright,
patchright, and manual navigator.webdriver/CDP-hiding args all still got
"Sorry, you have been blocked"; a genuinely bare `chrome.exe` process with no
CDP attached sails through). So the interactive login step
(launch_manual_login, used by scripts/canvas_login.py and
scripts/canvas_relogin.py) launches a plain subprocess instead of going
through Playwright — only the post-login verification (logged_in(), and the
daily sync's raw API requests) uses Playwright, and neither of those triggers
the block: authenticated requests to jhu.instructure.com never redirect
off-domain, and the daily sync hits Canvas's JSON API directly via
`context.request` (no rendered page, no JS, nothing for Cloudflare's browser
checks to see).
"""
import os, subprocess, json, time, urllib.request
from pathlib import Path
from . import config

DEBUG_PORT = 9333

ROOT = Path(__file__).resolve().parent.parent
PROFILE_DIR = ROOT / "data" / "browser_profiles" / "canvas"
# canvas_session is a browser *session* cookie (no expiry) — Chromium deletes
# session cookies from the profile's Cookies DB when a context closes cleanly,
# so relying on launch_persistent_context's own disk reload silently loses the
# login after the very next automated check. Snapshotting storage_state()
# (which captures live cookies regardless of session/persistent flag) and
# re-injecting it on launch sidesteps that entirely.
SESSION_STATE_FILE = ROOT / "data" / "browser_profiles" / "canvas_session_state.json"

_CHROME_CANDIDATES = [
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
]

def _chrome_path():
    for p in _CHROME_CANDIDATES:
        if os.path.exists(p):
            return p
    return None   # fall back to Playwright's own bundled Chromium

def _clear_stale_locks():
    """A crashed/killed prior run can leave Chrome's profile lock files behind,
    blocking the next launch. Safe to remove before starting a fresh session."""
    for name in ("SingletonLock", "SingletonSocket", "SingletonCookie"):
        f = PROFILE_DIR / name
        try:
            if f.exists() or f.is_symlink():
                f.unlink()
        except Exception:
            pass

def profile_exists():
    """False until scripts/canvas_login.py has been run once."""
    return PROFILE_DIR.exists() and any(PROFILE_DIR.iterdir())

def modules_url():
    base = (config.CANVAS_BASE_URL or "https://jhu.instructure.com").rstrip("/")
    return f"{base}/courses/{config.CANVAS_COURSE_ID}/modules"

def launch_manual_login(url, debug_port=DEBUG_PORT):
    """Open the persistent Canvas profile in a bare, non-CDP-driven Chrome
    process for the human to log in through. See the module docstring: any
    CDP-attached *navigation through the login redirect* gets hard-blocked by
    Cloudflare, but a plain subprocess isn't — and merely exposing a debug
    port (with nothing yet connected/navigating through it) doesn't change
    that, since Cloudflare's check fires on the page's own JS-visible
    automation fingerprint, not on whether a debug port exists. The debug
    port lets capture_live_session() read the *already-authenticated* cookie
    jar afterward without ever driving the login itself."""
    os.makedirs(PROFILE_DIR, exist_ok=True)
    _clear_stale_locks()
    exe = _chrome_path()
    if not exe:
        raise RuntimeError("Chrome not found — Canvas login needs the real Chrome binary")
    return subprocess.Popen(
        [exe, f"--user-data-dir={PROFILE_DIR}", f"--remote-debugging-port={debug_port}",
         "--new-window", url],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )

def capture_live_session(debug_port=DEBUG_PORT, timeout_s=15 * 60, poll_interval=2):
    """Poll the manual-login window's CDP debug endpoint until it reaches the
    modules page, then attach Playwright over CDP — no navigation, just
    reading the live cookie jar — and snapshot it via storage_state() while
    the session is still authenticated in memory. Necessary because
    canvas_session is a browser *session* cookie: Chromium never reloads
    session-only cookies from disk on a fresh launch (manual or automated),
    so waiting for the window to close and reading the profile's Cookies DB
    afterward silently loses the login every time. Returns True if captured,
    False on timeout (window never reached the modules page)."""
    expected = f"/courses/{config.CANVAS_COURSE_ID}/modules"
    deadline = time.time() + timeout_s
    reached = False
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{debug_port}/json", timeout=3) as resp:
                tabs = json.loads(resp.read())
            if any(expected in (t.get("url") or "") for t in tabs):
                reached = True
                break
        except Exception:
            pass
        time.sleep(poll_interval)
    if not reached:
        return False

    from playwright.sync_api import sync_playwright
    with sync_playwright() as pw:
        browser = pw.chromium.connect_over_cdp(f"http://127.0.0.1:{debug_port}")
        context = browser.contexts[0]
        SESSION_STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
        context.storage_state(path=str(SESSION_STATE_FILE))
        # deliberately no browser.close() — this is a CDP *attach*, not a
        # launch; closing it would kill the user's still-open window.
    return True


class BrowserCanvas:
    """Context manager. `with BrowserCanvas() as cv: cv.modules()`. Always
    headless now — the interactive login step uses launch_manual_login()
    instead, so BrowserCanvas only ever does headless verification/API
    calls."""

    def __init__(self, headless=True):
        self.headless = headless
        self.base = (config.CANVAS_BASE_URL or "https://jhu.instructure.com").rstrip("/")
        self.course = config.CANVAS_COURSE_ID
        self.context = None
        self._pw = None

    def __enter__(self):
        from playwright.sync_api import sync_playwright
        os.makedirs(PROFILE_DIR, exist_ok=True)
        _clear_stale_locks()
        self._pw = sync_playwright().start()
        kwargs = {"headless": self.headless, "viewport": {"width": 1280, "height": 900}}
        exe = _chrome_path()
        if exe:
            kwargs["executable_path"] = exe
        # launch_persistent_context has no storage_state kwarg (that's a
        # launch()+new_context() thing) — inject saved cookies by hand instead.
        self.context = self._pw.chromium.launch_persistent_context(str(PROFILE_DIR), **kwargs)
        if SESSION_STATE_FILE.exists():
            try:
                state = json.loads(SESSION_STATE_FILE.read_text())
                if state.get("cookies"):
                    self.context.add_cookies(state["cookies"])
            except Exception:
                pass
        return self

    def __exit__(self, exc_type, exc, tb):
        try:
            if self.context:
                self.context.close()
        finally:
            if self._pw:
                self._pw.stop()

    def _save_session(self):
        try:
            SESSION_STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
            self.context.storage_state(path=str(SESSION_STATE_FILE))
        except Exception:
            pass

    def logged_in(self):
        """Probe via the same JSON API the daily sync uses (no rendered page,
        nothing for Cloudflare to trip on) rather than navigating a page —
        page navigation redirects off-domain into a Cloudflare-guarded page
        when the session is stale and gives a false negative even when a
        rendered check would otherwise be fine. On success, snapshots the
        session so it survives this context closing (see SESSION_STATE_FILE)."""
        try:
            r = self.context.request.get(f"{self.base}/api/v1/users/self", timeout=30000)
        except Exception:
            return False
        ok = r.ok
        if ok:
            self._save_session()
        return ok

    def _get(self, path, extra_params=None):
        params = {"per_page": "100"}
        if extra_params:
            params.update(extra_params)
        r = self.context.request.get(f"{self.base}{path}", params=params, timeout=30000)
        if not r.ok:
            raise RuntimeError(f"Canvas request failed ({r.status}): {path}")
        self._save_session()
        return r.json()

    # --- same interface as canvas.Canvas ---

    def modules(self, course_id=None):
        return self._get(f"/api/v1/courses/{course_id or self.course}/modules", {"include[]": "items"})

    def assignments(self, course_id=None):
        return self._get(f"/api/v1/courses/{course_id or self.course}/assignments")

    def page(self, page_url_or_slug, course_id=None):
        return self._get(f"/api/v1/courses/{course_id or self.course}/pages/{page_url_or_slug}")

    def announcements(self, since_date=None, course_id=None):
        extra = {"context_codes[]": f"course_{course_id or self.course}"}
        if since_date:
            extra["start_date"] = since_date
        return self._get("/api/v1/announcements", extra)

    def file_text(self, file_id, course_id=None, max_chars=6000):
        """See canvas.Canvas.file_text -- same contract, browser-authenticated
        request context instead of a Bearer token."""
        try:
            meta = self._get(f"/api/v1/courses/{course_id or self.course}/files/{file_id}")
            url = meta.get("url")
            if not url:
                return ""
            r = self.context.request.get(url, timeout=30000)
            if not r.ok:
                return ""
            return r.text()[:max_chars]
        except Exception:
            return ""
