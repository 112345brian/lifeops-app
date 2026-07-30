"""Firebase Cloud Messaging client — reliable push for the Android widget's
daily briefing. The ntfy path (see ntfy.py) is fine for human-visible alerts,
but a manifest-registered BroadcastReceiver can't wake a stopped app for
implicit third-party broadcasts, so the widget listens for FCM instead
(reliable regardless of whether the app is running). See
android/app/src/main/kotlin/com/lifeops/briefing/BriefingFcmService.kt for the
receiving side and the token-registration flow.
"""
import datetime, json, os
from . import config, db

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
    return db.local_get("fcm_token", default={}).get("token") or ""

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
    # Python-side local-time timestamp, matching db.local_set's own
    # updated_at convention (see db.py) -- lets /api/health report token age
    # without needing kv_state's row-level updated_at exposed through
    # local_get.
    now = datetime.datetime.now().isoformat(timespec="seconds")
    db.local_set("fcm_token", {"token": token, "registered_at": now})
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
    out whether a push landed.

    Returns True if a send was actually attempted (token + service account
    both present), False if it no-oped. This is NOT delivery confirmation --
    just enough for _push_with_ack to tell "genuinely sent, awaiting an ack"
    apart from "nothing to send yet" (e.g. a fresh install with no
    registered token), which would otherwise be marked unacked forever and
    retried every tick indefinitely for no reason."""
    token = _device_token()
    if not token or not os.path.exists(config.FCM_SERVICE_ACCOUNT_FILE):
        db.local_set("fcm_last_send", {
            "type": msg_type, "ok": False,
            "at": datetime.datetime.now().isoformat(timespec="seconds"),
        })
        return False
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
    db.local_set("fcm_last_send", {
        "type": msg_type, "ok": True,
        "at": datetime.datetime.now().isoformat(timespec="seconds"),
    })
    return True

# These are pure inputs to attention.compute() (see briefing_service.build)
# with zero client-side readers -- BriefingState.fromJson only ever reads a
# fixed, named subset of `facts` (confirmed against
# android/.../data/BriefingState.kt), and none of these three keys are in it.
# Sending them anyway is what pushed real briefings over Android's 4KB FCM
# data-message cap (confirmed 2026-07-2x: "Message is too large" send
# failures on days with a longer overdue-coursework list).
_BRIEFING_FACTS_DROP = ("overdue", "coursework_at_risk", "due_today")


def _trim_facts_for_push(facts):
    """Shrinks `facts` for the wire without changing anything the widget
    renders. Drops _BRIEFING_FACTS_DROP entirely (see its comment), and
    trims attention["reasons"] down to domain+severity per reason --
    AttentionReason (BriefingState.kt) deliberately never carries
    title/recommended_action/due, which otherwise just duplicate the
    (already dropped) `overdue` title strings. The full, untrimmed facts
    dict still reaches the panel/`/api/briefing`/briefing.json via other
    call sites -- this trim is push-only."""
    trimmed = {k: v for k, v in facts.items() if k not in _BRIEFING_FACTS_DROP}
    attention = trimmed.get("attention")
    if isinstance(attention, dict) and isinstance(attention.get("reasons"), list):
        trimmed["attention"] = {**attention, "reasons": [
            {"domain": r.get("domain"), "severity": r.get("severity")}
            for r in attention["reasons"]
        ]}
    return trimmed


def send_briefing(date, text, facts, version):
    """Pushes the daily briefing text + stats. See _send's docstring for the
    delivery-reliability reasoning and return value, and
    _trim_facts_for_push's docstring for why `facts` is trimmed here but
    nowhere else."""
    return _send("briefing", {"date": date, "text": text, "facts": _trim_facts_for_push(facts)}, version)

def send_next_tasks(tasks, events, gym_ring, version):
    """Pushes a fresh next-tasks + today's-events snapshot -- the
    Tailscale-independent counterpart to NextTasksRefreshWorker's periodic
    direct pull, which stays in place as a self-heal fallback for the rare
    case a push gets dropped (FCM data-message delivery isn't guaranteed
    either, just far more often reachable than the tailnet). See _send's
    docstring for the return value."""
    return _send("next_tasks", {"tasks": tasks, "events": events, "gym_ring": gym_ring}, version)
