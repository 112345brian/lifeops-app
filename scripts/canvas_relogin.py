"""Non-interactive re-login for Canvas, launched by the LifeOps control
panel's "Re-login" button (lifeops/web.py: POST /account/canvas/relogin).

Same persistent Chrome profile as scripts/canvas_login.py, but this variant
is meant to be spawned by a service with no attached console — instead of
blocking on input() for you to press Enter, it polls the plain Chrome
window it opens (see lifeops/canvas_browser.py's module docstring for why
it's a plain, non-Playwright window) until you reach the modules page,
captures the session live via capture_live_session(), then you're free to
close the window whenever.

For manual terminal use, scripts/canvas_login.py is still the one to run.
"""
import sys, os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from lifeops import canvas_browser

TIMEOUT_S = 15 * 60


def main():
    proc = canvas_browser.launch_manual_login(canvas_browser.modules_url())
    ok = canvas_browser.capture_live_session(timeout_s=TIMEOUT_S)
    if not ok:
        proc.terminate()
        print("Timed out waiting for the modules page to load — run again if you need more time.")
        return
    print("Session captured — you can close the browser window now.")
    with canvas_browser.BrowserCanvas(headless=True) as cv:
        ok = cv.logged_in()
    print("Canvas session saved and verified." if ok
          else "Session captured but the follow-up check still failed — run again and make sure you "
               "reach the modules page before closing the window.")


if __name__ == "__main__":
    main()
