from lifeops.engines import social_engine
from lifeops.routine import Routine

PARTNER = Routine(id="partner", times=1, per_days=7, anchor="since_last")
FRIENDS = Routine(id="friends", times=1, per_days=7, anchor="since_last")


def test_overdue_partner_nudge():
    out = social_engine.plan(8, 3, PARTNER, FRIENDS, partner_name="Reina")
    assert any("Reina" in n for n in out["nudges"])


def test_overdue_friend_nudge():
    out = social_engine.plan(3, 10, PARTNER, FRIENDS)
    assert any("friend" in n.lower() for n in out["nudges"])


def test_no_nudge_when_recent():
    out = social_engine.plan(2, 2, PARTNER, FRIENDS)
    assert out["nudges"] == []


def test_no_nudge_when_days_unknown():
    out = social_engine.plan(None, None, PARTNER, FRIENDS)
    assert out["nudges"] == []


def test_exactly_seven_days_is_due_for_both():
    out = social_engine.plan(7, 7, PARTNER, FRIENDS)
    assert len(out["nudges"]) == 2


def test_plan_no_longer_creates_placeholder_tasks():
    """Locked down 2026-07-29: a hangout requires another person's
    agreement, unlike a solo commitment (gym), so this engine must never
    fabricate a "X (proposed)"/"Plan X" FlowSavvy task that reads as an
    already-arranged plan when nothing has actually been arranged."""
    out = social_engine.plan(30, 30, PARTNER, FRIENDS, partner_name="Reina")
    assert "creates" not in out


def test_custom_routine_threshold_is_honored():
    """A persisted override (e.g. via routine_store.save_routine) changes
    the actual nudge threshold, not just a cosmetic default -- this is the
    whole point of parameterizing plan() instead of hardcoding it."""
    short_fuse = Routine(id="partner", times=1, per_days=3, anchor="since_last")
    out = social_engine.plan(4, 100, short_fuse, FRIENDS)
    assert any("4 days" in n for n in out["nudges"])
