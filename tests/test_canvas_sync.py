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
    def __init__(self, modules, assignments, announcements=None, file_texts=None):
        self._modules = modules
        self._assignments = assignments
        self._announcements = announcements or []
        # {file_id (str): text} -- a linked file NOT in here (the default,
        # empty dict) simulates a real link that exists but can't be read
        # (no session, dead link, fetch error), same as the real
        # Canvas.file_text/BrowserCanvas.file_text contract of returning ""
        # on any failure.
        self._file_texts = file_texts or {}

    def modules(self, course_id=None):
        return self._modules

    def assignments(self, course_id=None):
        return self._assignments

    def page(self, slug, course_id=None):
        return {"body": ""}

    def announcements(self, since_date=None, course_id=None):
        return self._announcements

    def file_text(self, file_id, course_id=None, max_chars=6000):
        return self._file_texts.get(str(file_id), "")


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
    content_aware_labels = [
        {"name": "Pull the Dataset", "minutes": 45},
        {"name": "Explore & Model", "minutes": 120},
        {"name": "Write the Report", "minutes": 60},
    ]
    llm2 = FakeLLMWithPhaseLabels(content_aware_labels)
    cv2 = FakeCanvas(modules=modules,
                     assignments=[{"id": 9, "name": "Big Project",
                                   "due_at": "2026-07-20T23:59:59Z",
                                   "description": "<p>Real instructions now.</p>"}])
    canvas_domain._canvas_sync(cv2, lambda s: s, canvas_engine, llm2, fs, NOW)

    assert llm2.calls == 1   # description now present -- actually asked this time
    assert len(fs.created) == 3, "nothing new should be CREATED -- these tasks already exist"
    assert len(fs.updated) == 3, "all 3 existing phase tasks should be upserted in place"
    renamed = {item_id: kwargs["title"] for item_id, kwargs in fs.updated}
    assert set(renamed.values()) == {f"Big Project — {label['name']}" for label in content_aware_labels}
    # the upsert is faithful across the whole phase, not just the title --
    # duration and notes (including the re-stamped [canvas-ref] marker) get
    # corrected too, since the first pass's generic guess was only ever as
    # good as the empty description it had.
    by_title = {kwargs["title"]: kwargs for _, kwargs in fs.updated}
    for i, label in enumerate(content_aware_labels, start=1):
        kwargs = by_title[f"Big Project — {label['name']}"]
        assert kwargs["durationMinutes"] == label["minutes"]
        # per-phase marker, matching what creation stamps -- NOT a bare
        # "assignment:9" shared across all 3 phases, which would collapse
        # exact-id dedup for all of them on a future state-loss resync.
        assert f"[canvas-ref: assignment:9:phase:{i}]" in kwargs["notes"]
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


