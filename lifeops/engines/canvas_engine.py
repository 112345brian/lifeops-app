"""Canvas → FlowSavvy task planner.

Pure decision logic: no I/O, no API calls. Given structured module data
(assignments + readings), returns FlowSavvy task specs ready to POST.

The dedup/matching logic lives in canvas_dedup.py and the per-task
templating (classification, duration/splitting, task-kwargs construction)
lives in canvas_tasks.py; this module re-exports both so existing callers
(`canvas_engine.plan`, `canvas_engine.classify`, etc.) keep working, and
holds only the top-level plan() orchestration plus the small Canvas-name
parsing helpers (module_number, _parse_date) that plan() itself needs.
"""
import re
import datetime

from .canvas_dedup import (  # noqa: F401 — re-exported for existing callers
    REF_MARKER_RE,
    _normalize_title,
    _split_phase,
    _find_duplicate,
    extract_source_ids,
    _assignment_source_id,
    _reading_source_id,
    format_ref_note,
)
from .canvas_tasks import (  # noqa: F401 — re-exported for existing callers
    DAY,
    classify,
    _spread,
    _NO_DUE_DURATION,
    split_assignment,
    _READING_DURATION,
    reading_task,
)


# ── top-level planner ─────────────────────────────────────────────────────────

def plan(modules_data, existing_titles, today, existing_source_ids=None):
    """
    modules_data: list of dicts — one per newly-unlocked module:
      {
        module_num:   int,
        unlock_date:  datetime.date,
        assignments:  [{"name", "due_at", "submission_types", ...}],
        readings:     [{"author", "title", "type", "estimated_minutes"}] or [],
      }
    existing_titles: set of task titles already in FlowSavvy.
    today: datetime.date
    existing_source_ids: set of `[canvas-ref: ...]` ids already stamped into
      live FlowSavvy task notes (see extract_source_ids) — an exact-match
      dedup key that short-circuits the fuzzy title check below it, since a
      resplit/reworded task with the same source id is still the same
      underlying Canvas assignment or reading.

    Returns: {
        creates: [task_kwargs],   # ready to pass to fs.create_task(**t) after removing _dep_title
        report:  str,
    }
    """
    creates = []
    report_lines = []
    skipped_dupes = []
    existing_norms = {_normalize_title(t) for t in existing_titles}
    seen_source_ids = set(existing_source_ids or ())

    for mod in modules_data:
        num         = mod.get("module_num") or 0
        unlock      = mod.get("unlock_date") or today
        assignments = mod.get("assignments", [])
        readings    = mod.get("readings", [])

        # find earliest assignment due date in this module for reading deadlines
        asgn_dues = []
        for a in assignments:
            due = _parse_date(a.get("due_at"))
            if due:
                asgn_dues.append(due)
        earliest_due = min(asgn_dues) if asgn_dues else None
        readings_due = (earliest_due - 2 * DAY) if earliest_due else (unlock + 7 * DAY)
        if readings_due < today:
            readings_due = today          # late sync: never emit pre-overdue readings

        mod_lines = []

        # readings
        for r in readings:
            t = reading_task(num, r.get("author",""), r.get("title",""),
                             r.get("type","article"), unlock, readings_due, today,
                             locator=r.get("locator"), book_title=r.get("book_title"),
                             url=r.get("url"))
            sid = t.get("_source_id")
            if sid in seen_source_ids:
                skipped_dupes.append(t["title"]); continue
            dup = _find_duplicate(t["title"], existing_norms)
            if dup is None:
                t["_module_num"] = num
                creates.append(t)
                existing_norms.add(_normalize_title(t["title"]))
                if sid:
                    seen_source_ids.add(sid)
                mod_lines.append(f"  + {t['title']} ({t['durationMinutes']}m)")
            else:
                skipped_dupes.append(t["title"])

        # assignments
        for a in assignments:
            name  = a.get("name", "")
            atype = classify(name, a.get("submission_types", []))
            due   = _parse_date(a.get("due_at"))
            specs = split_assignment(num, name, atype, due, unlock, readings_due, today,
                                     assignment_id=a.get("id"),
                                     display_name=a.get("display_name"))
            for spec in specs:
                sid = spec.get("_source_id")
                if sid in seen_source_ids:
                    skipped_dupes.append(spec["title"]); continue
                dup = _find_duplicate(spec["title"], existing_norms)
                if dup is None:
                    spec["_module_num"] = num
                    creates.append(spec)
                    existing_norms.add(_normalize_title(spec["title"]))
                    if sid:
                        seen_source_ids.add(sid)
                    mod_lines.append(f"  + {spec['title']} ({spec['durationMinutes']}m)")
                else:
                    skipped_dupes.append(spec["title"])

        if mod_lines:
            report_lines.append(f"M{num:02d} (+{len(mod_lines)} tasks):")
            report_lines.extend(mod_lines)

    if skipped_dupes:
        report_lines.append(f"skipped {len(skipped_dupes)} duplicate(s):")
        report_lines.extend(f"  ~ {t}" for t in skipped_dupes)

    return {
        "creates":      creates,
        "report":       "\n".join(report_lines) if report_lines else "no new tasks",
    }


# Prefer a number immediately after "Module"/"Mod"/"M" (however the course
# names them) over the first digit ANYWHERE in the string — a name like
# "Week 3: Module 12" would otherwise mis-extract 3, not 12. The keyword is
# word-anchored (\b) so the "m" alternative matches a bare "M7" token but NOT
# the trailing "m" of "Midterm"/"Exam"/"Zoom"/"Problem" ("Midterm 1 - Module 9"
# must resolve to 9, not 1) — an un-anchored "m" silently mis-numbers modules.
_MODULE_NUM_RE = re.compile(r"\b(?:module|mod|m)\s*#?\s*(\d+)", re.I)


def module_number(name):
    """Extract a module number from a Canvas module name, or None.

    Falls back to the first digit anywhere only when no "Module N"/"M N"
    keyword token is present.
    """
    m = _MODULE_NUM_RE.search(name or "") or re.search(r"\d+", name or "")
    if not m:
        return None
    return int(m.group(1) if m.lastindex else m.group())


def _parse_date(dt_str):
    """'2026-07-05T23:59:59Z' → datetime.date, or None."""
    if not dt_str:
        return None
    try:
        return datetime.datetime.fromisoformat(dt_str.rstrip("Z")).date()
    except Exception:
        return None
