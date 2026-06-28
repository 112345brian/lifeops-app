import datetime
from unittest.mock import patch
from lifeops import adherence

NOW = datetime.datetime(2026, 7, 6, 10, 0, 0)
CUT = (NOW - datetime.timedelta(days=42)).isoformat()

def _ts(date, hour):
    return f"{date}T{hour:02d}:30:00"

def _ev(action, date, hour, meta=None):
    return {"action": action, "ts": _ts(date, hour), "source": "test",
            "meta": meta or {}}

def test_gym_no_history_returns_nones():
    with patch("lifeops.adherence.history") as h:
        h.events.return_value = []
        result = adherence.gym(NOW)
    assert result["morning_rate"] is None
    assert result["evening_rate"] is None
    assert result["pref_evening_hour"] is None
    assert result["done"] == 0
    assert result["missed"] == 0

def test_gym_evening_rate_calculated():
    done = [_ev("gym", "2026-06-20", 19), _ev("gym", "2026-06-22", 19),
            _ev("gym", "2026-06-24", 19)]  # 3 evening sessions
    missed = [_ev("gym_missed", "2026-06-21", 19, meta={"slot": "evening"})]
    with patch("lifeops.adherence.history") as h:
        h.events.side_effect = lambda a: done if a == "gym" else missed
        result = adherence.gym(NOW)
    assert result["evening_rate"] == pytest.approx(0.75)  # 3/(3+1)
    assert result["morning_rate"] is None  # no morning samples

def test_gym_pref_evening_hour_is_median():
    # 3 sessions at 19, 19, 20 → median = 19
    done = [_ev("gym", "2026-06-20", 19), _ev("gym", "2026-06-22", 19),
            _ev("gym", "2026-06-24", 20)]
    with patch("lifeops.adherence.history") as h:
        h.events.side_effect = lambda a: done if a == "gym" else []
        result = adherence.gym(NOW)
    assert result["pref_evening_hour"] == 19

def test_gym_rate_none_below_min_samples():
    # only 2 samples — below MIN_SAMPLES=3
    done = [_ev("gym", "2026-06-20", 19), _ev("gym", "2026-06-22", 19)]
    with patch("lifeops.adherence.history") as h:
        h.events.side_effect = lambda a: done if a == "gym" else []
        result = adherence.gym(NOW)
    assert result["evening_rate"] is None

def test_streak_consecutive_days():
    events = [
        {"action": "gym", "ts": "2026-07-06T19:00:00", "source": "test"},
        {"action": "gym", "ts": "2026-07-05T19:00:00", "source": "test"},
        {"action": "gym", "ts": "2026-07-04T19:00:00", "source": "test"},
    ]
    with patch("lifeops.adherence.history") as h:
        h.events.return_value = events
        assert adherence.streak("gym") == 3

def test_streak_broken():
    events = [
        {"action": "gym", "ts": "2026-07-06T19:00:00", "source": "test"},
        # gap: no 7-05
        {"action": "gym", "ts": "2026-07-04T19:00:00", "source": "test"},
    ]
    with patch("lifeops.adherence.history") as h:
        h.events.return_value = events
        assert adherence.streak("gym") == 1

def test_streak_empty():
    with patch("lifeops.adherence.history") as h:
        h.events.return_value = []
        assert adherence.streak("gym") == 0

def test_streak_dedupes_same_day():
    # two gym events on same day should count as 1
    events = [
        {"action": "gym", "ts": "2026-07-06T08:00:00", "source": "test"},
        {"action": "gym", "ts": "2026-07-06T19:00:00", "source": "test"},
        {"action": "gym", "ts": "2026-07-05T19:00:00", "source": "test"},
    ]
    with patch("lifeops.adherence.history") as h:
        h.events.return_value = events
        assert adherence.streak("gym") == 2


import pytest
