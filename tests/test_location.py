import json
import os
import time

from lifeops import history, location


def test_set_location_rejects_malformed_input(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    assert location.set_location(None, -76.61) is False
    assert location.set_location("not-a-number", -76.61) is False
    assert location.set_location(39.29, 200) is False  # lon out of range
    assert location.set_location(91, -76.61) is False  # lat out of range
    assert not os.path.exists(os.path.join(str(tmp_path), "logs", "phone_location.json"))


def test_set_location_persists_and_round_trips(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    assert location.set_location(39.29, -76.61) is True
    assert location.get_location() == ("39.29", "-76.61")

    saved = json.loads((tmp_path / "logs" / "phone_location.json").read_text(encoding="utf-8"))
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
    path = os.path.join(str(tmp_path), "logs", "phone_location.json")
    data = json.loads(open(path, encoding="utf-8").read())
    data["reported_at"] = time.time() - location._MAX_AGE_SECONDS - 1
    open(path, "w", encoding="utf-8").write(json.dumps(data))

    assert location.get_location() is None
