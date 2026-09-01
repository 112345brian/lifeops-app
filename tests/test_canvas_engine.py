"""canvas_engine — classification, phase splitting, spread math, dedup."""
import datetime
from lifeops.engines import canvas_engine as ce

TODAY  = datetime.date(2026, 6, 29)
UNLOCK = datetime.date(2026, 6, 29)
D = datetime.date


# ── classify ────────────────────────────────────────────────────────────────────

def test_classify_branches():
    assert ce.classify("Required Replies (1)") == "reply"
    assert ce.classify("Initial Findings", ["discussion_topic"]) == "discussion"
    assert ce.classify("M06 - M07: Prospectus") == "prospectus"
    assert ce.classify("Final Paper") == "final_paper"
    assert ce.classify("Final Project") == "final_project"
    assert ce.classify("Case Study/Evaluation Paper") == "paper"
    assert ce.classify("Presentations") == "presentation"
    assert ce.classify("Big Data Share-Out") == "presentation"
    assert ce.classify("Lab 3: Mapping") == "lab"
    assert ce.classify("Quarto Refresher Assignment") == "lab"
    assert ce.classify("Something Odd") == "assignment"


# ── _spread ─────────────────────────────────────────────────────────────────────

def test_spread_math():
    due = D(2026, 7, 20)
    assert ce._spread(due, [7, 3, 0]) == [D(2026, 7, 13), D(2026, 7, 17), due, due]

def test_spread_clamps_intermediates_to_today():
    due = D(2026, 7, 1)   # 2 days out; a 7-day gap would land in the past
    dates = ce._spread(due, [7, 3, 0], TODAY)
    assert dates[0] == TODAY            # clamped, not overdue-at-birth
    assert dates[-1] == due             # final due untouched

def test_spread_preserves_ordering_when_clamped_not_just_today():
    # regression: clamping every gap INDEPENDENTLY to `today` collapsed a
    # 4-phase chain onto the same date, leaving chained-dependency tasks
    # ("phase 2 blockedBy phase 1") all due the same day they could start --
    # sequentially impossible. Ordering must survive: each clamped date is at
    # least one day after the previous one, never past the real due date.
    due = TODAY + datetime.timedelta(days=2)
    dates = ce._spread(due, [14, 9, 5, 0], TODAY)
    for a, b in zip(dates, dates[1:]):
        assert a <= b, f"{dates} not monotonically non-decreasing"
    assert len(set(dates[:3])) == 3, "first 3 phases collapsed onto the same date"
    assert dates[-1] == due

def test_spread_already_overdue_deadline_does_not_predate_today():
    # regression: min(d, final_due) was applied AFTER max(d, floor), so when
    # final_due itself is already in the past (late sync -- lifeops was down,
    # or the assignment unlocked already overdue), every phase collapsed onto
    # that past date instead of today -- recreating the exact "born overdue"
    # bug the clamping exists to prevent.
    due = TODAY - datetime.timedelta(days=5)   # already overdue
    dates = ce._spread(due, [14, 9, 5, 0], TODAY)
    for d in dates[:-1]:
        assert d == TODAY, f"phase dated {d}, expected clamped to today ({TODAY})"
    assert dates[-1] == due, "final due date itself should stay truthful, not hidden"


# ── module_number ─────────────────────────────────────────────────────────────

def test_module_number_extraction():
    assert ce.module_number("Module 3") == 3
    assert ce.module_number("Week 3: Module 12") == 12   # keyword beats leading digit
    assert ce.module_number("M7") == 7                    # bare M-token still matches
    assert ce.module_number("Start Here") is None         # unnumbered utility module

def test_module_number_ignores_m_inside_words():
    # regression: an un-anchored "m" alternative matched the trailing "m" of
    # "Midterm"/"Exam"/"Zoom"/"Problem" followed by a space+digit, extracting the
    # WRONG number and mis-numbering the module in synced_modules.
    assert ce.module_number("Midterm 1 - Module 9") == 9
    assert ce.module_number("Exam 2: Module 8") == 8
    assert ce.module_number("Zoom 5 Recording - Module 4") == 4
    assert ce.module_number("Problem Set 5 Module 2") == 2


