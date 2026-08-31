"""Multi-course Canvas sync: config.canvas_courses() drives run_canvas to loop
over every configured course, each with its own FlowSavvy list and its own
canvas_state.json bucket (keyed by course_id) — see runner._canvas_sync and
config.canvas_courses.

Also covers the one-time migration of a pre-multi-course flat canvas_state.json
into the nested {"courses": {...}} schema.
"""
import datetime
import pytest

from lifeops import runner, history, config, state_store
from lifeops.domains import canvas as canvas_domain
from lifeops.engines import canvas_engine

NOW = datetime.datetime(2026, 7, 8, 9, 0, 0)


class _FakeMultiCanvas:
    """Routes modules()/assignments() by course_id — stands in for one
    BrowserCanvas/Canvas instance reused across every configured course."""
    def __init__(self, modules_by_course, assignments_by_course=None):
        self._modules_by_course = modules_by_course
        self._assignments_by_course = assignments_by_course or {}

    def modules(self, course_id=None):
        return self._modules_by_course.get(course_id, [])

    def assignments(self, course_id=None):
        return self._assignments_by_course.get(course_id, [])

    def page(self, slug, course_id=None):
        return {"body": ""}

    def announcements(self, since_date=None, course_id=None):
        return []


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


class _FakeLLM:
    def extract_readings(self, text, module_num):
        return []


def _module(mod_id, num, name=None, assignment_id=None):
    items = []
    if assignment_id is not None:
        items.append({"type": "Assignment", "content_id": assignment_id})
    return {"id": mod_id, "name": name or f"Module {num}",
            "unlock_at": "2026-01-01T00:00:00Z", "items": items}


def _assignment(aid, name="Reply", due="2026-07-05T23:59:59Z"):
    return {"id": aid, "name": name, "due_at": due}


@pytest.fixture
def sandbox(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    monkeypatch.setattr(canvas_domain, "_touch", lambda *a, **k: None)
    monkeypatch.setattr(canvas_domain, "_alert_once", lambda *a, **k: None)
    monkeypatch.setattr(history, "append", lambda *a, **k: None)
    return tmp_path


def test_two_courses_sync_independently(sandbox, monkeypatch):
    monkeypatch.setattr(config, "CANVAS_COURSES", "course-a:list-a,course-b:list-b")
    courses = config.canvas_courses()
    cv = _FakeMultiCanvas(
        modules_by_course={
            "course-a": [_module(1, 1, assignment_id=101)],
            "course-b": [_module(2, 1, assignment_id=201)],
        },
        assignments_by_course={
            "course-a": [_assignment(101)],
            "course-b": [_assignment(201)],
        },
    )
    fs = _FakeFS()
    for course in courses:
        canvas_domain._canvas_sync(cv, lambda s: s, canvas_engine, _FakeLLM(), fs, NOW, course)

    # each course's tasks landed in its OWN FlowSavvy list
    list_ids = {t["listId"] for t in fs.created}
    assert list_ids == {"list-a", "list-b"}

    sp = state_store.logs_path("canvas_state.json")
    st = state_store.load_json(sp)
    assert set(st["courses"]) == {"course-a", "course-b"}
    assert st["courses"]["course-a"]["synced_module_ids"] == [1]
    assert st["courses"]["course-b"]["synced_module_ids"] == [2]


def test_flood_hold_on_one_course_does_not_block_the_other(sandbox, monkeypatch):
    monkeypatch.setattr(config, "CANVAS_COURSES", "course-a:list-a,course-b:list-b")
    courses = config.canvas_courses()
    # course-a: a single reading-free module (small, normal create).
    # course-b: rely on the flood guard by pre-seeding a huge `creates` via a
    # spied plan() — simplest way to trigger it deterministically here.
    cv = _FakeMultiCanvas({
        "course-a": [_module(1, 1)],
        "course-b": [_module(2, 1)],
    })
    fs = _FakeFS()

    real_plan = canvas_engine.plan
    def _spy_plan(modules_data, existing_titles, today, existing_source_ids=None):
        if modules_data and modules_data[0].get("_mod_id") == 2:
            return {"creates": [{"title": f"flood-{i}", "durationMinutes": 10}
                                for i in range(20)],
                    "report": "flood"}
        return real_plan(modules_data, existing_titles, today, existing_source_ids)
    monkeypatch.setattr(canvas_engine, "plan", _spy_plan)

    for course in courses:
        canvas_domain._canvas_sync(cv, lambda s: s, canvas_engine, _FakeLLM(), fs, NOW, course)

    # course-a synced normally...
    sp = state_store.logs_path("canvas_state.json")
    st = state_store.load_json(sp)
    assert 1 in st["courses"]["course-a"].get("synced_module_ids", [])
    # ...course-b was held, not flooded, and not marked synced
    assert 2 not in st["courses"].get("course-b", {}).get("synced_module_ids", [])
    pending = state_store.load_json(state_store.logs_path("canvas_pending.json"))
    assert "course-b" in pending
    assert "course-a" not in pending


def test_legacy_flat_state_migrates_once(sandbox, monkeypatch):
    monkeypatch.setattr(config, "CANVAS_COURSE_ID", "legacy-course")
    monkeypatch.setattr(config, "CANVAS_COURSES", "")   # force legacy single-course fallback
    monkeypatch.setattr(config, "LIST_COURSE", "legacy-list")
    sp = state_store.logs_path("canvas_state.json")
    state_store.save_json_atomic(sp, {
        "synced_modules": [3], "synced_module_ids": [30],
        "task_titles": ["M03: Old Task"],
    })

    cv = _FakeMultiCanvas({"legacy-course": [_module(31, 4)]})
    fs = _FakeFS()
    canvas_domain._canvas_sync(cv, lambda s: s, canvas_engine, _FakeLLM(), fs, NOW)

    st = state_store.load_json(sp)
    assert "synced_modules" not in st                 # no stray flat keys left behind
    bucket = st["courses"]["legacy-course"]
    assert bucket["synced_module_ids"] == [30, 31]     # legacy id preserved + new one added
    assert "M03: Old Task" in bucket["task_titles"]

    # running again must not re-trigger the migration / lose anything
    canvas_domain._canvas_sync(cv, lambda s: s, canvas_engine, _FakeLLM(), fs, NOW)
    st2 = state_store.load_json(sp)
    assert st2["courses"]["legacy-course"]["synced_module_ids"] == [30, 31]
