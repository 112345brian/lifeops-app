from lifeops.engines import social_engine

GOOD_DAYS = ["2026-07-18", "2026-07-19", "2026-07-20"]

def test_protect_day_creates_both_when_neither_planned():
    out = social_engine.plan(partner_days=3, friend_days=3,
                             has_partner=False, has_friend=False,
                             good_days=GOOD_DAYS, is_protect_day=True,
                             partner_name="Reina")
    kinds = [c["kind"] for c in out["creates"]]
    assert "partner" in kinds
    assert "friends" in kinds

def test_protect_day_skips_existing_partner():
    out = social_engine.plan(partner_days=3, friend_days=3,
                             has_partner=True, has_friend=False,
                             good_days=GOOD_DAYS, is_protect_day=True)
    assert not any(c["kind"] == "partner" for c in out["creates"])
    assert any(c["kind"] == "friends" for c in out["creates"])

def test_protect_day_skips_existing_friend():
    out = social_engine.plan(partner_days=3, friend_days=3,
                             has_partner=False, has_friend=True,
                             good_days=GOOD_DAYS, is_protect_day=True)
    assert any(c["kind"] == "partner" for c in out["creates"])
    assert not any(c["kind"] == "friends" for c in out["creates"])

def test_non_protect_day_no_creates():
    out = social_engine.plan(partner_days=3, friend_days=3,
                             has_partner=False, has_friend=False,
                             good_days=GOOD_DAYS, is_protect_day=False)
    assert out["creates"] == []

def test_overdue_partner_nudge():
    out = social_engine.plan(partner_days=8, friend_days=3,
                             has_partner=True, has_friend=True,
                             good_days=GOOD_DAYS, is_protect_day=False,
                             partner_name="Reina")
    assert any("Reina" in n for n in out["nudges"])

def test_overdue_friend_nudge():
    out = social_engine.plan(partner_days=3, friend_days=10,
                             has_partner=True, has_friend=True,
                             good_days=GOOD_DAYS, is_protect_day=False)
    assert any("friend" in n.lower() for n in out["nudges"])

def test_no_nudge_when_recent():
    out = social_engine.plan(partner_days=2, friend_days=2,
                             has_partner=True, has_friend=True,
                             good_days=GOOD_DAYS, is_protect_day=False)
    assert out["nudges"] == []

def test_no_nudge_when_days_unknown():
    out = social_engine.plan(partner_days=None, friend_days=None,
                             has_partner=True, has_friend=True,
                             good_days=GOOD_DAYS, is_protect_day=False)
    assert out["nudges"] == []

def test_protect_day_single_good_day_uses_it_for_both():
    out = social_engine.plan(partner_days=3, friend_days=3,
                             has_partner=False, has_friend=False,
                             good_days=["2026-07-18"], is_protect_day=True)
    assert len(out["creates"]) == 2
    assert all(c["date"] == "2026-07-18" for c in out["creates"])

def test_protect_day_no_good_days_no_creates():
    out = social_engine.plan(partner_days=3, friend_days=3,
                             has_partner=False, has_friend=False,
                             good_days=[], is_protect_day=True)
    assert out["creates"] == []
