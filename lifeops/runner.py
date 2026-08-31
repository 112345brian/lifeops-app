"""Orchestrator — the cron entrypoint. Replaces the Claude 'daily-ops' routine.

Flow per domain: GATHER (clients) -> DECIDE (deterministic engine) -> APPLY (clients).
The LLM (lifeops.llm) is touched only for the judgment slivers. Per-domain
GATHER/DECIDE/APPLY logic lives in lifeops.domains.* — this module owns only
tiers, the run lock, and the DOMAINS registry that wires them together.

Run:  python -m lifeops.runner          # all wired domains
      python -m lifeops.runner gym      # one domain
"""
import sys, os, re, io, json, datetime, contextlib, requests
from . import config, ntfy, notify, gather, lock, history, fcm
from . import state_store
from .flowsavvy import FlowSavvy
from .ynab import YNAB
from .domains._shared import (_save_json_atomic, _alert_once, _push_with_ack,
                              _mark_push_acked, _utc_iso, _DIRTY)
from .domains import canvas as canvas_domain
from .domains import finance, household, social, planning

# ntfy signal body -> history action
_SIG = {"gym": "gym", "gym-nocount": "gym_skip",
        config.PARTNER_SIGNAL: "partner", "hung friends": "friends",
        "fell-asleep": "sleep", "woke-up": "wake"}

def _classify(title, notes=None):
    t = (title or "").lower()
    # An explicitly configured partner task is more specific than a generic
    # "friend" mention in its notes. Guard against PARTNER_TASK being blank
    # (settable via the Settings page) -- "" is a substring of every string,
    # so an empty config value would otherwise match and misclassify every
    # single task as "partner".
    if config.PARTNER_TASK and config.PARTNER_TASK.lower() in t:
        return "partner"
    if gather._is_friend_hangout(title, notes):
        return "friends"
    for k, v in [("gym", "gym"), ("laundry", "laundry"), ("clean room", "clean_room"),
                 ("clean bathroom", "clean_bathroom"), ("tidy car", "tidy_car"),
                 ("car wash", "car_wash"), ("oil change", "oil"),
                 ("meal prep", "meal"), ("groceries", "groceries"),
                 ("studio", "studio")]:
        if k in t:
            return v
    # coursework: canvas-created tasks look like "M07: ... [AS.470.703.81.SU26]"
    if re.match(r"^m\d{2}\b", t) or "[as." in t:
        return "course"
    return None

def _heartbeat(ok):
    """Ping a healthchecks.io-style URL so external monitoring knows we're alive;
    if we ever stop, it notices the silence and alerts you."""
    if not config.HEARTBEAT_URL:
        return
    url = config.HEARTBEAT_URL if ok else config.HEARTBEAT_URL.rstrip("/") + "/fail"
    try:
        import urllib.request
        urllib.request.urlopen(url, timeout=10)
    except Exception:
        pass

def check_panel_health(now):
    """Watchdog for the lifeops.web panel process. This runs from a SEPARATE
    process (runner.py, invoked by LifeOps-signal/-tick/-daily) that doesn't
    depend on the panel being alive, so it can detect and alert on exactly
    the failure mode nothing else catches: the panel dying silently with no
    independent monitoring (confirmed 2026-07-12 -- it was killed multiple
    times with nothing paging anyone; a human had to notice the widget or
    dashboard was broken, potentially hours later). Sends at most one alert
    per continuous outage, and one recovery alert when it comes back, via
    ntfy directly (not through the panel -- alerts must work precisely when
    the panel doesn't)."""
    sp = os.path.join(history.ROOT, "private", "logs", "panel_health_state.json")
    st = {"down_since": None, "alerted": False}
    st.update(state_store.load_json(sp, default={}))
    try:
        # Any HTTP response at all (even a 401 with no token) proves the
        # process is up and listening -- this isn't checking auth, just
        # liveness, so no WEB_TOKEN is needed here.
        requests.get("http://127.0.0.1:8765/api/status", timeout=5)
        is_up = True
    except Exception:
        is_up = False

    if is_up:
        if st["down_since"] and st["alerted"]:
            try:
                down_since = datetime.datetime.fromisoformat(st["down_since"])
                down_for_min = (now - down_since).total_seconds() / 60
                notify.alert(f"LifeOps panel is back up (was down ~{down_for_min:.0f} min).")
            except Exception as e:
                print(f"[panel_health] recovery alert failed (non-fatal): {e}")
        st["down_since"] = None
        st["alerted"] = False
    else:
        if not st["down_since"]:
            st["down_since"] = now.isoformat(timespec="seconds")
        down_since = datetime.datetime.fromisoformat(st["down_since"])
        down_for_min = (now - down_since).total_seconds() / 60
        # A few missed ticks' grace before paging -- a single hiccup (e.g.
        # mid-restart from register_web.ps1's own RestartCount cycle)
        # shouldn't alert; a sustained outage should. LifeOps-signal's
        # ~2-min cadence means ~6 min is only 2-3 consecutive misses.
        if down_for_min >= 6 and not st["alerted"]:
            try:
                notify.alert(f"⚠️ LifeOps panel unreachable for ~{down_for_min:.0f} min.",
                             priority="high")
                st["alerted"] = True
            except Exception as e:
                print(f"[panel_health] alert failed (non-fatal): {e}")
    os.makedirs(os.path.dirname(sp), exist_ok=True)
    _save_json_atomic(sp, st)

