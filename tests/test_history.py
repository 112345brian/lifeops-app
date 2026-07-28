import sqlite3

from lifeops import db, history


def test_append_and_events_round_trip(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    history.append("gym", ts="2026-07-01T12:00:00")
    history.append("meal", ts="2026-07-02T12:00:00")

    assert [e["action"] for e in history.events()] == ["gym", "meal"]
    assert [e["action"] for e in history.events("gym")] == ["gym"]


def test_last_returns_max_ts_for_action(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    history.append("gym", ts="2026-07-01T12:00:00")
    history.append("gym", ts="2026-07-03T12:00:00")

    assert history.last("gym") == "2026-07-03T12:00:00"
    assert history.last("meal") is None


def test_days_with_filters_by_date_range(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    history.append("gym", ts="2026-07-01T12:00:00")
    history.append("gym", ts="2026-07-05T12:00:00")
    history.append("gym", ts="2026-07-10T12:00:00")

    assert history.days_with("gym", "2026-07-01", "2026-07-05") == {"2026-07-01", "2026-07-05"}


def test_remove_day_deletes_every_matching_entry_regardless_of_source(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    history.append("gym", ts="2026-07-01T09:00:00", source="ntfy")
    history.append("gym", ts="2026-07-01T18:00:00", source="panel")
    history.append("meal", ts="2026-07-01T12:00:00")

    history.remove_day("gym", "2026-07-01")

    assert [e["action"] for e in history.events()] == ["meal"]


def test_remove_at_checks_expected_ts_and_action_before_deleting(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    history.append("gym", ts="2026-07-01T12:00:00")
    history.append("meal", ts="2026-07-02T12:00:00")

    assert history.remove_at(0, expect_ts="wrong") is False
    assert history.remove_at(0, expect_action="wrong") is False
    assert [e["action"] for e in history.events()] == ["gym", "meal"]

    assert history.remove_at(0, expect_ts="2026-07-01T12:00:00", expect_action="gym") is True
    assert [e["action"] for e in history.events()] == ["meal"]


def test_remove_at_out_of_range_is_a_noop(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    history.append("gym", ts="2026-07-01T12:00:00")

    assert history.remove_at(10) is False
    assert history.remove_at(-1) is False
    assert [e["action"] for e in history.events()] == ["gym"]


def test_meta_round_trips_through_json(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    history.append("canvas", ts="2026-07-01T12:00:00", meta={"title": "Homework 3"})

    assert history.events()[0]["meta"] == {"title": "Homework 3"}


def test_reads_degrade_to_empty_on_locked_database(tmp_path, monkeypatch):
    """A shared state.db makes lock contention a real possibility across
    unrelated domains' writes -- every engine in this app reads cadence from
    history, so a transient lock must not crash a whole domain run."""
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    def _boom():
        raise sqlite3.OperationalError("database is locked")
    monkeypatch.setattr(db, "state_conn", _boom)

    assert history.events() == []
    assert history.last("gym") is None
    assert history.days_with("gym", "2026-07-01", "2026-07-05") == set()
