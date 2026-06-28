"""Orchestrator — the cron entrypoint. Replaces the Claude 'daily-ops' routine.

Flow per domain: GATHER (clients) -> DECIDE (deterministic engine) -> APPLY (clients).
The LLM (lifeops.llm) is touched only for the judgment slivers.

Run:  python -m lifeops.runner          # all wired domains
      python -m lifeops.runner gym      # one domain
"""
import sys, os, json, datetime
from . import config, ntfy, gather, lock, history
from .flowsavvy import FlowSavvy
from .ynab import YNAB
from .engines import gym_engine, ynab_engine

_PRIO = {"urgent": "urgent", "high": "high", "none": "default"}

# ntfy signal body -> history action
_SIG = {"gym": "gym", "saw reina": "reina", "hung friends": "friends",
        "fell-asleep": "sleep", "woke-up": "wake"}

def _classify(title):
    t = (title or "").lower()
    for k, v in [("gym", "gym"), ("laundry", "laundry"), ("clean room", "clean_room"),
                 ("clean bathroom", "clean_bathroom"), ("tidy car", "tidy_car"),
                 ("car wash", "car_wash"), ("oil change", "oil"), ("reina", "reina"),
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
        act = _SIG.get((m.get("message") or "").strip().lower())
        if act:
            ts = datetime.datetime.fromtimestamp(m["time"]).isoformat(timespec="seconds")
            history.append(act, ts=ts, source="ntfy")
        st["ntfy_ts"] = max(st["ntfy_ts"], m.get("time", 0))
    frm = (now - datetime.timedelta(days=14)).strftime("%Y-%m-%dT%H:%M:%SZ")
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

def _alert_once(key, text, priority="default", tags=None):
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
    ntfy.alert(text, priority=priority, tags=tags)
    st[key] = today
    os.makedirs(os.path.dirname(sp), exist_ok=True)
    json.dump(st, open(sp, "w", encoding="utf-8"))

def run_gym(fs, yn, now):
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
        fs.recalculate()
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
        fs.recalculate()
    st["processed"] = out["processed"]; st["lastRunUtc"] = now.strftime("%Y-%m-%dT%H:%M:%SZ")
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
    inp = gather.social_input(fs, now)
    out = social_engine.plan(inp["reina_days"], inp["friend_days"], inp["has_reina"],
                             inp["has_friend"], inp["good_days"], inp["is_protect_day"])
    for c in out["creates"]:
        fs.create_task(title=c["title"], listId=config.LIST_PERSONAL,
                       schedulingHoursId=config.SH_EVENINGS, durationMinutes=120,
                       dueDateTime=f"{c['date']}T21:00:00",
                       canBeStartedAt=f"{c['date']}T17:00:00", isAutoIgnored=False,
                       notes="Protected social time (LifeOps).")
    if out["creates"]:
        fs.recalculate()
    for n in out["nudges"]:
        _alert_once("social:" + n[:24], n)
    print(f"[social] created {len(out['creates'])}, nudges {len(out['nudges'])}")

DOMAINS = {"gym": run_gym, "ynab": run_ynab, "chore": run_chore, "catchup": run_catchup,
           "homework": run_homework, "spend": run_spend, "social": run_social}

# Tiers let the cron run cheaply and often. TICK is deterministic + LLM-free and
# only writes on change, so it's safe to run every ~10 min. DAILY holds the
# heavier / LLM-touching work and runs once a morning.
TIERS = {
    "tick":  ["gym", "catchup", "spend"],
    "daily": ["ynab", "homework", "social", "chore", "meal"],
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
    ingest(fs, now)   # always update the completion history first
    args = sys.argv[1:] or ["tick"]
    names = []
    for a in args:
        names.extend(TIERS.get(a, [a]))
    for name in names:
        fn = DOMAINS.get(name)
        if not fn:
            continue   # not ported yet (or unknown) — skip quietly
        try:
            fn(fs, yn, now)
        except Exception as e:
            print(f"[{name}] ERROR: {e}")

if __name__ == "__main__":
    main()
