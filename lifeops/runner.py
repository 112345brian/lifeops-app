"""Orchestrator — the cron entrypoint. Replaces the Claude 'daily-ops' routine.

Flow per domain: GATHER (clients) -> DECIDE (deterministic engine) -> APPLY (clients).
The LLM (lifeops.llm) is touched only for the judgment slivers.

Run:  python -m lifeops.runner          # all wired domains
      python -m lifeops.runner gym      # one domain
"""
import sys, os, re, io, json, datetime, contextlib, hashlib, requests
from . import config, ntfy, notify, gather, lock, history, adherence, actions, attention, fcm
from .flowsavvy import FlowSavvy
from .ynab import YNAB
from .engines import gym_engine, ynab_engine

_PRIO = {"urgent": "urgent", "high": "high", "none": "default"}

# Canvas flood guard: a healthy incremental sync creates a handful of tasks; the
# two duplicate-flood incidents (2026-07-03/06) each tried to create ~59 in one
# run after a state-loss re-sync. More than this in a single run is almost always
# a re-sync, not that many real new tasks — so hold and ask instead of flooding.
_CANVAS_FLOOD_MAX = 8

def _save_json_atomic(path, data):
    """Write via a temp file + os.replace so a kill/crash mid-write (pythonw
    under a task-scheduler timeout, no different) can't leave a truncated or
    empty state file -- the exact failure mode behind canvas_state.json
    going missing twice (2026-07-03, 2026-07-06) and canvas sync silently
    re-extracting the whole course from scratch each time."""
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f)
    os.replace(tmp, path)

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
    """Mark the schedule changed. _run() recalculates ONCE at the end instead of
    every domain churning FlowSavvy separately (your calendar stays stable)."""
    _DIRTY[0] = True

def _utc_iso(days_ago=0):
    return (datetime.datetime.now(datetime.timezone.utc)
            - datetime.timedelta(days=days_ago)).strftime("%Y-%m-%dT%H:%M:%SZ")

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
    sp = os.path.join(history.ROOT, "logs", "panel_health_state.json")
    st = {"down_since": None, "alerted": False}
    try:
        st.update(json.load(open(sp, encoding="utf-8")))
    except Exception:
        pass
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
    sp = os.path.join(history.ROOT, "logs", "ingest_state.json")
    st = {"ntfy_ts": 0, "logged_ids": [], "handled_ntfy_msg_ids": []}
    try:
        st.update(json.load(open(sp, encoding="utf-8")))
    except Exception:
        pass
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
            # next-tasks payload -- see fcm.py's _send and runner.py's
            # _push_with_ack. Both msg_type and the version hash are always
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

def _push_ack_state_file(msg_type):
    return os.path.join(history.ROOT, "logs", f"push_ack_{msg_type}.json")

def _load_push_ack_state(sp):
    """Returns the parsed state dict, or None if missing/corrupt/not a dict
    (a plain set-to-`{}` or malformed file must not crash the caller --
    _mark_push_acked in particular runs inside ingest()'s per-message loop,
    where an uncaught exception would also drop the rest of that poll
    batch's ntfy_ts/handled_ntfy_msg_ids persistence)."""
    try:
        with open(sp, encoding="utf-8") as f:
            state = json.load(f)
    except Exception:
        return None
    return state if isinstance(state, dict) else None

def _push_with_ack(msg_type, snapshot, push_fn):
    """Push-until-confirmed wrapper around an FCM send. messaging.send()
    succeeding only means Firebase ACCEPTED the message for delivery, not
    that the phone ever received it (data messages can be silently dropped
    under Doze, a force-stopped app, etc.), so this tracks a real receipt
    confirmation instead of trusting the send call: `snapshot` is hashed
    into a short version id, `push_fn(version)` is called to actually send
    it (returning whether anything was actually sent -- see fcm._send), and
    the version is recorded as unacked UNLESS nothing was sent (e.g. no FCM
    token registered yet), in which case there's nothing to await an ack
    for and it's marked acked immediately -- otherwise a device that has
    never registered a token would retry this every tick forever, since
    "unacked" would never become true. The client echoes the version back
    as an `ack:<type>:<version>` ntfy signal once it's successfully
    persisted (see ingest()'s ack handler below).

    Skips the actual send only when BOTH the content is unchanged since the
    last push AND that push was acked -- an unacked previous push keeps
    getting retried every call even if nothing new happened, since "unacked"
    is exactly the signal that the last attempt may not have landed. Same
    "don't do wasted round-trips on a fixed schedule" philosophy as spend/
    canvas being excluded from the tick tier elsewhere in this file governs
    the content-unchanged half of this check."""
    version = hashlib.sha1(json.dumps(snapshot, sort_keys=True).encode()).hexdigest()[:16]
    sp = _push_ack_state_file(msg_type)
    state = _load_push_ack_state(sp)
    if state and state.get("version") == version and state.get("acked"):
        return
    sent = push_fn(version)
    os.makedirs(os.path.dirname(sp), exist_ok=True)
    _save_json_atomic(sp, {"version": version, "acked": not sent})

def _mark_push_acked(msg_type, version):
    """Called from ingest()'s ack:<type>:<version> signal handler. Only
    updates state if `version` matches the currently-tracked push -- an ack
    for a superseded version (e.g. the phone was slow to respond and a
    newer snapshot already went out) must not mark the NEW one acked."""
    sp = _push_ack_state_file(msg_type)
    state = _load_push_ack_state(sp)
    if state and state.get("version") == version:
        state["acked"] = True
        _save_json_atomic(sp, state)

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
    tasks = gather.next_tasks_input(fs, now, 3, schedule_items=schedule_items)
    events = gather.today_events_input(fs, now, schedule_items=schedule_items)
    snapshot = {"tasks": tasks, "events": events}
    _push_with_ack("next_tasks", snapshot, lambda version: notify.push_next_tasks(tasks, events, version))

def _alert_once(key, text, priority="default", tags=None, actions=None, click_anchor=""):
    """Send an alert at most once per calendar day per key. The tick runs every
    10 min — without this, advisory alerts would spam. click_anchor: panel
    section to deep-link into when the notification is tapped (e.g. "gym") —
    "" links to the panel root, which is still useful (opens the app).
    Omitted entirely if PANEL_URL isn't configured."""
    sp = os.path.join(history.ROOT, "logs", "alert_state.json")
    st = {}
    try:
        st = json.load(open(sp, encoding="utf-8"))
    except Exception:
        pass
    today = datetime.date.today().isoformat()
    if st.get(key) == today:
        return
    notify.alert(text, priority=priority, tags=tags, actions=actions,
                 click_anchor=click_anchor)
    st[key] = today
    os.makedirs(os.path.dirname(sp), exist_ok=True)
    _save_json_atomic(sp, st)