def ingest(fs, now):
    """Harvest completions from ntfy signals + FlowSavvy check-offs into the
    permanent history log. Runs every cycle; cheap and deduped."""
    sp = os.path.join(history.ROOT, "private", "logs", "ingest_state.json")
    st = {"ntfy_ts": 0, "logged_ids": [], "handled_ntfy_msg_ids": []}
    st.update(state_store.load_json(sp, default={}))
    # Same ordered-list + set pairing as handled_msg_ids below, and for the
    # same reason: a plain set has no guaranteed order, so truncating one to
    # "the last 1000" after converting back to a list doesn't reliably keep
    # the most recently logged completions.
    logged_ids = list(st["logged_ids"])
    logged = set(logged_ids)
    # ntfy's own delivery guarantee isn't exactly-once (redelivery on
    # reconnect/retry is real), and the phone can also redeliver via a
    # re-tap if the app's optimistic local removal silently failed --
    # dedupe by ntfy's own per-message id so a replayed "complete:<id>"
    # doesn't re-fire fs.complete_task (relying on FlowSavvy's completion
    # endpoint being idempotent is a documented assumption elsewhere, not a
    # guarantee this code should also depend on with zero defense-in-depth).
    # Keep an ORDERED list (oldest-first) alongside a set for O(1) lookups --
    # a plain set has no guaranteed iteration order, so slicing it after
    # converting back to a list does not reliably keep the most-recently-
    # handled ids once the 1000-entry cap is hit, silently defeating this
    # dedup for a genuine redelivery of a recent message.
    handled_msg_ids = list(st["handled_ntfy_msg_ids"])
    handled_msg_id_set = set(handled_msg_ids)
    for m in ntfy.poll(since=st["ntfy_ts"]):
        raw_body = (m.get("message") or "").strip()
        body = raw_body.lower()
        ts = datetime.datetime.fromtimestamp(m["time"]).isoformat(timespec="seconds")
        act = _SIG.get(body)
        if act:
            history.append(act, ts=ts, source="ntfy")
        elif body.startswith("sleep:"):   # real sleep duration (minutes) from the watch
            try:
                history.append("sleep_dur", ts=ts, source="ntfy",
                               meta={"minutes": int(float(body.split(":", 1)[1]))})
            except Exception:
                pass
        elif body.startswith("complete:"):
            # Widget checkbox tap, relayed via ntfy instead of a direct
            # Tailscale call to the panel -- the whole point being the phone
            # never needs tailnet connectivity just to check off a task. The
            # completed-tasks scan below (fs.list_items completed=True) picks
            # this up and logs it to history same as any other completion,
            # so there's nothing else to do here but tell FlowSavvy.
            # Extract the id from raw_body, NOT the lowercased body -- a
            # FlowSavvy task id isn't guaranteed to be safely lowercasable
            # (currently numeric in practice, but the field is a plain
            # String client-side with no case contract), and mangling it
            # here would 404 silently forever with no user-visible failure.
            tid = raw_body.split(":", 1)[1].strip()
            msg_id = m.get("id")
            if not tid:
                print("[ingest] complete signal missing a task id, skipping")
            elif msg_id and msg_id in handled_msg_id_set:
                pass  # already processed this exact ntfy message once
            else:
                try:
                    fs.complete_task(tid)
                    fs.recalculate()
                except Exception as e:
                    print(f"[ingest] complete signal failed for {tid}: {e}")
                if msg_id:
                    handled_msg_ids.append(msg_id)
                    handled_msg_id_set.add(msg_id)
        elif body.startswith("token:"):
            # Relay fallback for FCM token (re-)registration when the phone
            # isn't on the tailnet at install/token-rotation time -- see
            # RegisterTokenWorker.kt. Extract from raw_body, not the
            # lowercased body: FCM tokens are case-sensitive and commonly
            # contain their own literal colon (classic Instance ID format,
            # e.g. "dXXXX:APA91b..."), so this only ever splits on the
            # FIRST colon, same as the complete: handler above.
            new_token = raw_body.split(":", 1)[1].strip()
            if not fcm.register_token(new_token):
                print("[ingest] token signal had a malformed/missing token, skipping")
        elif body.startswith("ack:"):
            # Phone confirming it successfully persisted a pushed briefing/
            # next-tasks payload -- see fcm.py's _send and push_next_tasks
            # below. Both msg_type and the version hash are always
            # lowercase, so parsing from the lowercased `body` is fine here
            # (unlike token:/complete:, which need case preserved).
            parts = body.split(":", 2)
            if len(parts) == 3:
                _, msg_type, version = parts
                _mark_push_acked(msg_type, version)
            else:
                print("[ingest] malformed ack signal, skipping")
        st["ntfy_ts"] = max(st["ntfy_ts"], m.get("time", 0))
    st["handled_ntfy_msg_ids"] = handled_msg_ids[-1000:]
    frm = _utc_iso(14)
    try:
        comp = fs.list_items(itemType="task", completed=True, modifiedAfter=frm).get("items", [])
    except Exception:
        comp = []
    for t in comp:
        key = f"{t['id']}@{t.get('lastModified','')}"
        if key in logged:
            continue
        act = _classify(t.get("title"), t.get("notes"))
        if act:
            history.append(act, ts=(t.get("lastModified") or "")[:19], source="flowsavvy",
                           meta={"id": t["id"]})
        logged_ids.append(key)
        logged.add(key)
    st["logged_ids"] = logged_ids[-1000:]
    os.makedirs(os.path.dirname(sp), exist_ok=True)
    _save_json_atomic(sp, st)

