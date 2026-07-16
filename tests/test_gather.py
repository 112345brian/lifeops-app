import datetime

from lifeops import config, gather, history


def test_gym_ring_red_when_zero_sessions_in_trailing_week():
    ring = gather.gym_ring(gym_last_7d=0, gym_target=4, today_needed=True, today_done=False)
    assert ring == {"fill": 0.0, "color": "red"}


def test_gym_ring_green_when_behind_target_but_engine_didnt_schedule_today():
    """Behind target alone must NOT turn the ring yellow -- only an actual
    engine-scheduled session today does (today_needed). Otherwise color just
    re-derives fill>=1.0 under a different name instead of being a genuine
    same-day action signal, and stays yellow through legitimate rest days
    the engine already accounted for (confirmed 2026-07-14: "went to the
    gym yesterday and the day before, you can't be overdue, you need to
    rest" -- yet the ring showed yellow with no task scheduled at all)."""
    ring = gather.gym_ring(gym_last_7d=2, gym_target=4, today_needed=False, today_done=False)
    assert ring == {"fill": 0.5, "color": "green"}


def test_gym_ring_yellow_when_at_target_but_engine_scheduled_today():
    """At/above target does NOT mean "done for the week" if the scheduling
    engine already booked a session today -- gym_engine.plan() decided
    today is a go-day, and the ring must reflect that action is still
    needed, not just the raw ratio."""
    ring = gather.gym_ring(gym_last_7d=4, gym_target=4, today_needed=True, today_done=False)
    assert ring == {"fill": 1.0, "color": "yellow"}


def test_gym_ring_green_when_todays_session_already_done():
    """Completing today's session turns color green immediately, without
    waiting for fill to reflect it -- fill only grows via real accumulated
    sessions in the trailing window, per the user's explicit spec."""
    ring = gather.gym_ring(gym_last_7d=2, gym_target=4, today_needed=True, today_done=True)
    assert ring == {"fill": 0.5, "color": "green"}


def test_gym_ring_green_when_at_target_and_nothing_needed_today():
    ring = gather.gym_ring(gym_last_7d=5, gym_target=4, today_needed=False, today_done=False)
    assert ring == {"fill": 1.0, "color": "green"}


def test_gym_ring_fill_clamps_at_one_even_when_over_target():
    ring = gather.gym_ring(gym_last_7d=7, gym_target=4, today_needed=False, today_done=False)
    assert ring["fill"] == 1.0


def test_parse_note_overrides_reads_type_and_cost():
    assert gather._parse_note_overrides("type: friends\ncost: 30") == {
        "type": "friends", "types": ["friends"], "cost": 30.0,
    }


def test_parse_note_overrides_tolerates_dollar_sign_and_case():
    assert gather._parse_note_overrides("Type: Friends\nCost: $30.50") == {
        "type": "friends", "types": ["friends"], "cost": 30.5,
    }


def test_parse_note_overrides_ignores_malformed_cost():
    assert gather._parse_note_overrides("type: friends\ncost: free") == {"type": "friends", "types": ["friends"]}


def test_parse_note_overrides_reads_multiple_types():
    assert gather._parse_note_overrides("type: friends, concerts") == {
        "type": "friends", "types": ["friends", "concert"],
    }


def test_parse_note_overrides_empty_notes():
    assert gather._parse_note_overrides(None) == {}
    assert gather._parse_note_overrides("") == {}


