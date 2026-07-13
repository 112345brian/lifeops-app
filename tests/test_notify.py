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
                        lambda date, text, facts: calls.append((date, text, facts)))

    notify.push_briefing("2026-07-12", "text", {"gym": 2})

    assert calls == [("2026-07-12", "text", {"gym": 2})]


def test_push_next_tasks_routes_to_fcm(monkeypatch):
    calls = []
    monkeypatch.setattr(notify.fcm, "send_next_tasks",
                        lambda tasks, events: calls.append((tasks, events)))

    notify.push_next_tasks([{"id": "1"}], [{"title": "BBQ"}])

    assert calls == [([{"id": "1"}], [{"title": "BBQ"}])]