def push_next_tasks(fs, now, args):
    """Pushes a fresh next-tasks + today's-events snapshot via FCM -- the
    Tailscale-independent counterpart to the widget's periodic direct pull
    of /api/next-tasks (NextTasksRefreshWorker.kt), which stays in place
    unchanged as a self-heal fallback for the rare case a push is dropped
    AND never acked. Skipped on the signal tier (~2 min, phone-tap catchup
    only): tick (~10 min) is plenty fresh for a task list, and signal
    firing this too would be 5x the FCM sends for no real freshness gain."""
    if args == ["signal"]:
        return
    schedule_items = gather._upcoming_schedule(fs, now)
    tasks = gather.next_tasks_input(fs, now, 8, schedule_items=schedule_items)
    events = gather.today_events_input(fs, now, schedule_items=schedule_items)
    gym_ring = gather.gym_ring_now(fs, now)
    snapshot = {"tasks": tasks, "events": events, "gym_ring": gym_ring}
    _push_with_ack("next_tasks", snapshot, lambda version: notify.push_next_tasks(tasks, events, gym_ring, version))

DOMAINS = {"gym": household.run_gym, "ynab": finance.run_ynab, "chore": household.run_chore,
           "catchup": planning.run_catchup, "homework": planning.run_homework,
           "spend": finance.run_spend, "social": social.run_social,
           "meal": household.run_meal, "digest": planning.run_digest,
           "canvas": canvas_domain.run_canvas, "briefing": planning.run_briefing,
           "deadlines": planning.run_deadlines, "cashflow": finance.run_cashflow}

