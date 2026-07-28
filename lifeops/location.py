"""Phone-reported GPS location -- overrides the static WEATHER_LAT/WEATHER_LON
(.env) for weather lookups when the Android widget has reported a recent fix.
Single-user app -- one location on file, last write wins, same pattern as
fcm.py's device token.
"""
import time
from . import state_store

# A fix older than this is treated as stale (phone off, app uninstalled,
# permission revoked) -- better to fall back to the static WEATHER_LAT/LON
# than silently show weather for wherever the phone happened to be a day+
# ago. Comfortably above the ~4-8h reporting cadence the widget side uses.
_MAX_AGE_SECONDS = 24 * 3600


def _location_file():
    # Lives in the tracked/backed-up state (private/logs), not the local-only
    # gitignored logs/ -- this is GPS history, the most sensitive state file
    # in the app, so it belongs wherever the rest of durable state lives.
    return state_store.logs_path("phone_location.json")


def set_location(lat, lon):
    """Persists a fresh phone-reported fix. Returns False (no write) if
    lat/lon don't parse as real coordinates."""
    try:
        lat_f, lon_f = float(lat), float(lon)
    except (TypeError, ValueError):
        return False
    if not (-90 <= lat_f <= 90) or not (-180 <= lon_f <= 180):
        return False
    state_store.save_json_atomic(_location_file(),
                                 {"lat": lat_f, "lon": lon_f, "reported_at": time.time()})
    return True


def get_location():
    """Returns (lat, lon) as strings -- matching config.WEATHER_LAT/LON's own
    string type so weather.py can treat either source identically -- from
    the most recent phone report, or None if there's never been one or the
    latest one is older than _MAX_AGE_SECONDS."""
    data = state_store.load_json(_location_file())
    if not data:
        return None
    if time.time() - data.get("reported_at", 0) > _MAX_AGE_SECONDS:
        return None
    return str(data["lat"]), str(data["lon"])
