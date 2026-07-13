"""Deterministic cross-domain attention model.

The LLM may explain this state, but it never chooses it.  Keep this module
pure so every client can trust the same ordered reasons and headline.
"""

_RANK = {"ok": 0, "watch": 1, "risk": 2, "fucked": 3}
_DOMAIN_PRIORITY = {"coursework": 0, "system": 1, "money": 2, "gym": 3}


def _reason(domain, severity, title, action, *, due=None):
    out = {"domain": domain, "severity": severity, "title": title,
           "recommended_action": action}
    if due is not None:
        out["due"] = due
    return out


def compute(facts, system=None):
    """Return ``{state, symbol, label, headline, reasons}`` from plain data.

    ``facts`` is the structured daily-briefing input. ``system`` is optional
    runner health data, allowing the panel to escalate independently of the
    once-daily briefing.
    """
    has_system = system is not None
    system = system or {}
    reasons = []

    errors = system.get("errors") or {}
    age_mins = system.get("age_mins")
    if has_system and errors:
        reasons.append(_reason("system", "fucked", "Automation has errors",
                               "Open System and fix the failed domains."))
    elif has_system and (age_mins is None or age_mins >= 120):
        reasons.append(_reason("system", "risk", "LifeOps data is stale",
                               "Run catchup and check the scheduler."))
    elif has_system and age_mins >= 30:
        reasons.append(_reason("system", "watch", "LifeOps has not synced recently",
                               "Refresh if the next scheduled run does not recover."))

    for item in facts.get("overdue", []):
        hours = max(0, round(-float(item.get("due_in_h", 0))))
        severity = "fucked" if hours >= 24 else "risk"
        title = item.get("title") or "Overdue task"
        reasons.append(_reason("coursework", severity, f"Overdue: {title}",
                               "Do it now or deliberately reschedule it.", due=item.get("due")))

    seen = {r["title"].removeprefix("Overdue: ") for r in reasons}
    for title in facts.get("coursework_at_risk", []):
        if title not in seen:
            reasons.append(_reason("coursework", "risk", f"Deadline risk: {title}",
                                   "Protect enough focused time before the deadline."))

    due_today = facts.get("due_today") or []
    if due_today:
        title = due_today[0] if len(due_today) == 1 else f"{len(due_today)} tasks due today"
        reasons.append(_reason("coursework", "watch", title,
                               "Finish today's due work before lower-priority tasks."))

    # discretionary_dollars is net of known upcoming event costs (see
    # gather.spend_input's docstring) -- "after what's already spoken for,"
    # not the raw account balance, hence the copy below says so rather than
    # reading like the bank balance itself is gone.
    money = facts.get("discretionary_dollars")
    if money is not None and money < 0:
        reasons.append(_reason("money", "risk", "Discretionary balance is negative after upcoming plans",
                               "Pause optional spending and review the cash-flow plan."))
    elif money is not None and money < 100:
        reasons.append(_reason("money", "watch", "Discretionary buffer is low after upcoming plans",
                               "Check upcoming spending before committing."))

    gym = facts.get("gym_last_7d")
    target = facts.get("gym_target")
    if gym is not None and target and gym <= max(0, target - 2):
        reasons.append(_reason("gym", "watch", "Gym cadence is behind target",
                               "Take the next viable gym slot."))

    reasons.sort(key=lambda r: (-_RANK[r["severity"]],
                                _DOMAIN_PRIORITY.get(r["domain"], 99)))
    reasons = reasons[:6]
    state = reasons[0]["severity"] if reasons else "ok"
    visuals = {
        "ok": ("●", "OK"), "watch": ("▲", "WATCH"),
        "risk": ("◆", "RISK"), "fucked": ("■", "FUCKED"),
    }
    symbol, label = visuals[state]
    headline = (reasons[0]["recommended_action"] if reasons
                else "You are clear. Follow the next scheduled move.")
    return {"state": state, "symbol": symbol, "label": label,
            "headline": headline, "reasons": reasons}
