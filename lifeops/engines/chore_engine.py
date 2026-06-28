#!/usr/bin/env python3
"""Deterministic chore-recurrence engine.

Given chores Brian just completed (each tagged [cycle:Nd] in its notes), compute
the next occurrence: due = completion date + N days. Pure date math — no
judgment, no API. The LLM only gathers the completed tasks and creates whatever
this returns.

Usage: python chore_engine.py <input.json> <output.json>
Input: {"completed":[{"id","title","notes","completed_date","durationMinutes",
         "minLengthMinutes","listId","priority","schedulingHoursId","dueTime"}],
        "processed":[ids already handled]}
Output:{"creates":[{title,listId,durationMinutes,minLengthMinutes,priority,
         schedulingHoursId,notes,dueDateTime,canBeStartedAt}], "processed":[...]}
"""
import json, sys, re, datetime
DAY = datetime.timedelta(days=1)

def main():
    inp = json.load(open(sys.argv[1], encoding="utf-8"))
    processed = list(inp.get("processed", []))
    seen = set(processed)
    creates = []
    for c in inp.get("completed", []):
        if c["id"] in seen:
            continue
        m = re.search(r"\[cycle:(\d+)d\]", c.get("notes", "") or "")
        if not m:
            continue                                   # not a managed chore
        n = int(m.group(1))
        comp = datetime.date.fromisoformat(c["completed_date"])
        nextd = comp + n * DAY
        t = c.get("dueTime") or "20:00"
        lead = min(3, max(0, n - 1))
        creates.append({
            "title": c["title"],
            "listId": c.get("listId"),
            "durationMinutes": c.get("durationMinutes"),
            "minLengthMinutes": c.get("minLengthMinutes"),
            "priority": c.get("priority", "low"),
            "schedulingHoursId": c.get("schedulingHoursId"),
            "notes": c.get("notes"),
            "dueDateTime": f"{nextd.isoformat()}T{t}:00",
            "canBeStartedAt": f"{(nextd - lead*DAY).isoformat()}T{t}:00",
        })
        processed.append(c["id"]); seen.add(c["id"])
    out = {"creates": creates, "processed": processed[-300:]}
    json.dump(out, open(sys.argv[2], "w", encoding="utf-8"), indent=2)
    print(json.dumps(out, indent=2))

if __name__ == "__main__":
    main()