# ── split_assignment ────────────────────────────────────────────────────────────

def _split(atype, name="Thing", due=D(2026, 7, 20)):
    return ce.split_assignment(7, name, atype, due, UNLOCK, None, TODAY)

def test_none_due_date_does_not_crash_returns_single_task():
    for atype in ("paper", "final_paper", "lab", "discussion", "prospectus",
                  "reply", "presentation", "assignment"):
        specs = ce.split_assignment(7, "X", atype, None, UNLOCK, None, TODAY)
        assert len(specs) == 1
        assert "dueDateTime" not in specs[0]      # no invented deadline
        assert specs[0]["durationMinutes"] > 0

def test_paper_splits_three_phases_with_deps():
    specs = _split("paper")
    assert [s["title"].split("— ")[-1] for s in specs] == \
           ["Outline & Notes", "Draft", "Revise"]
    assert "_dep_title" not in specs[0]
    assert specs[1]["_dep_title"] == specs[0]["title"]
    assert specs[2]["_dep_title"] == specs[1]["title"]
    assert specs[2]["dueDateTime"].startswith("2026-07-20")

def test_final_paper_four_phases():
    specs = _split("final_paper")
    assert len(specs) == 4
    assert specs[-1]["dueDateTime"].startswith("2026-07-20")

def test_lab_three_phases():
    assert len(_split("lab")) == 3

def test_reply_single_task():
    specs = _split("reply")
    assert len(specs) == 1 and specs[0]["durationMinutes"] == 40

def test_discussion_with_data_smell_splits():
    specs = _split("discussion", name="Identifying and Sharing an API")
    assert len(specs) == 2
    assert specs[1]["_dep_title"] == specs[0]["title"]

def test_discussion_plain_single():
    assert len(_split("discussion", name="Introductions")) == 1

def test_close_deadline_is_high_priority_and_not_overdue():
    due = TODAY + datetime.timedelta(days=2)
    specs = _split("paper", due=due)
    assert all(s["priority"] == "high" for s in specs)
    for s in specs:   # no phase may be born overdue
        assert s["dueDateTime"][:10] >= TODAY.isoformat()

def test_start_respects_readings_due():
    rd = D(2026, 7, 3)
    specs = ce.split_assignment(7, "X", "reply", D(2026, 7, 5), UNLOCK, rd, TODAY)
    assert specs[0]["canBeStartedAt"].startswith("2026-07-03")


def test_split_assignment_tags_source_id_and_phase_count():
    # Each phase gets its OWN source id ("assignment:<id>:phase:<n>"), not a
    # shared one -- a shared id would make plan()'s exact-id dedup treat
    # phase 2+ as "already seen" the moment phase 1 is created, collapsing
    # every multi-phase assignment down to just its first phase.
    specs = ce.split_assignment(7, "Thing", "paper", D(2026, 7, 20), UNLOCK, None, TODAY,
                                assignment_id=555)
    assert len(specs) == 3
    ids = set()
    for i, s in enumerate(specs, start=1):
        assert s["_source_id"] == f"assignment:555:phase:{i}"
        assert s["_phase_index"] == i
        assert s["_phase_total"] == 3
        ids.add(s["_source_id"])
    assert len(ids) == 3


def test_split_assignment_single_phase_has_no_phase_count():
    specs = ce.split_assignment(7, "Thing", "reply", D(2026, 7, 20), UNLOCK, None, TODAY,
                                assignment_id=555)
    assert len(specs) == 1
    assert specs[0]["_source_id"] == "assignment:555"
    assert "_phase_index" not in specs[0]
    assert "_phase_total" not in specs[0]


