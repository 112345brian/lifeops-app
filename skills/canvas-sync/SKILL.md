---
name: canvas-sync
description: Sync Canvas assignments and readings for JHU AS.470.703.81.SU26 Urban Data Analytics into FlowSavvy. Run weekly on module unlock days (M07 Jun 29, M08 Jul 6, M09 Jul 13, M10 Jul 20, M11 Jul 27, M12 Aug 3).
---

You are syncing Canvas assignments and readings for Brian's JHU course AS.470.703.81.SU26 Urban Data Analytics (course ID 124987) into FlowSavvy.

**FlowSavvy connector:** ebd928b1-83c2-4d44-952d-f30c62bca79b  
**Canvas URL:** https://jhu.instructure.com/courses/124987  
**FlowSavvy listId:** 147765  
**FlowSavvy schedulingHoursId:** 428026  
**STATE FILE:** C:/Users/user/.claude/scheduled-tasks/canvas-sync/state.json — tracks which modules have been fully synced and which task titles already exist. Create if missing.

**TIMEZONE:** America/Los_Angeles. Establish today's date at the start.

---

## ATOMICITY RULE (applies to ALL tasks)

Every task must represent a single schedulable work session:
- Each individual reading (article, tutorial, chapter set, book) is its own task — never bundle multiple readings together
- Any piece of work estimated at 20 minutes or more gets its own task
- Anything under 20 minutes can be a note/step inside a related task
- Any assignment with obvious stages (research then write, code then interpret, draft then revise) should be split into one task per stage — even if each stage is only 45–75 min. Ask: would a person realistically stop and come back between these steps? If yes, split it.
- A single-mode task that takes 45–75 min in one sitting (like writing a discussion post) does NOT need to be split.

---

## PRIORITY RULE

- Assignments and projects: priority=normal
- Readings: priority=normal
- Anything due within 3 days of today: priority=high

---

## DATE LOGIC

Readings must be scheduled to be completed BEFORE any assignment that depends on them:
1. Readings due 2–3 days before the first assignment deadline in that module
2. Initial discussion posts / papers due on Canvas deadline
3. Required replies due on Canvas deadline (always after the initial post)

Set canBeStartedAt for assignments that depend on readings to after the readings are due.

---

## STEP 1 — Check Canvas for newly unlocked modules

Navigate to https://jhu.instructure.com/courses/124987/modules using the Claude in Chrome MCP tools (mcp__Claude_in_Chrome__navigate, mcp__Claude_in_Chrome__get_page_text, mcp__Claude_in_Chrome__read_page). Module unlock schedule:

- M05: Jun 15 | M06: Jun 22 | M07: Jun 29 | M08: Jul 6
- M09: Jul 13 | M10: Jul 20 | M11: Jul 27 | M12: Aug 3

Load state.json. For each module whose unlock date is today or earlier:
- If it is already in state.synced_modules, skip it (already fully synced).
- Otherwise, visit its Overview page and Readings & Resources page to get the full list of assignments and readings.

---

## STEP 2 — Create tasks for newly unlocked modules

Before creating any tasks for a module, call list_items (FlowSavvy connector, query="Urban Data Analytics") to see what already exists. Cross-reference against state.task_titles. Do NOT create a task whose title already exists in either source.

### Readings

**Title format:** `M0X: Read [Author], [Short Title]`

**Duration by resource type:**
- Short article / blog post: 20–30 min
- Tutorial with code: 40–60 min
- Documentation review (key sections): 45–60 min
- Academic book chapter: 40–50 min each
- Accessible book chapter (readable prose): 25–35 min each
- Full short book (~150–200 pages): 240 min
- First N chapters of a book: per-chapter estimate × N

**Due dates:** 2–3 days before the first assignment due in that module.  
**canBeStartedAt:** module unlock date.

### Assignments (discussions, papers, projects, homework)

**Title format:** `M0X: [Assignment Name]`  
For staged assignments: `M0X: [Assignment Name] — [Phase]`  
Example phases: "Setup & Data Exploration", "Analysis & Visualization", "Write-Up", "Revise & Polish", "Submit"

**Duration by assignment type:**
- Discussion initial post (single-mode writing): 60–90 min — one task, no split
- Discussion initial post (requires finding/analyzing data first): split into research (45–60 min) + write post (60 min)
- Required reply (1): 30–45 min — one task
- Short response paper: 150–180 min — split into outline/notes (45 min) + draft (90–120 min) + revise (30–45 min)
- Code-based analysis assignment: split into setup/explore (60–90 min), analysis/viz (90–120 min), write-up (60–90 min)
- Prospectus / research proposal: split into outline (60 min) + draft (120 min)
- Paper draft: split into outline (90 min), draft body (120 min × 2), revise (90 min)
- Final paper: split into incorporate feedback (120 min), rewrite/expand (150 min), polish/citations (120 min), proofread/submit (90 min)
- Presentation / share-out post: 90–120 min — one task unless it requires building slides separately

**canBeStartedAt:** module unlock date, or after the readings' due date if the assignment depends on readings.  
**dueDateTime:** actual Canvas due date for the final phase. Earlier phases get intermediate due dates spread logically before the final deadline.  
**Dependencies:** if task B depends on task A (e.g., a writing task depends on a reading task), set the "must be done after" dependency on task B pointing to task A's id. Capture ids from create_task responses.

**All tasks:** isAutoScheduled=true, listId=147765, schedulingHoursId=428026, priority=normal (or high if due within 3 days)

After creating all tasks for a module, call recalculate. Add the module number to state.synced_modules and all created task titles to state.task_titles. Save state.json.

---

## STEP 3 — Check for changes to existing assignments

Navigate to https://jhu.instructure.com/courses/124987/grades and the Modules page. Check:
- Due date changes vs. what's in FlowSavvy
- New assignments added to already-synced modules
- Any announcements posted since last Monday (navigate to the Announcements page)

Cross-reference against existing FlowSavvy tasks. For any due date that changed: call update_task with the corrected dueDateTime, then recalculate. Add a note in the report.

---

## STEP 4 — Report

Output a brief summary:
- Modules processed this run (or "no new modules unlocked")
- New tasks created (list each title and duration in minutes)
- Any due date changes found and updated
- Announcements worth flagging
- Any modules or pages that couldn't be accessed
