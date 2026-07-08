"""Orchestrator — the cron entrypoint. Replaces the Claude 'daily-ops' routine.

Flow per domain: GATHER (clients) -> DECIDE (deterministic engine) -> APPLY (clients).
The LLM (lifeops.llm) is touched only for the judgment slivers.

Run:  python -m lifeops.runner          # all wired domains
      python -m lifeops.runner gym      # one domain
"""
import sys, os, re, io, json, datetime, contextlib
from . import config, ntfy, gather, lock, history, adherence
from .flowsavvy import FlowSavvy
from .ynab import YNAB
from .engines import gym_engine, ynab_engine

_PRIO = {"urgent": "urgent", "high": "high", "none": "default"}

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

def _classify(title):
    t = (title or "").lower()
    for k, v in [("gym", "gym"), ("laundry", "laundry"), ("clean room", "clean_room"),
                 ("clean bathroom", "clean_bathroom"), ("tidy car", "tidy_car"),
                 ("car wash", "car_wash"), ("oil change", "oil"),
                 (config.PARTNER_TASK.lower(), "partner"),
                 ("friends", "friends"), ("meal prep", "meal"), ("groceries", "groceries"),
                 ("studio", "studio")]:
        if k in t:
            return v
    # coursework: canvas-created tasks look like "M07: ... [AS.470.703.81.SU26]"
    if re.match(r"^m\d{2}\b", t) or "[as." in t:
        return "course"
    return None