def test_split_assignment_no_id_no_source_tag():
    specs = ce.split_assignment(7, "Thing", "reply", D(2026, 7, 20), UNLOCK, None, TODAY)
    assert "_source_id" not in specs[0]


# ── reading_task ────────────────────────────────────────────────────────────────

def test_reading_task_duration_by_type():
    t = ce.reading_task(7, "Perry, W.", "Predictive Policing", "documentation",
                        UNLOCK, D(2026, 7, 3), TODAY)
    assert t["durationMinutes"] == 55
    assert t["title"] == "Read Perry, Predictive Policing"
    assert t["priority"] == "normal"
    assert "Module: M07" in t["notes"]

def test_reading_task_unknown_type_default():
    t = ce.reading_task(7, "X", "Y", "weird", UNLOCK, D(2026, 7, 3), TODAY)
    assert t["durationMinutes"] == 35


def test_reading_task_notes_include_full_citation_url_and_checkbox():
    t = ce.reading_task(7, "Perry, W.", "Ch. 3: Predictive Policing", "chapter",
                        UNLOCK, D(2026, 7, 3), TODAY, locator="Ch. 3, pp. 45-67",
                        book_title="Policing the Planet", url="https://example.com/book")
    notes = t["notes"]
    assert "Ch. 3: Predictive Policing" in notes
    assert "by Perry, W." in notes
    assert "in Policing the Planet" in notes
    assert "Ch. 3, pp. 45-67" in notes
    assert "Type: chapter" in notes
    assert "Link: https://example.com/book" in notes
    assert "- [ ] Downloaded" in notes


def test_reading_task_notes_omit_book_title_when_same_as_title():
    # the reading itself IS the book/article -- book_title equal to title
    # would just repeat the citation, not add information.
    t = ce.reading_task(7, "A", "Some Article", "article", UNLOCK, D(2026, 7, 3), TODAY,
                        book_title="Some Article")
    assert " in Some Article" not in t["notes"]


def test_reading_task_notes_have_no_link_line_when_no_url():
    t = ce.reading_task(7, "A", "Some Article", "article", UNLOCK, D(2026, 7, 3), TODAY)
    assert "Link:" not in t["notes"]
    assert "- [ ] Downloaded" in t["notes"]


def test_phase_count_for_matches_actual_split_counts():
    assert ce.phase_count_for("X", "reply", D(2026, 7, 20)) == 1
    assert ce.phase_count_for("X", "prospectus", D(2026, 7, 20)) == 2
    assert ce.phase_count_for("X", "paper", D(2026, 7, 20)) == 3
    assert ce.phase_count_for("X", "final_paper", D(2026, 7, 20)) == 4
    assert ce.phase_count_for("X", "assignment", D(2026, 7, 20)) == 3
    assert ce.phase_count_for("X", "assignment", None) == 1          # no due date -> unsplit
    assert ce.phase_count_for("Find the data", "discussion", D(2026, 7, 20)) == 2
    assert ce.phase_count_for("Just discuss", "discussion", D(2026, 7, 20)) == 1


def test_split_assignment_uses_content_aware_phase_labels():
    labels = ["Pull NYC Open Data", "Clean & Explore", "Write Findings"]
    specs = ce.split_assignment(4, "NYC Open Data Analysis", "assignment", D(2026, 7, 20),
                                UNLOCK, None, TODAY, phase_labels=labels)
    titles = [s["title"].split(" — ")[-1] for s in specs]
    assert titles == labels


def test_split_assignment_falls_back_when_phase_labels_count_mismatches():
    # a stale cache entry from before a re-classification, or a malformed
    # LLM response -- either way, must not crash or silently drop phases.
    specs = ce.split_assignment(4, "X", "assignment", D(2026, 7, 20), UNLOCK, None, TODAY,
                                phase_labels=["Only One"])
    titles = [s["title"].split(" — ")[-1] for s in specs]
    assert titles == ["Setup & Data Exploration", "Analysis & Visualization", "Write-Up"]


