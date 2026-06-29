"""Canvas LMS REST client.

Uses the Canvas REST API (not scraping) to fetch modules, assignments,
pages, and announcements for a single course.

Token: CANVAS_TOKEN in .env  (Settings → Account → New access token)
"""
import re, requests
from . import config

class Canvas:
    def __init__(self):
        self.base = (config.CANVAS_BASE_URL or "https://jhu.instructure.com").rstrip("/")
        self.h    = {"Authorization": f"Bearer {config.CANVAS_TOKEN}"}
        self.course = config.CANVAS_COURSE_ID

    def _get(self, path, extra_params=None):
        params = [("per_page", "100")]
        if extra_params:
            params.extend(extra_params)
        r = requests.get(f"{self.base}{path}", headers=self.h, params=params, timeout=30)
        r.raise_for_status()
        return r.json()

    def modules(self):
        """All modules with their items nested."""
        return self._get(
            f"/api/v1/courses/{self.course}/modules",
            extra_params=[("include[]", "items")],
        )

    def assignments(self):
        """All assignments for the course."""
        return self._get(f"/api/v1/courses/{self.course}/assignments")

    def page(self, page_url_or_slug):
        """Page body (HTML in body field)."""
        return self._get(f"/api/v1/courses/{self.course}/pages/{page_url_or_slug}")

    def announcements(self, since_date=None):
        """Recent announcements. since_date: 'YYYY-MM-DD'."""
        extra = [("context_codes[]", f"course_{self.course}")]
        if since_date:
            extra.append(("start_date", since_date))
        return self._get("/api/v1/announcements", extra_params=extra)

def strip_html(html):
    """Minimal HTML → plain text for feeding to the LLM."""
    text = re.sub(r"<br\s*/?>", "\n", html or "", flags=re.I)
    text = re.sub(r"<li[^>]*>", "\n• ", text, flags=re.I)
    text = re.sub(r"<[^>]+>", " ", text)
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()
