"""Small helpers for durable JSON state files under ``logs/``.

Most LifeOps state is intentionally simple JSON on disk. Keeping the load and
atomic-write rules here prevents each domain from re-solving corruption,
directory creation, and crash-safe replacement differently.
"""
import json, os, tempfile

from . import history


def logs_path(filename):
    return os.path.join(history.ROOT, "logs", filename)


def load_json(path, default=None, require_type=None):
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return default
    if require_type is not None and not isinstance(data, require_type):
        return default
    return data


def save_json_atomic(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=".state-", suffix=".tmp", dir=os.path.dirname(path))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(data, f)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
    finally:
        try:
            os.remove(tmp)
        except FileNotFoundError:
            pass


def append_line(path, line):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "a", encoding="utf-8") as f:
        f.write(line + "\n")