_GYM_AUTO_MARKER = "Auto-scheduled by LifeOps"          # system-created gym blocks carry this
_GYM_DONE_KW = ("completed", "went", "did it", "attended", "✅")
_GYM_BACKFILL_TTL_DAYS = 14


def _gym_backfill(fs, now, gym_tasks):
    """Log a manually-added gym item as attendance ("I went").

    Lets a past session be backfilled by dropping a gym event/task on the
    calendar (e.g. from your phone), instead of only via the control-panel
    calendar. HYBRID detection: a gym item you created — i.e. one WITHOUT the
    "Auto-scheduled by LifeOps" marker — counts automatically once its slot is
    in the PAST/elapsed; an explicit completed/went/✅ keyword in the title or
    notes forces it regardless of date (so you can log a session you'll do
    later today, or a future-dated slot you actually attended).

    A future gym item with no keyword is treated as a *plan*, not attendance,
    and left alone.

    Logged items are tracked in gym_state.json and pruned after
    _GYM_BACKFILL_TTL_DAYS (~2 weeks) — kept meanwhile as visible confirmation
    the session registered (tasks are also renamed "✅ … (logged)"). Idempotent:
    an id already in logged_backfills is never re-logged, so every tick is safe.

    `gym_tasks`: the open "Gym"-titled tasks run_gym already fetched (reused to
    avoid a duplicate query). Events are fetched here. Returns the set of ids
    handled this run so run_gym's cleanup pass skips them (they are attendance,
    not misses to delete).
    """
    sp = os.path.join(history.ROOT, "logs", "gym_state.json")
    try:
        st = json.load(open(sp, encoding="utf-8"))
    except Exception:
        st = {}
    logged = dict(st.get("logged_backfills", {}))   # id -> date logged (iso)
    today_iso = now.date().isoformat()
    now_iso = now.isoformat()

    # ── prune items logged more than TTL days ago (kept until then as receipts) ──
    for iid, logged_on in list(logged.items()):
        try:
            age = (now.date() - datetime.date.fromisoformat(logged_on)).days
        except Exception:
            age = 0
        if age >= _GYM_BACKFILL_TTL_DAYS:
            try:
                fs.delete_item(iid); _touch()
            except Exception:
                pass
            logged.pop(iid, None)

    # ── detect new manual backfills among gym-titled tasks + events ──
    candidates = list(gym_tasks)
    try:
        candidates += fs.list_items(itemType="event", query="Gym",
                                    completed=False).get("items", [])
    except Exception:
        pass

    handled = set()
    for it in candidates:
        iid = it.get("id")
        title = (it.get("title") or "").strip()
        if not iid or iid in logged or not title.lower().startswith("gym"):
            continue
        notes = it.get("notes") or ""
        if _GYM_AUTO_MARKER in notes:
            continue   # a system-scheduled block, not a manual log
        start = it.get("startDateTime") or it.get("dueDateTime") or ""
        d = start[:10]
        end = it.get("endDateTime") or start
        elapsed = bool(d) and (d < today_iso or (d == today_iso and bool(end) and end < now_iso))
        has_kw = any(k in (title + " " + notes).lower() for k in _GYM_DONE_KW)
        if not (elapsed or has_kw):
            continue   # a future planned gym with no explicit "went" — leave it

        day = d or today_iso
        if not history.days_with("gym", day, day):   # don't double-log a day already recorded
            history.append("gym", ts=(start[:19] or None), source="manual")
        logged[iid] = today_iso
        handled.add(iid)
        # visible confirmation on tasks (the client has no update_event)
        if it.get("itemType") == "task" and not title.startswith("✅"):
            try:
                fs.update_task(iid, title=f"✅ {title} (logged)")
            except Exception:
                pass

    if handled or logged != st.get("logged_backfills", {}):
        st["logged_backfills"] = logged
        os.makedirs(os.path.dirname(sp), exist_ok=True)
        _save_json_atomic(sp, st)
    return handled


