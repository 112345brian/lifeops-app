"""Firebase Cloud Messaging client — reliable push for the Android widget's
daily briefing. The ntfy path (see ntfy.py) is fine for human-visible alerts,
but a manifest-registered BroadcastReceiver can't wake a stopped app for
implicit third-party broadcasts, so the widget listens for FCM instead
(reliable regardless of whether the app is running). See
android/app/src/main/kotlin/com/lifeops/briefing/BriefingFcmService.kt for the
receiving side and the token-registration flow.
"""
import json, os
from . import config, history

_app = None

def _firebase_app():
    global _app
    if _app is None:
        import firebase_admin
        from firebase_admin import credentials
        cred = credentials.Certificate(config.FCM_SERVICE_ACCOUNT_FILE)
        _app = firebase_admin.initialize_app(cred)
    return _app

def _token_file():
    # Resolved from history.ROOT at call time (not import time) so tests
    # that monkeypatch history.ROOT to a sandbox dir actually redirect this
    # -- a previous version computed the path once at import, which read the
    # real on-disk token during every test run and sent live pushes.
    return os.path.join(history.ROOT, "logs", "fcm_token.json")

def _device_token():
    try:
        return json.load(open(_token_file(), encoding="utf-8")).get("token") or ""
    except Exception:
        return ""

def register_token(token):
    """Persists a fresh FCM device token. Single-user app -- one token on
    file, last write wins. Shared by web.py's /api/register-fcm-token
    (direct, Tailscale-gated) and runner.py's ntfy `token:<value>` signal
    handler (relay fallback for when the phone isn't on the tailnet), so
    both entry points validate and write identically. Returns False (no
    write) if `token` doesn't look like a real FCM token."""
    # FCM registration tokens are long opaque strings (typically 140-200+
    # chars); a generous sanity bound catches obvious garbage without
    # hardcoding Firebase's exact format.
    if not isinstance(token, str) or not (10 <= len(token) <= 4096):
        return False
    path = _token_file()
    os.makedirs(os.path.dirname(path), exist_ok=True)
    # Temp file + os.replace, not a direct write -- a kill/crash mid-write
    # must not leave a truncated token file (same reasoning as every other
    # durable state write in this codebase).
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump({"token": token}, f)
    os.replace(tmp, path)
    return True

def _send(msg_type, payload_dict, version):
    """Data-only message (no "notification" field) so the widget's
    BriefingFcmService.onMessageReceived always fires, foreground or
    background, rather than the OS auto-displaying it only when the app
    isn't running. Silently no-ops if there's no registered device token yet
    (widget not configured) or no service-account key on disk. `msg_type`
    lets the client dispatch to the right persist worker without guessing
    from the payload shape. `version` (a short content hash, see
    runner.py's _push_with_ack) is echoed back by the client as an
    `ack:<type>:<version>` ntfy signal once persisted -- messaging.send()
    succeeding only confirms Firebase ACCEPTED this for delivery, not that
    the device ever received it, so this is how the server actually finds
    out whether a push landed."""
    token = _device_token()
    if not token or not os.path.exists(config.FCM_SERVICE_ACCOUNT_FILE):
        return
    from firebase_admin import messaging
    # AndroidConfig priority="high" is required for prompt delivery -- the
    # FCM default ("normal") can be delayed indefinitely by the device's
    # power management (confirmed: a normal-priority test send never arrived,
    # the same high-priority payload landed within seconds).
    message = messaging.Message(
        data={"type": msg_type, "version": version, "payload": json.dumps(payload_dict)}, token=token,
        android=messaging.AndroidConfig(priority="high"),
    )
    messaging.send(message, app=_firebase_app())

def send_briefing(date, text, facts, version):
    """Pushes the daily briefing text + stats. See _send's docstring for the
    delivery-reliability reasoning."""
    _send("briefing", {"date": date, "text": text, "facts": facts}, version)

def send_next_tasks(tasks, events, version):
    """Pushes a fresh next-tasks + today's-events snapshot -- the
    Tailscale-independent counterpart to NextTasksRefreshWorker's periodic
    direct pull, which stays in place as a self-heal fallback for the rare
    case a push gets dropped (FCM data-message delivery isn't guaranteed
    either, just far more often reachable than the tailnet)."""
    _send("next_tasks", {"tasks": tasks, "events": events}, version)
