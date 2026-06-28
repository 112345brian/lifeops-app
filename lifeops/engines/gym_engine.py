#!/usr/bin/env python3
"""Deterministic gym-scheduling decision engine.

ALL scheduling logic lives here — counting, date math, week-solving, slot rules,
consecutive-day cap, urgency — so it is identical every run and never
hallucinated. `plan(inp)` is pure; the runner (or CLI) handles I/O + logging.
"""
import json, sys, os, datetime

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LOG = os.path.join(ROOT, "logs", "gym_log.jsonl")
DAY = datetime.timedelta(days=1)

def D(s):
    return datetime.date.fromisoformat(s)

def slot_for(day):
    if day.get("day_after_show"):
        return None
    # Hard-deadline day: coursework outranks the gym EVENING. Only a before-work
    # morning is acceptable (it doesn't eat the crunch evening), and only if sleep
    # allows; otherwise the gym yields this day entirely.
    if day.get("deadline_heavy"):
        if day.get("sleep_ok", True) and not day.get("prior_night_blocked"):
            return ("05:10", "06:10", "morning")
        return None
    if not day.get("evening_blocked"):
        return ("19:00", "20:00", "evening")
    if day.get("sleep_ok", True) and not day.get("prior_night_blocked"):
        return ("05:10", "06:10", "morning")
    return None

def run_length(date_str, busy):
    dt = D(date_str); n = 1
    x = dt - DAY
    while x.isoformat() in busy: n += 1; x -= DAY
    x = dt + DAY
    while x.isoformat() in busy: n += 1; x += DAY
    return n

def plan(inp):
    r = {"target": 4, "floor": 3, "max_consecutive": 2}
    r.update(inp.get("rules", {}))
    out = {"actions": [], "wind_down": [], "alert": {"level": "none", "text": ""}, "summary": ""}

    su = inp.get("sick_until")
    if su and D(inp["today"]) <= D(su):
        for g in inp.get("scheduled", []):
            if not g.get("manual") and not g.get("started"):
                out["actions"].append({"op": "delete", "date": g["date"], "reason": "sick / skip week"})
        out["summary"] = "sick week — paused"
        return out

    completed = int(inp.get("completed_count", 0))
    scheduled = inp.get("scheduled", [])
    have = completed + len(scheduled)
    target, floor = r["target"], r["floor"]
    needed = max(0, target - have)
    floor_needed = max(0, floor - have)
    sched_dates = {g["date"] for g in scheduled}

    cand = []
    for day in inp.get("days", []):
        if day["date"] in sched_dates:
            continue
        s = slot_for(day)
        if s:
            cand.append((day["date"], s))
    cand.sort(key=lambda c: c[0])

    chosen, busy = [], set(sched_dates)
    for date, slot in cand:
        if len(chosen) >= needed:
            break
        if run_length(date, busy | {date}) > r["max_consecutive"]:
            continue
        chosen.append((date, slot)); busy.add(date)

    for date, slot in chosen:
        out["actions"].append({"op": "create", "date": date, "start": slot[0], "end": slot[1],
                               "buffer_before": 10, "buffer_after": 10, "kind": slot[2]})
        if slot[2] == "morning":
            out["wind_down"].append({"date": (D(date) - DAY).isoformat(),
                                     "start": "21:00", "end": "23:00"})

    viable_left = len(cand)
    if floor_needed > viable_left:
        out["alert"] = {"level": "high",
                        "text": f"Heads up: set to miss {floor}x this week — only {viable_left} viable day(s) left."}
    elif floor_needed > 0 and floor_needed == viable_left:
        out["alert"] = {"level": "urgent",
                        "text": f"GO TODAY — last viable day to hit {floor}x this week."}

    out["summary"] = (f"have={have} target={target} needed={needed} "
                      f"chose={[c[0] for c in chosen]} viable_left={viable_left}")
    return out

def log(inp, out):
    line = {"ts": inp.get("now"), "summary": out["summary"],
            "n_actions": len(out["actions"]), "alert": out["alert"]["level"]}
    try:
        os.makedirs(os.path.dirname(LOG), exist_ok=True)
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(json.dumps(line) + "\n")
    except Exception:
        pass

def main():
    inp = json.load(open(sys.argv[1], encoding="utf-8"))
    out = plan(inp); log(inp, out)
    json.dump(out, open(sys.argv[2], "w", encoding="utf-8"), indent=2)
    print(json.dumps(out, indent=2))

if __name__ == "__main__":
    main()
