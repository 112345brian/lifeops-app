import os

import pytest
from fastapi.testclient import TestClient

from lifeops import config, history, web, runner, weather


def test_settings_writer_uses_same_env_file_as_runtime():
    assert web.ENV == str(config.ENV_FILE)


def test_every_runner_domain_is_available_in_panel():
    assert set(web.ALL_DOMAINS) == set(runner.DOMAINS)


def test_set_env_updates_runtime_env_file_atomically(tmp_path, monkeypatch):
    env = tmp_path / ".env"
    env.write_text("PARTNER_NAME=Old\nKEEP=value\n", encoding="utf-8")
    monkeypatch.setattr(web, "ENV", str(env))

    web._set_env("PARTNER_NAME", "New")

    assert env.read_text(encoding="utf-8") == "PARTNER_NAME=New\nKEEP=value\n"


def test_config_groups_covers_every_editable_key_with_a_label():
    grouped_keys = {item["key"] for group in web._config_groups() for item in group["fields"]}

    assert grouped_keys == set(web.EDITABLE)
    for key in web.EDITABLE:
        group, label, _help = web.CONFIG_META[key]
        assert group and label  # every key has a real (non-key-name) label


def test_config_bulk_save_writes_only_changed_editable_keys(tmp_path, monkeypatch):
    env = tmp_path / ".env"
    env.write_text("PARTNER_NAME=Old\nFRIENDS_TASK=Friends\n", encoding="utf-8")
    monkeypatch.setattr(web, "ENV", str(env))
    monkeypatch.setattr(config, "WEB_TOKEN", "")
    client = TestClient(web.app)

    response = client.post("/config", data={
        "PARTNER_NAME": "New",
        "FRIENDS_TASK": "Friends",  # unchanged -- shouldn't cause a rewrite
        "NOT_EDITABLE": "should be ignored",
    }, follow_redirects=False)

    assert response.status_code == 303
    assert response.headers["location"] == "/settings?msg=Saved.#config"
    written = env.read_text(encoding="utf-8")
    assert "PARTNER_NAME=New" in written
    assert "FRIENDS_TASK=Friends" in written
    assert "NOT_EDITABLE" not in written


def test_config_bulk_save_reports_no_changes_when_nothing_differs(tmp_path, monkeypatch):
    env = tmp_path / ".env"
    env.write_text("PARTNER_NAME=Same\n", encoding="utf-8")
    monkeypatch.setattr(web, "ENV", str(env))
    monkeypatch.setattr(config, "WEB_TOKEN", "")
    client = TestClient(web.app)

    response = client.post("/config", data={"PARTNER_NAME": "Same"}, follow_redirects=False)

    assert response.headers["location"] == "/settings?msg=No%20changes.#config"


def test_token_query_redirects_to_clean_url_and_sets_cookie(monkeypatch):
    monkeypatch.setattr(config, "WEB_TOKEN", "secret")
    client = TestClient(web.app)

    response = client.get("/?token=secret", headers={"accept": "text/html"}, follow_redirects=False)

    assert response.status_code == 303
    assert response.headers["location"] == "http://testserver/"
    assert "lifeops_auth=secret" in response.headers["set-cookie"]
    # Lax, not Strict: an ntfy notification's Click link is a top-level GET
    # navigation from outside the app, and Strict cookies can be withheld on
    # exactly that kind of navigation on some OS/browser notification
    # plumbing -- Lax still blocks the cross-site POST/embed cases Strict
    # exists to guard against.
    assert "SameSite=lax" in response.headers["set-cookie"]


def test_api_token_query_returns_json_without_browser_redirect(monkeypatch):
    monkeypatch.setattr(config, "WEB_TOKEN", "secret")

    response = TestClient(web.app).get("/api/status?token=secret", follow_redirects=False)

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("application/json")


@pytest.mark.parametrize("path", ["/", "/gym", "/schedule", "/history", "/settings"])
def test_non_recurring_pages_render_without_flowsavvy(path, monkeypatch):
    monkeypatch.setattr(config, "WEB_TOKEN", "")
    monkeypatch.setattr(web, "FlowSavvy", lambda: (_ for _ in ()).throw(AssertionError("network client")))

    response = TestClient(web.app).get(path)

    assert response.status_code == 200
    assert "LifeOps" in response.text


