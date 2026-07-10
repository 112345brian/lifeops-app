import pytest
from fastapi.testclient import TestClient

from lifeops import config, web, runner


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


def test_token_query_redirects_to_clean_url_and_sets_cookie(monkeypatch):
    monkeypatch.setattr(config, "WEB_TOKEN", "secret")
    client = TestClient(web.app)

    response = client.get("/?token=secret", follow_redirects=False)

    assert response.status_code == 303
    assert response.headers["location"] == "http://testserver/"
    assert "lifeops_auth=secret" in response.headers["set-cookie"]
    # Lax, not Strict: an ntfy notification's Click link is a top-level GET
    # navigation from outside the app, and Strict cookies can be withheld on
    # exactly that kind of navigation on some OS/browser notification
    # plumbing -- Lax still blocks the cross-site POST/embed cases Strict
    # exists to guard against.
    assert "SameSite=lax" in response.headers["set-cookie"]


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
