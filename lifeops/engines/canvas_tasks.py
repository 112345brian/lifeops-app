"""Canvas → FlowSavvy task templating: classification, duration/splitting
rules, and task-kwargs construction for assignments and readings.

Pure decision logic, no I/O.
"""
import datetime
import re

from .canvas_dedup import _assignment_source_id, _reading_source_id

DAY = datetime.timedelta(days=1)

# Canvas itself names multi-module assignments with a leading module-range
# tag ("M01-M03: Problem Set 1", "M09-M12: ... Final Proposal"). Blindly
# prepending "M{mod_num:02d}: " on top produced nonsense like
# "M01: M01-M03: Problem Set 1" — skip the prepend whenever the name/
# display_name already carries its own module (-range) prefix.
_MOD_PREFIX_RE = re.compile(r"^M\d{1,2}(?:\s*-\s*M?\d{1,2})?\s*:", re.I)


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
    if today is not None and final_due < today:
        # The real deadline has already passed (late sync — lifeops was down,
        # or the assignment was already overdue when unlocked). min(d, final_due)
        # below would otherwise override every max(d, floor) clamp and collapse
        # every phase onto that past date, recreating the exact "born overdue"
        # bug this clamping exists to prevent. There's no calendar left to
        # spread across at all — put every phase on today; final_due itself
        # stays truthful (it's genuinely overdue, no point hiding that).
        return [today] * len(gaps_before) + [final_due]
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


def split_assignment(mod_num, name, atype, due_date, unlock_date, readings_due, today,
                      assignment_id=None, display_name=None):
    """Return list of task kwargs dicts for a single assignment.

    due_date, unlock_date, readings_due: datetime.date or None
    assignment_id: Canvas assignment id, when known — every phase task
    returned carries a `_source_id` tag (plus `_phase_index`/`_phase_total`
    when split into more than one phase) so the runner can stamp a
    `[canvas-ref: assignment:<id>]` marker into its notes at creation.
    display_name: shortened name to use in the task TITLE tag, when the raw
    `name` is too long for a task-list title (see runner._display_name — an
    LLM-abbreviated name, e.g. "Policing Paper" for "Predictive Policing Case
    Study/Evaluation Paper"). `name` itself is still used for classification/
    the "data smell" heuristic below, since a shortened name can drop the
    keywords those look for. Defaults to `name` when omitted.
    """
    label = display_name or name
    tag   = label if _MOD_PREFIX_RE.match(label.strip()) else f"M{mod_num:02d}: {label}"
    start = max(unlock_date, readings_due) if readings_due else unlock_date
    prio  = "high" if due_date and (due_date - today).days <= 3 else "normal"
    source_id = _assignment_source_id(assignment_id)

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
        phases = [_task(tag, _NO_DUE_DURATION.get(atype, 60), None, start)]

    elif atype == "reply":
        phases = [_task(tag, 40, due_date, start)]

    elif atype == "discussion":
        # check if it smells like it needs data work first
        if any(w in name.lower() for w in ("data", "find", "identify", "research", "collect")):
            dates = _spread(due_date, [3, 0], today)
            phases = [
                _task(f"{tag} — Research",    55, dates[0], start),
                _task(f"{tag} — Write Post",  65, dates[1], dates[0], dep_title=f"{tag} — Research"),
            ]
        else:
            phases = [_task(tag, 75, due_date, start)]

    elif atype == "prospectus":
        dates = _spread(due_date, [5, 0], today)
        phases = [
            _task(f"{tag} — Outline",  60, dates[0], start),
            _task(f"{tag} — Draft",   120, dates[1], dates[0], dep_title=f"{tag} — Outline"),
        ]

    elif atype == "paper":
        dates = _spread(due_date, [7, 3, 0], today)
        phases = [
            _task(f"{tag} — Outline & Notes", 45,  dates[0], start),
            _task(f"{tag} — Draft",          110,  dates[1], dates[0], dep_title=f"{tag} — Outline & Notes"),
            _task(f"{tag} — Revise",          40,  dates[2], dates[1], dep_title=f"{tag} — Draft"),
        ]

    elif atype == "final_paper":
        # 4 phases → 4 gaps; last gap 0 so Proofread & Submit lands ON the deadline
        dates = _spread(due_date, [14, 9, 5, 0], today)
        phases = [
            _task(f"{tag} — Incorporate Feedback", 120, dates[0], start),
            _task(f"{tag} — Rewrite & Expand",     150, dates[1], dates[0],
                  dep_title=f"{tag} — Incorporate Feedback"),
            _task(f"{tag} — Polish & Citations",   120, dates[2], dates[1],
                  dep_title=f"{tag} — Rewrite & Expand"),
            _task(f"{tag} — Proofread & Submit",    90, dates[3], dates[2],
                  dep_title=f"{tag} — Polish & Citations"),
        ]

    elif atype in ("final_project", "lab", "assignment"):
        dates = _spread(due_date, [7, 3, 0], today)
        phases = [
            _task(f"{tag} — Setup & Data Exploration",  80, dates[0], start),
            _task(f"{tag} — Analysis & Visualization", 105, dates[1], dates[0],
                  dep_title=f"{tag} — Setup & Data Exploration"),
            _task(f"{tag} — Write-Up",                  75, dates[2], dates[1],
                  dep_title=f"{tag} — Analysis & Visualization"),
        ]

    elif atype == "presentation":
        phases = [_task(tag, 105, due_date, start)]

    else:
        phases = [_task(tag, 60, due_date, start)]

    if source_id:
        total = len(phases)
        for i, phase in enumerate(phases, start=1):
            phase["_source_id"] = source_id
            if total > 1:
                phase["_phase_index"] = i
                phase["_phase_total"] = total
    return phases


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