def test_recurring_page_shows_flowsavvy_outage_instead_of_500(monkeypatch):
    class BrokenFlowSavvy:
        def list_items(self, **kwargs):
            raise RuntimeError("offline")

    monkeypatch.setattr(config, "WEB_TOKEN", "")
    monkeypatch.setattr(web, "FlowSavvy", BrokenFlowSavvy)

    response = TestClient(web.app).get("/recurring")

    assert response.status_code == 200
    assert "FlowSavvy is unavailable" in response.text


def test_home_panel_uses_command_surface_classes(monkeypatch):
    monkeypatch.setattr(config, "WEB_TOKEN", "")
    monkeypatch.setattr(web, "FlowSavvy", lambda: (_ for _ in ()).throw(AssertionError("network client")))

    response = TestClient(web.app).get("/")

    assert response.status_code == 200
    assert 'class="card ops-hero' in response.text
    assert 'class="ops-headline"' in response.text


def test_home_briefing_renders_kpi_grid(monkeypatch):
    monkeypatch.setattr(config, "WEB_TOKEN", "")
    monkeypatch.setattr(web, "_today_briefing", lambda: {
        "text": "Briefing text.",
        "facts": {
            "due_today": ["Paper"],
            "coursework_hours_next_7d": 4.5,
            "gym_last_7d": 2,
            "gym_target": 4,
            "discretionary_dollars": 80,
        },
    })
    monkeypatch.setattr(web, "FlowSavvy", lambda: (_ for _ in ()).throw(AssertionError("network client")))

    response = TestClient(web.app).get("/")

    assert response.status_code == 200
    assert 'class="kpi-grid"' in response.text
    assert "Coursework" in response.text
    assert "Discretionary" in response.text


# ── Actions API ──────────────────────────────────────────────────────────

@pytest.fixture
def sandbox(tmp_path, monkeypatch):
    """Isolates every file-backed side effect the Actions API endpoints
    touch (history.jsonl, gym/schedule block files) from the real logs/
    directory, and stubs out _run_domain so tests never spawn a real
    subprocess. Returns the recorded _run_domain calls."""
    monkeypatch.setattr(config, "WEB_TOKEN", "")
    monkeypatch.setattr(history, "HIST", os.path.join(str(tmp_path), "logs", "history.jsonl"))
    os.makedirs(os.path.join(str(tmp_path), "logs"), exist_ok=True)
    monkeypatch.setattr(web, "GYM_STATE_FILE", os.path.join(str(tmp_path), "logs", "gym_state.json"))
    monkeypatch.setattr(web, "GYM_BLOCKS_FILE", os.path.join(str(tmp_path), "logs", "gym_blocks.json"))
    monkeypatch.setattr(web, "SCHED_BLOCKS_FILE", os.path.join(str(tmp_path), "logs", "schedule_blocks.json"))
    monkeypatch.setattr(config, "BLOCK_CAL", "")  # skip the FlowSavvy busy-event branch
    ran = []
    monkeypatch.setattr(web, "_run_domain", lambda name: ran.append(name))
    monkeypatch.setattr(web, "_current_attention", lambda *a, **k: {"state": "ok"})
    return ran


def test_api_gym_log_appends_history_and_replans(sandbox):
    response = TestClient(web.app).post("/api/gym/log")

    assert response.status_code == 200
    assert response.json()["ok"] is True
    assert [e["action"] for e in history.events()] == ["gym"]
    assert sandbox == ["gym"]


def test_api_gym_skip_appends_history_and_replans(sandbox):
    response = TestClient(web.app).post("/api/gym/skip")

    assert response.status_code == 200
    assert [e["action"] for e in history.events()] == ["gym_skip"]
    assert sandbox == ["gym"]


def test_api_schedule_block_day_rejects_bad_date(sandbox):
    response = TestClient(web.app).post("/api/schedule/block-day", json={"date": "not-a-date"})

    assert response.status_code == 400
    assert sandbox == []


def test_api_schedule_block_day_blocks_and_replans(sandbox):
    response = TestClient(web.app).post("/api/schedule/block-day", json={"date": "2026-08-01"})

    assert response.status_code == 200
    assert response.json()["ok"] is True
    assert "2026-08-01" in web._gym_blocks()
    assert sandbox == ["gym"]


def test_api_domain_run_rejects_unknown_domain(sandbox):
    response = TestClient(web.app).post("/api/domains/not-a-real-domain/run")

    assert response.status_code == 404
    assert sandbox == []


def test_api_domain_run_triggers_named_domain(sandbox):
    response = TestClient(web.app).post("/api/domains/gym/run")

    assert response.status_code == 200
    assert response.json() == {"ok": True, "domain": "gym"}
    assert sandbox == ["gym"]


