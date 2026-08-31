"""canvas.strip_html — plain-text conversion for feeding Canvas page HTML to
the LLM. Focus: <a href> preservation, since a plain tag-strip would throw
away exactly the reading links llm.extract_readings needs to surface."""
from lifeops.canvas import strip_html


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
