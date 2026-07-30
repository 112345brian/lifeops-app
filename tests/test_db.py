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


def test_state_get_set_round_trip(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    db.state_set("notify:alert_dedup:gym:normal", {"date": "2026-07-30"})

    assert db.state_get("notify:alert_dedup:gym:normal") == {"date": "2026-07-30"}


def test_state_get_returns_default_when_missing(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    assert db.state_get("notify:alert_dedup:nothing-here", default={}) == {}


def test_state_get_degrades_to_default_on_locked_database(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    def _boom():
        raise sqlite3.OperationalError("database is locked")
    monkeypatch.setattr(db, "state_conn", _boom)

    assert db.state_get("notify:alert_dedup:gym:normal", default={"fallback": True}) == {"fallback": True}


def test_state_set_is_visible_through_the_durable_state_db_not_local_db(tmp_path, monkeypatch):
    """The inverse of test_local_db_is_independent_of_state_db: state_set
    must land in the tracked, backed-up state.db, not the ephemeral,
    never-backed-up local.db -- this is the whole point of adding it
    (notify.py's alert-dedup marks need to survive a restore, unlike FCM
    token/weather cache, which don't)."""
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    db.state_set("notify:alert_dedup:gym:normal", {"date": "2026-07-30"})

    conn = db.local_conn()
    try:
        row = conn.execute(
            "SELECT value FROM kv_state WHERE key='notify:alert_dedup:gym:normal'").fetchone()
    finally:
        conn.close()
    assert row is None
