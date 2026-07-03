"""Canvas → FlowSavvy task planner.

Pure decision logic: no I/O, no API calls. Given structured module data
(assignments + readings), returns FlowSavvy task specs ready to POST.
"""
import re, datetime

DAY = datetime.timedelta(days=1)

# ── assignment classification ──────────────────────────────────────────────────

def classify(name, submission_types=None):
    n = name.lower()
    st = " ".join(submission_types or []).lower()
    if ("reply" in n or "replies" in n            # Canvas: "Required Replies (1)"
            or "response to peer" in n or "peer response" in n):
        return "reply"
    if "discussion" in n or "discussion_topic" in st:
        return "discussion"
    if "prospectus" in n or "proposal" in n:
        return "prospectus"
    if "final paper" in n or ("final" in n and "paper" in n):
        return "final_paper"
    if "final project" in n or ("final" in n and "project" in n):
        return "final_project"
    if "paper" in n or "response paper" in n or "essay" in n:
        return "paper"
    if "presentation" in n or "share-out" in n or "share out" in n:
        return "presentation"
    if "lab" in n or "homework" in n or ("assignment" in n and "reading" not in n):
        return "lab"
    return "assignment"


# ── duration + splitting rules ────────────────────────────────────────────────

def _spread(final_due, gaps_before, today=None):
    """Intermediate due dates given days-before-final gaps, plus the final due.
    gaps_before = [days_before_for_phase_1, ..., days_before_for_second_to_last]
    final_due is a datetime.date. Intermediates are clamped to `today` so a
    close deadline never emits phases that are already overdue at creation —
    but clamping each date to `today` INDEPENDENTLY would collapse several
    dependency-chained phases onto the identical due date (impossible to
    actually sequence: each phase is blockedBy the previous one, so "due
    today, due today, due today" for three chained tasks leaves zero time
    between them). Instead each phase is clamped to be at least one day after
    the previous (already-clamped) phase, so relative ordering survives a
    late sync; only once phases run out of room before the real deadline do
    they legitimately collapse onto that final date — there's no more
    calendar left to spread across.
    """
    dates = []
    floor = today
    for d in [final_due - datetime.timedelta(days=g) for g in gaps_before]:
        if floor is not None:
            d = max(d, floor)
            d = min(d, final_due)   # never push a phase past the real deadline
            floor = d + DAY
        dates.append(d)
    return dates + [final_due]


# fallback durations for assignments Canvas gives us no due date for (unsplit)
_NO_DUE_DURATION = {"reply": 40, "discussion": 75, "prospectus": 180, "paper": 195,
                    "final_paper": 480, "final_project": 260, "lab": 260,
                    "assignment": 260, "presentation": 105}


