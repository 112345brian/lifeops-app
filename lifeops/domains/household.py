"""Household domain: gym scheduling/adherence, chore cycling, meal-prep weeks."""
import datetime, os
from .. import actions, config, gather, history, state_store
from ..engines import gym_engine
from ..routine import status as routine_status
from .. import routine_store
from ._shared import _save_json_atomic, _alert_once, _logged_create, _touch, _utc_iso

_PRIO = {"urgent": "urgent", "high": "high", "none": "default"}

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
    sp = os.path.join(history.ROOT, "private", "logs", "gym_state.json")
    st = state_store.load_json(sp, default={})
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
        # visible confirmation on tasks only -- not extended to events here,
        # to keep this backfill-logging pass's behavior unchanged
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
        # Wednesday is not a before-work gym day, so Tuesday night does not
        # need a wind-down block. Prune any already-created ones too --
        # reads gym_engine's own exempt-weekday constant (not an
        # independently hardcoded "Tue") so this can't silently drift out
        # of sync with needs_wind_down()'s actual create-side decision.
        exempt_day = bool(d) and datetime.date.fromisoformat(d).strftime("%a") in gym_engine.DEFAULT_WIND_DOWN_EXEMPT_WEEKDAYS
        if past_day or elapsed_today or exempt_day:
            try:
                fs.delete_item(t["id"]); _touch()
            except Exception as e:
                delete_errors.append(f"{t['id']}: {e}")
    gym_state_path = os.path.join(history.ROOT, "private", "logs", "gym_state.json")
    sick_until = state_store.load_json(gym_state_path, default={}).get("sick_until")
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


def run_chore(fs, yn, now):
    sp = os.path.join(history.ROOT, "private", "logs", "chore_state.json")
    st = {"processed": [], "lastRunUtc": "1970-01-01T00:00:00Z"}
    st.update(state_store.load_json(sp, default={}))
    from ..engines import chore_engine
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


def run_meal(fs, yn, now):
    from .. import ntfy
    # Always drain the ntfy "meal-skip" cursor every tick, even when not due —
    # it's a single cheap poll, and freezing st["lastSkip"] while not due (an
    # earlier version of this fix did exactly that) let a stray tap sit
    # unconsumed for days, then replay against a LATER week the moment `due`
    # flips true again, spuriously wiping that week's freshly-created tasks.
    sp = os.path.join(history.ROOT, "private", "logs", "meal_state.json")
    st = {"lastSkip": 0}
    st.update(state_store.load_json(sp, default={}))
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
    meal_routine, _ = routine_store.load_routine("meal")
    due = routine_status(meal_routine, [last] if last else [], now)["due"]
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
