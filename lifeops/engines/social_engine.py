#!/usr/bin/env python3
"""Social-balance engine. Protects weekly Reina + friend time and nudges when
overdue. Recency comes from the durable history log. Pure logic."""

def plan(reina_days, friend_days, has_reina, has_friend, good_days, is_protect_day):
    creates, nudges = [], []
    if is_protect_day and good_days:
        if not has_reina:
            creates.append({"title": "Reina time", "date": good_days[0]})
        if not has_friend:
            creates.append({"title": "Friends", "date": good_days[1 if len(good_days) > 1 else 0]})
    if reina_days is not None and reina_days >= 7:
        nudges.append(f"It's been {reina_days} days since Reina — put a slot in for you two.")
    if friend_days is not None and friend_days >= 7:
        nudges.append(f"{friend_days} days since you saw a friend — reach out.")
    return {"creates": creates, "nudges": nudges}
