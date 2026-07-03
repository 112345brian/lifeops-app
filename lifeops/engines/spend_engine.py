#!/usr/bin/env python3
"""Spend forecaster (advisory; warn-only). Given upcoming outings (with their
MARGINAL out-of-pocket cost — transport/drinks, not food which has its own line)
and current discretionary balance, warn when fun money won't cover what's coming.
Pure logic."""

def plan(events, fun_money):
    if not events:
        return {"level": "none", "text": ""}
    total = sum(e.get("cost") or 0 for e in events)
    soonest = min(events, key=lambda e: e.get("days_until", 99))
    if fun_money < total:
        imminent = soonest.get("days_until", 99) <= 3
        labels = ", ".join(dict.fromkeys(str(e.get("label") or "?") for e in events))[:60]
        lvl = "high" if imminent else "default"
        return {"level": lvl,
                "text": f"💀 ~${total:.0f} of plans coming ({labels}) and only "
                        f"${fun_money:.0f} of fun money. Cool it."}
    return {"level": "none", "text": ""}
