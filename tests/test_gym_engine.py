import pytest
from lifeops.engines import gym_engine

MON = "2026-07-06"
RULES = {"allow_morning": True, "evening_start": "19:00", "evening_end": "20:00",
         "max_consecutive": 2}

def _day(date, evening_blocked=False, day_after_show=False, prior_night_blocked=False,
         deadline_heavy=False, sleep_ok=True):
    import datetime
    d = datetime.date.fromisoformat(date)
    return {"date": date, "weekday": d.strftime("%a"),
            "evening_blocked": evening_blocked, "day_after_show": day_after_show,
            "prior_night_blocked": prior_night_blocked,
            "deadline_heavy": deadline_heavy, "sleep_ok": sleep_ok}

def _inp(completed=0, scheduled=None, days=None, sick_until=None, rules=None):
    return {"today": MON, "now": f"{MON}T10:00:00", "sick_until": sick_until,
            "completed_count": completed, "scheduled": scheduled or [],
            "days": days or [], "rules": rules or RULES}

def _dates(n, start=MON):
    import datetime
    d = datetime.date.fromisoformat(start)
    return [(d + datetime.timedelta(days=i)).isoformat() for i in range(n)]


def test_creates_to_hit_target():
    days = [_day(d) for d in _dates(7)]
    out = gym_engine.plan(_inp(completed=0, days=days))
    creates = [a for a in out["actions"] if a["op"] == "create"]
    assert len(creates) == 4  # target=4, have=0

def test_no_creates_when_target_met():
    days = [_day(d) for d in _dates(7)]
    sched = [{"id": f"id{i}", "date": _dates(7)[i], "start": "19:00",
              "end": "20:00", "manual": False, "started": False} for i in range(4)]
    out = gym_engine.plan(_inp(completed=0, scheduled=sched, days=days))
    creates = [a for a in out["actions"] if a["op"] == "create"]
    assert creates == []

def test_completed_count_reduces_creates():
    days = [_day(d) for d in _dates(7)]
    out = gym_engine.plan(_inp(completed=3, days=days))
    creates = [a for a in out["actions"] if a["op"] == "create"]
    assert len(creates) == 1  # need 1 more to hit target=4

def test_sick_week_deletes_scheduled():
    days = [_day(d) for d in _dates(7)]
    sched = [{"id": "abc", "date": _dates(7)[1], "start": "19:00",
              "end": "20:00", "manual": False, "started": False}]
    out = gym_engine.plan(_inp(scheduled=sched, days=days, sick_until=_dates(7)[-1]))
    assert any(a["op"] == "delete" for a in out["actions"])
    assert not any(a["op"] == "create" for a in out["actions"])

def test_sick_week_skips_started_blocks():
    days = [_day(d) for d in _dates(7)]
    sched = [{"id": "abc", "date": MON, "start": "09:00",
              "end": "10:00", "manual": False, "started": True}]
    out = gym_engine.plan(_inp(scheduled=sched, days=days, sick_until=_dates(7)[-1]))
    assert not any(a["op"] == "delete" and a["date"] == MON for a in out["actions"])

def test_evening_blocked_falls_back_to_morning():
    days = [_day(_dates(7)[0], evening_blocked=True)] + [_day(d) for d in _dates(7)[1:]]
    out = gym_engine.plan(_inp(completed=3, days=days))
    creates = [a for a in out["actions"] if a["op"] == "create"]
    assert len(creates) == 1
    assert creates[0]["kind"] == "morning"

def test_deadline_heavy_forces_morning():
    # all days have deadline_heavy — only morning slots viable
    days = [_day(d, deadline_heavy=True) for d in _dates(7)]
    out = gym_engine.plan(_inp(completed=3, days=days))
    creates = [a for a in out["actions"] if a["op"] == "create"]
    assert all(a["kind"] == "morning" for a in creates)

def test_day_after_show_skipped():
    # day 1 is after a show → skip it, day 2 is clean
    dates = _dates(7)
    days = [_day(dates[0]), _day(dates[1], day_after_show=True)] + \
           [_day(d) for d in dates[2:]]
    out = gym_engine.plan(_inp(completed=3, days=days))
    creates = [a for a in out["actions"] if a["op"] == "create"]
    assert all(a["date"] != dates[1] for a in creates)