def run_gym(fs, yn, now):
    # clean up stale gym blocks; record genuine misses (no ping that day) so
    # adherence learning has data — by slot, to learn what he actually honors.
    # A block on a PAST date, or a TODAY block whose time has fully elapsed with no
    # workout logged, is a miss — delete it so it stops counting toward the target
    # and the engine can schedule a replacement (runs before gather, below).
    today = now.date().isoformat()
    now_iso = now.isoformat()
    did_today = bool(history.days_with("gym", today, today))
    # Fetch once and reuse for gather.gym_input below (same query it would
    # otherwise re-issue) — just filter out whatever this cleanup pass deletes.
    gym_open = [t for t in fs.list_items(itemType="task", query="Gym", completed=False).get("items", [])
                if (t.get("title") or "").startswith("Gym")]
    # Turn any manually-added gym item into logged attendance BEFORE the cleanup
    # below — otherwise a past session you dropped on the calendar would be
    # deleted and recorded as a miss. Handled items are dropped from gym_open so
    # cleanup leaves them (they're receipts, pruned on their own ~2-week TTL).
    backfilled = _gym_backfill(fs, now, gym_open)
    if backfilled:
        gym_open = [t for t in gym_open if t.get("id") not in backfilled]
    deleted_ids = set()
    delete_errors = []
    for t in gym_open:
        sd = t.get("startDateTime") or ""
        ed = t.get("endDateTime") or ""
        d = sd[:10]
        past_day = bool(d) and d < today
        elapsed_today = d == today and bool(ed) and ed < now_iso and not did_today
        if past_day or elapsed_today:
            if not history.days_with("gym", d, d) and not history.days_with("gym_missed", d, d):
                slot = "morning" if (sd[11:13] or "12") < "11" else "evening"
                history.append("gym_missed", ts=(sd[:19] or None), source="cleanup",
                               meta={"slot": slot})
            try:
                fs.delete_item(t["id"]); _touch(); deleted_ids.add(t["id"])
                actions.log("gym", f"removed stale gym block {d}",
                            t.get("title", "Gym"), item_id=t["id"], undoable=False)
            except Exception as e:
                delete_errors.append(f"{t['id']}: {e}")
    if deleted_ids:
        gym_open = [t for t in gym_open if t["id"] not in deleted_ids]
    # "Wind down" is a window-of-opportunity reminder, not a task you can do
    # late — once its block has passed, doing it holds no value, so prune it
    # the same way stale gym blocks are pruned above instead of letting it
    # sit as a stale/overdue item forever.
    wd_open = [t for t in fs.list_items(itemType="task", query="Wind down", completed=False).get("items", [])
              if (t.get("title") or "").startswith("Wind down")]
    for t in wd_open:
        sd = t.get("startDateTime") or ""
        ed = t.get("endDateTime") or ""
        d = sd[:10]
        past_day = bool(d) and d < today
        elapsed_today = d == today and bool(ed) and ed < now_iso
        if past_day or elapsed_today:
            try:
                fs.delete_item(t["id"]); _touch()
            except Exception as e:
                delete_errors.append(f"{t['id']}: {e}")
    gym_state_path = os.path.join(history.ROOT, "logs", "gym_state.json")
    try:
        sick_until = json.load(open(gym_state_path, encoding="utf-8")).get("sick_until")
    except Exception:
        sick_until = None
    inp = gather.gym_input(fs, now, sick_until=sick_until, gym_open=gym_open)
    out = gym_engine.plan(inp)
    gym_engine.log(inp, out)
    have = {s["date"] for s in inp["scheduled"]}
    for a in out["actions"]:
        if a["op"] == "create" and a["date"] not in have:
            _logged_create(fs, "gym", op=f"scheduled gym {a['date']}",
                           title="Gym", listId=config.LIST_PERSONAL, isAutoScheduled=False,
                           startDateTime=f"{a['date']}T{a['start']}:00",
                           endDateTime=f"{a['date']}T{a['end']}:00",
                           bufferBeforeMinutes=a["buffer_before"],
                           bufferAfterMinutes=a["buffer_after"],
                           notes="Auto-scheduled by LifeOps.")
        elif a["op"] == "delete":
            for s in inp["scheduled"]:
                if s["date"] == a["date"] and s.get("id"):
                    fs.delete_item(s["id"])
    # wind-down blocks (idempotent: skip if one already exists that day)
    if out["wind_down"]:
        existing = {(i.get("startDateTime") or "")[:10]
                    for i in fs.list_items(query="Wind down").get("items", [])}
        for w in out["wind_down"]:
            if w["date"] not in existing:
                _logged_create(fs, "gym", op=f"wind-down {w['date']}",
                               title="Wind down — early gym", listId=config.LIST_PERSONAL,
                               isAutoScheduled=False,
                               startDateTime=f"{w['date']}T{w['start']}:00",
                               endDateTime=f"{w['date']}T{w['end']}:00")
    if out["actions"] or out["wind_down"]:
        _touch()
    lvl = out["alert"]["level"]
    if lvl != "none":
        _alert_once("gym:" + lvl, out["alert"]["text"], _PRIO[lvl],
                    ["rotating_light"] if lvl == "urgent" else None, click_anchor="gym")
    print(f"[gym] {out['summary']}")
    if delete_errors:
        raise RuntimeError("gym cleanup: failed to delete stale item(s) — " + "; ".join(delete_errors))

def run_ynab(fs, yn, now):
    import datetime as _dt
    groups = yn.categories()
    cats = [c for g in groups for c in g["categories"]
            if not c.get("hidden") and not c.get("deleted")]
    since = (now.date() - _dt.timedelta(days=120)).isoformat()
    out = ynab_engine.plan(cats, yn.transactions(since_date=since),
                           yn.transactions(ttype="unapproved"), yn.month(),
                           cover_order=config.YNAB_COVER_ORDER, no_assign=config.YNAB_NO_ASSIGN)
    # novel payees: the ONLY LLM call, and only if a key is configured
    if config.ANTHROPIC_API_KEY and out["novel"]:
        from . import llm
        skip = set(config.YNAB_NO_ASSIGN)
        names = [c["name"] for c in cats if c["name"] not in skip]
        nid = {c["name"]: c["id"] for c in cats}
        for nv in out["novel"]:
            cat = llm.categorize_unknown(nv["payee"], nv["amount"], names)
            if cat in nid:
                out["categorize"].append({"id": nv["id"], "category_id": nid[cat]})
                if abs(nv["amount"]) * 1000 < ynab_engine.REVIEW:
                    out["approve"].append(nv["id"])
    catmap = {c["id"]: c["category_id"] for c in out["categorize"]}
    appr = set(out["approve"])
    updates = []
    for tid in set(catmap) | appr:
        u = {"id": tid}
        if tid in catmap: u["category_id"] = catmap[tid]
        if tid in appr:   u["approved"] = True
        updates.append(u)
    if updates:
        yn.update_transactions(updates)
    for mv in out["cover"]:
        yn.set_budgeted(mv["category_id"], mv["budgeted"])
    msg = (f"YNAB: categorized {len(out['categorize'])}, approved {len(appr)}, "
           f"{len(out['novel'])} novel, {len(out['holds'])} held, covered {len(out['cover'])} cat(s)")
    print("[ynab] " + msg)
    if appr or out["holds"]:
        notify.alert(msg)

def run_chore(fs, yn, now):
    sp = os.path.join(history.ROOT, "logs", "chore_state.json")
    st = {"processed": [], "lastRunUtc": "1970-01-01T00:00:00Z"}
    try: st.update(json.load(open(sp, encoding="utf-8")))
    except Exception: pass
    from .engines import chore_engine
    comp = fs.list_items(itemType="task", completed=True, modifiedAfter=st["lastRunUtc"]).get("items", [])
    completed = []
    for t in comp:
        if "[cycle:" not in (t.get("notes") or ""):
            continue
        completed.append({"id": t["id"], "title": t.get("title"), "notes": t.get("notes"),
                          "completed_date": (t.get("lastModified") or now.isoformat())[:10],
                          "durationMinutes": t.get("durationMinutes"),
                          "minLengthMinutes": t.get("minLengthMinutes"),
                          "listId": t.get("listId"), "priority": t.get("priority", "low"),
                          "schedulingHoursId": t.get("schedulingHoursId"),
                          "dueTime": (t.get("dueDateTime") or "")[11:16] or "20:00"})
    out = chore_engine.plan({"completed": completed, "processed": st["processed"]})
    for c in out["creates"]:
        _logged_create(fs, "chore", op="cycled chore",
                       title=c["title"], listId=c["listId"], durationMinutes=c["durationMinutes"],
                       minLengthMinutes=c["minLengthMinutes"], priority=c["priority"],
                       schedulingHoursId=c["schedulingHoursId"], notes=c["notes"],
                       dueDateTime=c["dueDateTime"], canBeStartedAt=c["canBeStartedAt"],
                       isAutoIgnored=False)
    if out["creates"]:
        _touch()
    st["processed"] = out["processed"]; st["lastRunUtc"] = _utc_iso()
    os.makedirs(os.path.dirname(sp), exist_ok=True); _save_json_atomic(sp, st)
    print(f"[chore] cycled {len(out['creates'])}")