def reading_task(mod_num, author, title, rtype, unlock_date, due_date, today, locator=None,
                 book_title=None, url=None):
    duration = _READING_DURATION.get(rtype, 35)
    prio = "high" if due_date and (due_date - today).days <= 3 else "normal"
    short_author = author.split(",")[0].strip() if author else "Source"
    short_title  = title[:50] if title else "reading"
    t = {
        "title":           f"M{mod_num:02d}: Read {short_author}, {short_title}",
        "durationMinutes": duration,
        "minLengthMinutes": min(duration, 20),
        "dueDateTime":     f"{due_date.isoformat()}T23:59:00" if due_date else None,
        "canBeStartedAt":  f"{unlock_date.isoformat()}T08:00:00",
        "priority":        prio,
        "_dep_title":      None,
        "_source_id":      _reading_source_id(mod_num, author, title),
    }
    # Full citation in notes — the title alone is truncated to 50 chars and
    # never carries author/book/locator/type, so it's not enough on its own
    # to find and confirm the actual source. `book_title` is the containing
    # book when `title` is just a chapter/excerpt name (e.g. `title`="Ch. 3:
    # Predictive Policing", `book_title`="Policing the Planet") — omitted
    # when the reading itself IS the whole book/article.
    citation_bits = [title or "reading"]
    if author:
        citation_bits.append(f"by {author}")
    if book_title and isinstance(book_title, str) and book_title != title:
        citation_bits.append(f"in {book_title}")
    if locator and isinstance(locator, str):
        citation_bits.append(locator)
    citation = " — ".join(citation_bits)
    notes_lines = [citation, f"Type: {rtype}"]
    if url and isinstance(url, str):
        notes_lines.append(f"Link: {url}")
    # A manual "- [ ] Downloaded" line — a plain markdown checkbox the user
    # can check off in the FlowSavvy notes once the source is actually pulled
    # down locally, since Canvas readings often need to be found/downloaded
    # separately from the task itself.
    notes_lines.append("")
    notes_lines.append("- [ ] Downloaded")
    t["notes"] = "\n".join(notes_lines)
    return t
