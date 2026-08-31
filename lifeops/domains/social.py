"""Social domain: partner/friends cadence nudges and "Plan X" lock-in."""
import os
from .. import config, gather, history, state_store
from .. import routine_store
from ._shared import _save_json_atomic, _alert_once, _touch, _utc_iso


def run_social(fs, yn, now):
    from ..engines import social_engine
    # LOCK-IN: completing a "Plan X" task confirms the proposed X block.
    # Restored 2026-07-14 -- an earlier refactor (aimed at making the widget
    # stop treating a tentative hold as an actual plan, see social_input's
    # docstring) deleted this whole mechanism with nothing replacing it.
    # Without it, completing "Plan Partner time" did nothing: the
    # "(proposed)" placeholder sat open forever, AND social_engine's
    # has_hold check still treated that open placeholder as satisfying the
    # cadence -- so the hangout was never re-proposed either. A "tentative
    # hold that never resolves and never gets replaced" isn't a real
    # workflow the widget-display fix was meant to introduce; separating
    # "planned" from "tentative" for DISPLAY purposes never required
    # deleting the mechanism that turns a completed plan into a real task.
    #
    # Legacy-only as of 2026-07-29: social_engine.plan() no longer proposes
    # new "X (proposed)"/"Plan X" tasks at all (a hangout needs another
    # person's agreement first, unlike a solo commitment like gym, so a
    # fabricated task reading as an already-arranged plan was actively
    # misleading). This block still exists purely to correctly resolve any
    # such tasks that were already open before that change shipped -- it
    # naturally goes inert once those are gone.
    sp = os.path.join(history.ROOT, "private", "logs", "social_state.json")
    st = {"lastLock": "1970-01-01T00:00:00Z"}
    st.update(state_store.load_json(sp, default={}))
    open_tasks = fs.list_items(itemType="task", completed=False).get("items", [])
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
    os.makedirs(os.path.dirname(sp), exist_ok=True)
    _save_json_atomic(sp, st)

    inp = gather.social_input(fs, now)
    partner_routine, _ = routine_store.load_routine("partner")
    friends_routine, _ = routine_store.load_routine("friends")
    out = social_engine.plan(inp["partner_days"], inp["friend_days"],
                             partner_routine, friends_routine, partner_name=config.PARTNER_NAME)
    for n in out["nudges"]:
        _alert_once("social:" + n[:24], n)
    print(f"[social] lock-check done; nudges {len(out['nudges'])}")
