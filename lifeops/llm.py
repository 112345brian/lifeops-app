"""The ONLY place the LLM is used: bounded judgment calls.

Each function takes structured facts and returns a structured decision — never
free-roaming. Used for the genuinely subjective slivers (e.g. categorizing a
novel payee). Everything deterministic lives in the engines, not here.
"""
import json, time
from anthropic import Anthropic
from . import config, state_store

_client = None
def _c():
    global _client
    if _client is None:
        _client = Anthropic(
            api_key=config.ANTHROPIC_API_KEY,
            timeout=config.LLM_TIMEOUT_SECONDS,
            max_retries=config.LLM_MAX_RETRIES,
        )
    return _client


def _usage_log_file():
    return state_store.logs_path("llm_usage.jsonl")


def _log_usage(fn_name, model, msg):
    """Best-effort append-only record of token spend per call, so cost is
    at least visible after the fact somewhere on disk -- previously there
    was no record of how much any of this cost, ever. One JSON line per
    call rather than a single mutable file: append-only means a crash
    mid-write can't corrupt prior history the way a read-modify-write to
    one JSON object could."""
    try:
        usage = getattr(msg, "usage", None)
        if usage is None:
            return
        path = _usage_log_file()
        line = json.dumps({
            "ts": time.time(), "fn": fn_name, "model": model,
            "input_tokens": usage.input_tokens, "output_tokens": usage.output_tokens,
        })
        state_store.append_line(path, line)
    except Exception:
        pass  # cost visibility is a nice-to-have, never worth failing the actual call over


def categorize_unknown(payee, amount, category_names):
    """Pick the best category for a novel/ambiguous transaction, or None if
    even the model shouldn't guess. Returns a category name from the list or None."""
    prompt = (
        "You categorize one bank transaction for a personal budget. "
        f"Payee: {payee!r}. Amount: {amount:.2f}. "
        f"Allowed categories: {category_names}. "
        "Reply with ONLY a JSON object {\"category\": \"<one of the allowed names, "
        "or null if genuinely unclear>\"}. Do not invent categories."
    )
    msg = _c().messages.create(
        model=config.JUDGE_MODEL, max_tokens=100,
        messages=[{"role": "user", "content": prompt}],
    )
    _log_usage("categorize_unknown", config.JUDGE_MODEL, msg)
    try:
        txt = msg.content[0].text.strip()
        txt = txt[txt.find("{"): txt.rfind("}") + 1]
        cat = json.loads(txt).get("category")
        return cat if cat in category_names else None
    except Exception:
        return None

def extract_readings(page_text, module_num):
    """Parse a Canvas Readings page into a structured list.
    Returns [{author, title, type, estimated_minutes, locator, book_title, url}]
    or [] on failure.
    type: article | blog | chapter | accessible_chapter | tutorial | documentation | book
    locator: chapter/page info as written on the page (e.g. "Ch. 3, pp. 45-67"),
    or "" if the page doesn't specify one — needed in FlowSavvy's task notes so
    the reading is actually findable, since the title alone is truncated/short.
    book_title: the containing book's title when `title` is just a chapter/
    excerpt name, or "" when the reading itself IS the whole book/article (so
    canvas_engine.reading_task's citation doesn't lose which book a chapter's
    actually from).
    url: the reading's direct link (JHU library permalink or open-source
    copy), or "" if none — `page_text` carries it as a trailing "[URL]" after
    each linked citation (see canvas.strip_html, which preserves <a href> for
    exactly this).
    """
    prompt = (
        f"Module {module_num} Readings & Resources page:\n\n{page_text[:4000]}\n\n"
        "List every individual reading/resource as a JSON array. Each element:\n"
        '{"author":"Last, F. (or org name)","title":"Short title","type":"article|blog|chapter|'
        'accessible_chapter|tutorial|documentation|book","estimated_minutes":30,'
        '"locator":"chapter/page range as stated, e.g. \'Ch. 3, pp. 45-67\' or \'pp. 12-20\', '
        'empty string if not specified","book_title":"the containing book\'s title, ONLY when '
        '\'title\' is a chapter/excerpt name rather than the book itself — empty string otherwise",'
        '"url":"the reading\'s own direct link, copied EXACTLY from its trailing \'[URL]\' marker '
        'in the text above — empty string if it has no such marker. Never invent or guess a URL."}\n'
        "Duration guidelines — article/blog: 20-30, tutorial-with-code: 40-60, "
        "documentation: 45-60, academic chapter: 40-50, accessible chapter: 25-35, "
        "full short book: 240. Return ONLY the JSON array."
    )
    try:
        msg = _c().messages.create(
            model=config.JUDGE_MODEL, max_tokens=1000,
            messages=[{"role": "user", "content": prompt}],
        )
        _log_usage("extract_readings", config.JUDGE_MODEL, msg)
        txt = msg.content[0].text.strip()
        txt = txt[txt.find("["): txt.rfind("]") + 1]
        return json.loads(txt)
    except Exception:
        return []

