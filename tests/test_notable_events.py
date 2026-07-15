import datetime

from lifeops import history, notable_events

NOW = datetime.datetime(2026, 7, 14, 8, 0, 0)  # Tuesday


def _configure(monkeypatch, tmp_path):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))


def _event(title, date, all_day=False, occurrence_date=None):
    return {"itemType": "event", "title": title, "startTime": f"{date}T10:00:00", "allDay": all_day,
            "occurrenceDate": occurrence_date}


def test_one_off_upcoming_event_is_notable(tmp_path, monkeypatch):
    _configure(monkeypatch, tmp_path)
    items = [_event("Family BBQ", "2026-07-18")]

    out = notable_events.upcoming_notable_events(items, NOW)

    assert out == [{"title": "Family BBQ", "date": "2026-07-18", "weekday": "Saturday",
                    "start": "2026-07-18T10:00:00"}]


def test_weekly_recurring_event_is_routine_not_notable(tmp_path, monkeypatch):
    """Three occurrences of the same title within the visible 21-day-style
    pull, ~7 days apart -- well under the 3-week routine threshold."""
    _configure(monkeypatch, tmp_path)
    items = [
        _event("Book Club", "2026-07-15"),
        _event("Book Club", "2026-07-22"),
        _event("Book Club", "2026-07-29"),
    ]

    out = notable_events.upcoming_notable_events(items, NOW)

    assert out == []


def test_all_day_events_are_ignored(tmp_path, monkeypatch):
    _configure(monkeypatch, tmp_path)
    items = [_event("Someone's Birthday", "2026-07-16", all_day=True)]

    assert notable_events.upcoming_notable_events(items, NOW) == []


def test_task_items_are_ignored(tmp_path, monkeypatch):
    _configure(monkeypatch, tmp_path)
    items = [{"itemType": "task", "title": "Gym", "startTime": "2026-07-16T18:00:00", "allDay": False}]

    assert notable_events.upcoming_notable_events(items, NOW) == []


def test_events_outside_the_lookahead_window_are_excluded(tmp_path, monkeypatch):
    _configure(monkeypatch, tmp_path)
    items = [_event("Concert", "2026-08-01")]  # ~18 days out, past LOOKAHEAD_DAYS=7

    assert notable_events.upcoming_notable_events(items, NOW) == []


def test_infrequent_recurrence_recognized_via_local_history(tmp_path, monkeypatch):
    """A 5-week-cadence event (a haircut) has only ONE occurrence visible in
    any single pull, but a previous occurrence recorded in local history
    (from an earlier day's pull) should still let it be recognized -- in
    this case there's no prior occurrence yet, so it's notable by default,
    then the recorded history should keep the NEXT occurrence notable too
    since the gap is >= ROUTINE_INTERVAL_DAYS."""
    _configure(monkeypatch, tmp_path)
    first_pull = [_event("Haircut", "2026-07-16")]
    notable_events.upcoming_notable_events(first_pull, NOW)

    # ~5 weeks later: a new occurrence, plus the old one has aged out of
    # the visible pull (a real FlowSavvy pull would only show upcoming
    # items) but is still in local history from the first call.
    later_now = NOW + datetime.timedelta(days=35)
    second_pull = [_event("Haircut", "2026-08-20")]  # ~35 days after the first

    out = notable_events.upcoming_notable_events(second_pull, later_now)

    assert out == [{"title": "Haircut", "date": "2026-08-20", "weekday": "Thursday",
                    "start": "2026-08-20T10:00:00"}]


def test_event_becomes_routine_once_a_close_recurrence_is_recorded(tmp_path, monkeypatch):
    """A title that looked one-off the first time it was seen should stop
    being notable once a second occurrence within ROUTINE_INTERVAL_DAYS is
    observed -- e.g. a "new weekly class" only reveals its true cadence
    once its second occurrence shows up in a pull."""
    _configure(monkeypatch, tmp_path)
    notable_events.upcoming_notable_events([_event("New Class", "2026-07-16")], NOW)

    # A week later, the class's second occurrence is now visible, 8 days
    # after the first -- well under the 3-week routine threshold, but this
    # second occurrence itself is outside THIS week's lookahead window; the
    # test only cares that recording it changes the classification.
    later_now = NOW + datetime.timedelta(days=1)
    second_pull = [_event("New Class", "2026-07-16"), _event("New Class", "2026-07-23")]

    out = notable_events.upcoming_notable_events(second_pull, later_now)

    # 2026-07-16 is still within the lookahead window from later_now
    # (2026-07-15); it's now routine because 2026-07-23 is a known
    # occurrence within ROUTINE_INTERVAL_DAYS of it.
    assert out == []


def test_occurrence_date_alone_does_not_hide_rare_recurring_event(tmp_path, monkeypatch):
    """FlowSavvy/Google Calendar's own repeat marker means "this is a
    series", not "this is routine" -- annual/rare recurring events are
    often exactly the events worth surfacing."""
    _configure(monkeypatch, tmp_path)
    items = [_event("Birthday Dinner", "2026-07-17", occurrence_date="2026-07-17")]

    assert notable_events.upcoming_notable_events(items, NOW) == [
        {"title": "Birthday Dinner", "date": "2026-07-17", "weekday": "Friday",
         "start": "2026-07-17T10:00:00"}
    ]


def test_close_occurrence_date_series_is_still_routine(tmp_path, monkeypatch):
    _configure(monkeypatch, tmp_path)
    items = [
        _event("PAY DAY", "2026-07-17", occurrence_date="2026-07-17"),
        _event("PAY DAY", "2026-07-31", occurrence_date="2026-07-31"),
    ]

    assert notable_events.upcoming_notable_events(items, NOW) == []


def test_known_routine_title_is_excluded_even_as_a_first_occurrence(tmp_path, monkeypatch):
    """A title the caller already knows is routine (config.ROUTINE_EVENT_TITLES)
    is excluded on its very first observed occurrence, matched
    case-insensitively -- doesn't need to wait for local history to infer
    the same thing."""
    _configure(monkeypatch, tmp_path)
    items = [_event("Team Standup", "2026-07-18")]

    out = notable_events.upcoming_notable_events(items, NOW, known_routine_titles={"team standup"})

    assert out == []
