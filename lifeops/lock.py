"""Single global run-lock so only ONE runner mutates FlowSavvy at a time.

Covers the cross-task race (tick vs daily overlapping) that MultipleInstances
can't, since those are separate scheduled tasks. Stale locks (from a crashed
run) are broken after STALE seconds.
"""
import os, time, atexit, secrets

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOCK = os.path.join(ROOT, "logs", "lifeops.lock")
STALE = 900  # 15 min — older than any legitimate run
_owner = None

class Locked(Exception):
    pass

def acquire():
    global _owner
    os.makedirs(os.path.dirname(LOCK), exist_ok=True)
    # break a stale lock left by a crashed run
    try:
        if time.time() - os.path.getmtime(LOCK) > STALE:
            os.remove(LOCK)
    except FileNotFoundError:
        pass
    owner = f"{os.getpid()}:{secrets.token_hex(16)}"
    try:
        fd = os.open(LOCK, os.O_CREAT | os.O_EXCL | os.O_WRONLY)  # atomic create
    except FileExistsError:
        raise Locked("another LifeOps run is active")
    os.write(fd, owner.encode()); os.close(fd)
    _owner = owner
    atexit.register(release)

def release():
    global _owner
    owner = _owner
    if not owner:
        return
    try:
        with open(LOCK, encoding="utf-8") as f:
            current = f.read()
        if current == owner:
            os.remove(LOCK)
    except (FileNotFoundError, OSError):
        pass
    finally:
        _owner = None
