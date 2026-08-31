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
    specs = ce.split_assignment(7, "Thing", "paper", D(2026, 7, 20), UNLOCK, None, TODAY,
                                assignment_id=555)
    assert len(specs) == 3
    for i, s in enumerate(specs, start=1):
        assert s["_source_id"] == "assignment:555"
        assert s["_phase_index"] == i
        assert s["_phase_total"] == 3


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
    assert t["title"] == "M07: Read Perry, Predictive Policing"
    assert t["priority"] == "normal"

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
    assert specs[0]["title"] == "M07: Thing"


def test_split_assignment_does_not_double_prefix_module_range_name():
    # Canvas itself names multi-module assignments "M01-M03: Problem Set 1" --
    # prepending "M{mod_num:02d}: " on top produced "M01: M01-M03: Problem Set 1".
    specs = ce.split_assignment(1, "M01-M03: Problem Set 1", "assignment",
                                D(2026, 7, 20), UNLOCK, None, TODAY)
    assert all(s["title"].startswith("M01-M03: Problem Set 1") for s in specs)
    assert not any(s["title"].startswith("M01: M01-M03") for s in specs)


def test_split_assignment_does_not_double_prefix_single_module_name():
    specs = ce.split_assignment(4, "M04: Some Assignment", "reply",
                                D(2026, 7, 20), UNLOCK, None, TODAY)
    assert specs[0]["title"] == "M04: Some Assignment"


def test_split_assignment_still_prefixes_plain_name():
    specs = ce.split_assignment(4, "Plain Assignment Name", "reply",
                                D(2026, 7, 20), UNLOCK, None, TODAY)
    assert specs[0]["title"] == "M04: Plain Assignment Name"


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
    mod = _module(readings=[{"author": "Perry, W.", "title": "RAND", "type": "article"}])
    existing = {"M07: Read Perry, RAND"}
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
    tag = f"M07: {name}"
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
    # A resplit/reworded task for the same Canvas assignment must not be
    # recreated just because its title no longer matches — the exact-id check
    # is authoritative over the fuzzy title check.
    mod = _module(assignments=[{"id": 42, "name": "Totally Reworded Title",
                                "due_at": "2026-07-20T23:59:59Z"}])
    out = ce.plan([mod], set(), TODAY, existing_source_ids={"assignment:42"})
    assert out["creates"] == []
    assert "skipped" in out["report"]


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
