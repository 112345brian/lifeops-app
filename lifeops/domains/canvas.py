"""Canvas domain: sync newly-unlocked modules into FlowSavvy tasks."""
import os, datetime
from .. import actions, config, history, state_store
from ..engines.canvas_tasks import classify as _classify, phase_count_for
from ..engines.canvas_engine import _parse_date
from ..canvas import extract_text_file_refs
from ._shared import _save_json_atomic, _alert_once, _touch

# Canvas flood guard: a healthy incremental sync creates a handful of tasks; the
# two duplicate-flood incidents (2026-07-03/06) each tried to create ~59 in one
# run after a state-loss re-sync. More than this in a single run is almost always
# a re-sync, not that many real new tasks — so hold and ask instead of flooding.
_CANVAS_FLOOD_MAX = 8

# Assignment names longer than this get LLM-shortened for the task TITLE only
# (see llm.shorten_assignment_name / canvas_engine.split_assignment's
# `display_name`) — a naive character slice would cut mid-phrase and lose the
# words that make a long name recognizable in a task list.
_ASSIGNMENT_NAME_SHORTEN_THRESHOLD = 35


def run_canvas(fs, yn, now):
    """Sync newly-unlocked Canvas modules → FlowSavvy tasks, once per
    configured course (see config.canvas_courses()).

    Runs once per day. Two credential paths, tried in order:
      1. CANVAS_TOKEN (real API token) — used directly if set.
      2. lifeops.canvas_browser (authenticated Playwright session) — used
         when no token exists (JHU disables self-service tokens). Requires
         a one-time interactive login: `python scripts/canvas_login.py`.
         If that session has since expired, alerts instead of failing quiet.
    BrowserCanvas is expensive to construct (launches a persistent browser
    context), so one instance is reused across every configured course
    rather than one per course — see canvas_browser's per-call `course_id`
    override.
    State: logs/canvas_state.json — tracks, per course id, which modules
    have been synced and which task titles already exist (prevents
    duplicates across runs, and doubles as a per-semester dedup log since a
    new semester is a new Canvas course id).
    """
    from ..canvas import strip_html
    from ..engines import canvas_engine
    from .. import llm

    courses = config.canvas_courses()
    if not courses:
        print("[canvas] skip (no CANVAS_COURSES / CANVAS_COURSE_ID configured)")
        return

    if config.CANVAS_TOKEN:
        from ..canvas import Canvas
        cv = Canvas()
        for course in courses:
            _canvas_sync(cv, strip_html, canvas_engine, llm, fs, now, course)
        return

    from .. import canvas_browser
    if not canvas_browser.profile_exists():
        print("[canvas] skip (no CANVAS_TOKEN and no browser profile — "
              "run `python scripts/canvas_login.py` once)")
        return
    try:
        with canvas_browser.BrowserCanvas() as cv:
            if not cv.logged_in():
                _alert_once("canvas:session:" + now.date().isoformat(),
                            "Canvas session expired — tap to re-login from the control panel, "
                            "or run `python scripts/canvas_login.py`.", "high",
                            click_anchor="settings#accounts")
                print("[canvas] skip (browser session expired)")
                return
            for course in courses:
                _canvas_sync(cv, strip_html, canvas_engine, llm, fs, now, course)
    except Exception as e:
        print(f"[canvas] browser session error: {e}")


def _migrate_legacy_canvas_state(st_root, legacy_course_id):
    """One-time migration: pre-multi-course canvas_state.json stored
    synced_modules/task_titles/etc. flat at the top level for the single
    configured course. Wrap them under courses[<course_id>] once — a
    "courses" key already present means this has already run (or the state
    was always empty), so this is idempotent."""
    if "courses" in st_root:
        return
    legacy_keys = ("synced_modules", "synced_module_ids", "task_titles",
                   "completed_cache", "flood_ack")
    bucket = {k: st_root.pop(k) for k in legacy_keys if k in st_root}
    st_root["courses"] = {legacy_course_id: bucket} if bucket else {}


def _display_name(assignment, short_titles, llm):
    """The name to render in a task title — LLM-shortened when the raw
    Canvas name is too long for a task-list title, cached per assignment id
    in `short_titles` (mutated in place) so the title stays stable across
    runs even though the LLM call itself isn't deterministic. Falls back to
    the raw name whenever shortening isn't needed or the call fails."""
    name = assignment.get("name", "")
    if len(name) <= _ASSIGNMENT_NAME_SHORTEN_THRESHOLD:
        return name
    aid = str(assignment.get("id"))
    cached = short_titles.get(aid)
    if cached:
        return cached
    short = llm.shorten_assignment_name(name, max_chars=_ASSIGNMENT_NAME_SHORTEN_THRESHOLD)
    if short:
        short_titles[aid] = short
        return short
    return name