def run_catchup(fs, yn, now):
    sp = os.path.join(history.ROOT, "logs", "catchup_state.json")
    st = {"lastHandled": 0}
    try: st.update(json.load(open(sp, encoding="utf-8")))
    except Exception: pass
    fired = any((m.get("message") or "").strip().lower() == "catchup"
                for m in ntfy.poll(since=st["lastHandled"]))
    if fired:
        fs.recalculate(reschedule_past=True)
        # The recalculate already happened -- a failed/rate-limited alert
        # (the ntfy-backed alert path raises on non-2xx) must not stop "lastHandled"
        # from being persisted below, or the same trigger message gets
        # replayed on every future tick, re-firing a full reschedule each
        # time until an alert happens to succeed.
        try:
            notify.alert("Catch-up: re-packed your whole schedule around what's left.")
        except Exception as e:
            print(f"[catchup] alert failed (non-fatal): {e}")
        print("[catchup] re-packed")
    else:
        print("[catchup] no trigger")
    st["lastHandled"] = int(now.timestamp())
    os.makedirs(os.path.dirname(sp), exist_ok=True); _save_json_atomic(sp, st)

def run_homework(fs, yn, now):
    from .engines import load_engine
    out = load_engine.plan(gather.homework_input(fs, now))
    for text, lvl in out["alerts"]:
        _alert_once("hw:" + text[:24], text, lvl)
    print(f"[homework] {len(out['alerts'])} alert(s)")

def run_spend(fs, yn, now):
    from .engines import spend_engine
    inp = gather.spend_input(fs, yn, now)
    out = spend_engine.plan(inp["events"], inp["fun_money"])
    if out["level"] != "none":
        _alert_once("spend", out["text"], out["level"])
    print(f"[spend] {out['level']} (fun=${inp['fun_money']:.0f}, {len(inp['events'])} events)")

def run_social(fs, yn, now):
    from .engines import social_engine
    sp = os.path.join(history.ROOT, "logs", "social_state.json")
    st = {"lastLock": "1970-01-01T00:00:00Z"}
    try: st.update(json.load(open(sp, encoding="utf-8")))
    except Exception: pass
    open_tasks = fs.list_items(itemType="task", completed=False).get("items", [])

    # LOCK-IN: completing a "Plan X" task confirms the proposed X block.
    for d in fs.list_items(itemType="task", completed=True, query="Plan",
                           modifiedAfter=st["lastLock"]).get("items", []):
        title = d.get("title") or ""
        if not title.startswith("Plan "):
            continue
        base = title[5:].strip()
        for t in open_tasks:
            if t.get("title") == f"{base} (proposed)":
                fs.create_task(title=base, listId=config.LIST_PERSONAL,
                               schedulingHoursId=t.get("schedulingHoursId") or config.SH_EVENINGS,
                               durationMinutes=t.get("durationMinutes") or 120,
                               dueDateTime=t.get("dueDateTime"), canBeStartedAt=t.get("canBeStartedAt"),
                               isAutoIgnored=False, notes="Locked in (LifeOps).")
                fs.delete_item(t["id"]); _touch()
                _alert_once(f"lock:{base}:{now.date()}", f"🔒 Locked in: {base}")
                break
    st["lastLock"] = _utc_iso()
    os.makedirs(os.path.dirname(sp), exist_ok=True); _save_json_atomic(sp, st)

    inp = gather.social_input(fs, now)
    out = social_engine.plan(inp["partner_days"], inp["friend_days"], inp["has_partner"],
                             inp["has_friend"], inp["good_days"], inp["is_protect_day"],
                             partner_name=config.PARTNER_NAME)
    for c in out["creates"]:
        base = config.PARTNER_TASK if c["kind"] == "partner" else config.FRIENDS_TASK
        date = c["date"]
        _logged_create(fs, "social", op=f"proposed {base} {date}",
                       title=f"{base} (proposed)", listId=config.LIST_PERSONAL,
                       schedulingHoursId=config.SH_EVENINGS, durationMinutes=120,
                       priority=config.PRIO_SOCIAL_PROPOSED,
                       dueDateTime=f"{date}T21:00:00", canBeStartedAt=f"{date}T17:00:00",
                       isAutoIgnored=False, notes="Proposed hangout — complete the 'Plan ...' task to lock it in.")
        plan_due = (datetime.date.fromisoformat(date) - datetime.timedelta(days=config.PLAN_LEAD_DAYS)).isoformat()
        _logged_create(fs, "social", op=f"plan-task for {base}",
                       title=f"Plan {base}", listId=config.LIST_PERSONAL,
                       schedulingHoursId=config.SH_EVENINGS, durationMinutes=15,
                       priority=config.PRIO_SOCIAL_PLAN,
                       dueDateTime=f"{plan_due}T21:00:00", isAutoIgnored=False,
                       notes="Reach out + arrange it. Completing this LOCKS IN the hangout.")
    if out["creates"]:
        _touch()
    for n in out["nudges"]:
        _alert_once("social:" + n[:24], n)
    print(f"[social] lock-check done; proposed {len(out['creates'])}; nudges {len(out['nudges'])}")

