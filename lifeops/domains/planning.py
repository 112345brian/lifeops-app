"""Planning domain: catch-up reschedule, homework/deadline risk, weekly digest, daily briefing."""
import datetime, os
from .. import adherence, briefing_service, config, gather, history, notify, ntfy, state_store
from ._shared import _save_json_atomic, _alert_once, _push_with_ack, _utc_iso


def run_catchup(fs, yn, now):
    sp = os.path.join(history.ROOT, "private", "logs", "catchup_state.json")
    st = {"lastHandled": 0}
    st.update(state_store.load_json(sp, default={}))
    fired = any((m.get("message") or "").strip().lower() == "catchup"
                for m in ntfy.poll(since=st["lastHandled"]))
    if fired:
        fs.recalculate(reschedule_past=True)
        # The recalculate already happened -- a failed/rate-limited alert
        # (the ntfy-backed alert path raises on non-2xx) must not stop "lastHandled"
        # from being persisted below, or the same trigger message gets
        # replayed on every future tick, re-firing a full reschedule each
        # time until an alert happens to succeed.
        try:
            notify.alert("Catch-up: re-packed your whole schedule around what's left.")
        except Exception as e:
            print(f"[catchup] alert failed (non-fatal): {e}")
        print("[catchup] re-packed")
    else:
        print("[catchup] no trigger")
    st["lastHandled"] = int(now.timestamp())
    os.makedirs(os.path.dirname(sp), exist_ok=True); _save_json_atomic(sp, st)


def run_homework(fs, yn, now):
    from ..engines import load_engine
    out = load_engine.plan(gather.homework_input(fs, now))
    for text, lvl in out["alerts"]:
        _alert_once("hw:" + text[:24], text, lvl)
    print(f"[homework] {len(out['alerts'])} alert(s)")


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
    from .. import llm
    try:
        _alert_once("digest:" + now.date().isoformat(), llm.weekly_digest(facts))
        print("[digest] sent")
    except Exception as e:
        print(f"[digest] error: {e}")


def run_briefing(fs, yn, now):
    """Daily morning briefing (once/day) — the daily counterpart to the weekly
    Sunday digest. Surfaces the risk/forecast the engines ALREADY compute
    (at-risk coursework, today's load vs. capacity, gym status, discretionary
    money) as one glanceable ntfy + a panel card, so a looming deadline or a
    dwindling budget shows up proactively instead of only when you go looking.
    Inspired by Motion's deadline-risk surfacing + Sunsama's morning plan.

    Fully deterministic, no LLM call -- see briefing_service.build for fact
    assembly and text composition."""
    briefing = briefing_service.build(fs, yn, now)
    date, text, facts = briefing["date"], briefing["text"], briefing["facts"]

    _alert_once("briefing:" + date, text, click_anchor="#briefing")
    try:
        _push_with_ack("briefing", briefing,
                       lambda version: notify.push_briefing(date, text, facts, version))
    except Exception as e:
        print(f"[briefing] fcm send error: {e}")
    bp = os.path.join(history.ROOT, "private", "logs", "briefing.json")
    _save_json_atomic(bp, briefing)
    print("[briefing] sent")


def run_deadlines(fs, yn, now):
    """Generalized deadline-risk watchdog (Motion-style) over ALL deadline-bearing
    tasks, not just coursework: alerts when the cumulative work due by a deadline
    can't realistically fit before it. deadline_risk emits only the earliest
    binding deadline, so this pushes at most one crunch alert per day."""
    from ..engines import load_engine
    out = load_engine.deadline_risk(gather.deadline_input(fs, now))
    for text, lvl in out["alerts"]:
        _alert_once("deadline:" + now.date().isoformat(), text, lvl)
    print(f"[deadlines] {len(out['alerts'])} at-risk")
