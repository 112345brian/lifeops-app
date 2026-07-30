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


def _alerted_today(key):
    return db.local_get(f"alert_dedup:{key}", default={}).get("date") == datetime.date.today().isoformat()


def _mark_alerted_today(key):
    db.local_set(f"alert_dedup:{key}", {"date": datetime.date.today().isoformat()})


def alert(text, priority="default", tags=None, actions=None, click_anchor="", msg_type="alert", dedup_key=None):
    """Send a human-visible alert.

    Today this is ntfy-backed for cross-platform reliability. Keeping the
    runner behind this function lets us add Android-native/Web Push channels
    without threading transport details through every domain.

    `msg_type` is the notification's semantic type (e.g. "urgent_alert",
    "action_result", "system_health") -- mirrors fcm._send's msg_type.
    Default "alert" preserves prior behavior exactly (no extra tag). Any
    other value is threaded onto ntfy's existing `tags` (rendered as an ntfy
    emoji/tag), since ntfy has no separate semantic-type field of its own.

    `dedup_key`, if given, sends at most once per calendar day per key --
    the SINGLE dedup mechanism for every notification in this codebase now:
    runner.py's ~15 tick-driven per-domain alerts (via its `_alert_once`
    wrapper, which just forwards its own `key` here) and this module's own
    push-unavailable fallback (see push_next_tasks) both go through this
    same parameter instead of each keeping separate dedup state/storage
    (previously runner.py used a private/logs/alert_state.json file while
    this module used a different db key -- two mechanisms that could
    independently decide to alert about the same underlying event, which is
    exactly how push_briefing's now-removed fallback ended up duplicating
    run_briefing's existing ntfy alert). web.py's `_canvas_status` reads
    this same `alert_dedup:<key>` storage to check whether today's canvas
    session alert already fired -- keep that in sync with any key-naming
    change here.

    The dedup key is only marked sent AFTER ntfy.alert succeeds: a failed
    send (e.g. ntfy.sh briefly down) leaves it unmarked so the NEXT call
    retries instead of silently giving up for the rest of the day. The
    exception itself still propagates unchanged -- this function doesn't
    swallow send failures; a caller that needs a failed *fallback* send to
    not break its own contract (push_next_tasks, a side channel that must
    never raise) catches it there instead, since for every other caller in
    this codebase a failed alert should fail loud, not be silently eaten.
    """
    if dedup_key is not None and _alerted_today(dedup_key):
        return
    if msg_type != "alert":
        tags = list(tags or []) + [msg_type]
    ntfy.alert(
        text,
        priority=priority,
        tags=tags,
        actions=actions,
        click=panel_url(click_anchor),
    )
    if dedup_key is not None:
        _mark_alerted_today(dedup_key)


def push_briefing(date, text, facts, version):
    """Push the structured daily briefing to rich clients. Returns whether a
    send was actually attempted -- see fcm.send_briefing's docstring.

    No ntfy fallback here (unlike push_next_tasks): runner.py's run_briefing
    already sends the full briefing text over ntfy unconditionally
    (_alert_once("briefing:"+date, ...)) before this is even called, so an
    FCM-unavailable install already gets the content -- a second "FCM push
    unavailable" alert on top would just be redundant noise, not a genuine
    fallback."""
    return fcm.send_briefing(date, text, facts, version)


def push_next_tasks(tasks, events, gym_ring, version):
    """Push a fresh next-tasks + today's-events snapshot to rich clients --
    the Tailscale-independent counterpart to the widget's periodic direct
    pull. See fcm.send_next_tasks's docstring, including the return value.

    Falls back to a deduped (see alert's dedup_key) ntfy alert when FCM
    no-ops, so a broken/unconfigured FCM doesn't silently drop the
    next-tasks refresh -- unlike push_briefing, there's no other existing
    alert covering this content. Side-channel only: the returned bool is
    untouched, and a failure sending the fallback itself (e.g. ntfy.sh
    briefly down) is caught and logged here rather than allowed to
    propagate -- this really is best-effort, unlike every other alert()
    caller in this codebase."""
    sent = fcm.send_next_tasks(tasks, events, gym_ring, version)
    if not sent:
        try:
            alert("Next tasks updated — FCM push unavailable, check the panel.",
                  msg_type="system_health", dedup_key="push_fallback:next_tasks")
        except Exception as e:
            print(f"[notify] push_next_tasks fallback alert failed: {e}")
    return sent