def run_meal(fs, yn, now):
    # Always drain the ntfy "meal-skip" cursor every tick, even when not due —
    # it's a single cheap poll, and freezing st["lastSkip"] while not due (an
    # earlier version of this fix did exactly that) let a stray tap sit
    # unconsumed for days, then replay against a LATER week the moment `due`
    # flips true again, spuriously wiping that week's freshly-created tasks.
    sp = os.path.join(history.ROOT, "logs", "meal_state.json")
    st = {"lastSkip": 0}
    try: st.update(json.load(open(sp, encoding="utf-8")))
    except Exception: pass
    skipped = any((m.get("message") or "").strip().lower() == "meal-skip"
                  for m in ntfy.poll(since=st["lastSkip"]))
    st["lastSkip"] = int(now.timestamp())
    os.makedirs(os.path.dirname(sp), exist_ok=True); _save_json_atomic(sp, st)

    # Everything past this point is FlowSavvy work (the actually expensive,
    # rate-limited part) — only worth doing while meal is genuinely due. A
    # skip tap that arrives outside the due window is drained above (so it
    # can't replay later) but intentionally not acted on: there's nothing to
    # delete yet, and honoring it here would incorrectly reset the "handled
    # this week" timer for a week that hasn't started.
    last = history.last("meal")
    due = not last or (now - datetime.datetime.fromisoformat(last)).days >= 6
    if not due:
        print("[meal] not due"); return

    if skipped:
        for t in fs.list_items(itemType="task", completed=False).get("items", []):
            if t.get("title") in ("Groceries", "Meal prep") and "LifeOps" in (t.get("notes") or ""):
                try: fs.delete_item(t["id"])
                except Exception: pass
        history.append("meal", source="skipped")   # counts as handled this week
        _touch()
        print("[meal] skipped (leftovers) — cleared this week"); return

    if fs.list_items(itemType="task", query="Meal prep", completed=False).get("items", []):
        print("[meal] already planned"); return
    d0 = now.date().isoformat()
    d3 = (now.date() + datetime.timedelta(days=3)).isoformat()
    d4 = (now.date() + datetime.timedelta(days=4)).isoformat()
    g = _logged_create(fs, "meal", op="added groceries",
                       title="Groceries", listId=config.LIST_PERSONAL,
                       schedulingHoursId=config.SH_PERSONAL, durationMinutes=60,
                       priority=config.PRIO_MEAL, dueDateTime=f"{d3}T19:00:00",
                       canBeStartedAt=f"{d0}T00:00:00",
                       isAutoIgnored=False, notes="Meal-prep week (LifeOps).")
    _logged_create(fs, "meal", op="added meal prep",
                   title="Meal prep", listId=config.LIST_PERSONAL,
                   schedulingHoursId=config.SH_PERSONAL, durationMinutes=120,
                   priority=config.PRIO_MEAL, dueDateTime=f"{d4}T19:00:00",
                   canBeStartedAt=f"{d3}T00:00:00",
                   blockedByIds=[g["id"]] if g.get("id") else None,
                   isAutoIgnored=False, notes="Cook after groceries (LifeOps).")
    _touch()
    _alert_once("meal", "Meal-prep week — Groceries + cook added.",
                actions=[("Have leftovers — skip", "meal-skip")])
    print("[meal] created groceries + cook")

def run_digest(fs, yn, now):
    """Weekly accountability digest (Sundays) — the one LLM-as-coach use."""
    if now.strftime("%a") != "Sun" or not config.ANTHROPIC_API_KEY:
        print("[digest] skip (not Sunday / no key)"); return
    mon = now.date() - datetime.timedelta(days=now.date().weekday())
    sun = mon + datetime.timedelta(days=6)
    wk = lambda a: len(history.days_with(a, mon.isoformat(), sun.isoformat()))
    facts = {"gym_done": wk("gym"), "gym_target": 4, "gym_adherence": adherence.gym(now),
             "chores_done": wk("laundry") + wk("clean_room") + wk("clean_bathroom"),
             "saw_partner": wk("partner"), "saw_friends": wk("friends")}
    from . import llm
    try:
        _alert_once("digest:" + now.date().isoformat(), llm.weekly_digest(facts))
        print("[digest] sent")
    except Exception as e:
        print(f"[digest] error: {e}")

def run_briefing(fs, yn, now):
    """Daily morning briefing (once/day) — the daily counterpart to the weekly
    Sunday digest. Surfaces the risk/forecast the engines ALREADY compute
    (at-risk coursework, today's load vs. capacity, gym status, discretionary
    money) as one glanceable ntfy + a panel card, so a looming deadline or a
    dwindling budget shows up proactively instead of only when you go looking.
    Inspired by Motion's deadline-risk surfacing + Sunsama's morning plan."""
    if not config.ANTHROPIC_API_KEY:
        print("[briefing] skip (no key)"); return
    from .engines import load_engine
    from . import llm
    today = now.date()

    # coursework: at-risk items + due-today + total load in the next 7 days
    # (reuse the homework watcher's own inputs so the two never disagree)
    hw = gather.homework_input(fs, now)
    risks = [t for t, _lvl in load_engine.plan(hw)["alerts"]]
    # generalized "won't fit" crunch across ALL deadline tasks (not just coursework)
    risks += [t for t, _lvl in load_engine.deadline_risk(gather.deadline_input(fs, now))["alerts"]]
    due_today = [a["title"] for a in hw if 0 <= a.get("due_in_h", 1e9) <= 24]
    overdue = [{"title": a["title"], "due_in_h": a.get("due_in_h"),
                "due": a.get("due")}
               for a in hw if a.get("due_in_h", 0) < 0 and a.get("remaining_min", 0) > 0]
    load_7d_h = round(sum(a.get("remaining_min", 0) for a in hw
                          if a.get("due_in_days", 99) <= 7) / 60.0, 1)

    # gym: sessions in the trailing 7 days vs target, and whether trained
    # today. Rolling, not calendar-week -- matches gym_input's own window
    # (engines/gym_engine's actual scheduling decisions already reason in
    # "≈N sessions in any trailing 7 days," specifically to avoid the
    # Monday reset letting the count get gamed across the boundary). The
    # briefing stat used to use a calendar week instead, which disagreed
    # with how the system actually judges "healthy" cadence and could look
    # arbitrarily bad/good purely based on which weekday it happened to be
    # (confirmed 2026-07-12).
    trail_start = (today - datetime.timedelta(days=6)).isoformat()
    trail_end = today.isoformat()
    gym_dates = history.days_with("gym", trail_start, trail_end)
    gym_last_7d = len(gym_dates - history.days_with("gym_skip", trail_start, trail_end))
    trained_today = today.isoformat() in gym_dates

    # money: discretionary balance + the nearest upcoming paid social events
    try:
        sp = gather.spend_input(fs, yn, now)
        fun_money = round(sp.get("fun_money", 0))
        near = sorted(sp.get("events", []), key=lambda e: e.get("days_until", 99))[:2]
        # No dollar figure in the label -- a calendar event is assumed
        # already paid for; cost still feeds fun_money's margin math below,
        # just never gets narrated (2026-07-12: user doesn't want ticket
        # prices mentioned at all).
        upcoming = [f"{e['label']} in {e['days_until']}d" for e in near]
    except Exception:
        fun_money, upcoming = None, []

    facts = {"date": today.isoformat(), "weekday": now.strftime("%A"),
             "coursework_at_risk": risks, "due_today": due_today,
             "overdue": overdue,
             "coursework_hours_next_7d": load_7d_h,
             "gym_last_7d": gym_last_7d, "gym_target": 4,
             "trained_today": trained_today,
             "discretionary_dollars": fun_money, "upcoming_paid_events": upcoming}
    facts["attention"] = attention.compute(facts)
    try:
        text = llm.daily_briefing(facts)
    except Exception as e:
        print(f"[briefing] llm error: {e}"); return
    # "#briefing" (not "briefing") -- panel_url() joins this straight onto
    # the base path, and the briefing card lives on the Home page as an
    # anchor, not its own route, so this needs the bare-path+anchor form.
    _alert_once("briefing:" + today.isoformat(), text, click_anchor="#briefing")
    # Second, silent push carrying the same {date, text, facts} the widget
    # renders -- via FCM (not ntfy): a manifest-registered receiver for
    # ntfy's implicit io.heckel.ntfy.MESSAGE_RECEIVED broadcast can't wake a
    # stopped app on modern Android, so it was unreliable. FCM's delivery
    # path is OS-privileged and wakes the app regardless. No-ops if the
    # widget hasn't registered a device token yet. Retried on the next
    # daily run if never acked -- see _push_with_ack.
    try:
        briefing_snapshot = {"date": today.isoformat(), "text": text, "facts": facts}
        _push_with_ack("briefing", briefing_snapshot,
                       lambda version: notify.push_briefing(today.isoformat(), text, facts, version))
    except Exception as e:
        print(f"[briefing] fcm send error: {e}")
    # persist for the panel (survives the once/day ntfy dedup)
    bp = os.path.join(history.ROOT, "logs", "briefing.json")
    os.makedirs(os.path.dirname(bp), exist_ok=True)
    _save_json_atomic(bp, {"date": today.isoformat(), "text": text, "facts": facts})
    print("[briefing] sent")

