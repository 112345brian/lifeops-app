"""FlowSavvy REST client — retry behavior on transient connection failures."""
import pytest
from unittest.mock import patch, Mock
import requests
from lifeops import flowsavvy as flowsavvy_module
from lifeops.flowsavvy import FlowSavvy
from lifeops import config


@pytest.fixture(autouse=True)
def no_sleep():
    # _request_times is a module-level global (the throttle must be shared
    # across every FlowSavvy() instance, since callers construct a fresh one
    # per call all over this codebase) -- reset it per test so accumulated
    # calls across the test session can never approach _RATE_LIMIT_MAX and
    # make _throttle()'s wait loop spin forever against a mocked, non-
    # advancing time.sleep.
    flowsavvy_module._request_times.clear()
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
    assert mock_get.call_count == 6   # 1 initial + 5 retries


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


def test_list_items_follows_pagination_to_return_the_complete_list():
    # regression (2026-09-03): /items is paginated (nextPageToken) but every
    # caller treats the returned "items" as the complete answer for
    # dedup/lookup -- silently returning only page 1 made an already-created
    # task look "missing" against live state, and the resulting "fix"
    # created a real duplicate before this was caught.
    fs = _client()
    page1 = _ok_response({"items": [{"id": "1"}, {"id": "2"}], "nextPageToken": "tok2"})
    page2 = _ok_response({"items": [{"id": "3"}], "nextPageToken": None})
    with patch("requests.get", side_effect=[page1, page2]) as mock_get:
        out = fs.list_items(itemType="task", listId="l1", completed=False)
    assert [i["id"] for i in out["items"]] == ["1", "2", "3"]
    assert mock_get.call_count == 2
    assert mock_get.call_args_list[1].kwargs["params"]["pageToken"] == "tok2"


def test_throttle_paces_a_burst_under_the_rate_limit_ceiling():
    # regression (2026-09-03): a live probe against the real account found
    # FlowSavvy rate-limits around 30 requests/60s with no Retry-After header
    # and a ~45-60s cooldown once tripped -- far worse than this client's
    # old backoff could survive. _throttle() must cap request RATE (not just
    # retry after failing), so simulate a burst well past _RATE_LIMIT_MAX and
    # confirm it never lets more than that many through within one window.
    fs = _client()
    ok = _ok_response({"items": []})
    with patch("requests.get", return_value=ok) as mock_get, \
         patch("lifeops.flowsavvy.time.sleep") as mock_sleep, \
         patch("lifeops.flowsavvy.time.monotonic") as mock_mono:
        # advance the fake clock by 0.01s per sleep call so _throttle's
        # window-pruning eventually admits new requests, instead of ever
        # spinning on a clock that (thanks to the mocked sleep) never moves
        t = [0.0]
        def fake_sleep(_):
            t[0] += 0.01
        mock_sleep.side_effect = fake_sleep
        mock_mono.side_effect = lambda: t[0]
        for _ in range(flowsavvy_module._RATE_LIMIT_MAX + 10):
            fs.list_items(itemType="task")
    assert mock_get.call_count == flowsavvy_module._RATE_LIMIT_MAX + 10
    assert mock_sleep.called, "a burst past the ceiling must trigger throttling waits"


def test_list_items_single_page_makes_one_call():
    fs = _client()
    page1 = _ok_response({"items": [{"id": "1"}]})   # no nextPageToken key at all
    with patch("requests.get", return_value=page1) as mock_get:
        out = fs.list_items(itemType="task", listId="l1")
    assert [i["id"] for i in out["items"]] == ["1"]
    assert mock_get.call_count == 1
