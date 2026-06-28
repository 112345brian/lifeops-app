#!/usr/bin/env python3
"""Social-balance engine. Protects weekly partner + friend time and nudges when
overdue. Recency from the durable history log. Pure logic — no names baked in
(the runner maps 'kind' to the configured task titles / partner name)."""

def plan(partner_days, friend_days, has_partner, has_friend, good_days, is_protect_day,
         partner_name="Partner"):
    creates, nudges = [], []
    if is_protect_day and good_days:
        if not has_partner:
            creates.append({"kind": "partner", "date": good_days[0]})
        if not has_friend:
            creates.append({"kind": "friends", "date": good_days[1 if len(good_days) > 1 else 0]})
    if partner_days is not None and partner_days >= 7:
        nudges.append(f"It's been {partner_days} days since {partner_name} — put a slot in for you two.")
    if friend_days is not None and friend_days >= 7:
        nudges.append(f"{friend_days} days since you saw a friend — reach out.")
    return {"creates": creates, "nudges": nudges}
