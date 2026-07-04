"""Non-interactive re-login for Canvas, launched by the LifeOps control
panel's "Re-login" button (lifeops/web.py: POST /account/canvas/relogin).

Same persistent Chrome profile as scripts/canvas_login.py, but this variant
is meant to be spawned by a service with no attached console — instead of
blocking on input() for you to press Enter, it polls logged_in() in the
background until it succeeds or a timeout elapses, then closes on its own.

For manual terminal use, scripts/canvas_login.py is still the one to run.
"""
import sys, os, time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from lifeops import canvas_browser

TIMEOUT_S = 15 * 60
POLL_S = 5


def main():
    with canvas_browser.BrowserCanvas(headless=False) as cv:
        page = cv.context.new_page()
        page.goto(f"{cv.base}/courses/{cv.course}/modules")
        deadline = time.time() + TIMEOUT_S
        ok = False
        while time.time() < deadline:
            time.sleep(POLL_S)
            if cv.logged_in():
                ok = True
                break
        page.close()
    print("Canvas session saved and verified." if ok
          else "Timed out waiting for login — run again if you need more time.")


if __name__ == "__main__":
    main()
