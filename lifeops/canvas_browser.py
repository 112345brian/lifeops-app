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
"""
import os
from pathlib import Path
from . import config

ROOT = Path(__file__).resolve().parent.parent
PROFILE_DIR = ROOT / "data" / "browser_profiles" / "canvas"

_CHROME_CANDIDATES = [
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
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


class BrowserCanvas:
    """Context manager. `with BrowserCanvas() as cv: cv.modules()`.
    headless=False is only for the interactive login script."""

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
        self.context = self._pw.chromium.launch_persistent_context(str(PROFILE_DIR), **kwargs)
        return self

    def __exit__(self, exc_type, exc, tb):
        try:
            if self.context:
                self.context.close()
        finally:
            if self._pw:
                self._pw.stop()

    def logged_in(self):
        """Probe: does the modules page actually load, or did we bounce
        somewhere else? Positive match on purpose — an unauthenticated
        session can land on the SSO form, a generic JHU landing page, or an
        "unauthorized" page on the SAME instructure.com domain, so blocklisting
        keywords is unreliable. Only the real modules page counts as success."""
        page = self.context.new_page()
        try:
            page.goto(f"{self.base}/courses/{self.course}/modules",
                      timeout=30000, wait_until="domcontentloaded")
            try:
                page.wait_for_load_state("networkidle", timeout=10000)
            except Exception:
                pass
            expected_path = f"/courses/{self.course}/modules"
            if expected_path not in page.url:
                return False
            try:
                return page.get_by_text("Course modules", exact=False).count() > 0
            except Exception:
                return True   # right URL and we couldn't check content — assume OK
        finally:
            page.close()

    def _get(self, path, extra_params=None):
        params = {"per_page": "100"}
        if extra_params:
            params.update(extra_params)
        r = self.context.request.get(f"{self.base}{path}", params=params, timeout=30000)
        if not r.ok:
            raise RuntimeError(f"Canvas request failed ({r.status}): {path}")
        return r.json()

    # --- same interface as canvas.Canvas ---

    def modules(self):
        return self._get(f"/api/v1/courses/{self.course}/modules", {"include[]": "items"})

    def assignments(self):
        return self._get(f"/api/v1/courses/{self.course}/assignments")

    def page(self, page_url_or_slug):
        return self._get(f"/api/v1/courses/{self.course}/pages/{page_url_or_slug}")

    def announcements(self, since_date=None):
        extra = {"context_codes[]": f"course_{self.course}"}
        if since_date:
            extra["start_date"] = since_date
        return self._get("/api/v1/announcements", extra)
