"""Outbound notification facade.

LifeOps domains should ask for product-level messages ("alert this", "push
today's briefing") instead of knowing which transport carries them. ntfy stays
the cross-platform fallback/human alert channel; FCM is the Android widget's
rich briefing path.
"""
import datetime
from . import db, fcm, ntfy


def panel_url(path=""):
    """Build a safe panel deep-link for transports that support click-through."""
    return ntfy.panel_url(path)


def alert(text, priority="default", tags=None, actions=None, click_anchor="", msg_type="alert"):
    """Send a human-visible alert.

    Today this is ntfy-backed for cross-platform reliability. Keeping the
    runner behind this function lets us add Android-native/Web Push channels
    without threading transport details through every domain.

    `msg_type` is the notification's semantic type (e.g. "urgent_alert",
    "action_result", "system_health") -- the first real type concept in this
    facade, mirroring fcm._send's msg_type. Default "alert" preserves prior
    behavior exactly (no extra tag). Any other value is threaded onto ntfy's
    existing `tags` (rendered as an ntfy emoji/tag), since ntfy has no
    separate semantic-type field of its own.
    """
    if msg_type != "alert":
        tags = list(tags or []) + [msg_type]
    ntfy.alert(
        text,
        priority=priority,
        tags=tags,
        actions=actions,
        click=panel_url(click_anchor),
    )


def _fallback_alert_once(key, text, click_anchor=""):
    """Sends the "FCM push unavailable" fallback at most once per calendar
    day per key -- mirrors runner.py's _alert_once, needed here (rather than
    left to a caller) because push_next_tasks runs on the ~10-min tick
    cadence (runner.py's push_next_tasks/_push_with_ack), so a persistently
    broken FCM would otherwise spam an ntfy alert every tick forever."""
    today = datetime.date.today().isoformat()
    dedup_key = f"push_fallback_alerted:{key}"
    if db.local_get(dedup_key, default={}).get("date") == today:
        return
    alert(text, click_anchor=click_anchor, msg_type="system_health")
    db.local_set(dedup_key, {"date": today})


def push_briefing(date, text, facts, version):
    """Push the structured daily briefing to rich clients. Returns whether a
    send was actually attempted -- see fcm.send_briefing's docstring.

    If FCM no-oped (no registered device token, or no service-account file on
    disk -- see fcm._send's docstring), falls back to a short ntfy alert (at
    most once/day, see _fallback_alert_once) so the user still learns the
    briefing is ready instead of the push silently vanishing. This is a
    side-channel only: the returned bool still reflects the FCM attempt,
    unchanged, so runner.py's ack-tracking bookkeeping (_push_with_ack)
    keeps working exactly as before."""
    sent = fcm.send_briefing(date, text, facts, version)
    if not sent:
        _fallback_alert_once("briefing",
            "Briefing ready — FCM push unavailable, check the panel.",
            click_anchor="#briefing")
    return sent


def push_next_tasks(tasks, events, gym_ring, version):
    """Push a fresh next-tasks + today's-events snapshot to rich clients --
    the Tailscale-independent counterpart to the widget's periodic direct
    pull. See fcm.send_next_tasks's docstring, including the return value.

    Same ntfy fallback as push_briefing when FCM no-ops -- see that
    docstring, including the once/day dedup (this runs on the ~10-min tick,
    so it needs the dedup even more than the once/day briefing push does).
    Side-channel only; the returned bool is untouched."""
    sent = fcm.send_next_tasks(tasks, events, gym_ring, version)
    if not sent:
        _fallback_alert_once("next_tasks",
            "Next tasks updated — FCM push unavailable, check the panel.")
    return sent
