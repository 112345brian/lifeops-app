import sqlite3

from lifeops import db, history, state_store


def test_save_and_load_json_round_trip(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    path = state_store.logs_path("state.json")

    state_store.save_json_atomic(path, {"ok": True})

    assert state_store.load_json(path, require_type=dict) == {"ok": True}


def test_save_json_atomic_overwrites_existing_key(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    path = state_store.logs_path("state.json")

    state_store.save_json_atomic(path, {"n": 1})
    state_store.save_json_atomic(path, {"n": 2})

    assert state_store.load_json(path, require_type=dict) == {"n": 2}


def test_load_json_returns_default_for_missing_or_wrong_type(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    path = state_store.logs_path("state.json")

    assert state_store.load_json(path, default={}, require_type=dict) == {}

    state_store.save_json_atomic(path, ["not", "a", "dict"])
    assert state_store.load_json(path, default={}, require_type=dict) == {}


def test_load_json_returns_default_for_corrupt_stored_value(tmp_path, monkeypatch):
    """A torn write is no longer possible through save_json_atomic itself --
    SQLite's transaction guarantees replace the old temp-file+rename
    atomicity -- but load_json should still degrade gracefully if a row's
    value column somehow isn't valid JSON, simulated here directly at the
    storage layer."""
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    path = state_store.logs_path("state.json")

    conn = db.state_conn()
    conn.execute(
        "INSERT INTO kv_state(key, value, updated_at) VALUES ('state', 'not json', 'x')"
    )
    conn.commit()
    conn.close()

    assert state_store.load_json(path, default={}, require_type=dict) == {}


def test_append_line_and_recent_lines(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    path = state_store.logs_path("events.jsonl")

    state_store.append_line(path, '{"x": 1}')
    state_store.append_line(path, '{"x": 2}')

    assert state_store.recent_lines(path, 10) == ['{"x": 2}', '{"x": 1}']


def test_trim_log_keeps_only_newest_rows(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    path = state_store.logs_path("events.jsonl")

    for i in range(5):
        state_store.append_line(path, str(i))
    state_store.trim_log(path, keep_newest=2)

    assert state_store.recent_lines(path, 10) == ["4", "3"]


def test_load_json_degrades_to_default_on_locked_database(tmp_path, monkeypatch):
    """A shared state.db makes lock contention a real possibility across
    unrelated domains' writes (see db.state_conn's docstring) -- the old
    file-based load_json caught OSError broadly for exactly this class of
    "any I/O hiccup," so reads must keep degrading gracefully instead of
    raising sqlite3.OperationalError up into a caller that never expected
    load_json to raise."""
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    path = state_store.logs_path("state.json")

    def _boom():
        raise sqlite3.OperationalError("database is locked")
    monkeypatch.setattr(db, "state_conn", _boom)

    assert state_store.load_json(path, default={"fallback": True}) == {"fallback": True}


def test_different_paths_use_independent_keys(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    state_store.save_json_atomic(state_store.logs_path("a.json"), {"v": "a"})
    state_store.save_json_atomic(state_store.logs_path("b.json"), {"v": "b"})

    assert state_store.load_json(state_store.logs_path("a.json")) == {"v": "a"}
    assert state_store.load_json(state_store.logs_path("b.json")) == {"v": "b"}
