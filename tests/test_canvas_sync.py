"""_canvas_sync — the orchestration body around canvas_engine.

Regression focus: due-date changes on already-synced assignments must be
re-checked on EVERY sync, not just when a new module unlocks. A fully-synced
course produces an empty modules_data every run; an early return on that
condition silently disabled due-date re-sync for the rest of the semester.
"""
import os
import datetime

from lifeops import runner, config
from lifeops.domains import canvas as canvas_domain
from lifeops.engines import canvas_engine


NOW = datetime.datetime(2026, 7, 8, 9, 0, 0)
COURSE_ID = "test-course"


class FakeCanvas:
    """Stands in for canvas.Canvas / canvas_browser.BrowserCanvas."""
    def __init__(self, modules, assignments, announcements=None):
        self._modules = modules
        self._assignments = assignments
        self._announcements = announcements or []

    def modules(self, course_id=None):
        return self._modules

    def assignments(self, course_id=None):
        return self._assignments

    def page(self, slug, course_id=None):
        return {"body": ""}

    def announcements(self, since_date=None, course_id=None):
        return self._announcements


class FakeFS:
    def __init__(self, course_tasks):
        self.course_tasks = course_tasks
        self.created = []
        self.updated = []

    def list_items(self, itemType=None, listId=None, completed=None, query=None):
        if completed:                      # completed-history probe
            return {"items": []}
        if query:                          # due-date-change probe (substring match)
            q = query.lower()
            return {"items": [t for t in self.course_tasks
                              if q in (t.get("title") or "").lower()]}
        return {"items": self.course_tasks}

    def create_task(self, **kwargs):
        self.created.append(kwargs)
        return {"id": f"new-{len(self.created)}"}

    def update_task(self, item_id, **kwargs):
        self.updated.append((item_id, kwargs))
        return {}


class FakeLLM:
    def extract_readings(self, text, module_num):
        return []


def _write_state(root, synced_modules, task_titles):
    from lifeops import state_store
    state_store.save_json_atomic(
        os.path.join(root, "private", "logs", "canvas_state.json"),
        {"courses": {COURSE_ID: {"synced_modules": synced_modules, "task_titles": task_titles}}},
    )


def test_due_date_change_synced_after_all_modules_already_synced(tmp_path, monkeypatch):
    # Course is FULLY synced (module 2 already in synced_modules), so
    # modules_data is empty this run. A Canvas due-date shift on the
    # already-created task must still propagate to FlowSavvy.
    monkeypatch.setattr(runner.history, "ROOT", str(tmp_path))
    monkeypatch.setattr(config, "CANVAS_COURSE_ID", COURSE_ID)
    monkeypatch.setattr(config, "CANVAS_COURSES", "")   # force legacy single-course fallback
    monkeypatch.setattr(config, "LIST_COURSE", "list-course")
    _write_state(str(tmp_path), synced_modules=[2],
                 task_titles=["M02: NYC Open Data Analysis [AS.470.703.81.SU26]"])

    fs = FakeFS(course_tasks=[{
        "id": "task-1",
        "title": "M02: NYC Open Data Analysis [AS.470.703.81.SU26]",
        "dueDateTime": "2026-07-15T23:59:00",   # what FlowSavvy currently holds
    }])
    cv = FakeCanvas(
        modules=[{"id": 100, "name": "Module 2",
                  "unlock_at": "2026-06-20T00:00:00Z", "items": []}],
        assignments=[{"id": 9, "name": "NYC Open Data Analysis",
                      "due_at": "2026-07-20T23:59:59Z"}],   # instructor moved it +5 days
    )

    canvas_domain._canvas_sync(cv, lambda s: s, canvas_engine, FakeLLM(), fs, NOW)

    assert fs.created == []                    # nothing new created
    assert len(fs.updated) == 1, "due-date change was not propagated"
    item_id, kwargs = fs.updated[0]
    assert item_id == "task-1"
    assert kwargs["dueDateTime"] == "2026-07-20T23:59:00"


def test_no_update_when_due_dates_match(tmp_path, monkeypatch):
    # Fully synced, Canvas due date unchanged → no spurious update_task churn.
    monkeypatch.setattr(runner.history, "ROOT", str(tmp_path))
    monkeypatch.setattr(config, "CANVAS_COURSE_ID", COURSE_ID)
    monkeypatch.setattr(config, "CANVAS_COURSES", "")   # force legacy single-course fallback
    monkeypatch.setattr(config, "LIST_COURSE", "list-course")
    _write_state(str(tmp_path), synced_modules=[2],
                 task_titles=["M02: NYC Open Data Analysis [AS.470.703.81.SU26]"])

    fs = FakeFS(course_tasks=[{
        "id": "task-1",
        "title": "M02: NYC Open Data Analysis [AS.470.703.81.SU26]",
        "dueDateTime": "2026-07-20T23:59:00",
    }])
    cv = FakeCanvas(
        modules=[{"id": 100, "name": "Module 2",
                  "unlock_at": "2026-06-20T00:00:00Z", "items": []}],
        assignments=[{"id": 9, "name": "NYC Open Data Analysis",
                      "due_at": "2026-07-20T23:59:59Z"}],
    )

    canvas_domain._canvas_sync(cv, lambda s: s, canvas_engine, FakeLLM(), fs, NOW)

    assert fs.created == []
    assert fs.updated == []


def test_multi_phase_assignment_dependencies_chain_through_real_creation(tmp_path, monkeypatch):
    # regression: this exercises the ACTUAL fs.create_task call site (not just
    # canvas_engine.plan()'s _dep_title field) -- a multi-phase assignment's
    # phases must come out the other end wired together via blockedByIds,
    # each phase depending on the one before it.
    monkeypatch.setattr(runner.history, "ROOT", str(tmp_path))
    monkeypatch.setattr(config, "CANVAS_COURSE_ID", COURSE_ID)
    monkeypatch.setattr(config, "CANVAS_COURSES", "")   # force legacy single-course fallback
    monkeypatch.setattr(config, "LIST_COURSE", "list-course")
    _write_state(str(tmp_path), synced_modules=[], task_titles=[])

    fs = FakeFS(course_tasks=[])
    cv = FakeCanvas(
        modules=[{"id": 100, "name": "Module 4", "unlock_at": "2026-06-20T00:00:00Z",
                  "items": [{"type": "Assignment", "content_id": 9}]}],
        assignments=[{"id": 9, "name": "Case Study/Evaluation Paper",
                      "due_at": "2026-07-20T23:59:59Z"}],   # classifies as "paper" -> 3 phases
    )

    canvas_domain._canvas_sync(cv, lambda s: s, canvas_engine, FakeLLM(), fs, NOW)

    assert len(fs.created) == 3, "all 3 phases should have been created (below the flood guard)"
    # FakeFS.create_task returns ids in creation order ("new-1", "new-2", ...)
    # and plan() emits phases in chronological order, so fs.created[i] is
    # phase i+1 -- assert the chain directly rather than by title lookup.
    outline, draft, revise = fs.created
    assert outline["title"] == "Case Study/Evaluation Paper — Outline & Notes"
    assert draft["title"]   == "Case Study/Evaluation Paper — Draft"
    assert revise["title"]  == "Case Study/Evaluation Paper — Revise"

    assert "blockedByIds" not in outline, "the first phase has nothing to depend on"
    assert draft["blockedByIds"] == ["new-1"], "Draft must be blockedBy Outline's real created id"
    assert revise["blockedByIds"] == ["new-2"], "Revise must be blockedBy Draft's real created id"

