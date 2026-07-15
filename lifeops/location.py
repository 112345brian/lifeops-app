"""Phone-reported GPS location -- overrides the static WEATHER_LAT/WEATHER_LON
(.env) for weather lookups when the Android widget has reported a recent fix.
Single-user app -- one location on file, last write wins, same pattern as
fcm.py's device token.
"""
import json, os, tempfile, time
from . import history

# A fix older than this is treated as stale (phone off, app uninstalled,
# permission revoked) -- better to fall back to the static WEATHER_LAT/LON
# than silently show weather for wherever the phone happened to be a day+
# ago. Comfortably above the ~4-8h reporting cadence the widget side uses.
_MAX_AGE_SECONDS = 24 * 3600


def _location_file():
    # A function, not a module-level constant -- history.ROOT is
    # monkeypatched per-test (see fcm.py's _token_file for the same
    # pattern); a constant would freeze in the real ROOT at import time and
    # tests would silently share one real location file on disk.
    return os.path.join(history.ROOT, "logs", "phone_location.json")


def set_location(lat, lon):
    """Persists a fresh phone-reported fix. Returns False (no write) if
    lat/lon don't parse as real coordinates."""
    try:
        lat_f, lon_f = float(lat), float(lon)
    except (TypeError, ValueError):
        return False
    if not (-90 <= lat_f <= 90) or not (-180 <= lon_f <= 180):
        return False
    path = _location_file()
    os.makedirs(os.path.dirname(path), exist_ok=True)
    # Temp file + fsync + os.replace, not a direct write -- same reasoning
    # as fcm.py's register_token (a kill/crash mid-write must not leave a
    # truncated location file). Unique temp name via mkstemp since this is
    # reachable from web.py's long-running server process.
    fd, tmp = tempfile.mkstemp(prefix="phone_location-", suffix=".tmp", dir=os.path.dirname(path))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump({"lat": lat_f, "lon": lon_f, "reported_at": time.time()}, f)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
    finally:
        try:
            os.remove(tmp)
        except FileNotFoundError:
            pass
    return True


def get_location():
    """Returns (lat, lon) as strings -- matching config.WEATHER_LAT/LON's own
    string type so weather.py can treat either source identically -- from
    the most recent phone report, or None if there's never been one or the
    latest one is older than _MAX_AGE_SECONDS."""
    try:
        data = json.load(open(_location_file(), encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError):
        return None
    if time.time() - data.get("reported_at", 0) > _MAX_AGE_SECONDS:
        return None
    return str(data["lat"]), str(data["lon"])
