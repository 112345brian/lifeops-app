import datetime

from lifeops import history, risk_tracking

NOW = datetime.datetime(2026, 7, 14, 8, 0, 0)  # Tuesday


def _configure(monkeypatch, tmp_path):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))


def test_new_item_is_surfaced_with_concrete_phrase(tmp_path, monkeypatch):
    _configure(monkeypatch, tmp_path)
    items = [{"title": "M08 Paper", "due_iso": "2026-07-16T17:00:00"}]

    out = risk_tracking.newly_at_risk(items, NOW)

    assert len(out) == 1
    assert out[0]["title"] == "M08 Paper"
    assert out[0]["phrase"] == 'Finish "M08 Paper" by Thursday 5:00pm'


def test_already_surfaced_item_is_not_repeated(tmp_path, monkeypatch):
    _configure(monkeypatch, tmp_path)
    items = [{"title": "M08 Paper", "due_iso": "2026-07-16T17:00:00"}]

    risk_tracking.newly_at_risk(items, NOW)
    out = risk_tracking.newly_at_risk(items, NOW + datetime.timedelta(days=1))

    assert out == []


def test_same_title_different_due_date_is_surfaced_again(tmp_path, monkeypatch):
    """A rescheduled deadline is a genuinely new fact worth mentioning
    again, even if the title is unchanged."""
    _configure(monkeypatch, tmp_path)
    risk_tracking.newly_at_risk([{"title": "M08 Paper", "due_iso": "2026-07-16T17:00:00"}], NOW)

    out = risk_tracking.newly_at_risk(
        [{"title": "M08 Paper", "due_iso": "2026-07-20T17:00:00"}], NOW + datetime.timedelta(days=1))

    assert len(out) == 1


def test_same_title_same_date_different_time_is_surfaced_again(tmp_path, monkeypatch):
    """The displayed phrase includes the due time, so a same-day reschedule is
    a new actionable fact and must not be suppressed by a date-only key."""
    _configure(monkeypatch, tmp_path)
    risk_tracking.newly_at_risk([{"title": "M08 Paper", "due_iso": "2026-07-16T09:00:00"}], NOW)

    out = risk_tracking.newly_at_risk(
        [{"title": "M08 Paper", "due_iso": "2026-07-16T17:00:00"}], NOW + datetime.timedelta(days=1))

    assert len(out) == 1
    assert out[0]["phrase"] == 'Finish "M08 Paper" by Thursday 5:00pm'


def test_items_missing_title_or_due_are_skipped(tmp_path, monkeypatch):
    _configure(monkeypatch, tmp_path)
    items = [{"title": "", "due_iso": "2026-07-16T17:00:00"}, {"title": "X", "due_iso": None}]

    assert risk_tracking.newly_at_risk(items, NOW) == []


def test_malformed_due_iso_is_skipped_not_crashed(tmp_path, monkeypatch):
    _configure(monkeypatch, tmp_path)
    items = [{"title": "X", "due_iso": "not-a-date"}]

    assert risk_tracking.newly_at_risk(items, NOW) == []


def test_stale_records_are_pruned_and_can_resurface(tmp_path, monkeypatch):
    _configure(monkeypatch, tmp_path)
    due = "2026-07-15T09:00:00"  # 1 day out from NOW -- not stale yet at NOW
    risk_tracking.newly_at_risk([{"title": "Old Task", "due_iso": due}], NOW)

    # 40 days later -- the due date is now well past _STALE_DAYS (30) in the past.
    out = risk_tracking.newly_at_risk(
        [{"title": "Old Task", "due_iso": due}], NOW + datetime.timedelta(days=40))

    assert len(out) == 1