def test_consecutive_cap_respected():
    # fill 4 consecutive days — should cap at 2 in a row
    dates = _dates(7)
    days = [_day(d) for d in dates]
    out = gym_engine.plan(_inp(completed=0, days=days))
    creates = [a for a in out["actions"] if a["op"] == "create"]
    # verify no 3-in-a-row
    chosen = sorted(a["date"] for a in creates)
    import datetime
    for i in range(len(chosen) - 2):
        a = datetime.date.fromisoformat(chosen[i])
        b = datetime.date.fromisoformat(chosen[i+1])
        c = datetime.date.fromisoformat(chosen[i+2])
        assert not (b - a == datetime.timedelta(days=1) and
                    c - b == datetime.timedelta(days=1)), "3 consecutive days chosen"

def test_no_morning_when_suppressed():
    rules = {**RULES, "allow_morning": False}
    days = [_day(d, evening_blocked=True) for d in _dates(7)]  # all evenings blocked, no morning
    out = gym_engine.plan(_inp(completed=3, days=days, rules=rules))
    creates = [a for a in out["actions"] if a["op"] == "create"]
    assert creates == []  # nowhere to go

def test_floor_alert_when_cant_hit_floor():
    # only 1 viable day left, need 3 to hit floor
    days = [_day(_dates(7)[0])] + [_day(d, evening_blocked=True) for d in _dates(7)[1:]]
    rules = {**RULES, "allow_morning": False}
    out = gym_engine.plan(_inp(completed=0, days=days, rules=rules))
    assert out["alert"]["level"] in ("high", "urgent")

def test_uses_configured_evening_time():
    rules = {**RULES, "evening_start": "18:00", "evening_end": "19:00"}
    days = [_day(d) for d in _dates(7)]
    out = gym_engine.plan(_inp(completed=3, days=days, rules=rules))
    creates = [a for a in out["actions"] if a["op"] == "create"]
    assert creates[0]["start"] == "18:00"

def test_consecutive_cap_counts_completed_history():
    # trained Sat+Sun (completed, not scheduled) — Monday would be a REAL 3rd
    # consecutive day and must be skipped even though nothing is "scheduled"
    import datetime
    dates = _dates(7)
    sat = (datetime.date.fromisoformat(MON) - datetime.timedelta(days=2)).isoformat()
    sun = (datetime.date.fromisoformat(MON) - datetime.timedelta(days=1)).isoformat()
    inp = _inp(completed=2, days=[_day(d) for d in dates])
    inp["completed_dates"] = [sat, sun]
    out = gym_engine.plan(inp)
    creates = [a for a in out["actions"] if a["op"] == "create"]
    assert all(a["date"] != MON for a in creates), "booked a real 3rd straight day"

def test_completed_day_not_rescheduled():
    inp = _inp(completed=1, days=[_day(d) for d in _dates(7)])
    inp["completed_dates"] = [MON]     # already trained today
    out = gym_engine.plan(inp)
    creates = [a for a in out["actions"] if a["op"] == "create"]
    assert all(a["date"] != MON for a in creates)

def test_viable_left_excludes_cap_rejected_days():
    # 3 open days but they're consecutive with completed Sat+Sun around them:
    # viable count must reflect the cap, not raw candidate count
    import datetime
    dates = _dates(7)
    days = [_day(dates[0]), _day(dates[1])] + \
           [_day(d, evening_blocked=True) for d in dates[2:]]
    rules = {**RULES, "allow_morning": False, "max_consecutive": 1}
    out = gym_engine.plan(_inp(completed=0, days=days, rules=rules))
    # with max_consecutive=1 only one of the two adjacent days is usable;
    # floor=3, so this must be a "set to miss" high alert with viable_left=1
    assert out["alert"]["level"] == "high"
    assert "only 1 viable" in out["alert"]["text"]

def test_viable_left_checks_candidates_against_each_other_not_just_busy():
    # regression: when `needed=0` the booking loop never runs, so `busy` stays
    # frozen at its starting value — the OLD viable_left checked every
    # remaining candidate against that frozen `busy` independently, so three
    # mutually-adjacent-to-EACH-OTHER (but not to `busy`) days all looked
    # individually viable even though max_consecutive=1 means at most 2 of
    # them could ever actually be booked together.
    days = [_day(d) for d in ["2026-07-06", "2026-07-09", "2026-07-10", "2026-07-11"]]
    rules = {**RULES, "max_consecutive": 1}
    inp = _inp(completed=0, days=days, rules={**rules})
    inp["rules"]["target"] = 0   # needed=0 -> the chosen-loop never runs
    out = gym_engine.plan(inp)
    assert "viable_left=3" in out["summary"], out["summary"]