def run_deadlines(fs, yn, now):
    """Generalized deadline-risk watchdog (Motion-style) over ALL deadline-bearing
    tasks, not just coursework: alerts when the cumulative work due by a deadline
    can't realistically fit before it. deadline_risk emits only the earliest
    binding deadline, so this pushes at most one crunch alert per day."""
    from .engines import load_engine
    out = load_engine.deadline_risk(gather.deadline_input(fs, now))
    for text, lvl in out["alerts"]:
        _alert_once("deadline:" + now.date().isoformat(), text, lvl)
    print(f"[deadlines] {len(out['alerts'])} at-risk")

def run_cashflow(fs, yn, now):
    """Panel-ONLY forward discretionary-balance projection (Monarch-style). No
    notifications by design — persists a 4-week curve the panel renders, from the
    current discretionary balance minus known upcoming paid social events."""
    try:
        sp = gather.spend_input(fs, yn, now)
    except Exception as e:
        print(f"[cashflow] gather error: {e}"); return
    bal = round(sp.get("fun_money", 0))
    events = sorted(sp.get("events", []), key=lambda e: e.get("days_until", 999))
    running, weeks = bal, []
    for w in range(4):
        spent = sum(e.get("cost", 0) for e in events
                    if w * 7 <= e.get("days_until", 999) < (w + 1) * 7)
        running -= spent
        weeks.append({"week": w + 1, "spent": spent, "balance": round(running)})
    proj = {"date": now.date().isoformat(), "start_balance": bal, "weeks": weeks,
            "dips_below_zero": any(wk["balance"] < 0 for wk in weeks),
            "events": [{"label": e.get("label"), "days_until": e.get("days_until"),
                        "cost": e.get("cost")} for e in events[:6]]}
    bp = os.path.join(history.ROOT, "logs", "cashflow.json")
    os.makedirs(os.path.dirname(bp), exist_ok=True)
    _save_json_atomic(bp, proj)
    print(f"[cashflow] 4wk projected; dips={proj['dips_below_zero']}")

def run_canvas(fs, yn, now):
    """Sync newly-unlocked Canvas modules → FlowSavvy tasks.

    Runs once per day. Two credential paths, tried in order:
      1. CANVAS_TOKEN (real API token) — used directly if set.
      2. lifeops.canvas_browser (authenticated Playwright session) — used
         when no token exists (JHU disables self-service tokens). Requires
         a one-time interactive login: `python scripts/canvas_login.py`.
         If that session has since expired, alerts instead of failing quiet.
    State: logs/canvas_state.json — tracks which modules have been synced
    and which task titles already exist (prevents duplicates across runs).
    """
    from .canvas import strip_html
    from .engines import canvas_engine
    from . import llm

    if config.CANVAS_TOKEN:
        from .canvas import Canvas
        _canvas_sync(Canvas(), strip_html, canvas_engine, llm, fs, now)
        return

    from . import canvas_browser
    if not canvas_browser.profile_exists():
        print("[canvas] skip (no CANVAS_TOKEN and no browser profile — "
              "run `python scripts/canvas_login.py` once)")
        return
    try:
        with canvas_browser.BrowserCanvas() as cv:
            if not cv.logged_in():
                _alert_once("canvas:session:" + now.date().isoformat(),
                            "Canvas session expired — tap to re-login from the control panel, "
                            "or run `python scripts/canvas_login.py`.", "high",
                            click_anchor="settings#accounts")
                print("[canvas] skip (browser session expired)")
                return
            _canvas_sync(cv, strip_html, canvas_engine, llm, fs, now)
    except Exception as e:
        print(f"[canvas] browser session error: {e}")


