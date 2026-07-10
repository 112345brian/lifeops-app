from unittest.mock import Mock, patch

import pytest
import requests

from lifeops import config, ntfy


def test_alert_raises_when_ntfy_rejects_message(monkeypatch):
    monkeypatch.setattr(config, "NTFY_ALERTS_TOPIC", "alerts")
    response = Mock()
    response.raise_for_status.side_effect = requests.HTTPError("503")

    with patch("lifeops.ntfy.requests.post", return_value=response):
        with pytest.raises(requests.HTTPError):
            ntfy.alert("hello")


def test_alert_is_noop_without_topic(monkeypatch):
    monkeypatch.setattr(config, "NTFY_ALERTS_TOPIC", "")
    with patch("lifeops.ntfy.requests.post") as post:
        ntfy.alert("hello")
    post.assert_not_called()
