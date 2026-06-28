"""Orchestrator — the cron entrypoint. Replaces the Claude 'daily-ops' routine.

Flow per domain: GATHER (clients) -> DECIDE (deterministic engine) -> APPLY (clients).
The LLM (lifeops.llm) is touched only for the judgment slivers.

Run:  python -m lifeops.runner          # all wired domains
      python -m lifeops.runner gym      # one domain
"""
import sys, datetime
from . import config, ntfy, gather, lock
from .flowsavvy import FlowSavvy
from .ynab import YNAB
from .engines import gym_engine, ynab_engine

_PRIO = {"urgent": "urgent", "high": "high", "none": "default"}

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
        ntfy.alert(out["alert"]["text"], priority=_PRIO[lvl],
                   tags=["rotating_light"] if lvl == "urgent" else None)
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

DOMAINS = {"gym": run_gym, "ynab": run_ynab}   # chore/homework/spend/social/catchup wire in next

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
