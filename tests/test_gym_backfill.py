"""runner._gym_backfill — log a manually-added gym item as attendance.

Backfill a past session by dropping a gym event/task on the calendar. Hybrid
detection: a user-created (unmarked) gym item counts once its slot is past;
an explicit completed/went/✅ keyword forces it regardless of date. Logged
items are tracked in gym_state.json and pruned after ~2 weeks. The whole point
is that this runs BEFORE run_gym's cleanup, so a backfill is never deleted as a
"miss."
"""
import datetime, json, os
import pytest

from lifeops import runner, history


NOW = datetime.datetime(2026, 7, 8, 12, 0, 0)   # Wed noon
TODAY = NOW.date().isoformat()


class _FakeFS:
    def __init__(self, events=None):
        self._events = events or []
        self.updated = []      # (id, kwargs)
        self.deleted = []      # id

    def list_items(self, itemType=None, query=None, completed=False):
        return {"items": self._events if itemType == "event" else []}

    def update_task(self, task_id, **body):
        self.updated.append((task_id, body))
        return {"id": task_id}

    def delete_item(self, item_id, **k):
        self.deleted.append(item_id)
        return {}


@pytest.fixture
def sandbox(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    monkeypatch.setattr(runner, "_touch", lambda *a, **k: None)
    logged = []
    monkeypatch.setattr(history, "append",
                        lambda action, **k: logged.append((action, k)))
    # empty history by default → days_with returns nothing
    monkeypatch.setattr(history, "days_with", lambda *a, **k: set())
    os.makedirs(os.path.join(str(tmp_path), "logs"), exist_ok=True)
    return tmp_path, logged


def _task(iid, title, start, end=None, notes=""):
    return {"id": iid, "itemType": "task", "title": title,
            "startDateTime": start, "endDateTime": end, "notes": notes}


def _state(tmp_path):
    p = os.path.join(str(tmp_path), "logs", "gym_state.json")
    return json.load(open(p, encoding="utf-8")) if os.path.exists(p) else {}


def test_past_unmarked_gym_task_is_logged_and_marked(sandbox):
    tmp, logged = sandbox
    fs = _FakeFS()
    task = _task("g1", "Gym", "2026-07-05T10:00:00", "2026-07-05T11:00:00")
    handled = runner._gym_backfill(fs, NOW, [task])

    assert handled == {"g1"}
    assert ("gym", {"ts": "2026-07-05T10:00:00", "source": "manual"}) in logged
    # visible confirmation: renamed with ✅ … (logged)
    assert fs.updated and fs.updated[0][0] == "g1"
    assert fs.updated[0][1]["title"].startswith("✅ Gym")
    assert _state(tmp)["logged_backfills"]["g1"] == TODAY


def test_system_scheduled_block_is_ignored(sandbox):
    tmp, logged = sandbox
    fs = _FakeFS()
    block = _task("s1", "Gym", "2026-07-05T18:00:00", "2026-07-05T19:00:00",
                  notes="Auto-scheduled by LifeOps.")
    handled = runner._gym_backfill(fs, NOW, [block])
    assert handled == set()
    assert logged == []


def test_future_gym_without_keyword_is_left_alone(sandbox):
    tmp, logged = sandbox
    fs = _FakeFS()
    plan = _task("f1", "Gym", "2026-07-11T18:00:00", "2026-07-11T19:00:00")
    handled = runner._gym_backfill(fs, NOW, [plan])
    assert handled == set()          # a plan, not attendance
    assert logged == []


def test_future_gym_with_keyword_is_logged(sandbox):
    tmp, logged = sandbox
    fs = _FakeFS()
    # e.g. logging a session you'll do later today, or an explicit "went"
    t = _task("k1", "Gym - completed", "2026-07-11T18:00:00", "2026-07-11T19:00:00")
    handled = runner._gym_backfill(fs, NOW, [t])
    assert handled == {"k1"}
    assert any(a == "gym" for a, _ in logged)


def test_already_logged_item_is_not_relogged(sandbox):
    tmp, logged = sandbox
    p = os.path.join(str(tmp), "logs", "gym_state.json")
    json.dump({"logged_backfills": {"g1": TODAY}}, open(p, "w"))
    fs = _FakeFS()
    task = _task("g1", "✅ Gym (logged)", "2026-07-05T10:00:00", "2026-07-05T11:00:00")
    handled = runner._gym_backfill(fs, NOW, [task])
    assert handled == set()
    assert logged == []


def test_day_already_recorded_is_not_double_logged(sandbox, monkeypatch):
    tmp, logged = sandbox
    # history already has a gym on that day (e.g. the geofence caught it)
    monkeypatch.setattr(history, "days_with",
                        lambda action, a, b: {"2026-07-05"} if action == "gym" else set())
    fs = _FakeFS()
    task = _task("g1", "Gym", "2026-07-05T10:00:00", "2026-07-05T11:00:00")
    handled = runner._gym_backfill(fs, NOW, [task])
    # still handled (tracked + renamed) but NOT re-appended to history
    assert handled == {"g1"}
    assert not any(a == "gym" for a, _ in logged)


def test_old_logged_backfill_is_pruned_after_ttl(sandbox):
    tmp, logged = sandbox
    old = (NOW.date() - datetime.timedelta(days=15)).isoformat()
    recent = (NOW.date() - datetime.timedelta(days=3)).isoformat()
    p = os.path.join(str(tmp), "logs", "gym_state.json")
    json.dump({"logged_backfills": {"old1": old, "keep1": recent}}, open(p, "w"))
    fs = _FakeFS()
    runner._gym_backfill(fs, NOW, [])
    assert fs.deleted == ["old1"]                       # aged out → deleted
    st = _state(tmp)["logged_backfills"]
    assert "old1" not in st and "keep1" in st           # recent receipt kept


def test_manual_event_is_logged_without_rename(sandbox):
    tmp, logged = sandbox
    # events are fetched inside _gym_backfill; no update_event on the client
    ev = {"id": "e1", "itemType": "event", "title": "Gym",
          "startDateTime": "2026-07-06T09:00:00", "endDateTime": "2026-07-06T10:00:00",
          "notes": ""}
    fs = _FakeFS(events=[ev])
    handled = runner._gym_backfill(fs, NOW, [])
    assert handled == {"e1"}
    assert any(a == "gym" for a, _ in logged)
    assert fs.updated == []          # events are not renamed
