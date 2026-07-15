"""NOAA/NWS weather -- free, no-API-key REST API at api.weather.gov.

Forecast location is the Android widget's last-reported phone GPS fix
(location.get_location(), see location.py) when one is on file and not
stale; otherwise the static WEATHER_LAT/WEATHER_LON (.env). Both unset =
the feature no-ops everywhere it's read (current() returns None), same
"blank = disabled" convention as PANEL_URL/WEB_TOKEN in config.py.
"""
import json, os
import requests
from . import config, history, location

_TIMEOUT = 10


def _grid_cache_file():
    # A function, not a module-level constant -- history.ROOT is
    # monkeypatched per-test (see fcm.py's _token_file for the same
    # pattern); a constant would freeze in the real ROOT at import time and
    # tests would silently share one real cache file on disk.
    return os.path.join(history.ROOT, "logs", "weather_grid_cache.json")


def _headers():
    return {"User-Agent": config.WEATHER_USER_AGENT, "Accept": "application/geo+json"}


def _get(url):
    """Thin seam over requests.get so tests can monkeypatch one function
    instead of mocking the requests library itself (matches this codebase's
    other external-client test style, e.g. fcm.py's _send)."""
    resp = requests.get(url, headers=_headers(), timeout=_TIMEOUT)
    resp.raise_for_status()
    return resp.json()


def _load_grid_cache():
    try:
        return json.load(open(_grid_cache_file(), encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError):
        return {}


def _save_grid_cache(data):
    path = _grid_cache_file()
    os.makedirs(os.path.dirname(path), exist_ok=True)
    json.dump(data, open(path, "w", encoding="utf-8"))


def _resolve_location():
    """(lat, lon) strings from the phone's last-reported fix if one is on
    file and fresh, else the static WEATHER_LAT/WEATHER_LON, else None."""
    return location.get_location() or (
        (config.WEATHER_LAT, config.WEATHER_LON) if config.WEATHER_LAT and config.WEATHER_LON else None
    )


def _forecast_urls(lat, lon):
    """The /points/{lat},{lon} -> forecast URL mapping is static for a fixed
    location, so it's cached to disk after the first lookup instead of
    hitting NWS's /points endpoint on every briefing run. Keyed by lat/lon,
    so a phone location update naturally busts the cache and re-resolves
    the new grid cell. Returns (hourly_url, daily_url)."""
    cache_key = f"{lat},{lon}"
    cache = _load_grid_cache()
    if cache.get("key") == cache_key and cache.get("hourly") and cache.get("daily"):
        return cache["hourly"], cache["daily"]
    points = _get(f"https://api.weather.gov/points/{lat},{lon}")
    props = points["properties"]
    hourly, daily = props["forecastHourly"], props["forecast"]
    _save_grid_cache({"key": cache_key, "hourly": hourly, "daily": daily})
    return hourly, daily


def _to_f(temp, unit):
    return round(temp * 9 / 5 + 32) if unit == "C" else round(temp)


def _today_high_low(daily_periods, today_date):
    """The daily forecast alternates day/night 12h periods. Matches
    periods against [today_date] via each period's real startTime rather
    than just taking "whichever daytime/nighttime period comes first" --
    that used to silently return TOMORROW's high once today's daytime
    period had already passed (e.g. called in the evening, when the first
    daytime period left in the response is tomorrow's), mislabeling it as
    today's (confirmed 2026-07-13)."""
    todays = [p for p in daily_periods if (p.get("startTime") or "")[:10] == today_date.isoformat()]
    high = next((p for p in todays if p.get("isDaytime")), None)
    low = next((p for p in todays if not p.get("isDaytime")), None)
    high_f = _to_f(high["temperature"], high.get("temperatureUnit", "F")) if high else None
    low_f = _to_f(low["temperature"], low.get("temperatureUnit", "F")) if low else None
    return high_f, low_f


def current(now):
    """Current conditions at the resolved location (see _resolve_location):
    {"temp_f", "high_f", "low_f", "condition"}, or None if unconfigured or
    the NWS API is unreachable -- best-effort, same as every other
    external-data pull in gather.py (a weather outage must not take down
    the whole briefing). [now] is only used to pin "today" for the
    high/low lookup -- see _today_high_low."""
    resolved = _resolve_location()
    if not resolved:
        return None
    try:
        hourly_url, daily_url = _forecast_urls(*resolved)
        hourly = _get(hourly_url)["properties"]["periods"]
        daily = _get(daily_url)["properties"]["periods"]
        now_period = hourly[0]
        temp_f = _to_f(now_period["temperature"], now_period.get("temperatureUnit", "F"))
        high_f, low_f = _today_high_low(daily, now.date())
        condition = now_period.get("shortForecast") or (daily[0].get("shortForecast") if daily else None)
        return {"temp_f": temp_f, "high_f": high_f, "low_f": low_f, "condition": condition}
    except Exception:
        return None
