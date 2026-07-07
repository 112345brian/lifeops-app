"""FlowSavvy REST client — retry behavior on transient connection failures."""
import pytest
from unittest.mock import patch, Mock
import requests
from lifeops.flowsavvy import FlowSavvy
from lifeops import config


@pytest.fixture(autouse=True)
def no_sleep():
    with patch("lifeops.flowsavvy.time.sleep"):
        yield


def _client():
    config.FLOWSAVVY_TOKEN = "x"
    config.FLOWSAVVY_BASE_URL = "https://my.flowsavvy.app/api"
    return FlowSavvy()


def _ok_response(payload=None):
    r = Mock()
    r.raise_for_status = Mock()
    r.json = Mock(return_value=payload or {})
    r.content = b"{}"
    return r


def test_get_retries_transient_connection_error_then_succeeds():
    # regression: a one-off SSL/TCP handshake blip (ConnectionError, never
    # reached the server) should be retried instead of failing the whole
    # domain run and paging a false-alarm health alert.
    fs = _client()
    with patch("requests.get", side_effect=[
        requests.exceptions.ConnectionError("SSLEOFError"),
        _ok_response({"items": []}),
    ]) as mock_get:
        out = fs.list_items(itemType="task")
    assert out == {"items": []}
    assert mock_get.call_count == 2


def test_get_gives_up_after_exhausting_retries():
    fs = _client()
    with patch("requests.get", side_effect=requests.exceptions.ConnectionError("down")) as mock_get:
        try:
            fs.list_items(itemType="task")
            assert False, "expected ConnectionError to propagate"
        except requests.exceptions.ConnectionError:
            pass
    assert mock_get.call_count == 3   # 1 initial + 2 retries


def test_post_does_not_retry_on_http_error_response():
    # a real response (even an error one) means the server saw the request --
    # retrying a POST here risks creating a duplicate task server-side.
    fs = _client()
    r = Mock()
    r.raise_for_status = Mock(side_effect=requests.exceptions.HTTPError("500"))
    with patch("requests.post", return_value=r) as mock_post:
        try:
            fs.create_task(title="X")
            assert False, "expected HTTPError to propagate"
        except requests.exceptions.HTTPError:
            pass
    assert mock_post.call_count == 1