def split_assignment(mod_num, name, atype, due_date, unlock_date, readings_due, today):
    """Return list of task kwargs dicts for a single assignment.

    due_date, unlock_date, readings_due: datetime.date or None
    """
    tag   = f"M{mod_num:02d}: {name}"
    start = max(unlock_date, readings_due) if readings_due else unlock_date
    prio  = "high" if due_date and (due_date - today).days <= 3 else "normal"

    def _task(title, duration, due, can_start, dep_title=None):
        t = {
            "title":               title,
            "durationMinutes":     duration,
            "minLengthMinutes":    min(duration, 45),
            "dueDateTime":         f"{due.isoformat()}T23:59:00" if due else None,
            "canBeStartedAt":      f"{can_start.isoformat()}T08:00:00",
            "priority":            prio,
            "_dep_title":          dep_title,   # resolved to id by runner
        }
        return {k: v for k, v in t.items() if v is not None}

    # No due date from Canvas → phase spreading has nothing to anchor on.
    # Emit ONE unsplit task with no deadline instead of crashing in _spread.
    if due_date is None:
        return [_task(tag, _NO_DUE_DURATION.get(atype, 60), None, start)]

    if atype == "reply":
        return [_task(tag, 40, due_date, start)]

    if atype == "discussion":
        # check if it smells like it needs data work first
        if any(w in name.lower() for w in ("data", "find", "identify", "research", "collect")):
            dates = _spread(due_date, [3, 0], today)
            return [
                _task(f"{tag} — Research",    55, dates[0], start),
                _task(f"{tag} — Write Post",  65, dates[1], dates[0], dep_title=f"{tag} — Research"),
            ]
        return [_task(tag, 75, due_date, start)]

    if atype == "prospectus":
        dates = _spread(due_date, [5, 0], today)
        return [
            _task(f"{tag} — Outline",  60, dates[0], start),
            _task(f"{tag} — Draft",   120, dates[1], dates[0], dep_title=f"{tag} — Outline"),
        ]

    if atype == "paper":
        dates = _spread(due_date, [7, 3, 0], today)
        return [
            _task(f"{tag} — Outline & Notes", 45,  dates[0], start),
            _task(f"{tag} — Draft",          110,  dates[1], dates[0], dep_title=f"{tag} — Outline & Notes"),
            _task(f"{tag} — Revise",          40,  dates[2], dates[1], dep_title=f"{tag} — Draft"),
        ]

    if atype == "final_paper":
        # 4 phases → 4 gaps; last gap 0 so Proofread & Submit lands ON the deadline
        dates = _spread(due_date, [14, 9, 5, 0], today)
        return [
            _task(f"{tag} — Incorporate Feedback", 120, dates[0], start),
            _task(f"{tag} — Rewrite & Expand",     150, dates[1], dates[0],
                  dep_title=f"{tag} — Incorporate Feedback"),
            _task(f"{tag} — Polish & Citations",   120, dates[2], dates[1],
                  dep_title=f"{tag} — Rewrite & Expand"),
            _task(f"{tag} — Proofread & Submit",    90, dates[3], dates[2],
                  dep_title=f"{tag} — Polish & Citations"),
        ]

    if atype in ("final_project", "lab", "assignment"):
        dates = _spread(due_date, [7, 3, 0], today)
        return [
            _task(f"{tag} — Setup & Data Exploration",  80, dates[0], start),
            _task(f"{tag} — Analysis & Visualization", 105, dates[1], dates[0],
                  dep_title=f"{tag} — Setup & Data Exploration"),
            _task(f"{tag} — Write-Up",                  75, dates[2], dates[1],
                  dep_title=f"{tag} — Analysis & Visualization"),
        ]

    if atype == "presentation":
        return [_task(tag, 105, due_date, start)]

    return [_task(tag, 60, due_date, start)]


# ── reading tasks ──────────────────────────────────────────────────────────────

_READING_DURATION = {
    "article":       25,
    "blog":          25,
    "chapter":       45,
    "accessible_chapter": 30,
    "tutorial":      50,
    "documentation": 55,
    "book":         240,
}

def reading_task(mod_num, author, title, rtype, unlock_date, due_date, today):
    duration = _READING_DURATION.get(rtype, 35)
    prio = "high" if due_date and (due_date - today).days <= 3 else "normal"
    short_author = author.split(",")[0].strip() if author else "Source"
    short_title  = title[:50] if title else "reading"
    return {
        "title":           f"M{mod_num:02d}: Read {short_author}, {short_title}",
        "durationMinutes": duration,
        "minLengthMinutes": min(duration, 20),
        "dueDateTime":     f"{due_date.isoformat()}T23:59:00" if due_date else None,
        "canBeStartedAt":  f"{unlock_date.isoformat()}T08:00:00",
        "priority":        prio,
        "_dep_title":      None,
    }


# ── top-level planner ─────────────────────────────────────────────────────────

def plan(modules_data, existing_titles, today):
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

    Returns: {
        creates: [task_kwargs],   # ready to pass to fs.create_task(**t) after removing _dep_title
        report:  str,
    }
    """
    creates = []
    report_lines = []

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
                             r.get("type","article"), unlock, readings_due, today)
            if t["title"] not in existing_titles:
                creates.append(t)
                existing_titles.add(t["title"])
                mod_lines.append(f"  + {t['title']} ({t['durationMinutes']}m)")

        # assignments
        for a in assignments:
            name  = a.get("name", "")
            atype = classify(name, a.get("submission_types", []))
            due   = _parse_date(a.get("due_at"))
            specs = split_assignment(num, name, atype, due, unlock, readings_due, today)
            for spec in specs:
                if spec["title"] not in existing_titles:
                    creates.append(spec)
                    existing_titles.add(spec["title"])
                    mod_lines.append(f"  + {spec['title']} ({spec['durationMinutes']}m)")

        if mod_lines:
            report_lines.append(f"M{num:02d} (+{len(mod_lines)} tasks):")
            report_lines.extend(mod_lines)

    return {
        "creates":      creates,
        "report":       "\n".join(report_lines) if report_lines else "no new tasks",
    }


def _parse_date(dt_str):
    """'2026-07-05T23:59:59Z' → datetime.date, or None."""
    if not dt_str:
        return None
    try:
        return datetime.datetime.fromisoformat(dt_str.rstrip("Z")).date()
    except Exception:
        return None
