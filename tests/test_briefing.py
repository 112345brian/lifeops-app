"""runner.run_briefing — the daily morning briefing.

Assembles facts the engines already compute (at-risk coursework, today's load,
gym, discretionary money), asks the LLM for one short briefing, alerts once/day,
and persists it for the panel.
"""
import datetime, json, os
import pytest

from lifeops import runner, history, config, gather, llm

NOW = datetime.datetime(2026, 7, 8, 8, 0, 0)   # Wed morning


@pytest.fixture
def sandbox(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    monkeypatch.setattr(config, "ANTHROPIC_API_KEY", "x")
    monkeypatch.setattr(history, "days_with",
                        lambda action, a, b: {"2026-07-06", "2026-07-08"} if action == "gym" else set())
    monkeypatch.setattr(gather, "homework_input", lambda fs, now: [
        {"title": "M08 Paper", "due_in_h": 12, "due_in_days": 0.5,
         "remaining_min": 180, "progress": 0},                       # heavy + due today
        {"title": "M09 Reading", "due_in_h": 100, "due_in_days": 4.2,
         "remaining_min": 60, "progress": 0},
    ])
    monkeypatch.setattr(gather, "deadline_input", lambda fs, now: [])   # generalized crunch: none extra
    monkeypatch.setattr(gather, "spend_input", lambda fs, yn, now: {
        "fun_money": 42.0,
        "events": [{"label": "Concert", "days_until": 3, "cost": 40, "type": "concert"}]})
    monkeypatch.setattr(llm, "daily_briefing",
                        lambda facts: f"BRIEFING: {len(facts['coursework_at_risk'])} risk(s)")
    alerts = []
    monkeypatch.setattr(runner, "_alert_once", lambda key, *a, **k: alerts.append(key))
    os.makedirs(os.path.join(str(tmp_path), "logs"), exist_ok=True)
    return tmp_path, alerts


def _briefing_file(tmp):
    p = os.path.join(str(tmp), "logs", "briefing.json")
    return json.load(open(p, encoding="utf-8")) if os.path.exists(p) else None


def test_briefing_builds_facts_alerts_and_persists(sandbox):
    tmp, alerts = sandbox
    runner.run_briefing(object(), object(), NOW)

    assert any(k.startswith("briefing:2026-07-08") for k in alerts)
    b = _briefing_file(tmp)
    assert b and b["date"] == "2026-07-08" and b["text"].startswith("BRIEFING:")
    f = b["facts"]
    assert "M08 Paper" in f["due_today"]              # due in 12h → today
    assert "M09 Reading" not in f["due_today"]        # due in 100h → not today
    assert f["coursework_at_risk"]                    # load_engine flagged the heavy+soon paper
    assert f["gym_this_week"] == 2                    # two gym days this week
    assert f["discretionary_dollars"] == 42
    assert f["upcoming_paid_events"] == ["Concert in 3d (~$40)"]


def test_briefing_skips_without_api_key(sandbox, monkeypatch):
    tmp, alerts = sandbox
    monkeypatch.setattr(config, "ANTHROPIC_API_KEY", "")
    runner.run_briefing(object(), object(), NOW)
    assert alerts == []
    assert _briefing_file(tmp) is None


def test_briefing_registered_as_domain_and_daily_tier():
    assert "briefing" in runner.DOMAINS
    assert runner.DOMAINS["briefing"] is runner.run_briefing
    assert "briefing" in runner.TIERS["daily"]
