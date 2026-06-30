"""Orchestrator — the cron entrypoint. Replaces the Claude 'daily-ops' routine.

Flow per domain: GATHER (clients) -> DECIDE (deterministic engine) -> APPLY (clients).
The LLM (lifeops.llm) is touched only for the judgment slivers.

Run:  python -m lifeops.runner          # all wired domains
      python -m lifeops.runner gym      # one domain
"""
import sys, os, json, datetime
from . import config, ntfy, gather, lock, history, adherence
from .flowsavvy import FlowSavvy
from .ynab import YNAB
from .engines import gym_engine, ynab_engine

_PRIO = {"urgent": "urgent", "high": "high", "none": "default"}

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
    json.dump(st, open(sp, "w", encoding="utf-8"))

def _alert_once(key, text, priority="default", tags=None, actions=None):
    """Send an alert at most once per calendar day per key. The tick runs every
    10 min — without this, advisory alerts would spam."""
    sp = os.path.join(history.ROOT, "logs", "alert_state.json")
    st = {}
    try:
        st = json.load(open(sp, encoding="utf-8"))
    except Exception:
        pass
    today = datetime.date.today().isoformat()
    if st.get(key) == today:
        return
    ntfy.alert(text, priority=priority, tags=tags, actions=actions)
    st[key] = today
    os.makedirs(os.path.dirname(sp), exist_ok=True)
    json.dump(st, open(sp, "w", encoding="utf-8"))

def run_gym(fs, yn, now):
    # clean up stale gym blocks; record genuine misses (no ping that day) so
    # adherence learning has data — by slot, to learn what he actually honors.
    # A block on a PAST date, or a TODAY block whose time has fully elapsed with no
    # workout logged, is a miss — delete it so it stops counting toward the target
    # and the engine can schedule a replacement (runs before gather, below).
    today = now.date().isoformat()
    now_iso = now.isoformat()
    did_today = bool(history.days_with("gym", today, today))
    for t in [t for t in fs.list_items(itemType="task", query="Gym", completed=False).get("items", [])
              if (t.get("title") or "").startswith("Gym")]:
        sd = t.get("startDateTime") or ""
        ed = t.get("endDateTime") or ""
        d = sd[:10]
        past_day = bool(d) and d < today
        elapsed_today = d == today and bool(ed) and ed < now_iso and not did_today
        if past_day or elapsed_today:
            if not history.days_with("gym", d, d):
                slot = "morning" if (sd[11:13] or "12") < "11" else "evening"
                history.append("gym_missed", ts=(sd[:19] or None), source="cleanup",
                               meta={"slot": slot})
            try: fs.delete_item(t["id"]); _touch()
            except Exception: pass
    inp = gather.gym_input(fs, now)
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
                    ["rotating_light"] if lvl == "urgent" else None)
    print(f"[gym] {out['summary']}")

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
        ntfy.alert(msg)

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
    os.makedirs(os.path.dirname(sp), exist_ok=True); json.dump(st, open(sp, "w", encoding="utf-8"))
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
        ntfy.alert("Catch-up: re-packed your whole schedule around what's left.")
        print("[catchup] re-packed")
    else:
        print("[catchup] no trigger")
    st["lastHandled"] = int(now.timestamp())
    os.makedirs(os.path.dirname(sp), exist_ok=True); json.dump(st, open(sp, "w", encoding="utf-8"))

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
    os.makedirs(os.path.dirname(sp), exist_ok=True); json.dump(st, open(sp, "w", encoding="utf-8"))

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
    sp = os.path.join(history.ROOT, "logs", "meal_state.json")
    st = {"lastSkip": 0}
    try: st.update(json.load(open(sp, encoding="utf-8")))
    except Exception: pass
    # Handle a "Have leftovers — skip" button tap: clear this week's tasks.
    skipped = any((m.get("message") or "").strip().lower() == "meal-skip"
                  for m in ntfy.poll(since=st["lastSkip"]))
    st["lastSkip"] = int(now.timestamp())
    os.makedirs(os.path.dirname(sp), exist_ok=True); json.dump(st, open(sp, "w", encoding="utf-8"))
    if skipped:
        for t in fs.list_items(itemType="task", completed=False).get("items", []):
            if t.get("title") in ("Groceries", "Meal prep") and "LifeOps" in (t.get("notes") or ""):
                try: fs.delete_item(t["id"])
                except Exception: pass
        history.append("meal", source="skipped")   # counts as handled this week
        _touch()
        print("[meal] skipped (leftovers) — cleared this week"); return

    last = history.last("meal")
    if last and (now - datetime.datetime.fromisoformat(last)).days < 6:
        print("[meal] not due"); return
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

DOMAINS = {"gym": run_gym, "ynab": run_ynab, "chore": run_chore, "catchup": run_catchup,
           "homework": run_homework, "spend": run_spend, "social": run_social,
           "meal": run_meal, "digest": run_digest}

# Tiers are keyed by latency need, not just cost. ingest() runs before every tier
# (so phone signals + completions are recorded each cycle no matter which fires).
TIERS = {
    # signal: the interactive path — a phone tap ("catchup") should re-pack the day
    # in ~2 min, not wait for the 10-min tick. Cheap: ntfy poll + ingest only.
    "signal": ["catchup"],
    # tick: all-day deterministic loop. gym lives here so a slot blocked mid-day gets
    # re-planned the same day; the engine only writes on real change, so no churn.
    "tick":   ["catchup", "spend", "gym"],
    # daily: heavier / LLM-touching work, once each morning.
    "daily":  ["ynab", "homework", "social", "chore", "meal", "digest"],
}

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

    try:
        ingest(fs, now)               # always update the completion history first
    except Exception as e:
        errors["ingest"] = str(e); print(f"[ingest] ERROR: {e}")

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
            fn(fs, yn, now)
        except Exception as e:
            errors[name] = str(e); print(f"[{name}] ERROR: {e}")

    if _DIRTY[0]:                      # ONE recalc per run, only if something changed
        try: fs.recalculate()
        except Exception as e: errors["recalculate"] = str(e)

    if errors:                        # fail loud — never silent
        _alert_once("health:" + now.date().isoformat(),
                    "⚠️ LifeOps errors — " + "; ".join(f"{k}: {v[:40]}" for k, v in errors.items()),
                    "high")
    _heartbeat(not errors)
    os.makedirs(os.path.dirname(hp), exist_ok=True)
    json.dump({"ts": now.isoformat(timespec="seconds"), "ran": names, "errors": errors},
              open(hp, "w", encoding="utf-8"))

if __name__ == "__main__":
    main()
