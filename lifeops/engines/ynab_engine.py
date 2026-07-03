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

def plan(categories, history, unapproved, month, cover_order=(), no_assign=()):
    pmap = _payee_map(history)
    no_assign_ids = {c["id"] for c in categories if c["name"] in set(no_assign)}
    categorize, approve, novel, holds = [], [], [], []
    for t in unapproved:
        amt = t.get("amount", 0)
        if amt > 0 or t.get("transfer_account_id"):
            continue                                  # income / transfer / refund — leave it
        cid = t.get("category_id")
        if cid and cid in no_assign_ids:
            # Pre-categorized straight into a pure fund (import rule / fat-finger).
            # A card swipe is never "Savings" — hold it for review, never auto-approve.
            holds.append({"id": t["id"], "why": "assigned to protected fund"})
            continue
        if not cid:
            counts = pmap.get((t.get("payee_name") or "").lower())
            if counts:
                best, n = counts.most_common(1)[0]
                total = sum(counts.values())
                # confident: 3+ sightings at >=70%, or a UNANIMOUS small sample of 2+.
                # (a single historical txn is not a rule — that goes to the LLM)
                ok = (n >= 3 and n / total >= 0.7) or (n == total and n >= 2)
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
            "holds": holds, "cover": _cover(month, cover_order)}

def _cover(month, cover_order):
    """Cover overspent categories by draining discretionary WANTS in priority
    order. Never touches anything not in cover_order (so savings/funds are safe).
    Sources are never driven below zero; uncovered overspend is left red."""
    if not cover_order:
        return []
    cats = month.get("categories", [])
    by_name = {c["name"].lower(): c for c in cats}
    sources = [by_name[n.lower()] for n in cover_order if n.lower() in by_name]
    avail = {s["id"]: s.get("balance", 0) for s in sources}
    new_budget = {}                                   # category_id -> updated budgeted
    def cur(c): return new_budget.get(c["id"], c.get("budgeted", 0))
    for c in cats:
        bal = c.get("balance", 0)
        if bal >= 0:
            continue
        deficit = -bal
        for s in sources:
            if deficit <= 0:
                break
            if s["id"] == c["id"]:
                continue
            give = min(deficit, avail.get(s["id"], 0))
            if give <= 0:
                continue
            new_budget[c["id"]] = cur(c) + give
            new_budget[s["id"]] = cur(s) - give
            avail[s["id"]] -= give
            deficit -= give
    return [{"category_id": cid, "budgeted": b} for cid, b in new_budget.items()]