def test_sleep_minutes_last_night_returns_none_without_watch_data(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    monkeypatch.setattr(history, "HIST", str(tmp_path / "logs" / "history.jsonl"))
    now = datetime.datetime(2026, 7, 13, 9, 0)

    assert gather.sleep_minutes_last_night(now) is None


def test_sleep_minutes_last_night_returns_latest_watch_reading_in_window(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    monkeypatch.setattr(history, "HIST", str(tmp_path / "logs" / "history.jsonl"))
    now = datetime.datetime(2026, 7, 13, 9, 0)
    history.append("sleep_dur", ts="2026-07-12T23:00:00", meta={"minutes": 300})
    history.append("sleep_dur", ts="2026-07-13T06:00:00", meta={"minutes": 402})

    assert gather.sleep_minutes_last_night(now) == 402


def test_sleep_minutes_last_night_ignores_readings_outside_the_18h_window(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    monkeypatch.setattr(history, "HIST", str(tmp_path / "logs" / "history.jsonl"))
    now = datetime.datetime(2026, 7, 13, 9, 0)
    history.append("sleep_dur", ts="2026-07-11T06:00:00", meta={"minutes": 402})

    assert gather.sleep_minutes_last_night(now) is None


class _FakeFlowSavvy:
    def __init__(self, gym_task_today):
        self._gym_task_today = gym_task_today

    def list_items(self, **kwargs):
        if not self._gym_task_today:
            return {"items": []}
        return {"items": [{"title": "Gym", "startDateTime": "2026-07-12T18:00:00"}]}


def test_gym_ring_now_reflects_trailing_history_and_todays_schedule(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    monkeypatch.setattr(history, "HIST", str(tmp_path / "logs" / "history.jsonl"))
    now = datetime.datetime(2026, 7, 12, 9, 0)
    for day in ["2026-07-07", "2026-07-09", "2026-07-10"]:
        history.append("gym", ts=f"{day}T18:00:00")

    ring = gather.gym_ring_now(_FakeFlowSavvy(gym_task_today=True), now)

    assert ring["gym_last_7d"] == 3
    assert ring["gym_target"] == 4
    assert ring["fill"] == 0.75
    assert ring["color"] == "yellow"


def test_gym_ring_now_green_after_completing_todays_session(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    monkeypatch.setattr(history, "HIST", str(tmp_path / "logs" / "history.jsonl"))
    now = datetime.datetime(2026, 7, 12, 19, 0)
    for day in ["2026-07-07", "2026-07-09", "2026-07-10", "2026-07-12"]:
        history.append("gym", ts=f"{day}T18:00:00")

    ring = gather.gym_ring_now(_FakeFlowSavvy(gym_task_today=False), now)

    assert ring["color"] == "green"
    assert ring["gym_last_7d"] == 4


def test_gym_ring_now_red_after_a_week_with_no_sessions(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    monkeypatch.setattr(history, "HIST", str(tmp_path / "logs" / "history.jsonl"))
    now = datetime.datetime(2026, 7, 12, 9, 0)

    ring = gather.gym_ring_now(_FakeFlowSavvy(gym_task_today=False), now)

    assert ring == {"fill": 0.0, "color": "red", "gym_last_7d": 0, "gym_target": 4, "today_done": False}


class _FakePaginatedFlowSavvy:
    """Splits itemType="event" across multiple pages via nextPageToken, the
    way the real FlowSavvy API does on an account with more events than fit
    in one page -- reproduces the 2026-07-14 bug where _all_events_cached
    made a single unpaginated call and silently never saw anything past
    page 1 (on a real account, that meant every event less than ~a year
    old, including same-day friend/partner hangouts, was invisible)."""

    def __init__(self, pages):
        self._pages = pages

    def list_items(self, **kwargs):
        if kwargs.get("itemType") != "event":
            return {"items": []}
        idx = 0
        token = kwargs.get("pageToken")
        if token is not None:
            idx = int(token)
        items = self._pages[idx]
        next_token = str(idx + 1) if idx + 1 < len(self._pages) else None
        return {"items": items, "nextPageToken": next_token}


def test_all_events_cached_pages_through_every_result(monkeypatch):
    monkeypatch.setattr(gather, "_ALL_EVENTS_CACHE", {})
    old_page = [{"id": "1", "title": "Ancient event"}]
    new_page = [{"id": "2", "title": "Chloe", "notes": "type: friends"}]
    fs = _FakePaginatedFlowSavvy([old_page, new_page])

    events = gather._all_events_cached(fs)

    assert events == old_page + new_page


class _FakeSpendFlowSavvy:
    """calendar_events maps calendarId -> its events; list_items with no
    calendarId returns the union across every calendar (mirrors the real
    FlowSavvy API's "all calendars" behavior), same as spend_input's broad
    sweep pass relies on. tasks is returned for itemType="task"."""

    def __init__(self, calendar_events, tasks=None):
        self._calendar_events = calendar_events
        self._tasks = tasks or []

    def list_items(self, **kwargs):
        if kwargs.get("itemType") == "task":
            return {"items": self._tasks}
        cid = kwargs.get("calendarId")
        if cid is not None:
            return {"items": self._calendar_events.get(cid, [])}
        all_events = [e for evs in self._calendar_events.values() for e in evs]
        return {"items": all_events}


class _FakeYnab:
    def __init__(self, balance_dollars):
        self._balance_dollars = balance_dollars

    def month(self):
        return {"categories": [
            {"name": "Shopping", "balance": self._balance_dollars * 1000},
            {"name": "Fun", "balance": 90_000},
        ]}


def _patch_spend_config(monkeypatch, event_cals=None, costs=None):
    monkeypatch.setattr(config, "EVENT_CALS", event_cals or {})
    monkeypatch.setattr(config, "COSTS", costs if costs is not None else {"friends": 35, "date": 50})
    monkeypatch.setattr(config, "DISCRETIONARY", ["shopping"])
    monkeypatch.setattr(config, "PARTNER_TASK", "Partner time")
    monkeypatch.setattr(config, "FRIENDS_TASK", "Friends")
    # spend_input's cross-calendar sweep is cached per-process (see
    # gather._all_events_cached) -- reset it per-test or a later test's
    # fake fs would silently see an earlier test's cached "all events".
    monkeypatch.setattr(gather, "_ALL_EVENTS_CACHE", {})


def test_spend_input_uses_projected_cost_for_mapped_calendar_event(monkeypatch):
    _patch_spend_config(monkeypatch, event_cals={"cal1": "friends"})
    fs = _FakeSpendFlowSavvy({"cal1": [
        {"id": "e1", "title": "Chloe hangout", "startDateTime": "2026-07-15T18:00:00", "notes": None},
    ]})
    now = datetime.datetime(2026, 7, 13, 9, 0)

    out = gather.spend_input(fs, _FakeYnab(200), now)

    assert out["events"] == [
        {"date": "2026-07-15", "type": "friends", "cost": 35, "label": "Chloe hangout", "days_until": 2},
    ]
    assert out["fun_money"] == 200
    assert out["net_fun_money"] == 165
    assert out["today_budget"] == 0
    assert out["ynab_category_balances"]["Fun"] == 90


def test_spend_input_today_budget_sums_only_same_day_events(monkeypatch):
    """today_budget must total just the days_until == 0 events -- the
    amount already earmarked for what's happening right now -- and ignore
    every other upcoming event's cost, unlike net_fun_money which nets all
    of them against the balance."""
    _patch_spend_config(monkeypatch, event_cals={"cal1": "friends", "cal2": "date"})
    fs = _FakeSpendFlowSavvy({
        "cal1": [{"id": "e1", "title": "Chloe hangout", "startDateTime": "2026-07-13T18:00:00", "notes": None}],
        "cal2": [{"id": "e2", "title": "Date night", "startDateTime": "2026-07-15T18:00:00", "notes": None}],
    })
    now = datetime.datetime(2026, 7, 13, 9, 0)

    out = gather.spend_input(fs, _FakeYnab(200), now)

    assert out["today_budget"] == 35
    assert out["net_fun_money"] == 200 - 35 - 50


def test_spend_input_explicit_note_cost_overrides_projected_cost(monkeypatch):
    _patch_spend_config(monkeypatch, event_cals={"cal1": "friends"})
    fs = _FakeSpendFlowSavvy({"cal1": [
        {"id": "e1", "title": "Chloe hangout", "startDateTime": "2026-07-15T18:00:00",
         "notes": "cost: 30"},
    ]})
    now = datetime.datetime(2026, 7, 13, 9, 0)

    out = gather.spend_input(fs, _FakeYnab(200), now)

    assert out["events"][0]["cost"] == 30
    assert out["net_fun_money"] == 170


def test_spend_input_sweeps_unmapped_calendar_event_with_explicit_type_note(monkeypatch):
    """The example from the user's own request: a hangout on a calendar
    never mapped in EVENT_CALS still gets counted, purely from its notes
    declaring "type: friends" (+ an explicit cost)."""
    _patch_spend_config(monkeypatch, event_cals={})
    fs = _FakeSpendFlowSavvy({"personal": [
        {"id": "e1", "title": "Chloe hangout", "startDateTime": "2026-07-15T18:00:00",
         "notes": "type: friends\ncost: 30"},
    ]})
    now = datetime.datetime(2026, 7, 13, 9, 0)

    out = gather.spend_input(fs, _FakeYnab(200), now)

    assert out["events"] == [
        {"date": "2026-07-15", "type": "friends", "cost": 30, "label": "Chloe hangout", "days_until": 2},
    ]
    assert out["net_fun_money"] == 170


def test_spend_input_ignores_unmapped_calendar_event_with_no_type_note(monkeypatch):
    """Without an explicit "type:" note, an event on an unmapped calendar
    must NOT be swept in -- otherwise every appointment on every calendar
    would count as a spend event."""
    _patch_spend_config(monkeypatch, event_cals={})
    fs = _FakeSpendFlowSavvy({"personal": [
        {"id": "e1", "title": "Dentist", "startDateTime": "2026-07-15T18:00:00", "notes": None},
    ]})
    now = datetime.datetime(2026, 7, 13, 9, 0)

    out = gather.spend_input(fs, _FakeYnab(200), now)

    assert out["events"] == []
    assert out["net_fun_money"] == 200


def test_spend_input_does_not_double_count_mapped_event_in_broad_sweep(monkeypatch):
    """An event that's already captured via its mapped calendar must not
    also get swept in again by the broad "every calendar" pass, even though
    that pass sees it too (same event, no calendarId filter)."""
    _patch_spend_config(monkeypatch, event_cals={"cal1": "friends"})
    fs = _FakeSpendFlowSavvy({"cal1": [
        {"id": "e1", "title": "Chloe hangout", "startDateTime": "2026-07-15T18:00:00",
         "notes": "type: friends"},
    ]})
    now = datetime.datetime(2026, 7, 13, 9, 0)

    out = gather.spend_input(fs, _FakeYnab(200), now)

    assert len(out["events"]) == 1


def test_spend_input_task_note_cost_override(monkeypatch):
    _patch_spend_config(monkeypatch, event_cals={})
    fs = _FakeSpendFlowSavvy({}, tasks=[
        {"title": "Friends", "startDateTime": "2026-07-15T18:00:00", "notes": "cost: 20"},
    ])
    now = datetime.datetime(2026, 7, 13, 9, 0)

    out = gather.spend_input(fs, _FakeYnab(200), now)

    assert out["events"][0]["cost"] == 20
    assert out["net_fun_money"] == 180


def test_spend_input_fun_money_stays_raw_for_other_consumers(monkeypatch):
    """fun_money must stay the RAW balance -- spend_engine.plan() and
    run_cashflow's projection both re-derive "balance vs. upcoming cost"
    from it themselves and would double-subtract event costs if this were
    already netted."""
    _patch_spend_config(monkeypatch, event_cals={"cal1": "friends"})
    fs = _FakeSpendFlowSavvy({"cal1": [
        {"id": "e1", "title": "Chloe hangout", "startDateTime": "2026-07-15T18:00:00", "notes": None},
    ]})
    now = datetime.datetime(2026, 7, 13, 9, 0)

    out = gather.spend_input(fs, _FakeYnab(200), now)

    assert out["fun_money"] == 200
    assert out["net_fun_money"] == 200 - out["events"][0]["cost"]


def test_spend_input_broad_sweep_is_cached_across_calls_with_same_fs(monkeypatch):
    """run_spend/run_briefing/run_cashflow all call spend_input with the
    SAME FlowSavvy instance within one runner.py _run() process -- the
    cross-calendar sweep must not re-fetch on every call (it used to,
    tripling that network round trip every day for identical results)."""
    _patch_spend_config(monkeypatch, event_cals={})
    calls = []
    real_fs = _FakeSpendFlowSavvy({"personal": [
        {"id": "e1", "title": "Chloe hangout", "startDateTime": "2026-07-15T18:00:00",
         "notes": "type: friends"},
    ]})

    def _counting_list_items(**kwargs):
        if kwargs.get("calendarId") is None and kwargs.get("itemType") == "event":
            calls.append(1)
        return _FakeSpendFlowSavvy.list_items(real_fs, **kwargs)
    real_fs.list_items = _counting_list_items
    now = datetime.datetime(2026, 7, 13, 9, 0)

    gather.spend_input(real_fs, _FakeYnab(200), now)
    gather.spend_input(real_fs, _FakeYnab(200), now)
    gather.spend_input(real_fs, _FakeYnab(200), now)

    assert len(calls) == 1


def _patch_social_config(monkeypatch, social_cal=""):
    monkeypatch.setattr(config, "PARTNER_TASK", "Partner time")
    monkeypatch.setattr(config, "FRIENDS_TASK", "Friends")
    monkeypatch.setattr(config, "SOCIAL_CAL", social_cal)
    monkeypatch.setattr(config, "PROPOSE_AHEAD_DAYS", 7)
    monkeypatch.setattr(config, "EVENT_CALS", {})
    monkeypatch.setattr(config, "FRIEND_NAMES", [])
    # social_input's friend_event_next now reads gather._ALL_EVENTS_CACHE
    # (see gather.py's 2026-07-14 fix reusing _all_events_cached instead of
    # its own fresh fs.list_items(itemType="event") call) -- same
    # per-process cache _patch_spend_config already has to reset, or a
    # later test's fake fs would silently see an earlier test's cached
    # "all events".
    monkeypatch.setattr(gather, "_ALL_EVENTS_CACHE", {})


def test_social_input_days_until_none_when_nothing_planned(monkeypatch):
    _patch_social_config(monkeypatch)
    fs = _FakeSpendFlowSavvy({}, tasks=[])
    now = datetime.datetime(2026, 7, 13, 9, 0)

    out = gather.social_input(fs, now)

    assert out["has_partner"] is False
    assert out["partner_days_until"] is None
    assert out["has_friend"] is False
    assert out["friend_days_until"] is None


def test_social_input_good_days_start_next_week(monkeypatch):
    _patch_social_config(monkeypatch)
    fs = _FakeSpendFlowSavvy({}, tasks=[])
    now = datetime.datetime(2026, 7, 13, 9, 0)

    out = gather.social_input(fs, now)

    assert min(datetime.date.fromisoformat(d) for d in out["good_days"]) >= now.date() + datetime.timedelta(days=7)


def test_social_input_good_days_nonempty_even_with_tiny_propose_ahead_days(monkeypatch):
    """PROPOSE_AHEAD_DAYS is user-editable via the panel's Settings page.
    `hi` used to be derived independently from PROPOSE_AHEAD_DAYS (not from
    the already-floored `lo`), so PROPOSE_AHEAD_DAYS <= 3 produced hi < lo
    and silently emptied good_days -- disabling all future hangout
    proposals with no error anywhere (confirmed 2026-07-14)."""
    _patch_social_config(monkeypatch)
    for tiny in (0, 1, 2, 3):
        monkeypatch.setattr(config, "PROPOSE_AHEAD_DAYS", tiny)
        fs = _FakeSpendFlowSavvy({}, tasks=[])
        now = datetime.datetime(2026, 7, 13, 9, 0)

        out = gather.social_input(fs, now)

        assert out["good_days"], f"good_days was empty for PROPOSE_AHEAD_DAYS={tiny}"
        assert min(datetime.date.fromisoformat(d) for d in out["good_days"]) >= now.date() + datetime.timedelta(days=7)


def test_social_input_days_until_reads_soonest_scheduled_task(monkeypatch):
    _patch_social_config(monkeypatch)
    fs = _FakeSpendFlowSavvy({}, tasks=[
        {"title": "Friends (proposed)", "startDateTime": "2026-07-20T18:00:00", "completed": False},
        {"title": "Friends", "startDateTime": "2026-07-17T18:00:00", "completed": False},
    ])
    now = datetime.datetime(2026, 7, 13, 9, 0)

    out = gather.social_input(fs, now)

    assert out["has_friend"] is True
    assert out["friend_days_until"] == 4


def test_social_input_placeholders_hold_capacity_but_do_not_set_next(monkeypatch):
    _patch_social_config(monkeypatch)
    fs = _FakeSpendFlowSavvy({}, tasks=[
        {"title": "Friends (proposed)", "startDateTime": "2026-07-20T18:00:00", "completed": False},
        {"title": "Plan Friends", "dueDateTime": "2026-07-17T21:00:00", "completed": False},
    ])
    now = datetime.datetime(2026, 7, 13, 9, 0)

    out = gather.social_input(fs, now)

    assert out["has_friend"] is True
    assert out["friend_days_until"] is None


def test_social_input_ignores_old_lifeops_locked_social_task_for_next(monkeypatch):
    _patch_social_config(monkeypatch)
    fs = _FakeSpendFlowSavvy({}, tasks=[
        {"title": "Friends", "startDateTime": "2026-07-19T21:00:00", "completed": False,
         "notes": "Locked in (LifeOps)."},
    ])
    now = datetime.datetime(2026, 7, 14, 9, 0)

    out = gather.social_input(fs, now)

    assert out["has_friend"] is True
    assert out["friend_days_until"] is None


def test_social_input_days_until_reads_friend_name_calendar_event(monkeypatch):
    _patch_social_config(monkeypatch)
    monkeypatch.setattr(config, "FRIEND_NAMES", ["Chloe"])
    fs = _FakeSpendFlowSavvy({"personal": [
        {"id": "e1", "title": "Chloe tonight", "startDateTime": "2026-07-13T19:00:00"},
    ]}, tasks=[
        {"title": "Friends (proposed)", "startDateTime": "2026-07-20T18:00:00", "completed": False},
    ])
    now = datetime.datetime(2026, 7, 13, 9, 0)

    out = gather.social_input(fs, now)

    assert out["has_friend"] is True
    assert out["friend_days_until"] == 0


def test_social_input_days_until_reads_friend_calendar_event_note_type(monkeypatch):
    _patch_social_config(monkeypatch)
    fs = _FakeSpendFlowSavvy({"personal": [
        {"id": "e1", "title": "Dinner", "startDateTime": "2026-07-14T19:00:00",
         "notes": "type: friends"},
    ]}, tasks=[])
    now = datetime.datetime(2026, 7, 13, 9, 0)

    out = gather.social_input(fs, now)

    assert out["has_friend"] is True
    assert out["friend_days_until"] == 1


def test_social_input_days_until_reads_friend_calendar_event_note_types(monkeypatch):
    _patch_social_config(monkeypatch)
    fs = _FakeSpendFlowSavvy({"personal": [
        {"id": "e1", "title": "Show", "startDateTime": "2026-07-14T19:00:00",
         "notes": "type: concerts, friends"},
    ]}, tasks=[])
    now = datetime.datetime(2026, 7, 13, 9, 0)

    out = gather.social_input(fs, now)

    assert out["has_friend"] is True
    assert out["friend_days_until"] == 1


def test_social_input_days_until_reads_friend_task_note_type(monkeypatch):
    _patch_social_config(monkeypatch)
    fs = _FakeSpendFlowSavvy({}, tasks=[
        {"title": "Dinner", "startDateTime": "2026-07-14T19:00:00",
         "notes": "type: friends", "completed": False},
    ])
    now = datetime.datetime(2026, 7, 13, 9, 0)

    out = gather.social_input(fs, now)

    assert out["has_friend"] is True
    assert out["friend_days_until"] == 1


def test_social_input_days_until_reads_friend_task_note_types(monkeypatch):
    _patch_social_config(monkeypatch)
    fs = _FakeSpendFlowSavvy({}, tasks=[
        {"title": "Show", "startDateTime": "2026-07-14T19:00:00",
         "notes": "type: concerts, friends", "completed": False},
    ])
    now = datetime.datetime(2026, 7, 13, 9, 0)

    out = gather.social_input(fs, now)

    assert out["has_friend"] is True
    assert out["friend_days_until"] == 1


def test_social_input_days_until_reads_social_cal_event(monkeypatch):
    _patch_social_config(monkeypatch, social_cal="cal1")
    fs = _FakeSpendFlowSavvy({"cal1": [
        {"id": "e1", "title": "Date night", "startDateTime": "2026-07-16T18:00:00"},
    ]}, tasks=[])
    now = datetime.datetime(2026, 7, 13, 9, 0)

    out = gather.social_input(fs, now)

    assert out["has_partner"] is True
    assert out["partner_days_until"] == 3


def test_social_input_ignores_undated_open_task_for_days_until(monkeypatch):
    """A "Friends" task with no scheduled time yet (still up for
    auto-scheduling) counts toward has_friend, same as always, but must not
    claim a phantom "planned for today" (days_until=0) -- only a task that
    actually has a start/due time on the calendar counts as "planned"."""
    _patch_social_config(monkeypatch)
    fs = _FakeSpendFlowSavvy({}, tasks=[
        {"title": "Friends", "startDateTime": None, "completed": False},
    ])
    now = datetime.datetime(2026, 7, 13, 9, 0)

    out = gather.social_input(fs, now)

    assert out["has_friend"] is True
    assert out["friend_days_until"] is None