def shorten_assignment_name(name, max_chars=35):
    """Shorten a long Canvas assignment name to a short, still-recognizable
    task-list label — e.g. "Predictive Policing Case Study/Evaluation Paper"
    -> "Policing Paper" — instead of a naive character slice, which would cut
    mid-word/mid-phrase and often lose exactly the words that make the title
    recognizable. Returns None on any failure; callers fall back to the
    original name (see runner._display_name, canvas_engine.split_assignment's
    `display_name`)."""
    prompt = (
        f"Shorten this assignment name to at most {max_chars} characters for a task-list "
        "title. Keep the most identifying words — the core topic and the deliverable type "
        "(e.g. \"Paper\"/\"Analysis\"/\"Prospectus\") — and drop filler words and genre "
        "qualifiers. Use standard abbreviations for well-known terms where it saves space "
        "(e.g. \"Machine Learning\" -> \"ML\", \"Artificial Intelligence\" -> \"AI\"), but "
        "otherwise do not add any word that isn't already in the original. Reply with ONLY "
        f"the shortened text, no quotes or extra punctuation.\n\nAssignment name: {name!r}"
    )
    try:
        msg = _c().messages.create(
            model=config.JUDGE_MODEL, max_tokens=30,
            messages=[{"role": "user", "content": prompt}],
        )
        _log_usage("shorten_assignment_name", config.JUDGE_MODEL, msg)
        short = msg.content[0].text.strip().strip('"').strip("'")
        return short if short and len(short) <= max_chars * 1.3 else None
    except Exception:
        return None


def weekly_digest(facts):
    """Turn the week's adherence stats into a blunt, supportive accountability
    note. This is NL synthesis — a real LLM job, not deterministic logic."""
    prompt = ("You are Brian's blunt but supportive accountability coach. From the "
              "stats, write 2-3 sentences: what he actually did this week, the ONE "
              "pattern worth fixing, and a real push for next week. Be direct and a "
              "little funny, never corny or preachy.\nStats: " + json.dumps(facts))
    msg = _c().messages.create(model=config.JUDGE_MODEL, max_tokens=220,
                               messages=[{"role": "user", "content": prompt}])
    _log_usage("weekly_digest", config.JUDGE_MODEL, msg)
    return msg.content[0].text.strip()

# NOTE: there used to be a daily_briefing() here, synthesizing the morning
# briefing text via the LLM. Retired 2026-07-15: its only remaining job was
# rephrasing attention.compute()'s already-plain-English headline in a
# slightly different voice (deadline risk and notable events had already
# been carved out to deterministic code -- see risk_tracking.py/
# notable_events.py). Not worth the cost, latency, or hallucination surface
# for a call whose real output was "restate this string, but sound more
# chief-of-staff about it." See briefing_service.build/compose_text.
