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


class FakeLLMWithPhaseLabels(FakeLLM):
    """Returns real content-aware labels ONLY when handed a non-empty
    description -- models the real llm.propose_assignment_phases contract
    (canvas_domain._phase_labels_for already refuses to call it at all
    without a description; this fake's `calls` counter still lets a test
    assert it was or wasn't invoked)."""
    def __init__(self, labels):
        self._labels = labels
        self.calls = 0

    def propose_assignment_phases(self, name, description, atype, count):
        self.calls += 1
        return self._labels if len(self._labels) == count else None


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


def test_generic_phase_names_get_renamed_once_a_description_shows_up(tmp_path, monkeypatch):
    # regression/feature: an assignment's whole semester is often visible --
    # and synced -- well before its real Canvas description is written, so
    # it gets the generic per-atype phase template at first. Once a later
    # sync sees a real description, the already-created tasks must be
    # renamed in place to the content-aware names, not left generic forever.
    monkeypatch.setattr(runner.history, "ROOT", str(tmp_path))
    monkeypatch.setattr(config, "CANVAS_COURSE_ID", COURSE_ID)
    monkeypatch.setattr(config, "CANVAS_COURSES", "")
    monkeypatch.setattr(config, "LIST_COURSE", "list-course")
    _write_state(str(tmp_path), synced_modules=[], task_titles=[])

    fs = FakeFS(course_tasks=[])
    modules = [{"id": 100, "name": "Module 4", "unlock_at": "2026-06-20T00:00:00Z",
                "items": [{"type": "Assignment", "content_id": 9}]}]

    # ── run 1: assignment has no description yet -- generic names ──
    llm1 = FakeLLMWithPhaseLabels(["should never be used"])
    cv1 = FakeCanvas(modules=modules,
                     assignments=[{"id": 9, "name": "Big Project",
                                   "due_at": "2026-07-20T23:59:59Z"}])   # no "description" key
    canvas_domain._canvas_sync(cv1, lambda s: s, canvas_engine, llm1, fs, NOW)

    assert llm1.calls == 0   # never even attempted -- no description to send
    assert len(fs.created) == 3
    generic_titles = [c["title"] for c in fs.created]
    assert generic_titles == [
        "Big Project — Setup & Data Exploration",
        "Big Project — Analysis & Visualization",
        "Big Project — Write-Up",
    ]
    assert fs.updated == []

    # ── run 2: same assignment, description has since been written ──
    content_aware_labels = ["Pull the Dataset", "Explore & Model", "Write the Report"]
    llm2 = FakeLLMWithPhaseLabels(content_aware_labels)
    cv2 = FakeCanvas(modules=modules,
                     assignments=[{"id": 9, "name": "Big Project",
                                   "due_at": "2026-07-20T23:59:59Z",
                                   "description": "<p>Real instructions now.</p>"}])
    canvas_domain._canvas_sync(cv2, lambda s: s, canvas_engine, llm2, fs, NOW)

    assert llm2.calls == 1   # description now present -- actually asked this time
    assert len(fs.created) == 3, "nothing new should be CREATED -- these tasks already exist"
    assert len(fs.updated) == 3, "all 3 existing phase tasks should be renamed in place"
    renamed = {item_id: kwargs["title"] for item_id, kwargs in fs.updated}
    assert set(renamed.values()) == {f"Big Project — {label}" for label in content_aware_labels}
    # renamed the SAME task ids that were created in run 1 (id-based, not
    # duplicated) -- FakeFS.create_task assigns "new-1"/"new-2"/"new-3" in
    # creation order.
    assert set(renamed.keys()) == {"new-1", "new-2", "new-3"}

    # ── run 3: idempotent -- must not keep re-renaming every run ──
    fs.updated.clear()
    llm3 = FakeLLMWithPhaseLabels(content_aware_labels)
    canvas_domain._canvas_sync(cv2, lambda s: s, canvas_engine, llm3, fs, NOW)
    assert llm3.calls == 0     # already marked content_aware -- never rechecked again
    assert fs.updated == []


class FakeLLMPerModuleReadings:
    """Returns one distinct reading per module -- extract_readings only ever
    sees ONE module's page text per call, so this is keyed off module_num,
    not off text content."""
    def __init__(self, by_module):
        self._by_module = by_module

    def extract_readings(self, text, module_num):
        return self._by_module.get(module_num, [])


def test_reading_dependencies_chain_across_modules_through_real_creation(tmp_path, monkeypatch):
    # regression: readings had NO dependency at all -- with no real Canvas
    # unlock-date gating (confirmed on real data: a first sync mid-semester
    # sees every already-published module as unlocked "today"), nothing
    # stopped module 8's reading from being scheduled before module 6's.
    monkeypatch.setattr(runner.history, "ROOT", str(tmp_path))
    monkeypatch.setattr(config, "CANVAS_COURSE_ID", COURSE_ID)
    monkeypatch.setattr(config, "CANVAS_COURSES", "")   # force legacy single-course fallback
    monkeypatch.setattr(config, "LIST_COURSE", "list-course")
    _write_state(str(tmp_path), synced_modules=[], task_titles=[])

    class _CanvasWithReadingsPage(FakeCanvas):
        def page(self, slug, course_id=None):
            return {"body": "<p>non-empty so strip_html/extract_readings actually runs</p>"}

    fs = FakeFS(course_tasks=[])
    page_item = {"type": "Page", "title": "Readings and Resources", "page_url": "readings"}
    cv = _CanvasWithReadingsPage(
        # module 8 listed BEFORE module 6 -- must still chain in the right direction
        modules=[
            {"id": 200, "name": "Module 8", "unlock_at": "2026-06-20T00:00:00Z", "items": [page_item]},
            {"id": 100, "name": "Module 6", "unlock_at": "2026-06-20T00:00:00Z", "items": [page_item]},
        ],
        assignments=[],
    )
    llm = FakeLLMPerModuleReadings({
        6: [{"author": "A", "title": "M6 Reading", "type": "article"}],
        8: [{"author": "B", "title": "M8 Reading", "type": "article"}],
    })

    canvas_domain._canvas_sync(cv, lambda s: s, canvas_engine, llm, fs, NOW)

    assert len(fs.created) == 2
    # FakeFS.create_task returns ids in creation order ("new-1", "new-2", ...)
    # and plan() processes modules in module-number order (6 before 8)
    # regardless of the input order above -- so fs.created[0] is module 6's
    # reading (id "new-1") and fs.created[1] is module 8's (blockedBy it).
    m6, m8 = fs.created
    assert m6["title"] == "Read A, M6 Reading"
    assert m8["title"] == "Read B, M8 Reading"
    assert "blockedByIds" not in m6, "the first reading has nothing to depend on"
    assert m8["blockedByIds"] == ["new-1"], "module 8's reading must be blockedBy module 6's"