def test_refresh_assignment_forces_upsert_even_when_already_content_aware(tmp_path, monkeypatch):
    # An assignment can be marked content_aware from a first pass that only
    # had thin/boilerplate content to go on (a linked file that couldn't be
    # fetched yet) -- the normal rename check skips anything already marked
    # content_aware forever. refresh_assignment() is the manual escape
    # hatch: the user later supplies/points to better content, and the next
    # sync must re-derive and upsert (title, duration, notes) faithfully.
    monkeypatch.setattr(runner.history, "ROOT", str(tmp_path))
    monkeypatch.setattr(config, "CANVAS_COURSE_ID", COURSE_ID)
    monkeypatch.setattr(config, "CANVAS_COURSES", "")
    monkeypatch.setattr(config, "LIST_COURSE", "list-course")
    _write_state(str(tmp_path), synced_modules=[], task_titles=[])

    fs = FakeFS(course_tasks=[])
    modules = [{"id": 100, "name": "Module 4", "unlock_at": "2026-06-20T00:00:00Z",
                "items": [{"type": "Assignment", "content_id": 9}]}]

    # ── run 1: thin boilerplate description -- LLM guesses phases anyway,
    # they get cached and marked content_aware (this models the PS3 case:
    # validation only checks field shape, not whether the guess is grounded) ──
    thin_labels = [
        {"name": "Setup", "minutes": 25},
        {"name": "Guessed Middle Step", "minutes": 85},
        {"name": "Finish & Submit", "minutes": 50},
    ]
    llm1 = FakeLLMWithPhaseLabels(thin_labels)
    cv1 = FakeCanvas(modules=modules,
                     assignments=[{"id": 9, "name": "Big Project",
                                   "due_at": "2026-07-20T23:59:59Z",
                                   "description": "<p>Boilerplate only.</p>"}])
    canvas_domain._canvas_sync(cv1, lambda s: s, canvas_engine, llm1, fs, NOW)
    assert len(fs.created) == 3
    assert [c["title"] for c in fs.created] == [f"Big Project — {l['name']}" for l in thin_labels]

    # run 2 with the SAME thin content: already marked content_aware, so the
    # rename check must NOT rerun the LLM or touch the tasks again.
    fs.updated.clear()
    llm_noop = FakeLLMWithPhaseLabels(thin_labels)
    canvas_domain._canvas_sync(cv1, lambda s: s, canvas_engine, llm_noop, fs, NOW)
    assert llm_noop.calls == 0
    assert fs.updated == []

    # ── the user points me at real content; I clear the cache ──
    assert canvas_domain.refresh_assignment(COURSE_ID, 9) is True

    # a second refresh on an assignment with nothing cached is a harmless no-op
    assert canvas_domain.refresh_assignment(COURSE_ID, 999) is False

    # ── run 3: better content now available -- must regenerate and upsert
    # the SAME 3 tasks in place, not create new ones, DESPITE having been
    # marked content_aware before ──
    real_labels = [
        {"name": "Pull the Real Dataset", "minutes": 45},
        {"name": "Fit the Actual Model", "minutes": 120},
        {"name": "Write the Real Report", "minutes": 60},
    ]
    llm2 = FakeLLMWithPhaseLabels(real_labels)
    cv2 = FakeCanvas(modules=modules,
                     assignments=[{"id": 9, "name": "Big Project",
                                   "due_at": "2026-07-20T23:59:59Z",
                                   "description": "<p>Real, richer instructions now.</p>"}])
    canvas_domain._canvas_sync(cv2, lambda s: s, canvas_engine, llm2, fs, NOW)

    assert llm2.calls == 1
    assert len(fs.created) == 3, "still no new tasks created"
    assert len(fs.updated) == 3, "all 3 phase tasks upserted with the real content"
    by_title = {kwargs["title"]: kwargs for _, kwargs in fs.updated}
    for label in real_labels:
        kwargs = by_title[f"Big Project — {label['name']}"]
        assert kwargs["durationMinutes"] == label["minutes"]
    assert {item_id for item_id, _ in fs.updated} == {"new-1", "new-2", "new-3"}