def _canvas_sync(cv, strip_html, canvas_engine, llm, fs, now):
    """Shared sync body — `cv` is either canvas.Canvas or
    canvas_browser.BrowserCanvas; both expose the same modules/assignments/
    page/announcements interface, so this logic doesn't care which."""
    sp = os.path.join(history.ROOT, "logs", "canvas_state.json")
    st = {"synced_modules": [], "task_titles": []}
    try:
        st.update(json.load(open(sp, encoding="utf-8")))
    except Exception:
        pass
    synced  = set(st["synced_modules"])                 # legacy dedup key: module NUMBER (rename/collision-fragile)
    synced_ids = set(st.get("synced_module_ids", []))   # stable dedup key: Canvas module id
    # `task_titles` persists ONLY the titles THIS engine actually created (see the save block
    # below). `seen_titles` is the run-local dedup working set: seeded from those persisted
    # titles, then augmented with completed_cache + the live-fetched FlowSavvy titles for THIS
    # run only. Those live sets are deliberately never persisted — folding them back into
    # task_titles grew it without bound across the whole multi-semester course and, via
    # canvas_engine's 0.93 fuzzy match, silently dropped a legitimately-new task as a
    # "duplicate" of a long-gone one. It also defeated the 20-day completed_cache eviction: an
    # evicted title lived on forever inside task_titles, so the cache never actually forgot.
    created_persisted = set(st["task_titles"])
    seen_titles = set(created_persisted)
    today = now.date()

    # 20-day rolling cache of completed task titles (avoids re-fetching history each run)
    cutoff = (today - datetime.timedelta(days=20)).isoformat()
    completed_cache = {title: dt for title, dt in st.get("completed_cache", {}).items()
                       if dt >= cutoff}
    seen_titles.update(completed_cache)

    # pull live FlowSavvy titles — both incomplete and recently completed.
    # No `query` filter: LIST_COURSE is a dedicated Canvas-sourced list, so
    # scoping by listId alone is sufficient — a substring filter like "M0"
    # would silently stop matching once modules reach M10+.
    existing = []
    try:
        existing = fs.list_items(itemType="task", listId=config.LIST_COURSE,
                                 completed=False).get("items", [])
        seen_titles.update(t.get("title", "") for t in existing)
    except Exception:
        pass

    # `synced_modules` empty while FlowSavvy already holds a real course
    # list is the signature of a lost/corrupted canvas_state.json (happened
    # 2026-07-03 and again 2026-07-06 -- the latter silently re-extracted
    # every unlocked module via the LLM and created 5 near-duplicate M07
    # readings that differed from the originals by more than the title dedup
    # could catch, since re-extraction isn't byte-stable). This can't
    # reliably be recovered from here (we don't know which modules were
    # truly already synced) but it should never again fail silently.
    if not synced and not synced_ids and len(existing) >= 5:
        _alert_once("canvas:state-reset:" + today.isoformat(),
                    f"⚠️ Canvas sync state looks lost (0 modules marked "
                    f"synced, but {len(existing)} tasks already exist in "
                    f"FlowSavvy) — about to re-extract every unlocked "
                    f"module from scratch. Check logs/canvas_state.json "
                    f"before this creates near-duplicates.", "high")
    try:
        done = fs.list_items(itemType="task", listId=config.LIST_COURSE,
                             completed=True).get("items", [])
        for t in done:
            title = t.get("title", "")
            if title and title not in completed_cache:
                completed_cache[title] = (t.get("lastModified") or today.isoformat())[:10]
        seen_titles.update(completed_cache)
    except Exception:
        pass

    try:
        modules = cv.modules()
    except Exception as e:
        # Same severity as the browser-session-expired path (both credential
        # paths must alert identically on auth failure — a revoked/stale
        # CANVAS_TOKEN should not degrade to print-only, which is silently
        # discarded under pythonw).
        _alert_once("canvas:token:" + now.date().isoformat(),
                    f"Canvas sync failed (token may be revoked/expired): {e}", "high")
        print(f"[canvas] failed to fetch modules: {e}"); return

    modules_data = []
    claimed_nums = set()   # legacy nums already matched to a stable id this run (collision guard)
    for mod in modules:
        name = mod.get("name", "") or ""
        # Word-anchored keyword extraction ("Module N"/"M N"), first-digit fallback —
        # see canvas_engine.module_number (an un-anchored "m" matched the trailing "m"
        # of "Midterm 1 - Module 9" and returned 1, mis-numbering the module).
        num = canvas_engine.module_number(name)
        if num is None:
            continue   # unnumbered utility module ("Start Here", "Syllabus", ...) — nothing to sync
        mod_id = mod["id"]

        # Dedup on the STABLE Canvas module id, not the number scraped from the
        # (renameable) module name. Keying on `num` alone let a mid-term
        # rename/renumber make an already-synced module look new (→ re-synced,
        # duplicate tasks) and let a second module sharing a scraped number
        # ("Supplementary readings for Module 5") get silently skipped forever.
        if mod_id in synced_ids:
            claimed_nums.add(num)   # this num is spoken for by a known id — free any collider below
            continue
        # Legacy migration: pre-id state only knows synced NUMBERS. Honor a legacy
        # num ONCE per run (the first module bearing it is the one we actually synced
        # before) and adopt that module's stable id, so future runs key on the id. A
        # second module with the same num is a genuine collision, not the synced one.
        if num in synced and num not in claimed_nums:
            claimed_nums.add(num)
            synced_ids.add(mod_id)
            continue

        unlock_str = mod.get("unlock_at") or mod.get("published_at") or ""
        unlock_date = canvas_engine._parse_date(unlock_str) or today
        if unlock_date > today:
            continue

        items = mod.get("items") or []
        modules_data.append({
            "module_num":  num,
            "unlock_date": unlock_date,
            "_mod_items":  items,
            "_mod_id":     mod_id,
        })

    if not modules_data:
        # No new modules to plan/create — but do NOT return here. The due-date
        # change check and announcement check below must run on EVERY sync, not
        # just when a module unlocks. Once a course is fully synced modules_data
        # is empty every run, and an early return here silently disabled due-date
        # re-sync for the rest of the semester (exactly when instructors most
        # often shift deadlines). plan([]) and the create loop are no-ops on an
        # empty list, so the flat flow below stays correct.
        print("[canvas] no new modules to sync")

    # bulk fetch all assignments once — required both to create new-module tasks
    # AND to re-check due dates on already-synced tasks below, so this fetch must
    # happen even on the no-new-modules path.
    try:
        all_assignments = {a["id"]: a for a in cv.assignments()}
    except Exception as e:
        print(f"[canvas] failed to fetch assignments: {e}"); return

    # populate assignments + readings per module
    for mod in modules_data:
        items = mod.pop("_mod_items")
        # keep "_mod_id" on the dict — needed at save time to persist the stable
        # id into synced_module_ids. plan() ignores unknown keys.

        asgns = []
        reading_page_slugs = []
        for item in items:
            if item.get("type") == "Assignment":
                cid = item.get("content_id")
                if cid and cid in all_assignments:
                    asgns.append(all_assignments[cid])
            elif item.get("type") == "Page":
                t = (item.get("title") or "").lower()
                if any(w in t for w in ("reading", "resource", "material")):
                    slug = item.get("page_url") or item.get("url", "").split("/pages/")[-1]
                    if slug:
                        reading_page_slugs.append(slug)

        # extract readings from pages via LLM
        readings = []
        for slug in reading_page_slugs:
            try:
                page = cv.page(slug)
                text = strip_html(page.get("body") or "")
                if text:
                    readings.extend(llm.extract_readings(text, mod["module_num"]))
            except Exception as e:
                print(f"[canvas] page {slug}: {e}")

        mod["assignments"] = asgns
        mod["readings"]    = readings

    # plan
    result = canvas_engine.plan(modules_data, seen_titles, today)

    # Flood guard — HOLD instead of flooding when a run wants to create an
    # implausible number of tasks (the state-loss re-sync signature). Write the
    # intended creates to a pending file, alert with a one-tap approve, and skip
    # both creation AND the state save this run (so nothing is marked synced and
    # the next unapproved run re-triggers the guard). Approving from the panel
    # sets `flood_ack` = today in canvas_state.json and re-runs canvas; the guard
    # bypasses for that day and creates normally — no replay logic needed.
    creates = result.get("creates", [])
    pp = os.path.join(history.ROOT, "logs", "canvas_pending.json")
    if len(creates) > _CANVAS_FLOOD_MAX and st.get("flood_ack") != today.isoformat():
        _save_json_atomic(pp, {"at": now.isoformat(), "count": len(creates),
                               "report": result.get("report", ""),
                               "titles": [c.get("title") for c in creates]})
        _alert_once("canvas:flood:" + today.isoformat(),
                    f"⚠️ Canvas sync wanted to create {len(creates)} tasks — held as suspicious "
                    f"(usually a state-loss re-sync, not that many real new tasks). "
                    f"Review + approve in the panel.", "high", click_anchor="settings#canvas")
        print(f"[canvas] HELD {len(creates)} creates (flood guard > {_CANVAS_FLOOD_MAX})")
        return
    # Cleared the guard: consume the one-shot ack and drop any stale pending file.
    st.pop("flood_ack", None)
    if os.path.exists(pp):
        try: os.remove(pp)
        except Exception: pass

    # apply: create tasks in FlowSavvy
    created_titles = {}   # title → id (for dependency wiring)
    for spec in result["creates"]:
        dep_title = spec.pop("_dep_title", None)
        kwargs = {
            "listId":            config.LIST_COURSE,
            "schedulingHoursId": config.SH_COURSE,
            "isAutoScheduled":   True,
            **spec,
        }
        if dep_title and dep_title in created_titles:
            # FlowSavvy's real dependency field (same one run_meal uses)
            kwargs["blockedByIds"] = [created_titles[dep_title]]
        try:
            r = fs.create_task(**kwargs)
            tid = (r or {}).get("id") or (r or {}).get("item", {}).get("id")
            if tid:
                created_titles[spec["title"]] = tid
            # durable audit trail — creations must survive discarded stdout.
            # creates_task=True tells the History page's undo button that
            # meta.id is a FlowSavvy item THIS log entry created (so undo
            # should delete it too), as opposed to an id that just
            # references something that already existed (e.g. a completed
            # task an ntfy/flowsavvy-sourced log entry points at).
            history.append("course_task", source="canvas",
                           meta={"id": tid, "title": spec["title"], "creates_task": bool(tid)})
            actions.log("canvas", "created course task", spec["title"],
                        item_id=tid, undoable=True)
            _touch()
        except Exception as e:
            print(f"[canvas] create failed for {spec.get('title','?')}: {e}")

    # check for due-date changes in already-synced assignments
    try:
        for a in all_assignments.values():
            name = a.get("name", "")
            new_due = a.get("due_at", "")
            if not new_due:
                continue
            # search FlowSavvy for matching title fragments
            for item in fs.list_items(itemType="task", listId=config.LIST_COURSE,
                                      completed=False, query=name[:30]).get("items", []):
                # only the unsplit / final task carries the Canvas due date;
                # phase tasks ("… — Draft") have staggered dues — leave them be
                title = item.get("title") or ""
                bare = title.rstrip("]").split(" [")[0]     # strip "[AS.…]" course tag
                if not (title.endswith(name) or bare.endswith(name)):
                    continue
                fs_due = (item.get("dueDateTime") or "")[:10]
                canvas_due = new_due[:10]
                if fs_due and canvas_due and fs_due != canvas_due:
                    fs.update_task(item["id"], dueDateTime=f"{canvas_due}T23:59:00")
                    _touch()
                    print(f"[canvas] updated due date for {title}: {fs_due}→{canvas_due}")
    except Exception as e:
        print(f"[canvas] change-check error: {e}")

    # check announcements
    try:
        import datetime as _dt
        since = (_dt.date.today() - _dt.timedelta(days=7)).isoformat()
        announcements = cv.announcements(since_date=since)
        for ann in announcements[:3]:
            title = ann.get("title", "")
            posted = (ann.get("posted_at") or "")[:10]
            print(f"[canvas] announcement ({posted}): {title}")
    except Exception:
        pass

    # save state
    for mod in modules_data:
        synced.add(mod["module_num"])       # legacy num set (display + state-loss heuristic)
        synced_ids.add(mod["_mod_id"])      # stable id set (authoritative dedup key)
    # Persist ONLY engine-created titles: what prior runs created plus what this run
    # created. Deliberately NOT `seen_titles` — that also holds the live FlowSavvy
    # incomplete/completed titles and the completed_cache, which are run-local dedup
    # inputs, not a record of what this engine produced. Folding them in grew
    # task_titles without bound and nullified the completed_cache eviction.
    created_persisted.update(created_titles.keys())
    st["synced_modules"]     = sorted(synced)
    st["synced_module_ids"]  = sorted(synced_ids)
    st["task_titles"]        = sorted(created_persisted)
    st["completed_cache"]    = completed_cache
    os.makedirs(os.path.dirname(sp), exist_ok=True)
    _save_json_atomic(sp, st)

    n = len(created_titles)
    print(f"[canvas] {n} task(s) created\n{result['report']}")


