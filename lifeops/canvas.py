"""Canvas LMS REST client.

Uses the Canvas REST API (not scraping) to fetch modules, assignments,
pages, and announcements for a single course.

Auth: CANVAS_TOKEN in .env (Settings → Account → New access token). If the
school disables self-service tokens, use lifeops.canvas_browser.BrowserCanvas
instead — same method surface, authenticated via a real browser session.
"""
import re, requests
from . import config

class Canvas:
    def __init__(self):
        self.base = (config.CANVAS_BASE_URL or "https://jhu.instructure.com").rstrip("/")
        self.h = {"Authorization": f"Bearer {config.CANVAS_TOKEN}"}
        self.course = config.CANVAS_COURSE_ID

    def _get(self, path, extra_params=None):
        params = [("per_page", "100")]
        if extra_params:
            params.extend(extra_params)
        r = requests.get(f"{self.base}{path}", headers=self.h, params=params, timeout=30)
        r.raise_for_status()
        return r.json()

    def modules(self, course_id=None):
        """All modules with their items nested."""
        return self._get(
            f"/api/v1/courses/{course_id or self.course}/modules",
            extra_params=[("include[]", "items")],
        )

    def assignments(self, course_id=None):
        """All assignments for the course."""
        return self._get(f"/api/v1/courses/{course_id or self.course}/assignments")

    def page(self, page_url_or_slug, course_id=None):
        """Page body (HTML in body field)."""
        return self._get(f"/api/v1/courses/{course_id or self.course}/pages/{page_url_or_slug}")

    def announcements(self, since_date=None, course_id=None):
        """Recent announcements. since_date: 'YYYY-MM-DD'."""
        extra = [("context_codes[]", f"course_{course_id or self.course}")]
        if since_date:
            extra.append(("start_date", since_date))
        return self._get("/api/v1/announcements", extra_params=extra)

    def file_text(self, file_id, course_id=None, max_chars=6000):
        """Best-effort raw text of an attached Canvas file (e.g. a .qmd
        problem set linked FROM an assignment description) -- returns "" on
        any failure or non-text content. An assignment's description is
        often just submission boilerplate ("answer the questions, render,
        submit") with the actual questions living in a linked file instead;
        see canvas.extract_text_file_refs for finding which links are worth
        fetching. The file API's metadata `url` is a pre-signed download
        link (not the courses/.../files/:id page itself), so this is a
        plain unauthenticated GET, same as a browser clicking the link."""
        try:
            meta = self._get(f"/api/v1/courses/{course_id or self.course}/files/{file_id}")
            url = meta.get("url")
            if not url:
                return ""
            r = requests.get(url, timeout=30)
            r.raise_for_status()
            return r.content.decode("utf-8", errors="ignore")[:max_chars]
        except Exception:
            return ""


# Canvas file links (including ones embedded via the rich-text editor's file
# picker) point at /courses/<id>/files/<file_id> -- captures the numeric id
# and the anchor's visible text (usually the original filename).
_FILE_LINK_RE = re.compile(r'<a[^>]+href="[^"]*?/files/(\d+)[^"]*"[^>]*>([^<]+)</a>', re.I)
# Text-based file types worth actually reading for an assignment's real
# content (a linked .qmd/.py/.md problem set) -- NOT data files (.csv, .xlsx)
# or binaries (.pdf, .docx), which either aren't useful as plain text or need
# their own parser this doesn't have.
_TEXT_FILE_EXTS = (".qmd", ".rmd", ".md", ".markdown", ".txt", ".r", ".py", ".ipynb")


def extract_text_file_refs(html):
    """[(file_id, filename), ...] for every Canvas file link in `html` whose
    filename looks like a text-based document. An assignment's Canvas
    `description` is frequently just submission/formatting boilerplate
    around a link to the actual problem set file -- this is how callers
    (see domains/canvas.py's _phase_labels_for) find and pull in the real
    content instead of proposing phases off boilerplate alone."""
    refs = []
    for file_id, filename in _FILE_LINK_RE.findall(html or ""):
        name = filename.strip()
        if name.lower().endswith(_TEXT_FILE_EXTS):
            refs.append((file_id, name))
    return refs


def strip_html(html):
    """Minimal HTML → plain text for feeding to the LLM.

    Readings pages link each citation straight to the JHU library/open-source
    copy (see canvas_engine.reading_task's `url` field) — a plain tag-strip
    would throw that href away with the rest of the markup, so every <a> is
    rewritten as "anchor text [URL]" BEFORE the generic tag-strip below runs,
    keeping the link visible in the plain text llm.extract_readings reads.
    """
    text = re.sub(r"<br\s*/?>", "\n", html or "", flags=re.I)
    text = re.sub(r"<li[^>]*>", "\n• ", text, flags=re.I)
    text = re.sub(r'<a\s[^>]*?href="([^"]*)"[^>]*>(.*?)</a>',
                  lambda m: f"{re.sub('<[^>]+>', ' ', m.group(2)).strip()} [{m.group(1)}]",
                  text, flags=re.I | re.S)
    text = re.sub(r"<[^>]+>", " ", text)
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()