def test_split_assignment_display_name_used_for_tag_only():
    # display_name shortens the TITLE tag, but the "data smell" classification
    # heuristic inside split_assignment must still see the FULL original name
    # -- a shortened display_name could easily have dropped "data"/"find"/etc.
    specs = ce.split_assignment(4, "Identifying and Sharing an API for Urban Data",
                                "discussion", D(2026, 7, 5), UNLOCK, None, TODAY,
                                display_name="API Discussion")
    assert len(specs) == 2                       # still split -- "data" matched in the full name
    assert all("API Discussion" in s["title"] for s in specs)
    assert all("Identifying and Sharing" not in s["title"] for s in specs)


def test_split_assignment_display_name_defaults_to_name():
    specs = ce.split_assignment(7, "Thing", "reply", D(2026, 7, 20), UNLOCK, None, TODAY)
    assert specs[0]["title"] == "Thing"
    assert specs[0]["notes"] == "Module: M07"


def test_split_assignment_module_range_name_goes_to_notes_not_title():
    # Canvas itself names multi-module assignments "M01-M03: Problem Set 1" --
    # that range is more informative than the single triggering module number,
    # so it's what ends up in notes; the title carries no module info at all.
    specs = ce.split_assignment(1, "M01-M03: Problem Set 1", "assignment",
                                D(2026, 7, 20), UNLOCK, None, TODAY)
    assert all(s["title"].startswith("Problem Set 1") for s in specs)
    assert not any("M01-M03" in s["title"] or "M01:" in s["title"] for s in specs)
    assert all(s["notes"] == "Module: M01-M03" for s in specs)


def test_split_assignment_single_module_name_goes_to_notes_not_title():
    specs = ce.split_assignment(4, "M04: Some Assignment", "reply",
                                D(2026, 7, 20), UNLOCK, None, TODAY)
    assert specs[0]["title"] == "Some Assignment"
    assert specs[0]["notes"] == "Module: M04"


def test_split_assignment_plain_name_unaffected_module_still_in_notes():
    specs = ce.split_assignment(4, "Plain Assignment Name", "reply",
                                D(2026, 7, 20), UNLOCK, None, TODAY)
    assert specs[0]["title"] == "Plain Assignment Name"
    assert specs[0]["notes"] == "Module: M04"


def test_reading_task_source_id_stable_and_distinct():
    a = ce.reading_task(7, "Perry, W.", "Predictive Policing", "documentation",
                        UNLOCK, D(2026, 7, 3), TODAY)
    b = ce.reading_task(7, "Perry, W.", "Predictive Policing", "documentation",
                        UNLOCK, D(2026, 7, 3), TODAY)
    c = ce.reading_task(7, "Perry, W.", "A Different Reading", "documentation",
                        UNLOCK, D(2026, 7, 3), TODAY)
    assert a["_source_id"] == b["_source_id"]     # deterministic for identical inputs
    assert a["_source_id"] != c["_source_id"]     # distinct readings, distinct ids
    assert a["_source_id"].startswith("reading:")


# ── plan ────────────────────────────────────────────────────────────────────────

def _module(num=7, assignments=None, readings=None):
    return {"module_num": num, "unlock_date": UNLOCK,
            "assignments": assignments or [], "readings": readings or []}

def test_plan_dedups_against_existing_titles():
    # regression: titles used to carry a leading "M07: " module prefix; that
    # moved into notes, but tasks created BEFORE this change still have the
    # old prefixed title live in FlowSavvy -- must still match.
    mod = _module(readings=[{"author": "Perry, W.", "title": "RAND", "type": "article"}])
    existing = {"M07: Read Perry, RAND"}
    out = ce.plan([mod], existing, TODAY)
    assert out["creates"] == []


def test_plan_dedups_legacy_prefixed_assignment_title():
    mod = _module(num=4, assignments=[{"name": "Some Assignment", "due_at": None}])
    existing = {"M04: Some Assignment"}   # pre-existing task, old title format
    out = ce.plan([mod], existing, TODAY)
    assert out["creates"] == []


