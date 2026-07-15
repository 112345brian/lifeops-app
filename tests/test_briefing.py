"""runner.run_briefing — the daily morning briefing.

Assembles facts the engines already compute (at-risk coursework, today's load,
gym, discretionary money) into a fully deterministic text (attention's own
headline + deadline phrases + notable events, no LLM -- see run_briefing's
docstring), alerts once/day, and persists it for the panel.
"""
import datetime, json, os
import pytest

from lifeops import runner, history, gather

NOW = datetime.datetime(2026, 7, 8, 8, 0, 0)   # Wed morning


@pytest.fixture
def sandbox(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
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
        "fun_money": 42.0, "today_budget": 15.0,
        "events": [{"label": "Concert", "days_until": 3, "cost": 40, "type": "concert"}]})
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
    assert b and b["date"] == "2026-07-08"
    f = b["facts"]
    # the pushed text leads with attention's own deterministic headline --
    # no LLM involved (see run_briefing's docstring)
    assert b["text"].startswith(f["attention"]["headline"])
    assert "M08 Paper" in f["due_today"]              # due in 12h → today
    assert "M09 Reading" not in f["due_today"]        # due in 100h → not today
    assert f["coursework_at_risk"]                    # load_engine flagged the heavy+soon paper
    assert f["gym_last_7d"] == 2                       # two gym days in the trailing 7
    assert f["discretionary_dollars"] == 42
    assert f["discretionary_today_dollars"] == 15
    assert f["upcoming_paid_events"] == ["Concert in 3d"]


def test_briefing_registered_as_domain_and_daily_tier():
    assert "briefing" in runner.DOMAINS
    assert runner.DOMAINS["briefing"] is runner.run_briefing
    assert "briefing" in runner.TIERS["daily"]


def test_briefing_mentions_a_same_day_zero_cost_event(sandbox, monkeypatch):
    """days_until 0 must be named even though a calendar event's cost is
    never otherwise narrated -- "nothing's on fire" shouldn't mean
    "nothing's happening." Deterministic (see today_event_names in
    run_briefing), not dependent on the top-2-nearest-events truncation."""
    tmp, alerts = sandbox
    monkeypatch.setattr(gather, "spend_input", lambda fs, yn, now: {
        "fun_money": 42.0,
        "events": [{"label": "Family BBQ", "days_until": 0, "cost": 0, "type": "family"}]})

    runner.run_briefing(object(), object(), NOW)

    b = _briefing_file(tmp)
    assert "Also today: Family BBQ." in b["text"]


def test_briefing_includes_deterministic_deadline_phrase_and_does_not_repeat_it(sandbox, monkeypatch):
    """The newly-at-risk phrase is built with NO LLM involvement (see
    risk_tracking.newly_at_risk) and appended to the pushed text -- and,
    since it's already-known the moment it's first shown, must not repeat
    on a same-day re-run."""
    tmp, alerts = sandbox
    monkeypatch.setattr(gather, "homework_input", lambda fs, now: [
        {"title": "M08 Paper", "due_in_h": 12, "due_in_days": 0.5,
         "remaining_min": 180, "progress": 0, "due_iso": "2026-07-09T09:00:00"}])

    runner.run_briefing(object(), object(), NOW)
    b = _briefing_file(tmp)
    assert 'Finish "M08 Paper" by Thursday 9:00am' in b["text"]

    runner.run_briefing(object(), object(), NOW)
    b2 = _briefing_file(tmp)
    assert 'Finish "M08 Paper"' not in b2["text"]


def test_briefing_includes_upcoming_notable_events(sandbox, monkeypatch):
    schedule_items = [{"itemType": "event", "title": "Family BBQ",
                       "startTime": "2026-07-11T10:00:00", "allDay": False}]
    monkeypatch.setattr(gather, "_upcoming_schedule", lambda fs, now: schedule_items)

    tmp, alerts = sandbox
    runner.run_briefing(object(), object(), NOW)

    b = _briefing_file(tmp)
    assert "Family BBQ (Saturday)" in b["text"]


def test_briefing_dedupes_same_day_event_between_today_and_notable_sections(sandbox, monkeypatch):
    monkeypatch.setattr(gather, "spend_input", lambda fs, yn, now: {
        "fun_money": 42.0,
        "events": [{"label": "Family BBQ", "days_until": 0, "cost": 0, "type": "family"}]})
    schedule_items = [{"itemType": "event", "title": "Family BBQ",
                       "startTime": "2026-07-08T18:00:00", "allDay": False}]
    monkeypatch.setattr(gather, "_upcoming_schedule", lambda fs, now: schedule_items)

    tmp, alerts = sandbox
    runner.run_briefing(object(), object(), NOW)

    text = _briefing_file(tmp)["text"]
    assert "Also today: Family BBQ." in text
    assert "Coming up: Family BBQ" not in text
