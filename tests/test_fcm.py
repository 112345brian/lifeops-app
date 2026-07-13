import json
import os

from lifeops import fcm, history


def test_register_token_rejects_malformed_input(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    assert fcm.register_token(None) is False
    assert fcm.register_token(123) is False
    assert fcm.register_token("too-short") is False
    assert fcm.register_token("x" * 4097) is False
    assert not os.path.exists(os.path.join(str(tmp_path), "logs", "fcm_token.json"))


def test_register_token_persists_and_round_trips(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    token = "d" * 20 + ":APA91b" + "x" * 100

    assert fcm.register_token(token) is True
    assert fcm._device_token() == token

    saved = json.loads((tmp_path / "logs" / "fcm_token.json").read_text(encoding="utf-8"))
    assert saved == {"token": token}


def test_register_token_last_write_wins(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    token_a = "a" * 20
    token_b = "b" * 20

    fcm.register_token(token_a)
    fcm.register_token(token_b)

    assert fcm._device_token() == token_b


def test_send_briefing_noop_without_registered_token(tmp_path, monkeypatch):
    """No token on file -- must not even attempt to import/call
    firebase_admin, so this is safe to run without real Firebase
    credentials configured."""
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    fcm.send_briefing("2026-07-13", "text", {"gym": 2})  # must not raise


def test_send_next_tasks_noop_without_registered_token(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    fcm.send_next_tasks([{"id": "1"}], [{"title": "BBQ"}])  # must not raise


def test_send_briefing_and_next_tasks_use_distinct_message_types(tmp_path, monkeypatch):
    """Both go through the shared _send helper, which is what the Android
    side dispatches on to pick the right persist worker -- confirms they're
    actually distinguishable, not just both saying "payload"."""
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    calls = []
    monkeypatch.setattr(fcm, "_send", lambda msg_type, payload: calls.append((msg_type, payload)))

    fcm.send_briefing("2026-07-13", "text", {"gym": 2})
    fcm.send_next_tasks([{"id": "1"}], [{"title": "BBQ"}])

    assert calls == [
        ("briefing", {"date": "2026-07-13", "text": "text", "facts": {"gym": 2}}),
        ("next_tasks", {"tasks": [{"id": "1"}], "events": [{"title": "BBQ"}]}),
    ]