def test_plan_dedups_against_course_code_suffix_variant():
    # regression: FlowSavvy decorates course-list titles with a trailing
    # " [AS.470.703.81.SU26]" the engine never emits — a raw equality check
    # missed this and created a real duplicate (see canvas_state.json:
    # "M02: NYC Open Data Analysis" vs "... [AS.470.703.81.SU26]").
    mod = _module(readings=[{"author": "Perry, W.", "title": "RAND", "type": "article"}])
    existing = {"M07: Read Perry, RAND [AS.470.703.81.SU26]"}
    out = ce.plan([mod], existing, TODAY)
    assert out["creates"] == []
    assert "skipped 1 duplicate" in out["report"]


def test_plan_dedups_near_identical_title_via_similarity():
    mod = _module(readings=[{"author": "Perry, W.", "title": "RAND", "type": "article"}])
    # trivial rewording of the same reading task, not byte-identical
    existing = {"M07: Read Perry,  RAND"}
    out = ce.plan([mod], existing, TODAY)
    assert out["creates"] == []


def test_plan_does_not_suppress_genuinely_different_titles():
    mod = _module(readings=[
        {"author": "Walker, K.", "title": "Analyzing U.S. Census Data", "type": "book"},
        {"author": "Walker, K.", "title": "Tidycensus Documentation", "type": "documentation"},
    ])
    out = ce.plan([mod], set(), TODAY)
    assert len(out["creates"]) == 2
    assert "skipped" not in out["report"]

def test_plan_paper_phases_not_dropped_by_similarity_dedup():
    # regression: "{tag} — Draft" and "{tag} — Revise" share an identical tag
    # and differ only in a short, near-identical phase word. Once the tag makes
    # the normalized title long enough the similarity ratio crossed threshold
    # and silently dropped "— Revise" as a false duplicate of "— Draft"
    # (canvas_state.json "M07:  Predictive Policing Case Study/Evaluation Paper ").
    name = "Predictive Policing Case Study/Evaluation Paper "
    mod = _module(assignments=[{"name": name, "due_at": "2026-07-20T23:59:59Z"}])
    assert ce.classify(name) == "paper"
    out = ce.plan([mod], set(), TODAY)
    tag = name
    titles = {c["title"] for c in out["creates"]}
    for phase in ("Outline & Notes", "Draft", "Revise"):
        assert f"{tag} — {phase}" in titles
    assert "skipped" not in out["report"]


def test_plan_assignment_with_missing_due_survives():
    mod = _module(assignments=[{"name": "Mystery Paper", "due_at": None},
                               {"name": "Real Reply", "due_at": "2026-07-05T23:59:59Z"}])
    out = ce.plan([mod], set(), TODAY)
    titles = [c["title"] for c in out["creates"]]
    assert any("Mystery Paper" in t for t in titles)   # didn't crash, still planned
    assert any("Real Reply" in t for t in titles)

def test_plan_report_counts_per_module():
    mods = [_module(num=7, readings=[{"author": "A", "title": "One", "type": "article"}]),
            _module(num=8, readings=[{"author": "B", "title": "Two", "type": "article"}])]
    out = ce.plan(mods, set(), TODAY)
    assert "M07 (+1 tasks):" in out["report"]
    assert "M08 (+1 tasks):" in out["report"]          # per-module, not cumulative

def test_plan_chains_readings_across_modules_in_order():
    # regression: readings had no dependency at all -- with no real Canvas
    # unlock-date gating (e.g. a first sync mid-semester, where every already-
    # published module reports unlock_at=null), nothing stopped a later
    # module's reading from being scheduled before an earlier one's.
    mods = [
        _module(num=6, readings=[{"author": "A", "title": "M6-First", "type": "article"},
                                 {"author": "A", "title": "M6-Second", "type": "article"}]),
        _module(num=8, readings=[{"author": "B", "title": "M8-Only", "type": "article"}]),
    ]
    out = ce.plan(mods, set(), TODAY)
    by_title = {c["title"]: c for c in out["creates"]}
    m6_first  = by_title["Read A, M6-First"]
    m6_second = by_title["Read A, M6-Second"]
    m8_only   = by_title["Read B, M8-Only"]

    assert m6_first.get("_dep_title") is None            # nothing before it
    assert m6_second.get("_dep_title") is None            # same module -- parallel, not chained
    assert m8_only["_dep_title"] == "Read A, M6-Second"   # blocked by the LAST reading of module 6