def _phase_labels_for(assignment, cache, llm, strip_html, cv=None, course_id=None):
    """Content-aware phase names for one multi-phase assignment (e.g. "Pull
    NYC Open Data" / "Clean & Explore" / "Build Visualizations" instead of
    the generic "Setup & Data Exploration"/"Analysis & Visualization"/
    "Write-Up" template), read from the assignment's actual Canvas
    `description` PLUS the text content of any linked .qmd/.py/.md/etc file
    (see canvas.extract_text_file_refs) via llm.propose_assignment_phases.
    The description alone is frequently just submission boilerplate ("answer
    the questions, render, submit") -- a problem set's REAL questions often
    live in a linked file instead, which `cv`/`course_id` (when given) let
    this fetch directly. Cached per assignment id in `cache` (mutated in
    place) so the labels stay stable across runs despite the LLM call itself
    not being deterministic. Returns None (canvas_tasks.split_assignment
    then falls back to its generic template) for single-phase assignments,
    no usable content at all, or any failure."""
    name = assignment.get("name", "")
    atype = _classify(name, assignment.get("submission_types", []), assignment.get("points_possible"))
    due = _parse_date(assignment.get("due_at"))
    count = phase_count_for(name, atype, due)
    if count <= 1:
        return None
    aid = str(assignment.get("id"))
    cached = cache.get(aid)
    if cached and len(cached) == count:
        return cached
    raw_description = assignment.get("description") or ""
    content_parts = []
    description = strip_html(raw_description)
    if description:
        content_parts.append(description)
    if cv is not None:
        for file_id, filename in extract_text_file_refs(raw_description):
            text = cv.file_text(file_id, course_id=course_id)
            if text:
                content_parts.append(f"--- {filename} ---\n{text}")
    content = "\n\n".join(content_parts)
    if not content:
        return None
    labels = llm.propose_assignment_phases(name, content, atype, count)
    if labels:
        cache[aid] = labels
    return labels