# Tiers are keyed by latency need, not just cost. ingest() runs before every tier
# (so phone signals + completions are recorded each cycle no matter which fires).
TIERS = {
    # signal: the interactive path — a phone tap ("catchup") should re-pack the
    # day in ~2 min, not wait for the 10-min tick. register_task.ps1 fires this
    # every 2 minutes; it must stay a real key here or that scheduled task
    # silently becomes a no-op (ingest-only, catchup never dispatches).
    "signal": ["catchup"],
    # tick (~10 min): gym lives here so a slot blocked mid-day gets re-planned
    # the same day (engine only writes on real change, so frequent runs don't
    # churn the calendar); meal lives here so a "Have leftovers — skip" tap is
    # honored within minutes instead of at tomorrow's 7:10am (its weekly-create
    # path checks due-ness locally first, so most ticks are a no-op read).
    # spend and canvas are NOT here — spend only ever alerts once/day (10-min
    # YNAB+FlowSavvy fetches were 143 wasted round-trips/day), and canvas syncs
    # at most once/day by nature (new modules unlock at most daily).
    "tick":  ["catchup", "meal", "gym"],
    # briefing runs in the daily tier; _alert_once dedups so the first daily run
    # of the day (the morning one) sends it and later runs are no-ops. It reads
    # homework/spend/gym state the other daily domains refresh, so it's ordered
    # last to brief on the freshest numbers.
    "daily": ["ynab", "homework", "social", "chore", "meal", "spend", "digest", "canvas",
              "deadlines", "cashflow", "briefing"],
}

def _selected_domains(args, enabled):
    """Expand tier/domain arguments once, preserving order and rejecting typos."""
    selected = []
    explicit = {a for a in args if a in DOMAINS}
    unknown = [a for a in args if a not in TIERS and a not in DOMAINS]
    for arg in args:
        if arg in unknown:
            continue
        for name in TIERS.get(arg, [arg]):
            if name not in selected and (name in explicit or enabled.get(name, True)):
                selected.append(name)
    return selected, unknown

def _capture(fn, *args):
    """Run fn with stdout captured so its one-line summaries survive pythonw
    (where prints are silently discarded). Echoes to a real console if any."""
    buf = io.StringIO()
    try:
        with contextlib.redirect_stdout(buf):
            fn(*args)
    finally:
        txt = buf.getvalue()
        if sys.__stdout__:
            try:
                sys.__stdout__.write(txt); sys.__stdout__.flush()
            except Exception:
                pass
    return txt.strip()

RUNS_LOG_MAX_ROWS = 2000   # trim to the newest N runs every call -- cheap as a DELETE, unlike the old byte-size rewrite-whole-file check

def _append_run_log(rec):
    """Durable per-run audit trail: what each domain actually did every cycle."""
    p = os.path.join(history.ROOT, "private", "logs", "runs.jsonl")
    try:
        state_store.append_line(p, json.dumps(rec))
        state_store.trim_log(p, keep_newest=RUNS_LOG_MAX_ROWS)
    except Exception:
        pass

def _backup_state_db():
    """Copies private/logs/state.db out to config.SQLITE_BACKUP_DIR (a
    OneDrive folder by default) as a timestamped snapshot, then prunes to
    the newest SQLITE_BACKUP_KEEP snapshots. Replaces the old git
    auto-commit-to-the-private-submodule sync: a single binary state.db
    doesn't diff/compact in git the way many small JSON files' appends did,
    so backup moved out-of-git per the user's own call (2026-07-28) rather
    than accepting ever-growing opaque-diff commits.

    Uses sqlite3's built-in online-backup API (Connection.backup), not a
    raw file copy -- safe to call against a live WAL-mode DB that web.py's
    long-running server process may be reading/writing concurrently, unlike
    copying the file directly which could grab a torn snapshot mid-write.

    Best-effort and daily-tier only (called once/day, not every run): this
    is a full snapshot, not an incremental diff, so it doesn't need
    every-run cadence the way the state writes themselves do.
    """
    src_path = os.path.join(history.private_logs_dir(), "state.db")
    if not os.path.exists(src_path):
        return
    try:
        import sqlite3
        os.makedirs(config.SQLITE_BACKUP_DIR, exist_ok=True)
        stamp = datetime.datetime.now().strftime("%Y%m%dT%H%M%S")
        dest_path = os.path.join(config.SQLITE_BACKUP_DIR, f"state-{stamp}.db")
        src = sqlite3.connect(src_path)
        dest = sqlite3.connect(dest_path)
        try:
            src.backup(dest)
        finally:
            dest.close()
            src.close()
        snapshots = sorted(
            f for f in os.listdir(config.SQLITE_BACKUP_DIR)
            if f.startswith("state-") and f.endswith(".db")
        )
        for stale in snapshots[:-config.SQLITE_BACKUP_KEEP]:
            try:
                os.remove(os.path.join(config.SQLITE_BACKUP_DIR, stale))
            except OSError:
                pass
    except Exception as e:
        print(f"[backup] state.db snapshot failed: {e}")

