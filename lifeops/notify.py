"""Outbound notification facade.

LifeOps domains should ask for product-level messages ("alert this", "push
today's briefing") instead of knowing which transport carries them. ntfy stays
the cross-platform fallback/human alert channel; FCM is the Android widget's
rich briefing path.
"""
from . import fcm, ntfy


def panel_url(path=""):
    """Build a safe panel deep-link for transports that support click-through."""
    return ntfy.panel_url(path)


def alert(text, priority="default", tags=None, actions=None, click_anchor=""):
    """Send a human-visible alert.

    Today this is ntfy-backed for cross-platform reliability. Keeping the
    runner behind this function lets us add Android-native/Web Push channels
    without threading transport details through every domain.
    """
    ntfy.alert(
        text,
        priority=priority,
        tags=tags,
        actions=actions,
        click=panel_url(click_anchor),
    )


def push_briefing(date, text, facts, version):
    """Push the structured daily briefing to rich clients. Returns whether a
    send was actually attempted -- see fcm.send_briefing's docstring."""
    return fcm.send_briefing(date, text, facts, version)


def push_next_tasks(tasks, events, gym_ring, version):
    """Push a fresh next-tasks + today's-events snapshot to rich clients --
    the Tailscale-independent counterpart to the widget's periodic direct
    pull. See fcm.send_next_tasks's docstring, including the return value."""
    return fcm.send_next_tasks(tasks, events, gym_ring, version)
