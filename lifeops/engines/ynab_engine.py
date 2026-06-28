#!/usr/bin/env python3
"""Deterministic YNAB decision engine.

Pure: given categories, history, unapproved txns, and the current month, decide
what to categorize / approve / hold / cover. The novel-payee judgment (the only
subjective bit) is handed back as `novel` for the LLM to resolve.
"""
from collections import defaultdict, Counter

REVIEW = 150_000  # milliunits ($150): never auto-approve at/above this

def _payee_map(history):
    m = defaultdict(Counter)
    for t in history:
        if t.get("approved") and t.get("category_id") and t.get("payee_name"):
            m[t["payee_name"].lower()][t["category_id"]] += 1
    return m

def plan(categories, history, unapproved, month, cover_from="", no_assign=()):
    pmap = _payee_map(history)
    no_assign_ids = {c["id"] for c in categories if c["name"] in set(no_assign)}
    categorize, approve, novel, holds = [], [], [], []
    for t in unapproved:
        amt = t.get("amount", 0)
        if amt > 0 or t.get("transfer_account_id"):
            continue                                  # income / transfer / refund — leave it
        cid = t.get("category_id")
        if not cid:
            counts = pmap.get((t.get("payee_name") or "").lower())
            if counts:
                best, n = counts.most_common(1)[0]
                ok = (n >= 3 and n / sum(counts.values()) >= 0.7) or sum(counts.values()) <= 2
                if ok and best not in no_assign_ids:   # never auto-assign a pure fund
                    cid = best
            if not cid:
                novel.append({"id": t["id"], "payee": t.get("payee_name"), "amount": amt / 1000})
                continue
            categorize.append({"id": t["id"], "category_id": cid})
        if abs(amt) >= REVIEW:
            holds.append({"id": t["id"], "why": "large/unusual"})
        else:
            approve.append(t["id"])
    return {"categorize": categorize, "approve": approve, "novel": novel,
            "holds": holds, "cover": _cover(month, cover_from)}

def _cover(month, cover_from):
    if not cover_from:
        return []                                     # opt-in only — don't raid funds
    cats = month.get("categories", [])
    by_name = {c["name"].lower(): c for c in cats}
    names = [cover_from.lower()] if cover_from else ["buffer", "emergency", "stuff i forgot to budget"]
    src = next((by_name[n] for n in names if n in by_name), None)
    if not src:
        return []
    avail, moved, moves = src.get("balance", 0), 0, []
    for c in cats:
        if c["id"] == src["id"]:
            continue
        bal = c.get("balance", 0)
        if bal < 0 and avail > 0:
            amt = min(-bal, avail)
            moves.append({"category_id": c["id"], "budgeted": c.get("budgeted", 0) + amt})
            avail -= amt; moved += amt
    if moved:
        moves.append({"category_id": src["id"], "budgeted": src.get("budgeted", 0) - moved})
    return moves
