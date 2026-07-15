import datetime

from lifeops import config, history, location, weather

NOW = datetime.datetime(2026, 7, 13, 9, 0)

POINTS_RESPONSE = {
    "properties": {
        "forecastHourly": "https://api.weather.gov/gridpoints/OFFICE/1,2/forecast/hourly",
        "forecast": "https://api.weather.gov/gridpoints/OFFICE/1,2/forecast",
    }
}

HOURLY_RESPONSE = {
    "properties": {
        "periods": [
            {"temperature": 73, "temperatureUnit": "F", "shortForecast": "Cloudy"},
        ]
    }
}

DAILY_RESPONSE = {
    "properties": {
        "periods": [
            {"isDaytime": True, "startTime": "2026-07-13T06:00:00-04:00",
             "temperature": 85, "temperatureUnit": "F", "shortForecast": "Sunny"},
            {"isDaytime": False, "startTime": "2026-07-13T18:00:00-04:00",
             "temperature": 67, "temperatureUnit": "F", "shortForecast": "Clear"},
        ]
    }
}


def _configure(monkeypatch, tmp_path):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    monkeypatch.setattr(config, "WEATHER_LAT", "39.29")
    monkeypatch.setattr(config, "WEATHER_LON", "-76.61")


def _fake_get(responses):
    def _get(url):
        return responses[url]
    return _get