def test_refresh_assignment_with_unchanged_content_still_upserts_but_warns(tmp_path, monkeypatch, capsys):
    # If the user asks for a refresh but the newly-fetched content is
    # IDENTICAL to what was already used (e.g. the linked file still can't
    # be fetched, or nothing on Canvas actually changed), re-upserting from
    # the same content isn't real progress -- this must still happen (a
    # re-roll of the LLM call can legitimately differ, and the user
    # explicitly asked for a recheck) but it must say so plainly, so a
    # silent "updated!" doesn't look like it used new information when it
    # didn't. Traceability: each assignment's phase_task_ids entry tracks a
    # content_hash so this comparison is possible at all.
    monkeypatch.setattr(runner.history, "ROOT", str(tmp_path))
    monkeypatch.setattr(config, "CANVAS_COURSE_ID", COURSE_ID)
    monkeypatch.setattr(config, "CANVAS_COURSES", "")
    monkeypatch.setattr(config, "LIST_COURSE", "list-course")
    _write_state(str(tmp_path), synced_modules=[], task_titles=[])

    fs = FakeFS(course_tasks=[])
    modules = [{"id": 100, "name": "Module 4", "unlock_at": "2026-06-20T00:00:00Z",
                "items": [{"type": "Assignment", "content_id": 9}]}]
    same_description = "<p>Boilerplate only.</p>"
    labels_v1 = [
        {"name": "Setup", "minutes": 25},
        {"name": "Guessed Middle Step", "minutes": 85},
        {"name": "Finish & Submit", "minutes": 50},
    ]
    cv = FakeCanvas(modules=modules,
                    assignments=[{"id": 9, "name": "Big Project",
                                  "due_at": "2026-07-20T23:59:59Z",
                                  "description": same_description}])
    canvas_domain._canvas_sync(cv, lambda s: s, canvas_engine, FakeLLMWithPhaseLabels(labels_v1), fs, NOW)

    from lifeops import state_store
    sp = os.path.join(str(tmp_path), "private", "logs", "canvas_state.json")
    st_before = state_store.load_json(sp)["courses"][COURSE_ID]["phase_task_ids"]["9"]
    assert st_before["content_hash"], "the content fingerprint must be recorded"

    assert canvas_domain.refresh_assignment(COURSE_ID, 9) is True
    fs.updated.clear()
    capsys.readouterr()   # discard run-1 output

    # same Canvas description as before -- a re-roll, not real new information
    labels_v2 = [
        {"name": "Setup Again", "minutes": 25},
        {"name": "Guessed Middle Step Again", "minutes": 85},
        {"name": "Finish & Submit Again", "minutes": 50},
    ]
    canvas_domain._canvas_sync(cv, lambda s: s, canvas_engine, FakeLLMWithPhaseLabels(labels_v2), fs, NOW)

    assert len(fs.updated) == 3, "still upserts -- the user explicitly asked for a recheck"
    out = capsys.readouterr().out
    assert "NOT new information" in out

    st_after = state_store.load_json(sp)["courses"][COURSE_ID]["phase_task_ids"]["9"]
    assert st_after["content_hash"] == st_before["content_hash"], \
        "hash is unchanged since the underlying content never changed"


class FakeLLMDriftingReadings:
    """Returns a slightly different reading title on every call -- models
    the real observed non-determinism of llm.extract_readings, used to prove
    readings_cache prevents the exact failure mode that caused a real
    incident (2026-07-06): a state-loss re-sync re-extracted an
    already-synced module's readings from scratch, and the reworded output
    dodged dedup entirely, creating near-duplicates."""
    def __init__(self):
        self.calls = 0

    def extract_readings(self, text, module_num):
        self.calls += 1
        return [{"author": "A", "title": f"M6 Reading (v{self.calls})", "type": "article"}]


def test_reading_extraction_cached_by_page_content_survives_a_resync(tmp_path, monkeypatch):
    monkeypatch.setattr(runner.history, "ROOT", str(tmp_path))
    monkeypatch.setattr(config, "CANVAS_COURSE_ID", COURSE_ID)
    monkeypatch.setattr(config, "CANVAS_COURSES", "")
    monkeypatch.setattr(config, "LIST_COURSE", "list-course")
    _write_state(str(tmp_path), synced_modules=[], task_titles=[])

    class _CanvasWithReadingsPage(FakeCanvas):
        def page(self, slug, course_id=None):
            return {"body": "<p>Stable, unchanged page text.</p>"}

    fs = FakeFS(course_tasks=[])
    page_item = {"type": "Page", "title": "Readings and Resources", "page_url": "readings"}
    cv = _CanvasWithReadingsPage(
        modules=[{"id": 100, "name": "Module 6", "unlock_at": "2026-06-20T00:00:00Z", "items": [page_item]}],
        assignments=[],
    )
    llm = FakeLLMDriftingReadings()

    canvas_domain._canvas_sync(cv, lambda s: s, canvas_engine, llm, fs, NOW)
    assert len(fs.created) == 1
    assert fs.created[0]["title"] == "Read A, M6 Reading (v1)"
    assert llm.calls == 1

    # ── simulate the real incident: canvas_state.json's synced-module
    # tracking gets lost (but task_titles/readings_cache survive), so the
    # next sync sees Module 6 as "new" again and re-extracts its page ──
    from lifeops import state_store
    sp = os.path.join(str(tmp_path), "private", "logs", "canvas_state.json")
    st_root = state_store.load_json(sp)
    st_root["courses"][COURSE_ID]["synced_modules"] = []
    st_root["courses"][COURSE_ID]["synced_module_ids"] = []
    state_store.save_json_atomic(sp, st_root)

    canvas_domain._canvas_sync(cv, lambda s: s, canvas_engine, llm, fs, NOW)

    # Without the content cache, this second call would re-extract with
    # DRIFTED wording ("v2") that dodges title-based dedup and creates a
    # near-duplicate -- exactly what happened in production. With the
    # page-content cache, the LLM isn't even called again, so the same exact
    # title comes back and title-based dedup (via the persisted task_titles)
    # correctly recognizes it as already-created.
    assert llm.calls == 1, "unchanged page content must reuse the cached extraction"
    assert len(fs.created) == 1, "no near-duplicate should be created on the resync"


