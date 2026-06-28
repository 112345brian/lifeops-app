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

def alert(text, priority="default", tags=None):
    """Push an alert to the phone. priority: min|low|default|high|urgent."""
    if not config.NTFY_ALERTS_TOPIC:
        return
    headers = {"Priority": priority}
    if tags:
        headers["Tags"] = ",".join(tags)
    requests.post(f"https://ntfy.sh/{config.NTFY_ALERTS_TOPIC}",
                  data=text.encode("utf-8"), headers=headers, timeout=30)
