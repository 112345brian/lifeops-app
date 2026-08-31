"""Canvas → FlowSavvy dedup/matching: title normalization + source-id refs.

Pure decision logic, no I/O.
"""
import re
import difflib
import hashlib

# ── dedup: FlowSavvy decorates course-list task titles with a trailing
# " [COURSE.CODE]" suffix that this engine never generates, so a raw
# title-equality check misses that pair and creates a duplicate (confirmed
# in logs/canvas_state.json: "M02: NYC Open Data Analysis" and
# "M02: NYC Open Data Analysis [AS.470.703.81.SU26]" both exist as separate
# completed tasks for the same assignment). Normalize away the suffix, and
# fall back to a similarity ratio to catch near-identical titles that
# survive normalization (minor rewording/typos across a re-sync). ──────────

_SUFFIX_RE = re.compile(r"\s*\[[\w.]+\]\s*$")
_SIMILARITY_THRESHOLD = 0.93

# Titles used to carry a leading "M01: "/"M01-M03: " module prefix (moved
# into the task's notes as a plain "Module: ..." line instead — see
# canvas_tasks.split_assignment/reading_task). Existing FlowSavvy tasks
# created before that change still have the OLD prefixed titles, so a raw
# comparison against a newly-generated bare title would miss them entirely
# and recreate a duplicate. Strip it here too, same rationale as the
# trailing course-tag suffix below.
_LEADING_MOD_RE = re.compile(r"^M\d{1,2}(?:\s*-\s*M?\d{1,2})?\s*:\s*", re.I)

# split_assignment() emits dependency-chained phases as "{tag} — {phase}".
# Two phases of the same assignment share an identical tag and differ only in
# the phase word ("Draft" vs "Revise"), which are short and near-identical:
# once the shared tag makes the normalized title long enough, the raw
# similarity ratio crosses _SIMILARITY_THRESHOLD and the ratio fallback drops a
# legitimately-distinct phase as a false duplicate (e.g. "— Revise" swallowed
# by "— Draft"). So when both titles carry a phase suffix we compare ONLY the
# tag and demand the phase match exactly — the ratio never fires across
# distinct phases.
_PHASE_SEP = " — "


def _normalize_title(title):
    t = _SUFFIX_RE.sub("", title or "").strip()
    t = _LEADING_MOD_RE.sub("", t).strip()
    return t.casefold()


def _split_phase(norm):
    """(tag, phase) for a normalized "{tag} — {phase}" title, else (norm, None).
    Uses the LAST separator so a tag containing " — " keeps the phase isolated."""
    idx = norm.rfind(_PHASE_SEP)
    if idx == -1:
        return norm, None
    return norm[:idx], norm[idx + len(_PHASE_SEP):]


def _find_duplicate(title, existing_norms):
    """Return the matched existing (normalized) title if `title` is an
    exact (post-normalization) or near-duplicate of one already present,
    else None."""
    norm = _normalize_title(title)
    if norm in existing_norms:
        return norm
    norm_tag, norm_phase = _split_phase(norm)
    for e in existing_norms:
        if norm_phase is not None:
            e_tag, e_phase = _split_phase(e)
            if e_phase is not None:
                # Both are phased tasks: never a duplicate across distinct
                # phases; for a matching phase, compare only the tag portion.
                if e_phase != norm_phase:
                    continue
                if difflib.SequenceMatcher(None, norm_tag, e_tag).ratio() >= _SIMILARITY_THRESHOLD:
                    return e
                continue
        if difflib.SequenceMatcher(None, norm, e).ratio() >= _SIMILARITY_THRESHOLD:
            return e
    return None


# ── dedup by source id: exact-match fallback/primary key ───────────────────────
# Title-based dedup (above) is inherently fuzzy — a re-split into differently
# worded phases, or an LLM re-extraction of a reading that isn't byte-stable,
# can slip past it either as a false negative (new duplicate) or false
# positive (legit new task dropped). Every created task also carries a
# `[canvas-ref: ...]` marker in its notes pointing back at the Canvas object
# that produced it — an assignment id for assignment-derived tasks (stable,
# straight from Canvas), or a content hash for LLM-extracted readings (Canvas
# gives readings no id at all). Callers scrape that marker out of live
# FlowSavvy task notes and pass the resulting id set in as `existing_source_ids`
# to `plan()`, where an exact id match short-circuits the fuzzy title check.

REF_MARKER_RE = re.compile(r"\[canvas-ref:\s*([^\]]+?)\s*\]")


def extract_source_ids(notes_texts):
    """Scrape `[canvas-ref: ...]` markers out of an iterable of task `notes`
    strings (as fetched live from FlowSavvy) into a set of source ids."""
    ids = set()
    for notes in notes_texts:
        ids.update(REF_MARKER_RE.findall(notes or ""))
    return ids


def _assignment_source_id(assignment_id):
    return f"assignment:{assignment_id}" if assignment_id is not None else None


def _reading_source_id(mod_num, author, title):
    """Readings have no Canvas id — hash the identifying fields instead. Not
    fully re-extraction-stable (author/title text can shift slightly between
    LLM runs), but exact when it does match, which title-fuzzy-matching alone
    can't offer."""
    digest = hashlib.sha1(f"{mod_num}|{author or ''}|{title or ''}".encode("utf-8")).hexdigest()[:16]
    return f"reading:{digest}"


def format_ref_note(base_notes, source_id, phase_index=None, phase_total=None):
    """Append the `[canvas-ref: ...]` marker (+ phase-of-N, when the engine
    split one assignment into several dependent tasks) to a task's notes."""
    if not source_id:
        return base_notes
    marker = f"[canvas-ref: {source_id}]"
    if phase_total and phase_total > 1:
        marker += f" (part {phase_index} of {phase_total})"
    return f"{base_notes}\n\n{marker}" if base_notes else marker
