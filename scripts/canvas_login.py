"""One-time (and occasional re-run) interactive Canvas login.

Run this by hand whenever LifeOps alerts that the Canvas session expired:

    python scripts\\canvas_login.py

Opens a REAL, visible Chrome window (a plain process, no automation attached
— see lifeops/canvas_browser.py's module docstring for why) using the same
persistent profile the LifeOps Canvas sync uses (data/browser_profiles/canvas/
— separate from your everyday Chrome profile). Log in with JHU SSO + Duo,
wait for the course modules page to load (URL should read
jhu.instructure.com/courses/.../modules, not canvas.jhu.edu), then come back
here and press Enter WITHOUT closing the window yet. canvas_session/
log_session_id are true session-only cookies that Chrome purges from its own
on-disk profile the moment a new Chrome process reopens it — so this script
snapshots them live via CDP first (see canvas_browser.capture_session_cookies),
then closes the window for you. That snapshot is what every future headless
sync run reuses, until it eventually expires again.
"""
import sys, os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from lifeops import canvas_browser


def main():
    print("Opening Canvas in a visible browser window...")
    proc = canvas_browser.launch_manual_login(canvas_browser.modules_url())
    input("\nLog in (JHU SSO + Duo). Once you see the course modules page "
          "(URL should read jhu.instructure.com/courses/.../modules — not "
          "canvas.jhu.edu), come back here and press Enter WITHOUT closing "
          "the browser window yet...\n")
    canvas_browser.capture_session_cookies()
    proc.terminate()
    with canvas_browser.BrowserCanvas(headless=True) as cv:
        ok = cv.logged_in()
    if ok:
        print("Session saved and verified — LifeOps Canvas sync will use it automatically.")
    else:
        print("Still doesn't look logged in. Run this again and make sure you "
              "reach the modules page before pressing Enter.")


if __name__ == "__main__":
    main()