DOMAINS = {"gym": run_gym, "ynab": run_ynab, "chore": run_chore, "catchup": run_catchup,
           "homework": run_homework, "spend": run_spend, "social": run_social,
           "meal": run_meal, "digest": run_digest, "canvas": run_canvas,
           "briefing": run_briefing, "deadlines": run_deadlines, "cashflow": run_cashflow}

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

RUNS_LOG_MAX = 2_000_000   # ~2MB before trimming to the newest 2000 runs

def _append_run_log(rec):
    """Durable per-run audit trail: what each domain actually did every cycle."""
    p = os.path.join(history.ROOT, "logs", "runs.jsonl")
    try:
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "a", encoding="utf-8") as f:
            f.write(json.dumps(rec) + "\n")
        if os.path.getsize(p) > RUNS_LOG_MAX:
            lines = open(p, encoding="utf-8").read().splitlines()
            with open(p, "w", encoding="utf-8") as f:
                f.write("\n".join(lines[-2000:]) + "\n")
    except Exception:
        pass

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
    hp = os.path.join(history.ROOT, "logs", "last_run.json")

    # resume-gap detection: if we'd been down a while, say so on the way back up
    try:
        prev = datetime.datetime.fromisoformat(json.load(open(hp))["ts"])
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

    try:                              # keep the widget fresh without Tailscale
        details["push_next_tasks"] = _capture(push_next_tasks, fs, now, args)
    except Exception as e:
        errors["push_next_tasks"] = str(e); details["push_next_tasks"] = f"ERROR: {e}"
        print(f"[push_next_tasks] ERROR: {e}")
    try:
        enabled = json.load(open(os.path.join(history.ROOT, "logs", "domains.json"), encoding="utf-8"))
    except Exception:
        enabled = {}
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
    os.makedirs(os.path.dirname(hp), exist_ok=True)
    json.dump(rec, open(hp, "w", encoding="utf-8"))
    _append_run_log(rec)

if __name__ == "__main__":
    main()
