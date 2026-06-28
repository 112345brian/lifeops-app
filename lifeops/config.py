"""Central config — loads secrets/settings from a .env file (never committed).

Nothing in here is a secret; the actual tokens live in .env on disk.
"""
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

def _load_env():
    env = ROOT / ".env"
    if env.exists():
        for line in env.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            os.environ.setdefault(k.strip(), v.strip())

_load_env()

# FlowSavvy — base URL + token come from my.flowsavvy.app/api/docs (you fill these in .env)
FLOWSAVVY_BASE_URL = os.environ.get("FLOWSAVVY_BASE_URL", "https://my.flowsavvy.app/api")
FLOWSAVVY_TOKEN    = os.environ.get("FLOWSAVVY_TOKEN", "")

# YNAB
YNAB_TOKEN   = os.environ.get("YNAB_TOKEN", "")
YNAB_BUDGET  = os.environ.get("YNAB_BUDGET", "last-used")
# Pure money-move / savings funds the categorizer must NEVER assign a spend to
# (a card swipe is never "Savings"). Lumpy spend-funds like Gifts/Splurge/Trips
# are NOT here — those do receive real transactions.
YNAB_NO_ASSIGN = [s.strip() for s in os.environ.get(
    "YNAB_NO_ASSIGN", "Savings,Emergency Fund,Stocks").split(",") if s.strip()]
# Overspending is covered by draining discretionary WANTS first, in this order —
# never savings/funds. Blowing a budget costs you fun, not your future; if even
# the wants can't cover it, it stays negative (and you get warned).
YNAB_COVER_ORDER = [s.strip() for s in os.environ.get(
    "YNAB_COVER_ORDER", "Shopping,Entertainment,Eating Out,Shows,Splurge").split(",") if s.strip()]

# Anthropic (judgment calls only)
ANTHROPIC_API_KEY = os.environ.get("ANTHROPIC_API_KEY", "")
JUDGE_MODEL       = os.environ.get("JUDGE_MODEL", "claude-haiku-4-5-20251001")

# ntfy
NTFY_SIGNAL_TOPIC = os.environ.get("NTFY_SIGNAL_TOPIC", "")   # phone -> app
NTFY_ALERTS_TOPIC = os.environ.get("NTFY_ALERTS_TOPIC", "")   # app -> phone

# FlowSavvy ids (from your account; the connector already revealed these)
LIST_PERSONAL = os.environ.get("LIST_PERSONAL", "6784")
LIST_COURSE   = os.environ.get("LIST_COURSE", "147765")
SH_EVENINGS   = os.environ.get("SH_EVENINGS", "427991")
SH_PERSONAL   = os.environ.get("SH_PERSONAL", "427988")

TZ = "America/Los_Angeles"

# --- personal identifiers: NEVER hardcode these; real values live in .env ---
PARTNER_NAME = os.environ.get("PARTNER_NAME", "Partner")
PARTNER_TASK = os.environ.get("PARTNER_TASK", f"{PARTNER_NAME} time")   # FlowSavvy task title
PARTNER_SIGNAL = os.environ.get("PARTNER_SIGNAL", f"saw {PARTNER_NAME.lower()}")  # ntfy body
FRIENDS_TASK = os.environ.get("FRIENDS_TASK", "Friends")
SOCIAL_CAL = os.environ.get("SOCIAL_CAL", "")          # partner's FlowSavvy calendar id
PROPOSE_AHEAD_DAYS = int(os.environ.get("PROPOSE_AHEAD_DAYS", "21"))  # propose hangouts ~3 weeks out
PLAN_LEAD_DAYS = int(os.environ.get("PLAN_LEAD_DAYS", "14"))          # "Plan it" task ~2 weeks before
HEARTBEAT_URL = os.environ.get("HEARTBEAT_URL", "")   # healthchecks.io ping (dead-man's switch)

# Deliberate priority hierarchy (FlowSavvy: asap > high > normal > low).
# Intended order: hard deadlines (Canvas, bumped by load-watcher) > gym (fixed
# block, immovable) > meal/coordination (normal) > chores & tentative social (low).
PRIO_MEAL = os.environ.get("PRIO_MEAL", "normal")
PRIO_SOCIAL_PLAN = os.environ.get("PRIO_SOCIAL_PLAN", "normal")      # the "go arrange it" to-do
PRIO_SOCIAL_PROPOSED = os.environ.get("PRIO_SOCIAL_PROPOSED", "low")  # tentative, unconfirmed

# Event calendars that block evenings / drive social spend: "id:type,id:type"
EVENT_CALS = dict(p.split(":") for p in os.environ.get("EVENT_CALS", "").split(",") if ":" in p)

# Discretionary "fun money" category names
DISCRETIONARY = [s.strip().lower() for s in os.environ.get(
    "DISCRETIONARY", "Shopping,Entertainment,Eating Out,Shows,Splurge").split(",") if s.strip()]

# Per-outing marginal cost by type
COSTS = {}
for _p in os.environ.get("OUTING_COSTS", "concert:40,party:35,date:50,friends:35").split(","):
    if ":" in _p:
        _k, _v = _p.split(":")
        try:
            COSTS[_k.strip()] = float(_v)
        except ValueError:
            pass
