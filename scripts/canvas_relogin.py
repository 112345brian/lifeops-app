"""Non-interactive re-login for Canvas, launched by the LifeOps control
panel's "Re-login" button (lifeops/web.py: POST /account/canvas/relogin).

Same persistent Chrome profile as scripts/canvas_login.py, but this variant
is meant to be spawned by a service with no attached console — instead of
blocking on input() for you to press Enter, it waits for you to close the
plain Chrome window it opens (see lifeops/canvas_browser.py's module
docstring for why it's a plain, non-Playwright window), then verifies the
session and exits on its own.

For manual terminal use, scripts/canvas_login.py is still the one to run.
"""
import sys, os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from lifeops import canvas_browser

TIMEOUT_S = 15 * 60


def main():
    proc = canvas_browser.launch_manual_login(canvas_browser.modules_url())
    try:
        proc.wait(timeout=TIMEOUT_S)
    except Exception:
        proc.terminate()
        print("Timed out waiting for the browser window to close — run again if you need more time.")
        return
    with canvas_browser.BrowserCanvas(headless=True) as cv:
        ok = cv.logged_in()
    print("Canvas session saved and verified." if ok
          else "Still doesn't look logged in — run again and make sure you "
               "reach the modules page before closing the window.")


if __name__ == "__main__":
    main()
