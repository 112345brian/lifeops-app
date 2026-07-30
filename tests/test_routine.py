import datetime

from lifeops.routine import Routine, next_due_date, status

NOW = datetime.datetime(2026, 7, 30, 9, 0, 0)


def test_since_last_not_due_when_recent():
    r = Routine(id="partner", times=1, per_days=7, anchor="since_last")
    out = status(r, ["2026-07-25T12:00:00"], NOW)
    assert out["due"] is False
    assert out["days_since_last"] == 4


def test_since_last_due_at_exact_boundary():
    """Matches today's inclusive >= semantics (test_social_engine.py's
    test_exactly_seven_days_is_due_for_both)."""
    r = Routine(id="partner", times=1, per_days=7, anchor="since_last")
    out = status(r, ["2026-07-23T09:00:00"], NOW)
    assert out["days_since_last"] == 7
    assert out["due"] is True


def test_since_last_due_when_no_history():
    r = Routine(id="friends", times=1, per_days=7, anchor="since_last")
    out = status(r, [], NOW)
    assert out["due"] is True
    assert out["days_since_last"] is None


def test_since_last_uses_most_recent_of_multiple_events():
    r = Routine(id="meal", times=1, per_days=6, anchor="since_last")
    out = status(r, ["2026-07-01T09:00:00", "2026-07-26T09:00:00", "2026-07-10T09:00:00"], NOW)
    assert out["days_since_last"] == 4
    assert out["due"] is False


def test_window_not_due_when_target_met():
    r = Routine(id="gym", times=4, per_days=7, anchor="window")
    events = [f"2026-07-{d:02d}T09:00:00" for d in (24, 25, 27, 29)]
    out = status(r, events, NOW)
    assert out["times_this_window"] == 4
    assert out["due"] is False


def test_window_due_when_below_target():
    r = Routine(id="gym", times=4, per_days=7, anchor="window")
    events = [f"2026-07-{d:02d}T09:00:00" for d in (24, 27)]
    out = status(r, events, NOW)
    assert out["times_this_window"] == 2
    assert out["due"] is True


def test_window_excludes_events_outside_window():
    r = Routine(id="gym", times=4, per_days=7, anchor="window")
    # 2026-07-22 is 8 days before NOW -- outside a trailing 7-day window.
    out = status(r, ["2026-07-22T09:00:00", "2026-07-29T09:00:00"], NOW)
    assert out["times_this_window"] == 1


def test_window_dedups_same_calendar_day():
    r = Routine(id="gym", times=4, per_days=7, anchor="window")
    out = status(r, ["2026-07-29T06:00:00", "2026-07-29T18:00:00"], NOW)
    assert out["times_this_window"] == 1


def test_window_empty_history_is_due():
    r = Routine(id="gym", times=4, per_days=7, anchor="window")
    out = status(r, [], NOW)
    assert out["times_this_window"] == 0
    assert out["due"] is True


def test_next_due_date_since_last():
    r = Routine(id="vacuum", times=1, per_days=7, anchor="since_last")
    assert next_due_date(r, datetime.date(2026, 7, 20)) == datetime.date(2026, 7, 27)


def test_next_due_date_rejects_window_anchor():
    r = Routine(id="gym", times=4, per_days=7, anchor="window")
    try:
        next_due_date(r, datetime.date(2026, 7, 20))
        assert False, "expected ValueError"
    except ValueError:
        pass
