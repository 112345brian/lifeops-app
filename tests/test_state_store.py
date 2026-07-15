import json

from lifeops import history, state_store


def test_save_and_load_json_round_trip(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    path = state_store.logs_path("state.json")

    state_store.save_json_atomic(path, {"ok": True})

    assert state_store.load_json(path, require_type=dict) == {"ok": True}


def test_load_json_returns_default_for_corrupt_or_wrong_type(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    path = state_store.logs_path("state.json")
    path_parent = tmp_path / "logs"
    path_parent.mkdir()
    (path_parent / "state.json").write_text("not json", encoding="utf-8")

    assert state_store.load_json(path, default={}, require_type=dict) == {}

    (path_parent / "state.json").write_text(json.dumps(["not", "a", "dict"]), encoding="utf-8")
    assert state_store.load_json(path, default={}, require_type=dict) == {}


def test_append_line_creates_parent_directory(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    path = state_store.logs_path("events.jsonl")

    state_store.append_line(path, '{"x": 1}')
    state_store.append_line(path, '{"x": 2}')

    assert (tmp_path / "logs" / "events.jsonl").read_text(encoding="utf-8").splitlines() == [
        '{"x": 1}',
        '{"x": 2}',
    ]