def _canvas_sync(cv, strip_html, canvas_engine, llm, fs, now, course=None):
    """Shared sync body for ONE Canvas course — `cv` is either canvas.Canvas
    or canvas_browser.BrowserCanvas; both expose the same modules/assignments/
    page/announcements interface (with an optional course_id override), so
    this logic doesn't care which. `course` is a dict from
    config.canvas_courses() ({'course_id', 'list_id', 'sh_id'}); defaults to
    the first configured course when omitted, for callers/tests written
    before multi-course support."""
    if course is None:
        courses = config.canvas_courses()
        if not courses:
            print("[canvas] skip (no course configured)")
            return
        course = courses[0]
    course_id = course["course_id"]

    sp = os.path.join(history.ROOT, "private", "logs", "canvas_state.json")
    st_root = state_store.load_json(sp, default={}) or {}
    _migrate_legacy_canvas_state(st_root, course_id)
    st = st_root["courses"].setdefault(course_id, {})
    st.setdefault("synced_modules", [])
    st.setdefault("task_titles", [])
    st.setdefault("source_ids", [])
    # Cached LLM-shortened assignment names, keyed by Canvas assignment id
    # (str) — computed once (see _display_name below) and reused forever
    # after, so a task's title stays stable across runs even though the
    # shortening call isn't deterministic between LLM invocations.
    short_titles = st.setdefault("short_titles", {})
    # Cached content-aware phase names (see _phase_labels_for), keyed by
    # Canvas assignment id (str) -- same stability rationale as short_titles.
    phase_labels_cache = st.setdefault("phase_labels", {})
    # Per-assignment record of which FlowSavvy task id holds which phase,
    # and whether those titles are already using real content-aware names
    # or still the generic fallback template -- an assignment's whole
    # semester is often visible (and synced) well before its description is
    # actually written, so "generic now, content-aware later" is the normal
    # case here, not an edge case. Lets a later run rename already-created
    # tasks in place once a description shows up (see the rename check near
    # the end of this function) -- keyed by str(assignment_id).
    phase_task_ids = st.setdefault("phase_task_ids", {})
    synced  = set(st["synced_modules"])                 # legacy dedup key: module NUMBER (rename/collision-fragile)
    synced_ids = set(st.get("synced_module_ids", []))   # stable dedup key: Canvas module id
    # `task_titles` persists ONLY the titles THIS engine actually created (see the save block
    # below). `seen_titles` is the run-local dedup working set: seeded from those persisted
    # titles, then augmented with completed_cache + the live-fetched FlowSavvy titles for THIS
    # run only. Those live sets are deliberately never persisted — folding them back into
    # task_titles grew it without bound across the whole multi-semester course and, via
    # canvas_engine's 0.93 fuzzy match, silently dropped a legitimately-new task as a
    # "duplicate" of a long-gone one. It also defeated the 20-day completed_cache eviction: an
    # evicted title lived on forever inside task_titles, so the cache never actually forgot.
    created_persisted = set(st["task_titles"])
    seen_titles = set(created_persisted)
    # `source_ids` mirrors task_titles but as the exact-match `[canvas-ref: ...]`
    # keys (see canvas_engine.extract_source_ids) — augmented below with ids
    # scraped live out of FlowSavvy notes, same pattern as seen_titles.
    created_source_ids = set(st["source_ids"])
    seen_source_ids = set(created_source_ids)
    today = now.date()

    # 20-day rolling cache of completed task titles (avoids re-fetching history each run)
    cutoff = (today - datetime.timedelta(days=20)).isoformat()
    completed_cache = {title: dt for title, dt in st.get("completed_cache", {}).items()
                       if dt >= cutoff}
    seen_titles.update(completed_cache)

    # pull live FlowSavvy titles — both incomplete and recently completed.
    # No `query` filter: this course's list_id is a dedicated Canvas-sourced
    # list, so scoping by listId alone is sufficient — a substring filter
    # like "M0" would silently stop matching once modules reach M10+.
    existing = []
    try:
        existing = fs.list_items(itemType="task", listId=course["list_id"],
                                 completed=False).get("items", [])
        seen_titles.update(t.get("title", "") for t in existing)
        seen_source_ids.update(canvas_engine.extract_source_ids(t.get("notes", "") for t in existing))
    except Exception:
        pass

    # `synced_modules` empty while FlowSavvy already holds a real course
    # list is the signature of a lost/corrupted canvas_state.json (happened
    # 2026-07-03 and again 2026-07-06 -- the latter silently re-extracted
    # every unlocked module via the LLM and created 5 near-duplicate M07
    # readings that differed from the originals by more than the title dedup
    # could catch, since re-extraction isn't byte-stable). This can't
    # reliably be recovered from here (we don't know which modules were
    # truly already synced) but it should never again fail silently.
    if not synced and not synced_ids and len(existing) >= 5:
        _alert_once("canvas:state-reset:" + course_id + ":" + today.isoformat(),
                    f"⚠️ Canvas sync state looks lost for course {course_id} (0 modules "
                    f"marked synced, but {len(existing)} tasks already exist in "
                    f"FlowSavvy) — about to re-extract every unlocked "
                    f"module from scratch. Check logs/canvas_state.json "
                    f"before this creates near-duplicates.", "high")
    try:
        done = fs.list_items(itemType="task", listId=course["list_id"],
                             completed=True).get("items", [])
        for t in done:
            title = t.get("title", "")
            if title and title not in completed_cache:
                completed_cache[title] = (t.get("lastModified") or today.isoformat())[:10]
        seen_titles.update(completed_cache)
        seen_source_ids.update(canvas_engine.extract_source_ids(t.get("notes", "") for t in done))
    except Exception:
        pass

    try:
        modules = cv.modules(course_id=course_id)
    except Exception as e:
        # Same severity as the browser-session-expired path (both credential
        # paths must alert identically on auth failure — a revoked/stale
        # CANVAS_TOKEN should not degrade to print-only, which is silently
        # discarded under pythonw).
        _alert_once("canvas:token:" + course_id + ":" + now.date().isoformat(),
                    f"Canvas sync failed for course {course_id} (token may be revoked/expired): {e}", "high")
        print(f"[canvas] failed to fetch modules: {e}"); return

    modules_data = []
    claimed_nums = set()   # legacy nums already matched to a stable id this run (collision guard)
    for mod in modules:
        name = mod.get("name", "") or ""
        # Word-anchored keyword extraction ("Module N"/"M N"), first-digit fallback —
        # see canvas_engine.module_number (an un-anchored "m" matched the trailing "m"
        # of "Midterm 1 - Module 9" and returned 1, mis-numbering the module).
        num = canvas_engine.module_number(name)
        if num is None:
            continue   # unnumbered utility module ("Start Here", "Syllabus", ...) — nothing to sync
        mod_id = mod["id"]

        # Dedup on the STABLE Canvas module id, not the number scraped from the
        # (renameable) module name. Keying on `num` alone let a mid-term
        # rename/renumber make an already-synced module look new (→ re-synced,
        # duplicate tasks) and let a second module sharing a scraped number
        # ("Supplementary readings for Module 5") get silently skipped forever.
        if mod_id in synced_ids:
            claimed_nums.add(num)   # this num is spoken for by a known id — free any collider below
            continue
        # Legacy migration: pre-id state only knows synced NUMBERS. Honor a legacy
        # num ONCE per run (the first module bearing it is the one we actually synced
        # before) and adopt that module's stable id, so future runs key on the id. A
        # second module with the same num is a genuine collision, not the synced one.
        if num in synced and num not in claimed_nums:
            claimed_nums.add(num)
            synced_ids.add(mod_id)
            continue

        unlock_str = mod.get("unlock_at") or mod.get("published_at") or ""
        unlock_date = canvas_engine._parse_date(unlock_str) or today
        if unlock_date > today:
            continue

        items = mod.get("items") or []
        modules_data.append({
            "module_num":  num,
            "unlock_date": unlock_date,
            "_mod_items":  items,
            "_mod_id":     mod_id,
        })

    if not modules_data:
        # No new modules to plan/create — but do NOT return here. The due-date
        # change check and announcement check below must run on EVERY sync, not
        # just when a module unlocks. Once a course is fully synced modules_data
        # is empty every run, and an early return here silently disabled due-date
        # re-sync for the rest of the semester (exactly when instructors most
        # often shift deadlines). plan([]) and the create loop are no-ops on an
        # empty list, so the flat flow below stays correct.
        print("[canvas] no new modules to sync")

    # bulk fetch all assignments once — required both to create new-module tasks
    # AND to re-check due dates on already-synced tasks below, so this fetch must
    # happen even on the no-new-modules path.
    try:
        all_assignments = {a["id"]: a for a in cv.assignments(course_id=course_id)}
    except Exception as e:
        print(f"[canvas] failed to fetch assignments: {e}"); return

    # Which assignments got REAL content-aware phase names this run, vs the
    # generic fallback template -- keyed by str(assignment_id), used both to
    # record alongside newly-created phase tasks below and by the retroactive
    # rename check further down (for assignments synced in an EARLIER run,
    # before this run learned their content-aware names).
    content_aware_by_aid = {}

    # populate assignments + readings per module
    for mod in modules_data:
        items = mod.pop("_mod_items")
        # keep "_mod_id" on the dict — needed at save time to persist the stable
        # id into synced_module_ids. plan() ignores unknown keys.

        asgns = []
        reading_page_slugs = []
        for item in items:
            if item.get("type") == "Assignment":
                cid = item.get("content_id")
                if cid and cid in all_assignments:
                    a = all_assignments[cid]
                    a.setdefault("display_name", _display_name(a, short_titles, llm))
                    if "_phase_labels" not in a:
                        a["_phase_labels"] = _phase_labels_for(a, phase_labels_cache, llm, strip_html,
                                                               cv=cv, course_id=course_id)
                        content_aware_by_aid[str(a.get("id"))] = a["_phase_labels"] is not None
                    asgns.append(a)
            elif item.get("type") == "Page":
                t = (item.get("title") or "").lower()
                if any(w in t for w in ("reading", "resource", "material")):
                    slug = item.get("page_url") or item.get("url", "").split("/pages/")[-1]
                    if slug:
                        reading_page_slugs.append(slug)

        # extract readings from pages via LLM
        readings = []
        for slug in reading_page_slugs:
            try:
                page = cv.page(slug, course_id=course_id)
                text = strip_html(page.get("body") or "")
                if text:
                    readings.extend(llm.extract_readings(text, mod["module_num"]))
            except Exception as e:
                print(f"[canvas] page {slug}: {e}")

        mod["assignments"] = asgns
        mod["readings"]    = readings

    # plan
    result = canvas_engine.plan(modules_data, seen_titles, today,
                                existing_source_ids=seen_source_ids)

    # Flood guard — HOLD instead of flooding when a run wants to create an
    # implausible number of tasks (the state-loss re-sync signature). Write the
    # intended creates to a pending file, alert with a one-tap approve, and skip
    # both creation AND the state save this run (so nothing is marked synced and
    # the next unapproved run re-triggers the guard). Approving from the panel
    # sets `flood_ack` = today in canvas_state.json and re-runs canvas; the guard
    # bypasses for that day and creates normally — no replay logic needed.
    # canvas_pending.json is keyed by course_id so a hold on one course never
    # blocks or clobbers another course's pending/normal sync.
    creates = result.get("creates", [])
    pp = os.path.join(history.ROOT, "private", "logs", "canvas_pending.json")
    pending_all = state_store.load_json(pp, default={}) or {}
    if len(creates) > _CANVAS_FLOOD_MAX and st.get("flood_ack") != today.isoformat():
        pending_all[course_id] = {"at": now.isoformat(), "count": len(creates),
                                  "report": result.get("report", ""),
                                  "titles": [c.get("title") for c in creates]}
        _save_json_atomic(pp, pending_all)
        _alert_once("canvas:flood:" + course_id + ":" + today.isoformat(),
                    f"⚠️ Canvas sync for course {course_id} wanted to create {len(creates)} tasks — "
                    f"held as suspicious (usually a state-loss re-sync, not that many real new "
                    f"tasks). Review + approve in the panel.", "high", click_anchor="settings#canvas")
        print(f"[canvas] HELD {len(creates)} creates for course {course_id} (flood guard > {_CANVAS_FLOOD_MAX})")
        return
    # Cleared the guard: consume the one-shot ack and drop any stale pending state
    # for THIS course only.
    st.pop("flood_ack", None)
    if pending_all.pop(course_id, None) is not None:
        if pending_all:
            _save_json_atomic(pp, pending_all)
        else:
            state_store.delete_key(pp)

    # apply: create tasks in FlowSavvy
    created_titles = {}   # title → id (for dependency wiring)
    for spec in result["creates"]:
        dep_title = spec.pop("_dep_title", None)
        source_id = spec.pop("_source_id", None)
        phase_index = spec.pop("_phase_index", None)
        phase_total = spec.pop("_phase_total", None)
        spec["notes"] = canvas_engine.format_ref_note(spec.get("notes", ""), source_id,
                                                       phase_index, phase_total)
        kwargs = {
            "listId":            course["list_id"],
            "schedulingHoursId": course["sh_id"],
            "isAutoScheduled":   True,
            **spec,
        }
        if dep_title and dep_title in created_titles:
            # FlowSavvy's real dependency field (same one household.run_meal uses)
            kwargs["blockedByIds"] = [created_titles[dep_title]]
        try:
            r = fs.create_task(**kwargs)
            tid = (r or {}).get("id") or (r or {}).get("item", {}).get("id")
            if tid:
                created_titles[spec["title"]] = tid
            if source_id:
                created_source_ids.add(source_id)
            if tid and phase_index and source_id and source_id.startswith("assignment:"):
                # source_id shape: "assignment:<id>:phase:<n>" -- record which
                # task holds which phase, and whether it's already using a
                # real content-aware name, so the rename check below (or a
                # future run) can find and update it once/if that changes.
                aid = source_id.split(":", 2)[1]
                entry = phase_task_ids.setdefault(
                    aid, {"content_aware": content_aware_by_aid.get(aid, False), "tasks": {}})
                entry["tasks"][str(phase_index)] = tid
            # durable audit trail — creations must survive discarded stdout.
            # creates_task=True tells the History page's undo button that
            # meta.id is a FlowSavvy item THIS log entry created (so undo
            # should delete it too), as opposed to an id that just
            # references something that already existed (e.g. a completed
            # task an ntfy/flowsavvy-sourced log entry points at).
            history.append("course_task", source="canvas",
                           meta={"id": tid, "title": spec["title"], "creates_task": bool(tid)})
            actions.log("canvas", "created course task", spec["title"],
                        item_id=tid, undoable=True)
            _touch()
        except Exception as e:
            print(f"[canvas] create failed for {spec.get('title','?')}: {e}")

    # check for due-date changes in already-synced assignments
    try:
        for a in all_assignments.values():
            name = a.get("name", "")
            new_due = a.get("due_at", "")
            if not new_due:
                continue
            # search FlowSavvy for matching title fragments
            for item in fs.list_items(itemType="task", listId=course["list_id"],
                                      completed=False, query=name[:30]).get("items", []):
                # only the unsplit / final task carries the Canvas due date;
                # phase tasks ("… — Draft") have staggered dues — leave them be
                title = item.get("title") or ""
                bare = title.rstrip("]").split(" [")[0]     # strip "[AS.…]" course tag
                if not (title.endswith(name) or bare.endswith(name)):
                    continue
                fs_due = (item.get("dueDateTime") or "")[:10]
                canvas_due = new_due[:10]
                if fs_due and canvas_due and fs_due != canvas_due:
                    fs.update_task(item["id"], dueDateTime=f"{canvas_due}T23:59:00")
                    _touch()
                    print(f"[canvas] updated due date for {title}: {fs_due}→{canvas_due}")
    except Exception as e:
        print(f"[canvas] change-check error: {e}")

    # Retroactive rename: an assignment synced while its Canvas description
    # was still empty/stub got the generic per-atype phase names (the whole
    # semester's assignments are often visible -- and synced -- well before
    # each one's real instructions are written). Recheck every
    # not-yet-content-aware assignment EVERY run and rename its
    # already-created phase tasks in place once real content-aware names
    # become available. Safe to do after the fact: FlowSavvy resolves
    # blockedByIds to real ids at creation time, not by title lookup, so
    # renaming a task's title doesn't disturb any dependency already wired.
    try:
        for aid_str, entry in phase_task_ids.items():
            if entry.get("content_aware"):
                continue
            try:
                aid = int(aid_str)
            except ValueError:
                continue
            a = all_assignments.get(aid)
            if a is None:
                continue
            name = a.get("name", "")
            atype = canvas_engine.classify(name, a.get("submission_types", []), a.get("points_possible"))
            due = canvas_engine._parse_date(a.get("due_at"))
            count = canvas_engine.phase_count_for(name, atype, due)
            new_labels = _phase_labels_for(a, phase_labels_cache, llm, strip_html,
                                           cv=cv, course_id=course_id)
            if not new_labels or len(new_labels) != count:
                continue   # still no usable description -- try again next run
            new_specs = canvas_engine.split_assignment(
                0, name, atype, due, due or today, None, today,
                assignment_id=aid, display_name=a.get("display_name"), phase_labels=new_labels)
            for phase_i_str, task_id in entry["tasks"].items():
                idx = int(phase_i_str) - 1
                if idx >= len(new_specs):
                    continue
                new_title = new_specs[idx]["title"]
                try:
                    fs.update_task(task_id, title=new_title)
                    _touch()
                    print(f"[canvas] renamed to content-aware phase name: {new_title}")
                except Exception as e:
                    print(f"[canvas] rename failed for assignment {aid} phase {phase_i_str}: {e}")
            entry["content_aware"] = True
    except Exception as e:
        print(f"[canvas] phase-rename check error: {e}")

    # check announcements
    try:
        import datetime as _dt
        since = (_dt.date.today() - _dt.timedelta(days=7)).isoformat()
        announcements = cv.announcements(since_date=since, course_id=course_id)
        for ann in announcements[:3]:
            title = ann.get("title", "")
            posted = (ann.get("posted_at") or "")[:10]
            print(f"[canvas] announcement ({posted}): {title}")
    except Exception:
        pass

    # save state
    for mod in modules_data:
        synced.add(mod["module_num"])       # legacy num set (display + state-loss heuristic)
        synced_ids.add(mod["_mod_id"])      # stable id set (authoritative dedup key)
    # Persist ONLY engine-created titles: what prior runs created plus what this run
    # created. Deliberately NOT `seen_titles` — that also holds the live FlowSavvy
    # incomplete/completed titles and the completed_cache, which are run-local dedup
    # inputs, not a record of what this engine produced. Folding them in grew
    # task_titles without bound and nullified the completed_cache eviction.
    created_persisted.update(created_titles.keys())
    st["synced_modules"]     = sorted(synced)
    st["synced_module_ids"]  = sorted(synced_ids)
    st["task_titles"]        = sorted(created_persisted)
    st["source_ids"]         = sorted(created_source_ids)
    st["completed_cache"]    = completed_cache
    st_root["courses"][course_id] = st
    os.makedirs(os.path.dirname(sp), exist_ok=True)
    _save_json_atomic(sp, st_root)

    n = len(created_titles)
    print(f"[canvas] {n} task(s) created for course {course_id}\n{result['report']}")
