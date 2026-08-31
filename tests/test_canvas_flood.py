"""runner._canvas_sync — flood guard.

A healthy incremental sync creates a handful of tasks; a state-loss re-sync
tries to create the whole course (~59). The guard HOLDS when a run would create
more than _CANVAS_FLOOD_MAX, writing a pending file + alert instead of flooding,
and only proceeds once approved (flood_ack).
"""
import datetime, os
import pytest

from lifeops import runner, history, config, state_store
from lifeops.domains import canvas as canvas_domain
from lifeops.engines import canvas_engine

NOW = datetime.datetime(2026, 7, 8, 9, 0, 0)
COURSE_ID = "test-course"


def _strip_html(s):
    return s or ""


class _FakeCanvas:
    def __init__(self, n_readings):
        self._n = n_readings

    def modules(self, course_id=None):
        return [{"id": 9, "name": "Module 9", "unlock_at": "2026-01-01T00:00:00Z",
                 "items": [{"type": "Page", "title": "Readings", "page_url": "rp"}]}]

    def assignments(self, course_id=None):
        return []

    def page(self, slug, course_id=None):
        return {"body": "x"}

    def announcements(self, since_date=None, course_id=None):
        return []


class _FakeLLM:
    def __init__(self, n):
        self._n = n

    def extract_readings(self, text, module_num):
        # n genuinely distinct readings (unrelated words) so none collapse under
        # the 0.93 similarity dedup — we want the raw count to reach the guard
        auth = ["Alvarez", "Bennett", "Chen", "Diaz", "Ellis", "Ford", "Gupta",
                "Hughes", "Ito", "Jones", "Kim", "Lopez", "Mata", "Novak"]
        topic = ["Rivers", "Mountains", "Deserts", "Oceans", "Forests", "Glaciers",
                 "Volcanoes", "Prairies", "Canyons", "Coral Reefs", "Tundra",
                 "Wetlands", "Savannahs", "Estuaries"]
        return [{"author": auth[i], "title": topic[i], "type": "article"}
                for i in range(self._n)]


class _FakeFS:
    def __init__(self):
        self.created = []

    def list_items(self, itemType=None, listId=None, completed=False, query=None, **k):
        return {"items": []}

    def create_task(self, **kwargs):
        self.created.append(kwargs)
        return {"id": f"new{len(self.created)}"}

    def update_task(self, *a, **k):
        return {}


@pytest.fixture
def sandbox(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    monkeypatch.setattr(config, "CANVAS_COURSE_ID", COURSE_ID)
    monkeypatch.setattr(config, "CANVAS_COURSES", "")   # force legacy single-course fallback
    monkeypatch.setattr(config, "LIST_COURSE", "list-course")
    monkeypatch.setattr(config, "SH_COURSE", "sh-course")
    monkeypatch.setattr(canvas_domain, "_touch", lambda *a, **k: None)
    monkeypatch.setattr(history, "append", lambda *a, **k: None)
    monkeypatch.setattr(history, "days_with", lambda *a, **k: set())
    alerts = []
    monkeypatch.setattr(canvas_domain, "_alert_once", lambda key, *a, **k: alerts.append(key))
    os.makedirs(os.path.join(str(tmp_path), "private", "logs"), exist_ok=True)
    return tmp_path, alerts


def _sp(tmp):
    return state_store.logs_path("canvas_state.json")


def _course_state(tmp):
    """This course's bucket, unwrapped from the top-level {"courses": {...}}."""
    st = state_store.load_json(_sp(tmp)) or {}
    return st.get("courses", {}).get(COURSE_ID, {})


def _pending(tmp):
    """This course's held-sync receipt, unwrapped from the top-level
    {course_id: {...}} pending file."""
    p = state_store.load_json(state_store.logs_path("canvas_pending.json"))
    return (p or {}).get(COURSE_ID)


def _run(tmp, fs, n_readings):
    state_store.save_json_atomic(_sp(tmp), {"courses": {COURSE_ID: {"synced_modules": [], "task_titles": []}}})
    canvas_domain._canvas_sync(_FakeCanvas(n_readings), _strip_html, canvas_engine,
                        _FakeLLM(n_readings), fs, NOW)


def test_big_run_is_held_not_flooded(sandbox):
    tmp, alerts = sandbox
    fs = _FakeFS()
    _run(tmp, fs, 12)                                   # 12 > _CANVAS_FLOOD_MAX (8)
    assert fs.created == []                             # nothing created
    p = _pending(tmp)
    assert p and p["count"] == 12                       # held, with a pending receipt
    assert any(k.startswith("canvas:flood:") for k in alerts)
    # state NOT advanced — module 9 must not be marked synced while held
    assert 9 not in _course_state(tmp).get("synced_modules", [])


def test_small_run_creates_normally(sandbox):
    tmp, alerts = sandbox
    fs = _FakeFS()
    _run(tmp, fs, 3)                                    # 3 <= 8
    assert len(fs.created) == 3
    assert _pending(tmp) is None
    assert not any(k.startswith("canvas:flood:") for k in alerts)


def test_approved_run_bypasses_guard_and_clears_pending(sandbox):
    tmp, alerts = sandbox
    fs = _FakeFS()
    # a stale pending file + a same-day ack (what the panel's approve writes)
    state_store.save_json_atomic(state_store.logs_path("canvas_pending.json"), {COURSE_ID: {"count": 12}})
    state_store.save_json_atomic(_sp(tmp), {"courses": {COURSE_ID: {
        "synced_modules": [], "task_titles": [], "flood_ack": NOW.date().isoformat()}}})
    canvas_domain._canvas_sync(_FakeCanvas(12), _strip_html, canvas_engine,
                        _FakeLLM(12), fs, NOW)
    assert len(fs.created) == 12                        # bypassed → created
    assert _pending(tmp) is None                        # pending cleared
    st = _course_state(tmp)
    assert "flood_ack" not in st                        # one-shot ack consumed
    assert 9 in st.get("synced_modules", [])
