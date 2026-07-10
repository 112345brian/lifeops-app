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

def _device_token():
    # Resolved from history.ROOT at call time (not import time) so tests
    # that monkeypatch history.ROOT to a sandbox dir actually redirect this
    # -- a previous version computed the path once at import, which read the
    # real on-disk token during every test run and sent live pushes.
    token_file = os.path.join(history.ROOT, "logs", "fcm_token.json")
    try:
        return json.load(open(token_file, encoding="utf-8")).get("token") or ""
    except Exception:
        return ""

def send_briefing(date, text, facts):
    """Data-only message (no "notification" field) so the widget's
    BriefingFcmService.onMessageReceived always fires, foreground or
    background, rather than the OS auto-displaying it only when the app
    isn't running. Silently no-ops if there's no registered device token yet
    (widget not configured) or no service-account key on disk."""
    token = _device_token()
    if not token or not os.path.exists(config.FCM_SERVICE_ACCOUNT_FILE):
        return
    from firebase_admin import messaging
    payload = json.dumps({"date": date, "text": text, "facts": facts})
    # AndroidConfig priority="high" is required for prompt delivery -- the
    # FCM default ("normal") can be delayed indefinitely by the device's
    # power management (confirmed: a normal-priority test send never arrived,
    # the same high-priority payload landed within seconds).
    message = messaging.Message(
        data={"payload": payload}, token=token,
        android=messaging.AndroidConfig(priority="high"),
    )
    messaging.send(message, app=_firebase_app())
