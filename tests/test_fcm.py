import pytest

from lifeops import config, db, fcm, history


def test_register_token_rejects_malformed_input(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    assert fcm.register_token(None) is False
    assert fcm.register_token(123) is False
    assert fcm.register_token("too-short") is False
    assert fcm.register_token("x" * 4097) is False
    assert db.local_get("fcm_token") is None


def test_register_token_persists_and_round_trips(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    token = "d" * 20 + ":APA91b" + "x" * 100

    assert fcm.register_token(token) is True
    assert fcm._device_token() == token

    saved = db.local_get("fcm_token")
    assert saved["token"] == token
    # registered_at is a real, parseable ISO-8601 timestamp -- this is what
    # /api/health uses to compute token age.
    import datetime
    datetime.datetime.fromisoformat(saved["registered_at"])


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
    credentials configured. Must return False (nothing was sent) so
    runner.py's _push_with_ack can tell "no-op" apart from "sent, awaiting
    ack" instead of marking this unacked forever."""
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    assert fcm.send_briefing("2026-07-13", "text", {"gym": 2}, "v1") is False


def test_send_records_last_send_outcome_on_noop(tmp_path, monkeypatch):
    """Even a no-op (no token registered) should durably record the attempt
    so /api/health can surface "sends have been no-oping" instead of just
    silence."""
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    fcm.send_briefing("2026-07-13", "text", {"gym": 2}, "v1")

    last_send = db.local_get("fcm_last_send")
    assert last_send["type"] == "briefing"
    assert last_send["ok"] is False
    assert last_send["at"]


def test_send_records_last_send_outcome_on_success(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    fcm.register_token("d" * 20)
    fake_cred_file = tmp_path / "service-account.json"
    fake_cred_file.write_text("{}", encoding="utf-8")
    monkeypatch.setattr(config, "FCM_SERVICE_ACCOUNT_FILE", str(fake_cred_file))
    monkeypatch.setattr(fcm, "_firebase_app", lambda: "fake-app")

    messaging = pytest.importorskip("firebase_admin.messaging")
    monkeypatch.setattr(messaging, "send", lambda message, app: None)

    fcm.send_next_tasks([{"id": "1"}], [], {"fill": 0.0, "color": "red"}, "abc123")

    last_send = db.local_get("fcm_last_send")
    assert last_send["type"] == "next_tasks"
    assert last_send["ok"] is True
    assert last_send["at"]


def test_send_next_tasks_noop_without_registered_token(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    assert fcm.send_next_tasks([{"id": "1"}], [{"title": "BBQ"}], {"fill": 0.5, "color": "yellow"}, "v1") is False


def test_send_briefing_and_next_tasks_use_distinct_message_types(tmp_path, monkeypatch):
    """Both go through the shared _send helper, which is what the Android
    side dispatches on to pick the right persist worker -- confirms they're
    actually distinguishable, not just both saying "payload"."""
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    calls = []
    monkeypatch.setattr(fcm, "_send",
                        lambda msg_type, payload, version: calls.append((msg_type, payload, version)))

    fcm.send_briefing("2026-07-13", "text", {"gym": 2}, "v1")
    fcm.send_next_tasks([{"id": "1"}], [{"title": "BBQ"}], {"fill": 0.5, "color": "yellow"}, "v2")

    assert calls == [
        ("briefing", {"date": "2026-07-13", "text": "text", "facts": {"gym": 2}}, "v1"),
        ("next_tasks", {"tasks": [{"id": "1"}], "events": [{"title": "BBQ"}], "gym_ring": {"fill": 0.5, "color": "yellow"}}, "v2"),
    ]


def test_send_briefing_drops_client_unused_facts_and_trims_reasons(tmp_path, monkeypatch):
    """overdue/coursework_at_risk/due_today have zero readers in
    BriefingState.fromJson and attention.reasons only needs domain+severity
    client-side (see AttentionReason's docstring) -- these were pushing real
    briefings over Android's 4KB FCM data-message cap. Confirms the trim
    drops exactly those keys and reduces reasons to domain+severity, while
    leaving every other fact (including ones fromJson DOES read) untouched."""
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    calls = []
    monkeypatch.setattr(fcm, "_send",
                        lambda msg_type, payload, version: calls.append((msg_type, payload, version)))

    facts = {
        "gym_last_7d": 2,
        "overdue": [{"title": "Long overdue reading", "due_in_h": -100, "due": "2026-07-01T00:00:00"}],
        "coursework_at_risk": ["Some paper"],
        "due_today": ["Some task"],
        "attention": {
            "state": "risk", "symbol": "◆", "label": "RISK", "headline": "Do it now.",
            "reasons": [{"domain": "coursework", "severity": "risk",
                         "title": "Overdue: Long overdue reading",
                         "recommended_action": "Do it now.", "due": "2026-07-01T00:00:00"}],
        },
    }

    fcm.send_briefing("2026-07-13", "text", facts, "v1")

    assert calls == [("briefing", {"date": "2026-07-13", "text": "text", "facts": {
        "gym_last_7d": 2,
        "attention": {
            "state": "risk", "symbol": "◆", "label": "RISK", "headline": "Do it now.",
            "reasons": [{"domain": "coursework", "severity": "risk"}],
        },
    }}, "v1")]


def test_send_embeds_version_in_message_data_for_ack_round_trip(tmp_path, monkeypatch):
    """The version travels in the actual FCM message data, not just as a
    Python-side parameter -- this is what the client echoes back as
    ack:<type>:<version>, so it has to actually reach the device."""
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    fcm.register_token("d" * 20)
    fake_cred_file = tmp_path / "service-account.json"
    fake_cred_file.write_text("{}", encoding="utf-8")
    monkeypatch.setattr(config, "FCM_SERVICE_ACCOUNT_FILE", str(fake_cred_file))
    monkeypatch.setattr(fcm, "_firebase_app", lambda: "fake-app")

    messaging = pytest.importorskip("firebase_admin.messaging")
    sent = []
    monkeypatch.setattr(messaging, "send", lambda message, app: sent.append(message))

    result = fcm.send_next_tasks([{"id": "1"}], [], {"fill": 0.0, "color": "red"}, "abc123")

    assert result is True
    assert len(sent) == 1
    assert sent[0].data["type"] == "next_tasks"
    assert sent[0].data["version"] == "abc123"
