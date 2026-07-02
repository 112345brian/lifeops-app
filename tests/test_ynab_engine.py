"""ynab_engine — the engine that touches real money. Every branch tested."""
from lifeops.engines import ynab_engine

CATS = [
    {"id": "c-eat",  "name": "Eating Out"},
    {"id": "c-shop", "name": "Shopping"},
    {"id": "c-sav",  "name": "Savings"},
]

def _txn(id="t1", amount=-12_000, payee="Chipotle", category_id=None, transfer=None):
    return {"id": id, "amount": amount, "payee_name": payee,
            "category_id": category_id, "transfer_account_id": transfer}

def _hist(payee, cid, n):
    return [{"approved": True, "category_id": cid, "payee_name": payee}
            for _ in range(n)]

def _plan(history=(), unapproved=(), month=None, cover_order=(), no_assign=("Savings",)):
    return ynab_engine.plan(CATS, list(history), list(unapproved),
                            month or {"categories": []},
                            cover_order=cover_order, no_assign=no_assign)


# ── categorize / approve ────────────────────────────────────────────────────────

def test_confident_payee_categorized_and_approved():
    out = _plan(history=_hist("Chipotle", "c-eat", 4), unapproved=[_txn()])
    assert out["categorize"] == [{"id": "t1", "category_id": "c-eat"}]
    assert out["approve"] == ["t1"]
    assert out["novel"] == []

def test_single_occurrence_goes_to_llm_not_auto():
    # one historical sighting must NOT become an auto-rule
    out = _plan(history=_hist("Chipotle", "c-eat", 1), unapproved=[_txn()])
    assert out["categorize"] == []
    assert len(out["novel"]) == 1

def test_two_unanimous_occurrences_auto_categorize():
    out = _plan(history=_hist("Chipotle", "c-eat", 2), unapproved=[_txn()])
    assert out["categorize"] == [{"id": "t1", "category_id": "c-eat"}]

def test_split_small_sample_not_auto():
    # 1x Eating Out + 1x Shopping: not unanimous → LLM decides
    hist = _hist("Chipotle", "c-eat", 1) + _hist("Chipotle", "c-shop", 1)
    out = _plan(history=hist, unapproved=[_txn()])
    assert out["categorize"] == []
    assert len(out["novel"]) == 1

def test_majority_below_70pct_not_auto():
    hist = _hist("Chipotle", "c-eat", 3) + _hist("Chipotle", "c-shop", 2)
    out = _plan(history=hist, unapproved=[_txn()])   # 3/5 = 60%
    assert out["categorize"] == []

def test_never_auto_assigns_pure_fund():
    out = _plan(history=_hist("Weird Payee", "c-sav", 5), unapproved=[_txn(payee="Weird Payee")])
    assert out["categorize"] == []
    assert len(out["novel"]) == 1     # falls through to LLM instead

def test_precategorized_fund_txn_held_not_approved():
    # pre-set straight into Savings (import rule / fat-finger) → hold, never approve
    out = _plan(unapproved=[_txn(category_id="c-sav")])
    assert out["approve"] == []
    assert out["holds"] == [{"id": "t1", "why": "assigned to protected fund"}]

def test_precategorized_normal_txn_approved():
    out = _plan(unapproved=[_txn(category_id="c-eat")])
    assert out["approve"] == ["t1"]
    assert out["holds"] == []

def test_large_txn_held_for_review():
    out = _plan(history=_hist("Chipotle", "c-eat", 4),
                unapproved=[_txn(amount=-200_000)])   # $200 ≥ $150 review line
    assert out["approve"] == []
    assert out["holds"] == [{"id": "t1", "why": "large/unusual"}]
    assert out["categorize"] == [{"id": "t1", "category_id": "c-eat"}]  # still categorized

def test_income_and_transfers_skipped():
    out = _plan(unapproved=[_txn(amount=50_000), _txn(id="t2", transfer="acct")])
    assert out["approve"] == [] and out["novel"] == [] and out["holds"] == []


# ── cover (overspend) ───────────────────────────────────────────────────────────

def _month(**balances):
    """balances: name -> (balance, budgeted)"""
    return {"categories": [{"id": f"c-{n[:4].lower()}", "name": n,
                            "balance": b, "budgeted": g}
                           for n, (b, g) in balances.items()]}

def test_cover_moves_from_want_to_deficit():
    m = _month(**{"Eating Out": (-10_000, 50_000), "Shopping": (30_000, 40_000)})
    out = ynab_engine._cover(m, ["Shopping"])
    moves = {c["category_id"]: c["budgeted"] for c in out}
    assert moves["c-eati"] == 60_000     # +10k
    assert moves["c-shop"] == 30_000     # -10k

def test_cover_never_drives_source_below_zero():
    m = _month(**{"Eating Out": (-50_000, 50_000), "Shopping": (30_000, 40_000)})
    out = ynab_engine._cover(m, ["Shopping"])
    moves = {c["category_id"]: c["budgeted"] for c in out}
    assert moves["c-shop"] == 10_000     # gave only its 30k balance
    assert moves["c-eati"] == 80_000     # covered partially; rest stays red

def test_cover_skips_self_funding():
    m = _month(**{"Shopping": (-10_000, 40_000)})
    assert ynab_engine._cover(m, ["Shopping"]) == []

def test_cover_drains_sources_in_priority_order():
    m = _month(**{"Eating Out": (-40_000, 0),
                  "Shopping": (30_000, 30_000), "Splurge": (30_000, 30_000)})
    out = ynab_engine._cover(m, ["Shopping", "Splurge"])
    moves = {c["category_id"]: c["budgeted"] for c in out}
    assert moves["c-shop"] == 0          # fully drained first
    assert moves["c-splu"] == 20_000     # then 10k from second

def test_cover_negative_source_gives_nothing():
    m = _month(**{"Eating Out": (-10_000, 0), "Shopping": (-5_000, 20_000)})
    out = ynab_engine._cover(m, ["Shopping"])
    assert out == []                     # an overdrawn want can't cover anything

def test_cover_empty_order_returns_empty():
    m = _month(**{"Eating Out": (-10_000, 0)})
    assert ynab_engine._cover(m, []) == []
