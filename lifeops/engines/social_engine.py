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
logs it themselves.

The due-check itself (v1 consolidation, 2026-07-30) now goes through
lifeops.routine's shared primitive instead of an inline >=7 comparison --
see routine.py's module docstring for the fuller design this is a first
slice of.

partner_routine/friends_routine are passed in, not hardcoded here, so this
stays pure and testable: loading persisted overrides (see
lifeops/routine_store.py) is I/O, which belongs in the caller
(runner.run_social), matching this module's own "Pure logic" contract and
gym_engine.py's identical "engines are pure, the runner handles I/O"
principle."""
from ..routine import status_since_last


def plan(partner_days, friend_days, partner_routine, friends_routine, partner_name="Partner"):
    # None means "unknown," not "overdue" -- guard first, same as the
    # original inline check, since routine.status_since_last(None) treats
    # None as "never done, so due" (the right default for a fresh routine,
    # but not what social's nudge should do on missing data).
    nudges = []
    if partner_days is not None and status_since_last(partner_routine, partner_days)["due"]:
        nudges.append(f"It's been {partner_days} days since {partner_name} — put a slot in for you two.")
    if friend_days is not None and status_since_last(friends_routine, friend_days)["due"]:
        nudges.append(f"{friend_days} days since you saw a friend — reach out.")
    return {"nudges": nudges}