def test_missing_linked_file_flags_phases_as_info_missing(tmp_path, monkeypatch):
    # The PS3 failure mode: the description is boilerplate that links to a
    # real .qmd file, but the file couldn't be read this run (no session /
    # fetch error). The LLM still returns field-valid phases from the
    # boilerplate alone -- those phases must be flagged "info missing" (in
    # the task's own notes AND in state), not left indistinguishable from a
    # genuinely grounded result.
    monkeypatch.setattr(runner.history, "ROOT", str(tmp_path))
    monkeypatch.setattr(config, "CANVAS_COURSE_ID", COURSE_ID)
    monkeypatch.setattr(config, "CANVAS_COURSES", "")
    monkeypatch.setattr(config, "LIST_COURSE", "list-course")
    _write_state(str(tmp_path), synced_modules=[], task_titles=[])

    fs = FakeFS(course_tasks=[])
    modules = [{"id": 100, "name": "Module 4", "unlock_at": "2026-06-20T00:00:00Z",
                "items": [{"type": "Assignment", "content_id": 9}]}]
    boilerplate_with_link = (
        '<p>Answer the questions and render. '
        '<a href="https://x.test/courses/1/files/555?wrap=1">ps3.qmd</a></p>'
    )
    labels = [
        {"name": "Setup", "minutes": 25},
        {"name": "Guessed Middle Step", "minutes": 85},
        {"name": "Finish & Submit", "minutes": 50},
    ]
    # file_texts=None (default empty) -- the linked file exists but this run
    # can't read it, same as a dead/unauthenticated fetch in production.
    cv = FakeCanvas(modules=modules,
                    assignments=[{"id": 9, "name": "Big Project",
                                  "due_at": "2026-07-20T23:59:59Z",
                                  "description": boilerplate_with_link}])
    canvas_domain._canvas_sync(cv, lambda s: s, canvas_engine, FakeLLMWithPhaseLabels(labels), fs, NOW)

    assert len(fs.created) == 3
    for spec in fs.created:
        assert "Info missing" in spec["notes"]
        assert "ps3.qmd" in spec["notes"]

    missing = canvas_domain.list_missing_info(COURSE_ID)
    assert len(missing) == 1
    assert missing[0]["assignment_id"] == "9"
    assert missing[0]["missing_files"] == ["ps3.qmd"]
    assert set(missing[0]["task_ids"]) == {"new-1", "new-2", "new-3"}

    # ── the file becomes readable (session restored) and the user refreshes ──
    assert canvas_domain.refresh_assignment(COURSE_ID, 9) is True
    fs.updated.clear()
    real_labels = [
        {"name": "Pull the Real Dataset", "minutes": 45},
        {"name": "Fit the Actual Model", "minutes": 120},
        {"name": "Write the Real Report", "minutes": 60},
    ]
    cv2 = FakeCanvas(modules=modules,
                     assignments=[{"id": 9, "name": "Big Project",
                                   "due_at": "2026-07-20T23:59:59Z",
                                   "description": boilerplate_with_link}],
                     file_texts={"555": "Question 1: do the thing. Question 2: do another thing."})
    canvas_domain._canvas_sync(cv2, lambda s: s, canvas_engine, FakeLLMWithPhaseLabels(real_labels), fs, NOW)

    assert len(fs.updated) == 3
    for _, kwargs in fs.updated:
        assert "Info missing" not in kwargs["notes"], "the file is readable now -- no more warning"
    assert canvas_domain.list_missing_info(COURSE_ID) == [], \
        "resolved -- nothing should still show as missing info"


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


