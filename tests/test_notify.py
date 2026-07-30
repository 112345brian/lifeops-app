import pytest

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


def test_push_briefing_never_falls_back_to_ntfy(monkeypatch):
    """Unlike push_next_tasks, push_briefing has no ntfy fallback --
    runner.py's run_briefing already sends the full briefing text over ntfy
    unconditionally before this runs, so a second "FCM unavailable" alert
    on top would just be redundant noise, not a genuine fallback."""
    monkeypatch.setattr(notify.fcm, "send_briefing", lambda date, text, facts, version: False)
    alert_calls = []
    monkeypatch.setattr(notify, "alert", lambda *a, **k: alert_calls.append((a, k)))

    result = notify.push_briefing("2026-07-12", "text", {"gym": 2}, "abc123")

    assert result is False
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
    persistently no-oping FCM must not spam an ntfy alert every tick. This
    exercises the real dedup path (notify.alert's dedup_key), so it mocks
    the transport (ntfy.alert) rather than notify.alert itself -- mocking
    notify.alert away would bypass the dedup logic entirely."""
    monkeypatch.setattr(notify.fcm, "send_next_tasks",
                        lambda tasks, events, gym_ring, version: False)
    ntfy_calls = []
    monkeypatch.setattr(notify.ntfy, "alert", lambda *a, **k: ntfy_calls.append((a, k)))

    notify.push_next_tasks([{"id": "1"}], [], {"fill": 0.5, "color": "yellow"}, "v1")
    notify.push_next_tasks([{"id": "1"}], [], {"fill": 0.5, "color": "yellow"}, "v2")
    notify.push_next_tasks([{"id": "1"}], [], {"fill": 0.5, "color": "yellow"}, "v3")

    assert len(ntfy_calls) == 1


def test_push_next_tasks_fallback_swallows_alert_failure(monkeypatch):
    """A failure sending the fallback itself (e.g. ntfy.sh briefly down)
    must not raise out of push_next_tasks -- side-channel only, its return
    value contract is untouched. (notify.alert's own dedup_key logic
    separately guarantees it isn't marked sent on a failed send -- see
    test_alert_dedup_key_only_marks_sent_after_a_successful_send -- this
    test is specifically about push_next_tasks's own try/except.)"""
    monkeypatch.setattr(notify.fcm, "send_next_tasks",
                        lambda tasks, events, gym_ring, version: False)
    calls = []
    def _boom(*a, **k):
        calls.append((a, k))
        raise RuntimeError("ntfy.sh unreachable")
    monkeypatch.setattr(notify, "alert", _boom)

    result = notify.push_next_tasks([{"id": "1"}], [], {"fill": 0.5, "color": "yellow"}, "v1")

    assert result is False
    assert len(calls) == 1


def test_alert_dedup_key_suppresses_repeat_calls_same_day(monkeypatch):
    ntfy_calls = []
    monkeypatch.setattr(notify.ntfy, "alert", lambda *a, **k: ntfy_calls.append((a, k)))

    notify.alert("gym behind", dedup_key="gym:normal")
    notify.alert("gym behind", dedup_key="gym:normal")
    notify.alert("gym behind", dedup_key="gym:normal")

    assert len(ntfy_calls) == 1


def test_alert_dedup_key_does_not_cross_suppress_different_keys(monkeypatch):
    """gym/homework/social/etc. each dedup on their own key -- a homework
    alert firing today must not suppress a same-day gym alert."""
    ntfy_calls = []
    monkeypatch.setattr(notify.ntfy, "alert", lambda *a, **k: ntfy_calls.append((a, k)))

    notify.alert("gym behind", dedup_key="gym:normal")
    notify.alert("hw due", dedup_key="hw:some-task")

    assert len(ntfy_calls) == 2


def test_alert_without_dedup_key_always_sends(monkeypatch):
    ntfy_calls = []
    monkeypatch.setattr(notify.ntfy, "alert", lambda *a, **k: ntfy_calls.append((a, k)))

    notify.alert("one-off")
    notify.alert("one-off")

    assert len(ntfy_calls) == 2


def test_alert_dedup_key_only_marks_sent_after_a_successful_send(monkeypatch):
    """A failed send (ntfy.alert raising) must not mark the dedup key --
    otherwise a transient ntfy outage would silently suppress the alert for
    the rest of the day even though it never actually landed."""
    def _boom(*a, **k):
        raise RuntimeError("ntfy.sh unreachable")
    monkeypatch.setattr(notify.ntfy, "alert", _boom)

    with pytest.raises(RuntimeError):
        notify.alert("gym behind", dedup_key="gym:normal")

    ntfy_calls = []
    monkeypatch.setattr(notify.ntfy, "alert", lambda *a, **k: ntfy_calls.append((a, k)))
    notify.alert("gym behind", dedup_key="gym:normal")

    assert len(ntfy_calls) == 1
