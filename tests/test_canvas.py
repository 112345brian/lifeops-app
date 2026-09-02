"""canvas.strip_html — plain-text conversion for feeding Canvas page HTML to
the LLM. Focus: <a href> preservation, since a plain tag-strip would throw
away exactly the reading links llm.extract_readings needs to surface.

Also covers extract_text_file_refs/Canvas.file_text -- an assignment
description is frequently just submission boilerplate ("answer the
questions, render, submit") with the REAL content living in a linked file
(a .qmd problem set) instead; these find and fetch that file's text.
"""
from lifeops.canvas import strip_html, extract_text_file_refs, Canvas


def test_strip_html_preserves_link_as_trailing_bracket():
    html = 'Grimmer, J. (2015). <a href="https://example.com/paper">We are all social scientists now</a>.'
    out = strip_html(html)
    assert "We are all social scientists now [https://example.com/paper]" in out


def test_strip_html_preserves_multiple_links_independently():
    html = ('<li><a href="https://a.example/">Reading A</a></li>'
            '<li><a href="https://b.example/">Reading B</a></li>')
    out = strip_html(html)
    assert "Reading A [https://a.example/]" in out
    assert "Reading B [https://b.example/]" in out


def test_strip_html_link_anchor_with_nested_tags():
    # Canvas often wraps the anchor text in <em>/<span> -- those inner tags
    # must be stripped from the anchor text itself, not just left dangling.
    html = '<a href="https://example.com/">(Chapter 1) <em>Intro</em></a>'
    out = strip_html(html)
    assert "(Chapter 1) Intro [https://example.com/]" in out


def test_strip_html_no_link_unaffected():
    html = "<p>Just plain text, no links here.</p>"
    assert strip_html(html) == "Just plain text, no links here."


def test_strip_html_handles_none_and_empty():
    assert strip_html(None) == ""
    assert strip_html("") == ""


# ── extract_text_file_refs ──────────────────────────────────────────────────

def test_extract_text_file_refs_finds_qmd_link():
    html = ('<a href="https://jhu.instructure.com/courses/131470/files/18888376?wrap=1">'
            'ps1_ML_1.16.26.qmd</a>')
    assert extract_text_file_refs(html) == [("18888376", "ps1_ML_1.16.26.qmd")]


def test_extract_text_file_refs_skips_data_and_binary_files():
    html = ('<a href=".../files/1">sim_data.csv</a>'
            '<a href=".../files/2">notes.pdf</a>'
            '<a href=".../files/3">homework.docx</a>')
    assert extract_text_file_refs(html) == []


def test_extract_text_file_refs_finds_multiple_and_mixed():
    html = ('<a href=".../files/1">sim_data.csv</a>'
            '<a href=".../files/2">ps1.qmd</a>'
            '<a href=".../files/3">notes.md</a>')
    assert extract_text_file_refs(html) == [("2", "ps1.qmd"), ("3", "notes.md")]


def test_extract_text_file_refs_empty_for_no_links():
    assert extract_text_file_refs("<p>no links here</p>") == []
    assert extract_text_file_refs("") == []
    assert extract_text_file_refs(None) == []


# ── Canvas.file_text ─────────────────────────────────────────────────────────

class _FakeCanvasForFileText(Canvas):
    def __init__(self, meta, download_text):
        self._meta = meta
        self._download_text = download_text
        self.course = "131470"

    def _get(self, path, extra_params=None):
        return self._meta


def test_file_text_fetches_and_truncates(monkeypatch):
    cv = _FakeCanvasForFileText({"url": "https://s3.example/signed-download"}, "real content here")

    class _Resp:
        def raise_for_status(self):
            pass
        content = b"the quick brown fox " * 500   # long enough to exercise max_chars

    monkeypatch.setattr("lifeops.canvas.requests.get", lambda *a, **k: _Resp())
    text = cv.file_text("18888376", course_id="131470", max_chars=50)
    assert len(text) == 50
    assert text == ("the quick brown fox " * 500)[:50]


def test_file_text_returns_empty_on_missing_url():
    cv = _FakeCanvasForFileText({}, "")   # no "url" key -- e.g. a deleted/locked file
    assert cv.file_text("18888376", course_id="131470") == ""


def test_file_text_returns_empty_on_any_failure(monkeypatch):
    cv = _FakeCanvasForFileText({"url": "https://s3.example/signed-download"}, "")

    def _raise(*a, **k):
        raise ConnectionError("network down")
    monkeypatch.setattr("lifeops.canvas.requests.get", _raise)
    assert cv.file_text("18888376", course_id="131470") == ""
