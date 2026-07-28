import sqlite3

from lifeops import db, history


def test_local_get_set_round_trip(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    db.local_set("fcm_token", {"token": "abc123"})

    assert db.local_get("fcm_token") == {"token": "abc123"}


def test_local_get_returns_default_when_missing(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    assert db.local_get("weather_grid_cache", default={}) == {}


def test_local_get_degrades_to_default_on_locked_database(tmp_path, monkeypatch):
    """Mirrors state_store.load_json's contract -- fcm._device_token and
    weather._load_grid_cache each caught their own I/O errors broadly
    before this migration, so local_get must keep degrading gracefully
    rather than propagating a lock/corruption error."""
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    def _boom():
        raise sqlite3.OperationalError("database is locked")
    monkeypatch.setattr(db, "local_conn", _boom)

    assert db.local_get("fcm_token", default={"fallback": True}) == {"fallback": True}


def test_local_db_is_independent_of_state_db(tmp_path, monkeypatch):
    """logs/local.db and private/logs/state.db must be two separate files --
    local.db is never backed up (see db.py's module docstring), so a key
    written there must not be visible through the tracked state.db."""
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    db.local_set("fcm_token", {"token": "xyz"})

    conn = db.state_conn()
    try:
        row = conn.execute("SELECT value FROM kv_state WHERE key='fcm_token'").fetchone()
    finally:
        conn.close()
    assert row is None
