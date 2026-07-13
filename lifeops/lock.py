"""Single global run-lock so only ONE runner mutates FlowSavvy at a time.

Covers the cross-task race (tick vs daily overlapping) that MultipleInstances
can't, since those are separate scheduled tasks. Stale locks (from a crashed
run) are broken after STALE seconds.
"""
import os, time, atexit, secrets

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOCK = os.path.join(ROOT, "logs", "lifeops.lock")
# Was 900s (15 min). If web.py's panel process gets killed externally while
# mid-request inside _exclusive() (the exact failure mode diagnosed
# 2026-07-12), this lock is orphaned and every runner.py invocation --
# signal, tick, AND daily -- gets Locked and skips its ENTIRE cycle until
# this breaks, not just one operation. LifeOps-signal exists specifically
# for ~2-min responsiveness; a 15-min stale window meant exactly the outage
# that matters most (panel already dead) also silently stalled the fast
# catch-up path 7.5x past its own design target. 300s still leaves a wide
# (5-10x) margin over any realistic single web.py request duration or
# runner tier's worst case, while cutting the blocking window 3x.
STALE = 300  # 5 min — still generous, no longer ~7x the signal loop's own cadence
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
