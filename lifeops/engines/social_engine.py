#!/usr/bin/env python3
"""Social-balance engine. Nudges when partner/friend time is overdue.
Recency from the durable history log. Pure logic — no names baked in (the
runner maps the configured partner name into the nudge text).

Used to also auto-create "X (proposed)"/"Plan X" FlowSavvy placeholder
tasks on a protect day, reserving a slot for a hangout that hadn't actually
been arranged with anyone yet. Dropped 2026-07-29 (user's own call): unlike
a solo commitment (gym), a hangout requires another person's agreement
first, so a fabricated task reading as a real commitment was actively
misleading rather than useful. Nudge-only now -- the user arranges it and
logs it themselves."""

def plan(partner_days, friend_days, partner_name="Partner"):
    nudges = []
    if partner_days is not None and partner_days >= 7:
        nudges.append(f"It's been {partner_days} days since {partner_name} — put a slot in for you two.")
    if friend_days is not None and friend_days >= 7:
        nudges.append(f"{friend_days} days since you saw a friend — reach out.")
    return {"nudges": nudges}
