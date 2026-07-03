"""Adherence learning — the feedback loop that makes the system *effective*.

It reads the durable history to measure what Brian ACTUALLY does, so the engines
schedule what he'll follow through on instead of an aspirational plan he ignores.
The whole point: maximize real adherence, not scheduling elegance.
"""
import datetime
from . import history

MIN_SAMPLES = 3   # don't "learn" from noise

def _hour(ts):
    """Robust hour extraction — handles offsets, 'Z', space separators; a fixed
    string slice silently misreads anything non-canonical."""
    try:
        return datetime.datetime.fromisoformat((ts or "").replace("Z", "+00:00")).hour
    except (ValueError, TypeError):
        try:
            return int(ts[11:13])
        except (ValueError, TypeError, IndexError):
            return 12

def _slot(ts):
    return "morning" if _hour(ts) < 11 else "evening"

def gym(now, days=42):
    """How reliably he completes gym by slot-type, and his real preferred time."""
    cut = (now - datetime.timedelta(days=days)).isoformat()
    done = [e for e in history.events("gym") if e["ts"] >= cut]
    missed = [e for e in history.events("gym_missed") if e["ts"] >= cut]

    def rate(s):
        d = sum(1 for e in done if _slot(e["ts"]) == s)
        m = sum(1 for e in missed if (e.get("meta") or {}).get("slot") == s)
        return (d / (d + m)) if (d + m) >= MIN_SAMPLES else None

    eve = sorted(_hour(e["ts"]) for e in done if _slot(e["ts"]) == "evening")
    return {"morning_rate": rate("morning"), "evening_rate": rate("evening"),
            "pref_evening_hour": eve[len(eve) // 2] if eve else None,
            "done": len(done), "missed": len(missed)}

def streak(action, now=None):
    """Current consecutive-day streak for an action (for momentum framing).
    Pass `now` to make it truly *current*: a streak whose latest day is before
    yesterday already broke and reports 0 instead of its stale length."""
    dates = sorted({e["ts"][:10] for e in history.events(action)}, reverse=True)
    if not dates:
        return 0
    latest = datetime.date.fromisoformat(dates[0])
    if now is not None and (now.date() - latest).days > 1:
        return 0
    n, cur = 0, latest
    for ds in dates:
        if datetime.date.fromisoformat(ds) == cur:
            n += 1; cur -= datetime.timedelta(days=1)
        else:
            break
    return n