def ingest(fs, now):
    """Harvest completions from ntfy signals + FlowSavvy check-offs into the
    permanent history log. Runs every cycle; cheap and deduped."""
    sp = os.path.join(history.ROOT, "logs", "ingest_state.json")
    st = {"ntfy_ts": 0, "logged_ids": []}
    try:
        st.update(json.load(open(sp, encoding="utf-8")))
    except Exception:
        pass
    logged = set(st["logged_ids"])
    for m in ntfy.poll(since=st["ntfy_ts"]):
        body = (m.get("message") or "").strip().lower()
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
        st["ntfy_ts"] = max(st["ntfy_ts"], m.get("time", 0))
    frm = _utc_iso(14)
    try:
        comp = fs.list_items(itemType="task", completed=True, modifiedAfter=frm).get("items", [])
    except Exception:
        comp = []
    for t in comp:
        key = f"{t['id']}@{t.get('lastModified','')}"
        if key in logged:
            continue
        act = _classify(t.get("title"))
        if act:
            history.append(act, ts=(t.get("lastModified") or "")[:19], source="flowsavvy",
                           meta={"id": t["id"]})
        logged.add(key)
    st["logged_ids"] = list(logged)[-1000:]
    os.makedirs(os.path.dirname(sp), exist_ok=True)
    _save_json_atomic(sp, st)

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
    ntfy.alert(text, priority=priority, tags=tags, actions=actions,
              click=ntfy.panel_url(click_anchor))
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
            except Exception as e:
                delete_errors.append(f"{t['id']}: {e}")
    if deleted_ids:
        gym_open = [t for t in gym_open if t["id"] not in deleted_ids]
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
            fs.create_task(title="Gym", listId=config.LIST_PERSONAL, isAutoScheduled=False,
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
                fs.create_task(title="Wind down — early gym", listId=config.LIST_PERSONAL,
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
        ntfy.alert(msg, click=ntfy.panel_url())

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
        fs.create_task(title=c["title"], listId=c["listId"], durationMinutes=c["durationMinutes"],
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
        ntfy.alert("Catch-up: re-packed your whole schedule around what's left.",
                  click=ntfy.panel_url())
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
        fs.create_task(title=f"{base} (proposed)", listId=config.LIST_PERSONAL,
                       schedulingHoursId=config.SH_EVENINGS, durationMinutes=120,
                       priority=config.PRIO_SOCIAL_PROPOSED,
                       dueDateTime=f"{date}T21:00:00", canBeStartedAt=f"{date}T17:00:00",
                       isAutoIgnored=False, notes="Proposed hangout — complete the 'Plan ...' task to lock it in.")
        plan_due = (datetime.date.fromisoformat(date) - datetime.timedelta(days=config.PLAN_LEAD_DAYS)).isoformat()
        fs.create_task(title=f"Plan {base}", listId=config.LIST_PERSONAL,
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
    g = fs.create_task(title="Groceries", listId=config.LIST_PERSONAL,
                       schedulingHoursId=config.SH_PERSONAL, durationMinutes=60,
                       priority=config.PRIO_MEAL, dueDateTime=f"{d3}T19:00:00",
                       canBeStartedAt=f"{d0}T00:00:00",
                       isAutoIgnored=False, notes="Meal-prep week (LifeOps).")
    fs.create_task(title="Meal prep", listId=config.LIST_PERSONAL,
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
                            click_anchor="accounts")
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
    synced  = set(st["synced_modules"])
    seen_titles = set(st["task_titles"])
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
    if not synced and len(existing) >= 5:
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
    for mod in modules:
        name = mod.get("name", "") or ""
        # Prefer a number immediately after "Module"/"M" (however the course
        # names them) over the first digit ANYWHERE in the string — a name
        # like "Week 3: Module 12" would otherwise mis-extract 3, not 12,
        # silently mis-numbering/deduping the wrong module.
        num_match = re.search(r"(?:module|m)\s*#?\s*(\d+)", name, re.I) or re.search(r"\d+", name)
        if not num_match:
            continue   # unnumbered utility module ("Start Here", "Syllabus", ...) — nothing to sync
        num = int(num_match.group(1) if num_match.lastindex else num_match.group())
        unlock_str = mod.get("unlock_at") or mod.get("published_at") or ""
        unlock_date = canvas_engine._parse_date(unlock_str) or today
        if num in synced or unlock_date > today:
            continue

        items = mod.get("items") or []
        modules_data.append({
            "module_num":  num,
            "unlock_date": unlock_date,
            "_mod_items":  items,
            "_mod_id":     mod["id"],
        })

    if not modules_data:
        print("[canvas] no new modules to sync"); return

    # bulk fetch all assignments once
    try:
        all_assignments = {a["id"]: a for a in cv.assignments()}
    except Exception as e:
        print(f"[canvas] failed to fetch assignments: {e}"); return

    # populate assignments + readings per module
    for mod in modules_data:
        items = mod.pop("_mod_items")
        mod_id = mod.pop("_mod_id")

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
            # durable audit trail — creations must survive discarded stdout
            history.append("course_task", source="canvas",
                           meta={"id": tid, "title": spec["title"]})
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
        synced.add(mod["module_num"])
    seen_titles.update(created_titles.keys())
    st["synced_modules"]  = sorted(synced)
    st["task_titles"]     = sorted(seen_titles)
    st["completed_cache"] = completed_cache
    os.makedirs(os.path.dirname(sp), exist_ok=True)
    _save_json_atomic(sp, st)

    n = len(created_titles)
    print(f"[canvas] {n} task(s) created\n{result['report']}")


DOMAINS = {"gym": run_gym, "ynab": run_ynab, "chore": run_chore, "catchup": run_catchup,
           "homework": run_homework, "spend": run_spend, "social": run_social,
           "meal": run_meal, "digest": run_digest, "canvas": run_canvas}

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
    "daily": ["ynab", "homework", "social", "chore", "meal", "spend", "digest", "canvas"],
}

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

    names, explicit = [], set()
    for a in (sys.argv[1:] or ["tick"]):
        if a in TIERS:
            names.extend(TIERS[a])
        else:
            names.append(a); explicit.add(a)   # explicit "run X" always runs
    try:
        enabled = json.load(open(os.path.join(history.ROOT, "logs", "domains.json"), encoding="utf-8"))
    except Exception:
        enabled = {}
    names = [n for n in names if n in explicit or enabled.get(n, True)]
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
        _alert_once("health:" + now.date().isoformat(),
                    "⚠️ LifeOps errors — " + "; ".join(f"{k}: {v[:40]}" for k, v in errors.items()),
                    "high")
    _heartbeat(not errors)
    rec = {"ts": now.isoformat(timespec="seconds"), "args": sys.argv[1:],
           "ran": names, "errors": errors, "details": details}
    os.makedirs(os.path.dirname(hp), exist_ok=True)
    json.dump(rec, open(hp, "w", encoding="utf-8"))
    _append_run_log(rec)

if __name__ == "__main__":
    main()
