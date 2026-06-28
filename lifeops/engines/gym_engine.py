#!/usr/bin/env python3
"""Deterministic gym-scheduling decision engine.

The LLM agent GATHERS a situation (from FlowSavvy / calendars / ntfy) into an
input JSON and EXECUTES the plan this returns. ALL scheduling logic lives here —
counting, date math, week-solving, slot rules, consecutive-day cap, urgency —
so it is identical every run and never hallucinated. Also appends a persistent
log so decisions reference something real.

Usage: python gym_engine.py <input.json> <output.json>

Input JSON shape:
{
  "today": "2026-06-28",                     # local date
  "now":   "2026-06-28T09:00:00",
  "sick_until": null,                          # or "YYYY-MM-DD"
  "completed_count": 1,                        # Gym ✅ this week (Mon-Sun)
  "scheduled": [                               # future Gym tasks this week
     {"date":"2026-06-28","start":"19:00","end":"20:00","manual":false,"started":false}
  ],
  "days": [                                    # horizon (next ~7 days)
     {"date":"2026-06-29","weekday":"Mon","evening_blocked":true,
      "day_after_show":false,"prior_night_blocked":false,"sleep_ok":true}
  ],
  "rules": {"target":4,"floor":3,"max_consecutive":2}   # optional overrides
}
"""
import json, sys, os, datetime

DIR = os.path.dirname(os.path.abspath(__file__))
LOG = os.path.join(DIR, "gym_log.jsonl")
DAY = datetime.timedelta(days=1)

def D(s):  # 'YYYY-MM-DD' -> date
    return datetime.date.fromisoformat(s)

def slot_for(day):
    """Return (start,end,kind) for a day, or None if it's not a gym day."""
    if day.get("day_after_show"):
        return None                                   # recovery morning/day
    if not day.get("evening_blocked"):
        return ("19:00", "20:00", "evening")          # preferred: costs no sleep
    # evening blocked -> a before-work morning, only if sleep allows and the
    # prior night is free enough for an early bedtime
    if day.get("sleep_ok", True) and not day.get("prior_night_blocked"):
        return ("05:10", "06:10", "morning")
    return None                                       # no viable slot today

def run_length(date_str, busy):
    """Length of the consecutive gym-day run that date_str would belong to."""
    dt = D(date_str); n = 1
    x = dt - DAY
    while x.isoformat() in busy:
        n += 1; x -= DAY
    x = dt + DAY
    while x.isoformat() in busy:
        n += 1; x += DAY
    return n

def main():
    inp = json.load(open(sys.argv[1], encoding="utf-8"))
    out_path = sys.argv[2]
    r = {"target": 4, "floor": 3, "max_consecutive": 2}
    r.update(inp.get("rules", {}))
    out = {"actions": [], "wind_down": [], "alert": {"level": "none", "text": ""},
           "summary": ""}

    # 0) sick / off-week -> pull this week's un-started auto gyms, stay quiet
    su = inp.get("sick_until")
    if su and D(inp["today"]) <= D(su):
        for g in inp.get("scheduled", []):
            if not g.get("manual") and not g.get("started"):
                out["actions"].append({"op": "delete", "date": g["date"],
                                        "reason": "sick / skip week"})
        out["summary"] = "sick week — paused, no nags"
        return finish(out, inp)

    completed = int(inp.get("completed_count", 0))
    scheduled = inp.get("scheduled", [])
    have = completed + len(scheduled)
    target, floor = r["target"], r["floor"]
    needed = max(0, target - have)
    floor_needed = max(0, floor - have)
    sched_dates = {g["date"] for g in scheduled}

    # candidate (date, slot) pairs from the horizon, earliest first (front-loads)
    cand = []
    for day in inp.get("days", []):
        if day["date"] in sched_dates:
            continue                                  # already has a gym
        s = slot_for(day)
        if s:
            cand.append((day["date"], s))
    cand.sort(key=lambda c: c[0])

    # greedily choose earliest viable days, respecting the consecutive-day cap
    chosen = []
    busy = set(sched_dates)
    for date, slot in cand:
        if len(chosen) >= needed:
            break
        if run_length(date, busy | {date}) > r["max_consecutive"]:
            continue
        chosen.append((date, slot))
        busy.add(date)

    for date, slot in chosen:
        out["actions"].append({"op": "create", "date": date, "start": slot[0],
                                "end": slot[1], "buffer_before": 10,
                                "buffer_after": 10, "kind": slot[2]})
        if slot[2] == "morning":
            prior = (D(date) - DAY).isoformat()
            out["wind_down"].append({"date": prior, "start": "21:00", "end": "23:00",
                                     "reason": "early gym next morning"})

    # urgency — based ONLY on real counts and viable days left
    viable_left = len(cand)
    if floor_needed > viable_left:
        out["alert"] = {"level": "high",
                        "text": f"Heads up: you're set to miss {floor}x this week "
                                f"— only {viable_left} viable day(s) left."}
    elif floor_needed > 0 and floor_needed == viable_left:
        out["alert"] = {"level": "urgent",
                        "text": f"GO TODAY — last viable day to hit {floor}x this week."}

    out["summary"] = (f"have={have} target={target} needed={needed} "
                      f"chose={[c[0] for c in chosen]} viable_left={viable_left}")
    return finish(out, inp)

def finish(out, inp):
    line = {"ts": inp.get("now"), "summary": out["summary"],
            "n_actions": len(out["actions"]), "alert": out["alert"]["level"]}
    try:
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(json.dumps(line) + "\n")
    except Exception:
        pass
    json.dump(out, open(sys.argv[2], "w", encoding="utf-8"), indent=2)
    print(json.dumps(out, indent=2))

if __name__ == "__main__":
    main()
