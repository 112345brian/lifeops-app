"""load_engine.deadline_risk (generalized 'won't fit' watchdog) and
runner.run_cashflow (panel-only discretionary projection, no notifications)."""
import datetime, json, os
import pytest

from lifeops.engines import load_engine
from lifeops import runner, history, gather


# ── deadline_risk ────────────────────────────────────────────────────────────────

def test_no_items_no_alert():
    assert load_engine.deadline_risk([]) == {"alerts": []}


def test_comfortable_load_no_alert():
    # 1h of work due in 10 days: available ~35h — fits easily
    items = [{"title": "Reading", "due_in_days": 10, "remaining_min": 60}]
    assert load_engine.deadline_risk(items)["alerts"] == []


def test_crunch_flags_risk():
    # 10h of work due in 2 days: available ~7h — does not fit
    items = [{"title": "Big Paper", "due_in_days": 2, "remaining_min": 600}]
    out = load_engine.deadline_risk(items)
    assert len(out["alerts"]) == 1
    assert out["alerts"][0][1] == "high"
    assert "Big Paper" in out["alerts"][0][0]


def test_reports_only_earliest_binding_deadline():
    # sorted by due: 1d/5h binds first (avail 3.5h); the 5d item is downstream
    items = [{"title": "Later", "due_in_days": 5, "remaining_min": 180},
             {"title": "Soonest", "due_in_days": 1, "remaining_min": 300}]
    out = load_engine.deadline_risk(items)
    assert len(out["alerts"]) == 1
    assert "Soonest" in out["alerts"][0][0]      # earliest binding, not "Later"


def test_zero_remaining_items_ignored():
    items = [{"title": "Done", "due_in_days": 1, "remaining_min": 0}]
    assert load_engine.deadline_risk(items)["alerts"] == []


# ── run_cashflow ─────────────────────────────────────────────────────────────────

NOW = datetime.datetime(2026, 7, 8, 9, 0, 0)


@pytest.fixture
def sandbox(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    # run_cashflow must NEVER notify — fail loudly if it tries
    monkeypatch.setattr(runner, "_alert_once",
                        lambda *a, **k: pytest.fail("cashflow must not send notifications"))
    monkeypatch.setattr(runner, "_touch", lambda *a, **k: None)
    os.makedirs(os.path.join(str(tmp_path), "logs"), exist_ok=True)
    return tmp_path


def _cashflow_file(tmp):
    p = os.path.join(str(tmp), "logs", "cashflow.json")
    return json.load(open(p, encoding="utf-8")) if os.path.exists(p) else None


def test_cashflow_projects_weekly_and_never_notifies(sandbox, monkeypatch):
    monkeypatch.setattr(gather, "spend_input", lambda fs, yn, now: {
        "fun_money": 100.0,
        "events": [{"label": "Concert", "days_until": 3, "cost": 40},
                   {"label": "Date", "days_until": 10, "cost": 50}]})
    runner.run_cashflow(object(), object(), NOW)   # would fail if it notified
    c = _cashflow_file(sandbox)
    assert c["start_balance"] == 100
    bals = [w["balance"] for w in c["weeks"]]
    assert bals == [60, 10, 10, 10]                # wk1 −40, wk2 −50, then flat
    assert c["dips_below_zero"] is False


def test_cashflow_flags_going_negative(sandbox, monkeypatch):
    monkeypatch.setattr(gather, "spend_input", lambda fs, yn, now: {
        "fun_money": 30.0,
        "events": [{"label": "Show", "days_until": 2, "cost": 40}]})
    runner.run_cashflow(object(), object(), NOW)
    c = _cashflow_file(sandbox)
    assert c["weeks"][0]["balance"] == -10
    assert c["dips_below_zero"] is True


def test_cashflow_and_deadlines_registered():
    assert runner.DOMAINS["cashflow"] is runner.run_cashflow
    assert runner.DOMAINS["deadlines"] is runner.run_deadlines
    assert "cashflow" in runner.TIERS["daily"] and "deadlines" in runner.TIERS["daily"]
