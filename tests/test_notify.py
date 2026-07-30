from lifeops import notify


def test_alert_routes_through_ntfy_with_panel_click(monkeypatch):
    calls = []
    monkeypatch.setattr(notify.ntfy, "panel_url", lambda anchor: f"https://panel/{anchor}")
    monkeypatch.setattr(notify.ntfy, "alert", lambda *a, **k: calls.append((a, k)))

    notify.alert("hello", priority="high", tags=["warning"], actions=[("Open", "body")],
                 click_anchor="settings#health")

    assert calls == [(("hello",), {
        "priority": "high",
        "tags": ["warning"],
        "actions": [("Open", "body")],
        "click": "https://panel/settings#health",
    })]


def test_push_briefing_routes_to_fcm(monkeypatch):
    calls = []
    monkeypatch.setattr(notify.fcm, "send_briefing",
                        lambda date, text, facts, version: calls.append((date, text, facts, version)) or True)

    result = notify.push_briefing("2026-07-12", "text", {"gym": 2}, "abc123")

    assert calls == [("2026-07-12", "text", {"gym": 2}, "abc123")]
    assert result is True


def test_push_next_tasks_routes_to_fcm(monkeypatch):
    calls = []
    monkeypatch.setattr(notify.fcm, "send_next_tasks",
                        lambda tasks, events, gym_ring, version: calls.append((tasks, events, gym_ring, version)) or True)

    result = notify.push_next_tasks([{"id": "1"}], [{"title": "BBQ"}], {"fill": 0.5, "color": "yellow"}, "abc123")

    assert calls == [([{"id": "1"}], [{"title": "BBQ"}], {"fill": 0.5, "color": "yellow"}, "abc123")]
    assert result is True


def test_alert_tags_msg_type(monkeypatch):
    calls = []
    monkeypatch.setattr(notify.ntfy, "panel_url", lambda anchor: f"https://panel/{anchor}")
    monkeypatch.setattr(notify.ntfy, "alert", lambda *a, **k: calls.append((a, k)))

    notify.alert("system is down", tags=["warning"], msg_type="system_health")

    assert calls[0][1]["tags"] == ["warning", "system_health"]


def test_alert_default_msg_type_adds_no_tag(monkeypatch):
    calls = []
    monkeypatch.setattr(notify.ntfy, "panel_url", lambda anchor: f"https://panel/{anchor}")
    monkeypatch.setattr(notify.ntfy, "alert", lambda *a, **k: calls.append((a, k)))

    notify.alert("plain alert")

    assert calls[0][1]["tags"] is None


def test_push_briefing_falls_back_to_ntfy_when_fcm_noops(monkeypatch):
    monkeypatch.setattr(notify.fcm, "send_briefing", lambda date, text, facts, version: False)
    alert_calls = []
    monkeypatch.setattr(notify, "alert", lambda *a, **k: alert_calls.append((a, k)))

    result = notify.push_briefing("2026-07-12", "text", {"gym": 2}, "abc123")

    assert result is False
    assert len(alert_calls) == 1
    assert alert_calls[0][1]["msg_type"] == "system_health"


def test_push_briefing_no_fallback_when_fcm_sends(monkeypatch):
    monkeypatch.setattr(notify.fcm, "send_briefing", lambda date, text, facts, version: True)
    alert_calls = []
    monkeypatch.setattr(notify, "alert", lambda *a, **k: alert_calls.append((a, k)))

    result = notify.push_briefing("2026-07-12", "text", {"gym": 2}, "abc123")

    assert result is True
    assert alert_calls == []


def test_push_next_tasks_falls_back_to_ntfy_when_fcm_noops(monkeypatch):
    monkeypatch.setattr(notify.fcm, "send_next_tasks",
                        lambda tasks, events, gym_ring, version: False)
    alert_calls = []
    monkeypatch.setattr(notify, "alert", lambda *a, **k: alert_calls.append((a, k)))

    result = notify.push_next_tasks([{"id": "1"}], [], {"fill": 0.5, "color": "yellow"}, "abc123")

    assert result is False
    assert len(alert_calls) == 1
    assert alert_calls[0][1]["msg_type"] == "system_health"


def test_push_next_tasks_no_fallback_when_fcm_sends(monkeypatch):
    monkeypatch.setattr(notify.fcm, "send_next_tasks",
                        lambda tasks, events, gym_ring, version: True)
    alert_calls = []
    monkeypatch.setattr(notify, "alert", lambda *a, **k: alert_calls.append((a, k)))

    result = notify.push_next_tasks([{"id": "1"}], [], {"fill": 0.5, "color": "yellow"}, "abc123")

    assert result is True
    assert alert_calls == []


def test_push_next_tasks_fallback_dedups_within_the_same_day(monkeypatch):
    """push_next_tasks runs on the ~10-min tick cadence (runner.py) -- a
    persistently no-oping FCM must not spam an ntfy alert every tick."""
    monkeypatch.setattr(notify.fcm, "send_next_tasks",
                        lambda tasks, events, gym_ring, version: False)
    alert_calls = []
    monkeypatch.setattr(notify, "alert", lambda *a, **k: alert_calls.append((a, k)))

    notify.push_next_tasks([{"id": "1"}], [], {"fill": 0.5, "color": "yellow"}, "v1")
    notify.push_next_tasks([{"id": "1"}], [], {"fill": 0.5, "color": "yellow"}, "v2")
    notify.push_next_tasks([{"id": "1"}], [], {"fill": 0.5, "color": "yellow"}, "v3")

    assert len(alert_calls) == 1


def test_push_briefing_fallback_dedups_within_the_same_day(monkeypatch):
    monkeypatch.setattr(notify.fcm, "send_briefing",
                        lambda date, text, facts, version: False)
    alert_calls = []
    monkeypatch.setattr(notify, "alert", lambda *a, **k: alert_calls.append((a, k)))

    notify.push_briefing("2026-07-12", "text", {"gym": 2}, "v1")
    notify.push_briefing("2026-07-12", "text", {"gym": 2}, "v2")

    assert len(alert_calls) == 1


def test_push_briefing_and_push_next_tasks_fallback_dedup_independently(monkeypatch):
    """The two push types must not share a dedup key -- a broken briefing
    push shouldn't suppress the (separately useful) next-tasks fallback."""
    monkeypatch.setattr(notify.fcm, "send_briefing", lambda date, text, facts, version: False)
    monkeypatch.setattr(notify.fcm, "send_next_tasks",
                        lambda tasks, events, gym_ring, version: False)
    alert_calls = []
    monkeypatch.setattr(notify, "alert", lambda *a, **k: alert_calls.append((a, k)))

    notify.push_briefing("2026-07-12", "text", {"gym": 2}, "v1")
    notify.push_next_tasks([{"id": "1"}], [], {"fill": 0.5, "color": "yellow"}, "v1")

    assert len(alert_calls) == 2