def test_plan_chains_readings_regardless_of_input_module_order():
    # module 8 handed in BEFORE module 6 -- plan() must still process them in
    # module-number order so the dependency points the right direction.
    mods = [
        _module(num=8, readings=[{"author": "B", "title": "M8-Only", "type": "article"}]),
        _module(num=6, readings=[{"author": "A", "title": "M6-Only", "type": "article"}]),
    ]
    out = ce.plan(mods, set(), TODAY)
    by_title = {c["title"]: c for c in out["creates"]}
    assert by_title["Read A, M6-Only"].get("_dep_title") is None
    assert by_title["Read B, M8-Only"]["_dep_title"] == "Read A, M6-Only"


def test_plan_reading_chain_survives_a_module_with_no_new_readings():
    # module 7's only reading already exists (deduped) -- module 8's first
    # reading must still chain back to module 6's, not silently drop the
    # dependency just because module 7 contributed nothing new.
    mods = [
        _module(num=6, readings=[{"author": "A", "title": "M6-Only", "type": "article"}]),
        _module(num=7, readings=[{"author": "C", "title": "Already Exists", "type": "article"}]),
        _module(num=8, readings=[{"author": "B", "title": "M8-Only", "type": "article"}]),
    ]
    existing = {"Read C, Already Exists"}
    out = ce.plan(mods, existing, TODAY)
    by_title = {c["title"]: c for c in out["creates"]}
    assert by_title["Read B, M8-Only"]["_dep_title"] == "Read A, M6-Only"


def test_plan_readings_due_two_days_before_earliest_assignment():
    mod = _module(assignments=[{"name": "R", "due_at": "2026-07-10T23:59:59Z"}],
                  readings=[{"author": "A", "title": "T", "type": "article"}])
    out = ce.plan([mod], set(), TODAY)
    reading = next(c for c in out["creates"] if "Read" in c["title"])
    assert reading["dueDateTime"].startswith("2026-07-08")

def test_plan_late_sync_never_emits_overdue_readings():
    # assignment due tomorrow → readings_due would be yesterday; clamp to today
    mod = _module(assignments=[{"name": "R", "due_at": "2026-06-30T23:59:59Z"}],
                  readings=[{"author": "A", "title": "T", "type": "article"}])
    out = ce.plan([mod], set(), TODAY)
    reading = next(c for c in out["creates"] if "Read" in c["title"])
    assert reading["dueDateTime"][:10] == TODAY.isoformat()

def test_plan_missing_unlock_defaults_to_today():
    mod = {"module_num": 9, "assignments": [],
           "readings": [{"author": "A", "title": "T", "type": "article"}]}
    out = ce.plan([mod], set(), TODAY)          # no unlock_date key at all
    assert len(out["creates"]) == 1
    assert out["creates"][0]["canBeStartedAt"].startswith(TODAY.isoformat())


def test_plan_dedups_by_source_id_even_with_different_title():
    # A reworded task for the same (single-phase) Canvas assignment must not
    # be recreated just because its title no longer matches — the exact-id
    # check is authoritative over the fuzzy title check.
    mod = _module(assignments=[{"id": 42, "name": "Totally Reworded Reply",
                                "due_at": "2026-07-20T23:59:59Z"}])
    out = ce.plan([mod], set(), TODAY, existing_source_ids={"assignment:42"})
    assert out["creates"] == []
    assert "skipped" in out["report"]


