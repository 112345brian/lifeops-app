"""The ONLY place the LLM is used: bounded judgment calls.

Each function takes structured facts and returns a structured decision — never
free-roaming. Used for the genuinely subjective slivers (e.g. categorizing a
novel payee). Everything deterministic lives in the engines, not here.
"""
import json
from anthropic import Anthropic
from . import config

_client = None
def _c():
    global _client
    if _client is None:
        _client = Anthropic(api_key=config.ANTHROPIC_API_KEY)
    return _client

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
    try:
        txt = msg.content[0].text.strip()
        txt = txt[txt.find("{"): txt.rfind("}") + 1]
        cat = json.loads(txt).get("category")
        return cat if cat in category_names else None
    except Exception:
        return None

def extract_readings(page_text, module_num):
    """Parse a Canvas Readings page into a structured list.
    Returns [{author, title, type, estimated_minutes}] or [] on failure.
    type: article | blog | chapter | accessible_chapter | tutorial | documentation | book
    """
    prompt = (
        f"Module {module_num} Readings & Resources page:\n\n{page_text[:4000]}\n\n"
        "List every individual reading/resource as a JSON array. Each element:\n"
        '{"author":"Last, F. (or org name)","title":"Short title","type":"article|blog|chapter|'
        'accessible_chapter|tutorial|documentation|book","estimated_minutes":30}\n'
        "Duration guidelines — article/blog: 20-30, tutorial-with-code: 40-60, "
        "documentation: 45-60, academic chapter: 40-50, accessible chapter: 25-35, "
        "full short book: 240. Return ONLY the JSON array."
    )
    try:
        msg = _c().messages.create(
            model=config.JUDGE_MODEL, max_tokens=1000,
            messages=[{"role": "user", "content": prompt}],
        )
        txt = msg.content[0].text.strip()
        txt = txt[txt.find("["): txt.rfind("]") + 1]
        return json.loads(txt)
    except Exception:
        return []

def weekly_digest(facts):
    """Turn the week's adherence stats into a blunt, supportive accountability
    note. This is NL synthesis — a real LLM job, not deterministic logic."""
    prompt = ("You are Brian's blunt but supportive accountability coach. From the "
              "stats, write 2-3 sentences: what he actually did this week, the ONE "
              "pattern worth fixing, and a real push for next week. Be direct and a "
              "little funny, never corny or preachy.\nStats: " + json.dumps(facts))
    msg = _c().messages.create(model=config.JUDGE_MODEL, max_tokens=220,
                               messages=[{"role": "user", "content": prompt}])
    return msg.content[0].text.strip()


def daily_briefing(facts):
    """One short morning briefing from today's facts — schedule load, at-risk
    coursework deadlines, gym status, discretionary money. NL synthesis of data
    the engines already computed; keep it a glanceable heads-up, not a lecture."""
    prompt = ("You are Brian's sharp, concise morning chief-of-staff. From the facts, "
              "write a 2-4 sentence briefing for TODAY: lead with anything genuinely "
              "at risk (a deadline that won't fit, money running short), then ONE "
              "concrete suggestion if warranted. Plain, direct, a little dry. No "
              "greeting, no sign-off, no emoji spam. If nothing's on fire, say so "
              "briefly.\n"
              "The deterministic attention object is authoritative: never change its "
              "state or imply a lower severity; use its headline/reasons to lead.\n"
              "Always name any upcoming_paid_events entry with days_until 0 (something "
              "happening TODAY) even if its cost is $0 or nothing else is at risk -- a "
              "$0 family event is exactly the kind of thing a chief-of-staff should "
              "still mention, since 'nothing's on fire' shouldn't mean 'nothing's "
              "happening.'\n"
              "Gym over the trailing 7 days (gym_last_7d, a ROLLING window, not the "
              "calendar week -- don't say \"this week\" for it), discretionary dollars, "
              "and coursework hours next 7d are shown as raw numbers in a stat row "
              "directly below this text -- NEVER include those numbers or ratios "
              "themselves anywhere in your sentences, even while explaining a risk. Say "
              "the JUDGMENT (behind pace / on track / tight / plenty of room), never the "
              "figure that produced it.\n"
              "BAD (restates the stat row, and wrongly implies calendar week): \"Gym is "
              "tracking 2/4 for the week.\"\n"
              "GOOD (same point, no number, no calendar-week framing): \"Gym's behind "
              "pace lately -- fit in a couple more sessions.\"\n"
              "BAD: \"You have 8.5 coursework hours budgeted next 7 days.\"\n"
              "GOOD: \"This week's coursework load is tight against other deadlines.\"\n"
              "BAD: \"You have $113 discretionary left.\"\n"
              "GOOD: \"Discretionary funds are thin heading into this week.\"\n"
              "Never mention a specific dollar cost for a calendar event -- if it's "
              "on the calendar it's assumed already paid for, so a ticket price is "
              "never worth surfacing (name the event itself, not what it costs).\n"
              "Format: put the single most important sentence in **bold**, on its "
              "own line. Put every other sentence on its own line too (separate "
              "lines with \\n, not run together in one paragraph). Use **bold** "
              "sparingly, only for genuine emphasis.\nFacts: "
              + json.dumps(facts))
    msg = _c().messages.create(model=config.JUDGE_MODEL, max_tokens=240,
                               messages=[{"role": "user", "content": prompt}])
    return msg.content[0].text.strip()