def test_current_returns_none_when_unconfigured(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    monkeypatch.setattr(config, "WEATHER_LAT", "")
    monkeypatch.setattr(config, "WEATHER_LON", "")

    assert weather.current(NOW) is None


def test_current_returns_temp_high_low_condition(tmp_path, monkeypatch):
    _configure(monkeypatch, tmp_path)
    responses = {
        "https://api.weather.gov/points/39.29,-76.61": POINTS_RESPONSE,
        "https://api.weather.gov/gridpoints/OFFICE/1,2/forecast/hourly": HOURLY_RESPONSE,
        "https://api.weather.gov/gridpoints/OFFICE/1,2/forecast": DAILY_RESPONSE,
    }
    monkeypatch.setattr(weather, "_get", _fake_get(responses))

    out = weather.current(NOW)

    assert out == {"temp_f": 73, "high_f": 85, "low_f": 67, "condition": "Cloudy"}


def test_current_converts_celsius_to_fahrenheit(tmp_path, monkeypatch):
    _configure(monkeypatch, tmp_path)
    hourly_c = {"properties": {"periods": [
        {"temperature": 23, "temperatureUnit": "C", "shortForecast": "Cloudy"},
    ]}}
    daily_c = {"properties": {"periods": [
        {"isDaytime": True, "startTime": "2026-07-13T06:00:00-04:00",
         "temperature": 29, "temperatureUnit": "C", "shortForecast": "Sunny"},
        {"isDaytime": False, "startTime": "2026-07-13T18:00:00-04:00",
         "temperature": 19, "temperatureUnit": "C", "shortForecast": "Clear"},
    ]}}
    responses = {
        "https://api.weather.gov/points/39.29,-76.61": POINTS_RESPONSE,
        "https://api.weather.gov/gridpoints/OFFICE/1,2/forecast/hourly": hourly_c,
        "https://api.weather.gov/gridpoints/OFFICE/1,2/forecast": daily_c,
    }
    monkeypatch.setattr(weather, "_get", _fake_get(responses))

    out = weather.current(NOW)

    assert out == {"temp_f": 73, "high_f": 84, "low_f": 66, "condition": "Cloudy"}


def test_current_returns_none_on_api_error(tmp_path, monkeypatch):
    _configure(monkeypatch, tmp_path)

    def _raise(url):
        raise ConnectionError("network down")
    monkeypatch.setattr(weather, "_get", _raise)

    assert weather.current(NOW) is None


def test_forecast_urls_cached_to_disk_after_first_lookup(tmp_path, monkeypatch):
    _configure(monkeypatch, tmp_path)
    calls = []

    def _get(url):
        calls.append(url)
        if url == "https://api.weather.gov/points/39.29,-76.61":
            return POINTS_RESPONSE
        if url == "https://api.weather.gov/gridpoints/OFFICE/1,2/forecast/hourly":
            return HOURLY_RESPONSE
        return DAILY_RESPONSE
    monkeypatch.setattr(weather, "_get", _get)

    weather.current(NOW)
    weather.current(NOW)

    # /points is only hit once across both calls -- the second run reuses
    # the cached forecast URLs instead of re-resolving the grid location.
    assert calls.count("https://api.weather.gov/points/39.29,-76.61") == 1


def test_forecast_urls_cache_invalidated_when_location_changes(tmp_path, monkeypatch):
    _configure(monkeypatch, tmp_path)
    responses = {
        "https://api.weather.gov/points/39.29,-76.61": POINTS_RESPONSE,
        "https://api.weather.gov/gridpoints/OFFICE/1,2/forecast/hourly": HOURLY_RESPONSE,
        "https://api.weather.gov/gridpoints/OFFICE/1,2/forecast": DAILY_RESPONSE,
    }
    calls = []

    def _get(url):
        calls.append(url)
        return responses[url]
    monkeypatch.setattr(weather, "_get", _get)
    weather.current(NOW)

    monkeypatch.setattr(config, "WEATHER_LAT", "40.71")
    monkeypatch.setattr(config, "WEATHER_LON", "-74.01")
    responses["https://api.weather.gov/points/40.71,-74.01"] = POINTS_RESPONSE
    weather.current(NOW)

    assert calls.count("https://api.weather.gov/points/40.71,-74.01") == 1


def test_phone_location_takes_priority_over_static_config(tmp_path, monkeypatch):
    """A fresh phone-reported fix must override the static WEATHER_LAT/LON,
    not just supplement it -- see weather._resolve_location."""
    _configure(monkeypatch, tmp_path)
    location.set_location(40.71, -74.01)
    phone_points = {"properties": {
        "forecastHourly": "https://api.weather.gov/gridpoints/NYC/1,2/forecast/hourly",
        "forecast": "https://api.weather.gov/gridpoints/NYC/1,2/forecast",
    }}
    responses = {
        "https://api.weather.gov/points/40.71,-74.01": phone_points,
        "https://api.weather.gov/gridpoints/NYC/1,2/forecast/hourly": HOURLY_RESPONSE,
        "https://api.weather.gov/gridpoints/NYC/1,2/forecast": DAILY_RESPONSE,
    }
    monkeypatch.setattr(weather, "_get", _fake_get(responses))

    out = weather.current(NOW)

    assert out == {"temp_f": 73, "high_f": 85, "low_f": 67, "condition": "Cloudy"}


def test_falls_back_to_static_config_when_no_phone_location_reported(tmp_path, monkeypatch):
    _configure(monkeypatch, tmp_path)
    responses = {
        "https://api.weather.gov/points/39.29,-76.61": POINTS_RESPONSE,
        "https://api.weather.gov/gridpoints/OFFICE/1,2/forecast/hourly": HOURLY_RESPONSE,
        "https://api.weather.gov/gridpoints/OFFICE/1,2/forecast": DAILY_RESPONSE,
    }
    monkeypatch.setattr(weather, "_get", _fake_get(responses))

    out = weather.current(NOW)

    assert out == {"temp_f": 73, "high_f": 85, "low_f": 67, "condition": "Cloudy"}


def test_evening_call_does_not_show_tomorrows_high_as_todays(tmp_path, monkeypatch):
    """Regression test for the exact bug found in code review 2026-07-13:
    _today_high_low used to just take "whichever daytime period comes
    first," which in the evening (today's daytime period already past) is
    TOMORROW's forecast, mislabeled as today's high."""
    _configure(monkeypatch, tmp_path)
    evening_now = datetime.datetime(2026, 7, 13, 20, 0)
    daily_evening = {"properties": {"periods": [
        {"isDaytime": False, "startTime": "2026-07-13T18:00:00-04:00",
         "temperature": 64, "temperatureUnit": "F", "shortForecast": "Clear"},
        {"isDaytime": True, "startTime": "2026-07-14T06:00:00-04:00",
         "temperature": 90, "temperatureUnit": "F", "shortForecast": "Sunny"},
    ]}}
    responses = {
        "https://api.weather.gov/points/39.29,-76.61": POINTS_RESPONSE,
        "https://api.weather.gov/gridpoints/OFFICE/1,2/forecast/hourly": HOURLY_RESPONSE,
        "https://api.weather.gov/gridpoints/OFFICE/1,2/forecast": daily_evening,
    }
    monkeypatch.setattr(weather, "_get", _fake_get(responses))

    out = weather.current(evening_now)

    # today's low (Tonight) is present; today's high is NOT (already
    # passed and not in the response) -- must not fall back to tomorrow's
    # 90 and call it today's high.
    assert out["low_f"] == 64
    assert out["high_f"] is None
