"""Orchestrator — the cron entrypoint. Replaces the Claude 'daily-ops' routine.

Flow per domain: GATHER (clients) -> DECIDE (deterministic engine) -> APPLY (clients).
The LLM (lifeops.llm) is touched only for the judgment slivers.

Run:  python -m lifeops.runner          # all wired domains
      python -m lifeops.runner gym      # one domain
"""
import sys, datetime
from . import config, ntfy, gather
from .flowsavvy import FlowSavvy
from .engines import gym_engine

_PRIO = {"urgent": "urgent", "high": "high", "none": "default"}

def run_gym(fs, now):
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

DOMAINS = {"gym": run_gym}   # chore/homework/spend/social/catchup/ynab wire in as ported

# Tiers let the cron run cheaply and often. TICK is deterministic + LLM-free and
# only writes on change, so it's safe to run every ~10 min. DAILY holds the
# heavier / LLM-touching work and runs once a morning.
TIERS = {
    "tick":  ["gym", "catchup", "spend"],
    "daily": ["ynab", "homework", "social", "chore", "meal"],
}

def main():
    fs = FlowSavvy()
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
            fn(fs, now)
        except Exception as e:
            print(f"[{name}] ERROR: {e}")

if __name__ == "__main__":
    main()