def main():
    try:
        lock.acquire()
    except lock.Locked:
        print("another LifeOps run is active — skipping this cycle")
        return
    try:
        _run()
    finally:
        lock.release()

def _run():
    fs = FlowSavvy()
    yn = YNAB()
    now = datetime.datetime.now()
    _DIRTY[0] = False
    errors = {}
    hp = os.path.join(history.ROOT, "private", "logs", "last_run.json")

    # resume-gap detection: if we'd been down a while, say so on the way back up
    try:
        prev = datetime.datetime.fromisoformat(state_store.load_json(hp)["ts"])
        gap_h = (now - prev).total_seconds() / 3600
        if gap_h > 6:
            _alert_once("gap:" + now.date().isoformat(),
                        f"⚠️ LifeOps was down ~{gap_h:.0f}h (now back).", "high")
    except Exception:
        pass

    details = {}
    try:                              # always update the completion history first
        details["ingest"] = _capture(ingest, fs, now)
    except Exception as e:
        errors["ingest"] = str(e); details["ingest"] = f"ERROR: {e}"
        print(f"[ingest] ERROR: {e}")

    try:                              # watchdog: is the web panel itself alive?
        details["panel_health"] = _capture(check_panel_health, now)
    except Exception as e:
        errors["panel_health"] = str(e); details["panel_health"] = f"ERROR: {e}"
        print(f"[panel_health] ERROR: {e}")

    args = sys.argv[1:] or ["tick"]

    enabled = state_store.load_json(
        os.path.join(history.ROOT, "private", "logs", "domains.json"), default={})
    names, unknown = _selected_domains(args, enabled)
    if unknown:
        errors["dispatch"] = "unknown domain/tier: " + ", ".join(unknown)
        details["dispatch"] = "ERROR: " + errors["dispatch"]
    for name in names:
        fn = DOMAINS.get(name)
        if not fn:
            continue
        try:
            details[name] = _capture(fn, fs, yn, now)
        except Exception as e:
            errors[name] = str(e); details[name] = f"ERROR: {e}"
            print(f"[{name}] ERROR: {e}")

    if _DIRTY[0]:                      # ONE recalc per run, only if something changed
        try: fs.recalculate()
        except Exception as e: errors["recalculate"] = str(e)

    try:                              # keep the widget fresh without Tailscale --
        # AFTER domain dispatch + recalculate, not before, so a task this
        # same tick's domains just created/rescheduled/completed is
        # reflected in what actually gets pushed, instead of pushing a
        # snapshot that's already stale the moment it's sent.
        details["push_next_tasks"] = _capture(push_next_tasks, fs, now, args)
    except Exception as e:
        errors["push_next_tasks"] = str(e); details["push_next_tasks"] = f"ERROR: {e}"
        print(f"[push_next_tasks] ERROR: {e}")

    if errors:                        # fail loud — never silent
        try:
            _alert_once("health:" + now.date().isoformat(),
                        "⚠️ LifeOps errors — " + "; ".join(f"{k}: {v[:40]}" for k, v in errors.items()),
                        "high")
        except Exception as e:
            # A notification outage must not prevent the heartbeat and durable
            # run logs from recording the original failure.
            errors["health_alert"] = str(e)
    _heartbeat(not errors)
    rec = {"ts": now.isoformat(timespec="seconds"), "args": sys.argv[1:],
           "ran": names, "errors": errors, "details": details}
    state_store.save_json_atomic(hp, rec)
    _append_run_log(rec)
    if "daily" in args:
        _backup_state_db()

if __name__ == "__main__":
    main()
