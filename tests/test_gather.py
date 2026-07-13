import datetime

from lifeops import gather, history


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
