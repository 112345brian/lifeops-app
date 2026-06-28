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
