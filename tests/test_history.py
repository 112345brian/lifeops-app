import json

from lifeops import history


def _write(path, lines):
    path.write_text("".join(lines), encoding="utf-8")


def test_events_skip_corrupt_lines_without_hiding_valid_history(tmp_path, monkeypatch):
    path = tmp_path / "history.jsonl"
    monkeypatch.setattr(history, "HIST", str(path))
    _write(path, [
        json.dumps({"action": "gym", "ts": "2026-07-01T12:00:00"}) + "\n",
        "{partial write\n",
        json.dumps({"action": "meal", "ts": "2026-07-02T12:00:00"}) + "\n",
    ])

    assert [e["action"] for e in history.events()] == ["gym", "meal"]


def test_remove_at_uses_valid_event_index_and_preserves_corrupt_line(tmp_path, monkeypatch):
    path = tmp_path / "history.jsonl"
    monkeypatch.setattr(history, "HIST", str(path))
    _write(path, [
        json.dumps({"action": "gym", "ts": "2026-07-01T12:00:00"}) + "\n",
        "{partial write\n",
        json.dumps({"action": "meal", "ts": "2026-07-02T12:00:00"}) + "\n",
    ])

    history.remove_at(1)

    assert [e["action"] for e in history.events()] == ["gym"]
    assert "{partial write" in path.read_text(encoding="utf-8")


def test_remove_at_out_of_range_does_not_rewrite_file(tmp_path, monkeypatch):
    path = tmp_path / "history.jsonl"
    monkeypatch.setattr(history, "HIST", str(path))
    original = json.dumps({"action": "gym", "ts": "2026-07-01T12:00:00"}) + "\n"
    path.write_text(original, encoding="utf-8")

    history.remove_at(10)

    assert path.read_text(encoding="utf-8") == original
