"""ntfy client — read phone signals, send alerts. No auth (public topics)."""
import time, requests
from . import config

def poll(since=0):
    """Return list of messages on the signal topic since a unix ts."""
    if not config.NTFY_SIGNAL_TOPIC:
        return []
    url = f"https://ntfy.sh/{config.NTFY_SIGNAL_TOPIC}/json"
    r = requests.get(url, params={"poll": 1, "since": int(since)}, timeout=30)
    r.raise_for_status()
    msgs = []
    for line in r.text.splitlines():
        line = line.strip()
        if not line:
            continue
        import json
        try:
            m = json.loads(line)
            if m.get("event") == "message":
                msgs.append(m)
        except Exception:
            pass
    return msgs

def alert(text, priority="default", tags=None, actions=None, click=None):
    """Push an alert to the phone. priority: min|low|default|high|urgent.
    actions: list of (label, signal_body) — renders tap buttons that POST the
    signal_body back to the SIGNAL topic, so the system can react to your tap.
    click: URL opened when the notification body itself is tapped (not an
    action button) — pass a panel_url(...) fragment to deep-link into the
    relevant control-panel section."""
    if not config.NTFY_ALERTS_TOPIC:
        return
    headers = {"Priority": priority}
    if tags:
        headers["Tags"] = ",".join(tags)
    if actions and config.NTFY_SIGNAL_TOPIC:
        sig = f"https://ntfy.sh/{config.NTFY_SIGNAL_TOPIC}"
        headers["Actions"] = "; ".join(
            f"http, {label}, {sig}, method=POST, body={body}, clear=true"
            for label, body in actions)
    if click:
        headers["Click"] = click
    r = requests.post(f"https://ntfy.sh/{config.NTFY_ALERTS_TOPIC}",
                      data=text.encode("utf-8"), headers=headers, timeout=30)
    r.raise_for_status()

def panel_url(path=""):
    """Build a link into the control panel for a Click header. `path` is
    joined straight onto the base (a page route like "gym", optionally with
    a "#section" anchor like "settings#accounts") since the panel is split
    across pages, not a single scrollable one. Returns None (omit Click
    entirely) if PANEL_URL isn't configured."""
    if not config.PANEL_URL:
        return None
    base = config.PANEL_URL.rstrip("/")
    return f"{base}/{path}" if path else base
