import time

from lifeops import history, location, state_store


def test_set_location_rejects_malformed_input(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    assert location.set_location(None, -76.61) is False
    assert location.set_location("not-a-number", -76.61) is False
    assert location.set_location(39.29, 200) is False  # lon out of range
    assert location.set_location(91, -76.61) is False  # lat out of range
    assert state_store.load_json(state_store.logs_path("phone_location.json")) is None


def test_set_location_persists_and_round_trips(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    assert location.set_location(39.29, -76.61) is True
    assert location.get_location() == ("39.29", "-76.61")

    saved = state_store.load_json(state_store.logs_path("phone_location.json"))
    assert saved["lat"] == 39.29
    assert saved["lon"] == -76.61


def test_set_location_last_write_wins(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    location.set_location(39.29, -76.61)
    location.set_location(40.71, -74.01)

    assert location.get_location() == ("40.71", "-74.01")


def test_get_location_returns_none_when_never_reported(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    assert location.get_location() is None


def test_get_location_returns_none_when_stale(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    location.set_location(39.29, -76.61)
    path = state_store.logs_path("phone_location.json")
    data = state_store.load_json(path)
    data["reported_at"] = time.time() - location._MAX_AGE_SECONDS - 1
    state_store.save_json_atomic(path, data)

    assert location.get_location() is None