def test_api_register_fcm_token_persists_valid_token(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "WEB_TOKEN", "")
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    (tmp_path / "logs").mkdir(exist_ok=True)
    token = "d" * 20 + ":APA91b" + "x" * 100

    response = TestClient(web.app).post("/api/register-fcm-token", json={"fcm_token": token})

    assert response.status_code == 200
    assert response.json() == {"ok": True}
    assert web.fcm._device_token() == token


def test_api_register_fcm_token_rejects_malformed_token(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "WEB_TOKEN", "")
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    (tmp_path / "logs").mkdir(exist_ok=True)

    response = TestClient(web.app).post("/api/register-fcm-token", json={"fcm_token": "short"})

    assert response.status_code == 400
    assert web.fcm._device_token() == ""


def test_api_location_persists_valid_coordinates(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "WEB_TOKEN", "")
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    (tmp_path / "logs").mkdir(exist_ok=True)

    response = TestClient(web.app).post("/api/location", json={"lat": 39.29, "lon": -76.61})

    assert response.status_code == 200
    assert response.json() == {"ok": True}
    assert web.location.get_location() == ("39.29", "-76.61")


def test_api_location_rejects_malformed_coordinates(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "WEB_TOKEN", "")
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    (tmp_path / "logs").mkdir(exist_ok=True)

    response = TestClient(web.app).post("/api/location", json={"lat": 200, "lon": -76.61})

    assert response.status_code == 400
    assert web.location.get_location() is None


def test_current_attention_treats_missing_last_run_as_no_system_data(monkeypatch):
    """A fresh install (or any moment logs/last_run.json is missing/
    unreadable) makes _last_run() return None. _current_attention must pass
    that through as None, not coerce it to {} -- attention.compute treats
    system={} as 'system data present but everything empty' (age_mins
    missing -> stale), which previously produced a false 'risk: LifeOps
    data is stale' reading on a machine that has simply never run yet."""
    monkeypatch.setattr(web, "_last_run", lambda: None)
    monkeypatch.setattr(web, "_today_briefing", lambda: None)

    result = web._current_attention()

    assert result["state"] == "ok"
    assert result["reasons"] == []


def test_api_task_complete_returns_fresh_tasks_and_events(monkeypatch):
    monkeypatch.setattr(config, "WEB_TOKEN", "")
    monkeypatch.setattr(web, "_current_attention", lambda *a, **k: {"state": "ok"})

    class FakeFlowSavvy:
        def __init__(self):
            self.completed = []
            self.recalculated = False

        def complete_task(self, task_id):
            self.completed.append(task_id)

        def recalculate(self):
            self.recalculated = True

        def get_schedule(self, start, end):
            return {"scheduleItems": [
                {"itemType": "task", "itemId": "t2", "title": "Next up",
                 "startTime": "2099-01-01T09:00:00", "completed": False},
            ]}

    fake = FakeFlowSavvy()
    monkeypatch.setattr(web, "FlowSavvy", lambda: fake)

    response = TestClient(web.app).post("/api/tasks/t1/complete")

    assert response.status_code == 200
    body = response.json()
    assert body["completed_id"] == "t1"
    assert [t["id"] for t in body["tasks"]] == ["t2"]
    assert body["events"] == []
    assert fake.completed == ["t1"]
    assert fake.recalculated is True


def test_api_next_tasks_includes_weather_refreshed_on_the_same_pull(monkeypatch):
    """weather.current() is called from the same ~15-min-polled endpoint as
    gym_ring, not just once/day inside run_briefing -- otherwise the widget
    keeps showing whatever NOAA said that morning all day (confirmed
    2026-07-15: a stale 64°F reported mid-afternoon)."""
    monkeypatch.setattr(config, "WEB_TOKEN", "")
    monkeypatch.setattr(web, "_current_attention", lambda *a, **k: {"state": "ok"})
    monkeypatch.setattr(weather, "current", lambda now: {
        "temp_f": 71, "high_f": 80, "low_f": 60, "condition": "Cloudy"})

    class FakeFlowSavvy:
        def get_schedule(self, start, end):
            return {"scheduleItems": []}

    monkeypatch.setattr(web, "FlowSavvy", lambda: FakeFlowSavvy())

    response = TestClient(web.app).get("/api/next-tasks")

    assert response.status_code == 200
    assert response.json()["weather"] == {
        "temp_f": 71, "high_f": 80, "low_f": 60, "condition": "Cloudy"}
