import datetime

from lifeops import config, gather, history


def test_gym_ring_red_when_zero_sessions_in_trailing_week():
    ring = gather.gym_ring(gym_last_7d=0, gym_target=4, today_needed=True, today_done=False)
    assert ring == {"fill": 0.0, "color": "red"}


def test_gym_ring_yellow_when_behind_target_and_not_done_today():
    ring = gather.gym_ring(gym_last_7d=2, gym_target=4, today_needed=False, today_done=False)
    assert ring == {"fill": 0.5, "color": "yellow"}


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
    assert gather._parse_note_overrides("type: friends\ncost: 30") == {"type": "friends", "cost": 30.0}


def test_parse_note_overrides_tolerates_dollar_sign_and_case():
    assert gather._parse_note_overrides("Type: Friends\nCost: $30.50") == {
        "type": "friends", "cost": 30.5,
    }


def test_parse_note_overrides_ignores_malformed_cost():
    assert gather._parse_note_overrides("type: friends\ncost: free") == {"type": "friends"}


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
        return {"categories": [{"name": "Shopping", "balance": self._balance_dollars * 1000}]}


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
