from lifeops.engines import social_engine


def test_overdue_partner_nudge():
    out = social_engine.plan(partner_days=8, friend_days=3, partner_name="Reina")
    assert any("Reina" in n for n in out["nudges"])


def test_overdue_friend_nudge():
    out = social_engine.plan(partner_days=3, friend_days=10)
    assert any("friend" in n.lower() for n in out["nudges"])


def test_no_nudge_when_recent():
    out = social_engine.plan(partner_days=2, friend_days=2)
    assert out["nudges"] == []


def test_no_nudge_when_days_unknown():
    out = social_engine.plan(partner_days=None, friend_days=None)
    assert out["nudges"] == []


def test_exactly_seven_days_is_due_for_both():
    out = social_engine.plan(partner_days=7, friend_days=7)
    assert len(out["nudges"]) == 2


def test_plan_no_longer_creates_placeholder_tasks():
    """Locked down 2026-07-29: a hangout requires another person's
    agreement, unlike a solo commitment (gym), so this engine must never
    fabricate a "X (proposed)"/"Plan X" FlowSavvy task that reads as an
    already-arranged plan when nothing has actually been arranged."""
    out = social_engine.plan(partner_days=30, friend_days=30, partner_name="Reina")
    assert "creates" not in out
