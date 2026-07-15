"""Push-until-acked state for FCM-delivered widget payloads."""
import hashlib, json

from . import state_store


def state_file(msg_type):
    return state_store.logs_path(f"push_ack_{msg_type}.json")


def _load_state(path):
    return state_store.load_json(path, default=None, require_type=dict)


def version_for(snapshot):
    return hashlib.sha1(json.dumps(snapshot, sort_keys=True).encode()).hexdigest()[:16]


def push_with_ack(msg_type, snapshot, push_fn):
    """Send ``snapshot`` unless this exact version is already acked.

    ``push_fn(version)`` returns whether a real send was attempted. A no-op
    send, such as no registered FCM token, writes no state so the same content
    can still be delivered later after configuration changes.
    """
    version = version_for(snapshot)
    path = state_file(msg_type)
    state = _load_state(path)
    if state and state.get("version") == version and state.get("acked"):
        return
    sent = push_fn(version)
    if not sent:
        return
    state_store.save_json_atomic(path, {"version": version, "acked": False})


def mark_acked(msg_type, version):
    path = state_file(msg_type)
    state = _load_state(path)
    if state and state.get("version") == version:
        state["acked"] = True
        state_store.save_json_atomic(path, state)