def test_plan_dedups_each_phase_of_a_split_assignment_independently():
    # A multi-phase assignment persists ONE source id per phase
    # ("assignment:<id>:phase:<n>") -- simulates a prior run having already
    # created phases 1-2; only phase 3 (Write-Up) should still be missing.
    mod = _module(assignments=[{"id": 77, "name": "Big Project", "due_at": "2026-07-20T23:59:59Z"}])
    seen = {"assignment:77:phase:1", "assignment:77:phase:2"}
    out = ce.plan([mod], set(), TODAY, existing_source_ids=seen)
    assert len(out["creates"]) == 1
    assert out["creates"][0]["title"].endswith("Write-Up")


def test_plan_creates_every_phase_of_a_split_assignment_on_first_sync():
    # regression: sharing ONE source_id across all phases of an assignment
    # made plan() treat phase 2+ as "already seen" the instant phase 1 was
    # created within the SAME run, silently dropping every phase after the
    # first -- big assignments never actually got broken into their full set
    # of session pieces.
    mod = _module(assignments=[{"id": 77, "name": "Big Project", "due_at": "2026-07-20T23:59:59Z"}])
    out = ce.plan([mod], set(), TODAY)
    titles = [c["title"] for c in out["creates"]]
    assert len(titles) == 3
    assert any(t.endswith("Setup & Data Exploration") for t in titles)
    assert any(t.endswith("Analysis & Visualization") for t in titles)
    assert any(t.endswith("Write-Up") for t in titles)


def test_plan_does_not_merge_distinct_assignments_with_similar_shortened_names():
    # regression: two DIFFERENT assignments whose LLM-shortened display names
    # collapse to near-identical text (an "Initial Proposal" and a "Final
    # Proposal" both shortened to "... Proposal") must not fuzzy-merge into
    # one just because their titles look alike -- their distinct Canvas ids
    # are authoritative.
    mod = _module(assignments=[
        {"id": 1, "name": "M03-M08: Machine Learning Model Revision - Initial Proposal",
         "due_at": "2026-07-05T23:59:59Z", "display_name": "Machine Learning Model Revision Proposal"},
        {"id": 2, "name": "M09-M12: Machine Learning Model Revision - Final Proposal",
         "due_at": "2026-07-20T23:59:59Z", "display_name": "Machine Learning Model Revision Proposal"},
    ])
    out = ce.plan([mod], set(), TODAY)
    outlines = [c for c in out["creates"] if c["title"].endswith("Outline")]
    assert len(outlines) == 2, "both assignments' Outline phase should survive, not just one"


def test_plan_creates_when_source_id_not_seen():
    mod = _module(assignments=[{"id": 42, "name": "Real Reply",
                                "due_at": "2026-07-05T23:59:59Z"}])
    out = ce.plan([mod], set(), TODAY, existing_source_ids={"assignment:999"})
    assert len(out["creates"]) == 1


def test_extract_source_ids_scrapes_ref_markers():
    notes = ["some notes\n\n[canvas-ref: assignment:7]", "no marker here", None]
    assert ce.extract_source_ids(notes) == {"assignment:7"}


def test_format_ref_note_appends_marker_and_phase():
    note = ce.format_ref_note("original notes", "assignment:7", phase_index=2, phase_total=3)
    assert "original notes" in note
    assert "[canvas-ref: assignment:7]" in note
    assert "part 2 of 3" in note
    assert ce.format_ref_note("", "assignment:7") == "[canvas-ref: assignment:7]"
    assert ce.format_ref_note("notes", None) == "notes"


# ── _parse_date ─────────────────────────────────────────────────────────────────

def test_parse_date_variants():
    assert ce._parse_date("2026-07-05T23:59:59Z") == D(2026, 7, 5)
    assert ce._parse_date("2026-07-05") == D(2026, 7, 5)
    assert ce._parse_date(None) is None
    assert ce._parse_date("") is None
    assert ce._parse_date("garbage") is None
