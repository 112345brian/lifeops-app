"""One-time (and occasional re-run) interactive Canvas login.

Run this by hand whenever LifeOps alerts that the Canvas session expired:

    python scripts\\canvas_login.py

Opens a REAL, visible Chrome window using the same persistent profile the
LifeOps Canvas sync uses (data/browser_profiles/canvas/ — separate from your
everyday Chrome profile). Log in with JHU SSO + Duo, wait for the course
modules page to load, then come back here and press Enter. The session is
saved to disk and reused automatically by every future headless sync run
until it eventually expires again.
"""
import sys, os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from lifeops import canvas_browser


def main():
    print("Opening Canvas in a visible browser window...")
    with canvas_browser.BrowserCanvas(headless=False) as cv:
        page = cv.context.new_page()
        page.goto(f"{cv.base}/courses/{cv.course}/modules")
        input("\nLog in (JHU SSO + Duo). Once you see the course modules page, "
              "press Enter here...\n")
        page.close()
        ok = cv.logged_in()
    if ok:
        print("Session saved and verified — LifeOps Canvas sync will use it automatically.")
    else:
        print("Still doesn't look logged in. Run this again and make sure you "
              "reach the modules page before pressing Enter.")


if __name__ == "__main__":
    main()
