"""Helpers shared by every domain module (and reused by the orchestrator's
own ingest/push-next-tasks/panel-health passes, which aren't domains but
need the same audit-log, dirty-flag, and push-ack machinery)."""
import datetime
from .. import actions, notify, push_state, state_store


def _save_json_atomic(path, data):
    state_store.save_json_atomic(path, data)


def _logged_create(fs, domain, op="created", **kwargs):
    """fs.create_task + an audit-log entry ("LifeOps added X"), returning the
    raw response so callers can still wire dependencies off the new id. The
    created task is marked undoable (undo = delete it) when an id came back."""
    r = fs.create_task(**kwargs)
    tid = (r or {}).get("id") or (r or {}).get("item", {}).get("id")
    actions.log(domain, op, kwargs.get("title", "?"), item_id=tid, undoable=True)
    return r


_DIRTY = [False]


def _touch():
    """Mark the schedule changed. runner._run() recalculates ONCE at the end
    instead of every domain churning FlowSavvy separately (your calendar
    stays stable)."""
    _DIRTY[0] = True


def _utc_iso(days_ago=0):
    return (datetime.datetime.now(datetime.timezone.utc)
            - datetime.timedelta(days=days_ago)).strftime("%Y-%m-%dT%H:%M:%SZ")


def _alert_once(key, text, priority="default", tags=None, actions=None, click_anchor=""):
    """Send an alert at most once per calendar day per key. The tick runs every
    10 min — without this, advisory alerts would spam. click_anchor: panel
    section to deep-link into when the notification is tapped (e.g. "gym") —
    "" links to the panel root, which is still useful (opens the app).
    Omitted entirely if PANEL_URL isn't configured.

    Thin wrapper: the dedup logic itself lives in notify.alert's dedup_key
    param -- the single dedup mechanism for every notification in this
    codebase (shared with notify.py's own push-unavailable fallback,
    see notify.alert's docstring) -- kept as a local name/signature here so
    call sites (and their tests, which monkeypatch this directly) don't need
    to change."""
    notify.alert(text, priority=priority, tags=tags, actions=actions,
                 click_anchor=click_anchor, dedup_key=key)


def _push_ack_state_file(msg_type):
    return push_state.state_file(msg_type)


def _load_push_ack_state(sp):
    """Returns the parsed state dict, or None if missing/corrupt/not a dict
    (a plain set-to-`{}` or malformed file must not crash the caller --
    _mark_push_acked in particular runs inside ingest()'s per-message loop,
    where an uncaught exception would also drop the rest of that poll
    batch's ntfy_ts/handled_ntfy_msg_ids persistence)."""
    return state_store.load_json(sp, default=None, require_type=dict)


def _push_with_ack(msg_type, snapshot, push_fn):
    """Push-until-confirmed wrapper around an FCM send. messaging.send()
    succeeding only means Firebase ACCEPTED the message for delivery, not
    that the phone ever received it (data messages can be silently dropped
    under Doze, a force-stopped app, etc.), so this tracks a real receipt
    confirmation instead of trusting the send call: `snapshot` is hashed
    into a short version id, `push_fn(version)` is called to actually send
    it (returning whether anything was actually sent -- see fcm._send), and
    on a genuine send the version is recorded as unacked. The client echoes
    the version back as an `ack:<type>:<version>` ntfy signal once it's
    successfully persisted (see runner.ingest()'s ack handler).

    When push_fn reports nothing was actually sent (e.g. no FCM token
    registered yet), NO state is written at all -- persisting a fabricated
    "acked" sentinel here would be wrong: once a token is later registered
    via fcm.register_token, the very next call for this SAME unchanged
    snapshot would hash to the same version and get skipped by the
    unchanged-and-acked check below, even though nothing was ever actually
    delivered to the device. Leaving state untouched means an unconfigured
    device just retries (cheaply -- push_fn no-ops before any network call)
    every call until a real send finally succeeds.

    Skips the actual send only when BOTH the content is unchanged since the
    last push AND that push was acked -- an unacked previous push keeps
    getting retried every call even if nothing new happened, since "unacked"
    is exactly the signal that the last attempt may not have landed. Same
    "don't do wasted round-trips on a fixed schedule" philosophy as spend/
    canvas being excluded from the tick tier elsewhere governs the
    content-unchanged half of this check."""
    return push_state.push_with_ack(msg_type, snapshot, push_fn)


def _mark_push_acked(msg_type, version):
    """Called from runner.ingest()'s ack:<type>:<version> signal handler.
    Only updates state if `version` matches the currently-tracked push -- an
    ack for a superseded version (e.g. the phone was slow to respond and a
    newer snapshot already went out) must not mark the NEW one acked."""
    push_state.mark_acked(msg_type, version)
