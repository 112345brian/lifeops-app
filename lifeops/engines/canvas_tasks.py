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


# fallback total-effort minutes for assignments Canvas gives us no due date
# for (no due date to spread across, so no lead-days either -- see
# _total_and_lead)
_NO_DUE_DURATION = {"reply": 40, "discussion": 75, "prospectus": 180, "paper": 195,
                    "final_paper": 480, "final_project": 260, "lab": 260,
                    "assignment": 260, "presentation": 105}

# No single work session should ever run longer than this. Rather than build
# a few generic phases and then mechanically chop whichever one is too long
# into "(1/2)"/"(2/2)" halves of the same vague blob -- not an ATOMIC task,
# just an arbitrary time-slice -- split_assignment instead figures out up
# front how many real sessions the total estimated effort needs (see
# phase_count_for) and asks for that many DISTINCT, content-aware session
# names directly (see llm.propose_assignment_phases / domains/canvas.py's
# _phase_labels_for), falling back to a generic-but-still-distinct per-atype
# name list only when no content-aware labels are available.
_MAX_SESSION_MINUTES = 80


def _expand_task(task, max_minutes=_MAX_SESSION_MINUTES):
    """Split one task dict whose durationMinutes exceeds max_minutes into
    several same-day, dependency-chained sub-sessions (title suffixed
    " (i/N)"), each within the cap. Returns a list of 1+ task dicts -- a
    single-element list, task unchanged, when it already fits. Used for
    reading_task's "book" type (240min) -- a single reading has no separate
    logical phases to name, so a plain duration-based split is the best
    available option there. split_assignment does NOT use this: assignments
    get real content-aware session names instead (see above)."""
    duration = task["durationMinutes"]
    if duration <= max_minutes:
        return [task]
    n = -(-duration // max_minutes)   # ceil division
    base, extra = divmod(duration, n)
    base_source_id = task.get("_source_id")
    sub_tasks = []
    prev_title = task.get("_dep_title")
    for i in range(n):
        dur = base + (1 if i < extra else 0)   # spread the remainder across the first sessions
        sub = dict(task)
        sub["title"] = f"{task['title']} ({i + 1}/{n})"
        sub["durationMinutes"] = dur
        sub["minLengthMinutes"] = min(dur, task.get("minLengthMinutes", dur))
        if prev_title is not None:
            sub["_dep_title"] = prev_title
        else:
            sub.pop("_dep_title", None)   # match _task()'s convention: absent, not None, when there's no dep
        if base_source_id:
            sub["_source_id"] = f"{base_source_id}:session:{i + 1}"
        sub_tasks.append(sub)
        prev_title = sub["title"]
    return sub_tasks


def _split_even(total, n):
    """n durations summing to `total`, each within 1 minute of total/n."""
    base, extra = divmod(total, n)
    return [base + (1 if i < extra else 0) for i in range(n)]


def _even_gaps(lead_days, n):
    """n-1 evenly-spaced day-gaps before the deadline (most lead time first,
    ending at gap 0 so the last session lands ON the deadline) -- feeds
    _spread() to produce n total dates for an n-session assignment."""
    if n <= 1:
        return []
    return [round(lead_days * (n - i) / n) for i in range(1, n)]


def _is_data_discussion(name):
    return any(w in name.lower() for w in ("data", "find", "identify", "research", "collect"))


# Total estimated effort (minutes) and lead time (days before the deadline
# the first session should start) per atype -- the basis for how many
# sessions an assignment needs (phase_count_for: ceil(total/80)) and how
# they're spread across the calendar (_even_gaps). "discussion_data" is the
# data-smell-flagged discussion sub-case (see _is_data_discussion); plain
# discussion/reply fit in one session and need no lead time.
_TOTAL_MINUTES_WITH_DUE = {
    "reply": 40, "discussion": 75, "discussion_data": 120,
    "prospectus": 180, "paper": 195, "final_paper": 480,
    "final_project": 260, "lab": 260, "assignment": 260, "presentation": 105,
}
_LEAD_DAYS = {
    "discussion_data": 3, "prospectus": 5, "paper": 7, "final_paper": 14,
    "final_project": 7, "lab": 7, "assignment": 7, "presentation": 3,
}

# Default session names per atype -- used only when no content-aware
# `phase_labels` are available (no Canvas description, or the LLM call
# failed/returned the wrong count). Length MUST match _TOTAL_MINUTES_WITH_DUE
# ceil-divided by _MAX_SESSION_MINUTES for that key (asserted in tests) --
# these are still distinct, ordered steps, never a duration-based "(i/N)"
# split of one name.
_DEFAULT_PHASE_NAMES = {
    "discussion_data": ["Research", "Write Post"],
    "prospectus":      ["Outline", "Draft", "Polish"],
    "paper":           ["Outline & Notes", "Draft", "Revise"],
    "final_paper":     ["Incorporate Feedback", "Rewrite Introduction & Methods",
                        "Rewrite Results & Discussion", "Polish & Citations",
                        "Proofread", "Final Formatting & Submit"],
    "final_project":   ["Setup & Data Exploration", "Analysis", "Visualization", "Write-Up"],
    "lab":             ["Setup & Data Exploration", "Analysis", "Visualization", "Write-Up"],
    "assignment":      ["Setup & Data Exploration", "Analysis", "Visualization", "Write-Up"],
    "presentation":    ["Prepare Slides & Script", "Rehearse & Polish"],
}


def _effort_key(name, atype):
    """Resolves atype (+ the discussion data-smell heuristic) to the key
    used for total-minutes/lead-days/default-name lookups above."""
    if atype == "discussion":
        return "discussion_data" if _is_data_discussion(name) else "discussion"
    return atype


def _total_and_lead(name, atype, due_date):
    key = _effort_key(name, atype)
    if due_date is None:
        return _NO_DUE_DURATION.get(atype, 60), 0
    return _TOTAL_MINUTES_WITH_DUE.get(key, 60), _LEAD_DAYS.get(key, 0)


def phase_count_for(name, atype, due_date):
    """How many sessions split_assignment will emit for this (name, atype,
    due_date) -- a pure lookup, safe for callers (e.g. the runner, before it
    decides whether to bother requesting LLM-authored phase_labels) to call
    without duplicating split_assignment's own branching. This is the REAL
    session count (duration-driven, capped at _MAX_SESSION_MINUTES each),
    not a fixed "3 generic phases" guess -- content-aware phase_labels
    describe exactly this many distinct, actionable sessions."""
    total, _ = _total_and_lead(name, atype, due_date)
    return max(1, -(-total // _MAX_SESSION_MINUTES))


def split_assignment(mod_num, name, atype, due_date, unlock_date, readings_due, today,
                      assignment_id=None, display_name=None, phase_labels=None):
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
    phase_labels: content-aware session names (e.g. from
    llm.propose_assignment_phases, reading the assignment's actual Canvas
    description) to use INSTEAD of the generic per-atype defaults in
    _DEFAULT_PHASE_NAMES — e.g. ["Pull NYC Open Data", "Clean & Explore",
    "Build Visualizations"] instead of "Setup & Data Exploration"/etc. Used
    only when its length matches phase_count_for's result for this
    (name, atype, due_date); otherwise silently falls back to the generic
    defaults so a stale/malformed/absent label set never breaks scheduling.
    """
    # The module number/range used to be glued onto the TITLE ("M01: Thing",
    # or "M01: M01-M03: Thing" before that double-prefix fix) -- moved into
    # the notes instead, as a plain "Module: ..." line, so the title itself
    # stays just the assignment name + phase. Canvas's own module-range name
    # (e.g. "M01-M03: Problem Set 1") is more informative than the single
    # module that happened to trigger this sync, so prefer it when present.
    label = display_name or name
    prefix_match = _MOD_PREFIX_RE.match(label.strip())
    if prefix_match:
        tag = label.strip()[prefix_match.end():].strip()
        module_note = f"Module: {prefix_match.group(0).rstrip(':').strip()}"
    else:
        tag = label
        module_note = f"Module: M{mod_num:02d}"
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
            "notes":               module_note,
            "_dep_title":          dep_title,   # resolved to id by runner
        }
        return {k: v for k, v in t.items() if v is not None}

    key = _effort_key(name, atype)
    total, lead_days = _total_and_lead(name, atype, due_date)
    n = max(1, -(-total // _MAX_SESSION_MINUTES))
    durations = _split_even(total, n)
    dates = [None] * n if due_date is None else _spread(due_date, _even_gaps(lead_days, n), today)

    if n == 1:
        phases = [_task(tag, durations[0], dates[0], start)]
    else:
        if (phase_labels and len(phase_labels) == n
                and all(isinstance(x, str) and x.strip() for x in phase_labels)):
            names = [x.strip() for x in phase_labels]
        else:
            names = _DEFAULT_PHASE_NAMES.get(key) or [f"Part {i + 1}" for i in range(n)]
        phases = []
        prev_title = None
        for i in range(n):
            # no due date -> nothing to sequence sessions BY date, only by
            # the dependency chain itself -- every session starts eligible
            # at `start`, same as the single-task no-due-date case always did.
            can_start = start if (i == 0 or due_date is None) else dates[i - 1]
            title = f"{tag} — {names[i]}"
            phases.append(_task(title, durations[i], dates[i], can_start, dep_title=prev_title))
            prev_title = title

    if source_id:
        phase_total = len(phases)
        for i, phase in enumerate(phases, start=1):
            # A shared assignment-level source_id across phases would make
            # plan()'s exact-id dedup treat every phase after the first as
            # "already seen" the moment the first phase is created within the
            # SAME planning run -- silently collapsing a multi-session
            # assignment down to just its first session. Scope the id to the
            # phase when there's more than one, so sessions of one assignment
            # can never collide with each other; the same session
            # re-encountered across repeat module occurrences (or future
            # runs) still shares one id and correctly dedups.
            phase["_source_id"] = f"{source_id}:phase:{i}" if phase_total > 1 else source_id
            if phase_total > 1:
                phase["_phase_index"] = i
                phase["_phase_total"] = phase_total
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
                 book_title=None, url=None, dep_title=None):
    """Returns a list of 1+ task kwargs dicts (dependency-chained) -- a
    "book"-type reading (240min) exceeds one sitting, so it's split into
    several chained sessions same as an overlong assignment phase (see
    _expand_task); most readings fit in one sitting and come back as a
    single-element list.
    dep_title: title of a reading task this one shouldn't be started before
    (see canvas_engine.plan(), which chains the FIRST reading of each module
    to the LAST reading of the previous module) -- without it, two readings
    with no due-date gating (e.g. every module already unlocked on a first
    sync) are equally schedulable in any order, including module 8's reading
    before module 6's."""
    duration = _READING_DURATION.get(rtype, 35)
    prio = "high" if due_date and (due_date - today).days <= 3 else "normal"
    short_author = author.split(",")[0].strip() if author else "Source"
    short_title  = title[:50] if title else "reading"
    t = {
        "title":           f"Read {short_author}, {short_title}",
        "durationMinutes": duration,
        "minLengthMinutes": min(duration, 20),
        "dueDateTime":     f"{due_date.isoformat()}T23:59:00" if due_date else None,
        "canBeStartedAt":  f"{unlock_date.isoformat()}T08:00:00",
        "priority":        prio,
        "_dep_title":      dep_title,
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
    # Module number moved here rather than glued onto the title (see
    # split_assignment's identical move) so the title stays just the citation.
    notes_lines = [f"Module: M{mod_num:02d}", citation, f"Type: {rtype}"]
    if url and isinstance(url, str):
        notes_lines.append(f"Link: {url}")
    # A manual "- [ ] Downloaded" line — a plain markdown checkbox the user
    # can check off in the FlowSavvy notes once the source is actually pulled
    # down locally, since Canvas readings often need to be found/downloaded
    # separately from the task itself.
    notes_lines.append("")
    notes_lines.append("- [ ] Downloaded")
    t["notes"] = "\n".join(notes_lines)
    return _expand_task(t)
