"""One-time (and occasional re-run) interactive Canvas login.

Run this by hand whenever LifeOps alerts that the Canvas session expired:

    python scripts\\canvas_login.py

Opens a REAL, visible Chrome window (a plain process, no automation attached
— see lifeops/canvas_browser.py's module docstring for why) using the same
persistent profile the LifeOps Canvas sync uses (data/browser_profiles/canvas/
— separate from your everyday Chrome profile). Log in with JHU SSO + Duo and
wait for the course modules page to load — this script polls for that itself
(see canvas_browser.capture_live_session) and snapshots the live session the
moment it's reached, so there's no need to come back and press Enter; just
close the window whenever you're done. That snapshot is what every future
headless sync run reuses, until it eventually expires again.
"""
import sys, os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from lifeops import canvas_browser


def main():
    print("Opening Canvas in a visible browser window...")
    proc = canvas_browser.launch_manual_login(canvas_browser.modules_url())
    print("Log in (JHU SSO + Duo). Waiting for the course modules page to load...")
    ok = canvas_browser.capture_live_session()
    if not ok:
        proc.terminate()
        print("Timed out waiting for the modules page to load — run again if you need more time.")
        return
    print("Session captured — you can close the browser window now.")
    with canvas_browser.BrowserCanvas(headless=True) as cv:
        ok = cv.logged_in()
    if ok:
        print("Session saved and verified — LifeOps Canvas sync will use it automatically.")
    else:
        print("Session captured but the follow-up check still failed — run again and make sure you "
              "reach the modules page before closing the window.")


if __name__ == "__main__":
    main()
