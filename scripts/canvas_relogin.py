"""Non-interactive re-login for Canvas, launched by the LifeOps control
panel's "Re-login" button (lifeops/web.py: POST /account/canvas/relogin).

Same persistent Chrome profile as scripts/canvas_login.py, but this variant
is meant to be spawned by a service with no attached console — instead of
blocking on input() for you to press Enter, it polls for a successful
cookie capture (canvas_browser.capture_session_cookies) while you're logging
in, then waits for you to close the plain Chrome window it opens (see
lifeops/canvas_browser.py's module docstring for why it's a plain,
non-Playwright window). Polling instead of a single capture-at-the-end is
necessary because canvas_session/log_session_id are session-only cookies
that vanish from the profile's own disk state the moment a new Chrome
process reopens it -- capturing periodically means whichever poll lands
after you actually finish logging in keeps a good snapshot, however long
you take.

For manual terminal use, scripts/canvas_login.py is still the one to run.
"""
import sys, os, time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from lifeops import canvas_browser

TIMEOUT_S = 15 * 60
POLL_S = 5


def main():
    proc = canvas_browser.launch_manual_login(canvas_browser.modules_url())
    deadline = time.time() + TIMEOUT_S
    captured = False
    while proc.poll() is None and time.time() < deadline:
        if canvas_browser.capture_session_cookies():
            captured = True
        time.sleep(POLL_S)
    if proc.poll() is None:
        proc.terminate()
        if not captured:
            print("Timed out waiting for the browser window to close — run again if you need more time.")
            return
    if not captured:
        print("Browser window closed before a session was ever captured — run again.")
        return
    with canvas_browser.BrowserCanvas(headless=True) as cv:
        ok = cv.logged_in()
    print("Canvas session saved and verified." if ok
          else "Still doesn't look logged in — run again and make sure you "
               "reach the modules page before closing the window.")


if __name__ == "__main__":
    main()
