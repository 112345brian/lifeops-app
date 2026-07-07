# Changelog

Notable changes, newest first. Personal project, versioned simply (see
`VERSION` / `lifeops.__version__`) — dates and the reasoning behind each
change matter more here than semver strictness.

## Unreleased

### Added
- **Gym Calendar in the control panel.** A Mon-aligned 2-week grid
  (`#gym-calendar`) backed by the same history actions and `gym_blocks` list
  the other gym controls use. Tap a past/today cell to cycle went ✅ →
  didn't go → blank; tap a today/future blank cell to mark it don't-schedule
  🚫 → blank. New `POST /gym/cycle-date` endpoint and `history.remove_day()`
  helper (deletes every entry for an action on a given date, regardless of
  source — used to undo a manual log/unlog toggle from the UI).
  - Fixed during review: the calendar only reads `gym`/`gym_skip` history,
    not the nightly cleanup's separate `gym_missed` marker (`runner.py`), so
    a day the cleanup already auto-logged as missed still read as blank.
    Marking it "went" from the calendar would then leave both a `gym` and a
    `gym_missed` entry for the same date, double-counting it in
    `adherence.gym()`'s rate calculation. `/gym/cycle-date` now clears any
    `gym_missed` entry before logging a fresh `gym`.

### Fixed
- **Gym cleanup could re-log the same missed session on every tick.**
  `run_gym` recorded a `gym_missed` history entry whenever a stale/elapsed
  Gym task was found, but only checked `history.days_with("gym", ...)` first
  — if the FlowSavvy delete then failed (or hadn't run yet), the same day
  got a fresh `gym_missed` entry on every subsequent tick. Now also checks
  `history.days_with("gym_missed", ...)` before logging, so a day is
  recorded as missed once.
- **Failed Gym task deletions were silently swallowed.** `fs.delete_item`
  errors during stale-task cleanup were caught and discarded (`except
  Exception: pass`), so a persistently undeletable task would loop forever
  with no visible signal. Now collects delete errors and raises a
  `RuntimeError` after the tick's summary is printed, so failures surface
  instead of failing silently.
- **Control panel 500'd on every page load.** A Starlette upgrade deprecated
  the old `TemplateResponse(name, context)` call form in favor of
  `TemplateResponse(request, name, context)`; the old form silently passed
  the context dict as `name`, which jinja2's template cache can't hash
  (`TypeError: unhashable type: 'dict'`). Caught this live when restarting
  the panel to deploy the gym fixes above — updated the one call site in
  `lifeops/web.py`.
- **The Config card's restart button silently did nothing.** `_restart_server`
  spawned its detached PowerShell helper (the one that runs `schtasks /end`
  then `/run`, needed because ending the task kills this process before it
  can restart itself) with `DETACHED_PROCESS`. With no console at all,
  `powershell.exe` exits immediately without running the script — so the
  endpoint always returned a success redirect while never actually cycling
  the task. Switched to `CREATE_NO_WINDOW` (a real, hidden console), which
  the helper actually needs to execute; verified an end-to-end restart and a
  back-to-back double-restart both now work.
- **Canvas login/re-login always hit a Cloudflare block.** The interactive
  login step navigated to Canvas's login redirect
  (`jhu.instructure.com/login` → ... → `canvas.jhu.edu`) through Playwright,
  and that redirect chain sits behind Cloudflare Bot Fight Mode, which
  hard-blocks any CDP-attached navigation — confirmed vanilla Playwright,
  patchright, and manual anti-detection launch args all still got "Sorry,
  you have been blocked," while a genuinely bare `chrome.exe` process (no
  CDP attached) sailed through. `scripts/canvas_login.py` and
  `scripts/canvas_relogin.py` now open the login page via a new
  `canvas_browser.launch_manual_login()` helper (a plain subprocess) instead
  of a Playwright-driven page; Playwright is only used afterward, for
  `logged_in()` verification and the daily sync's raw API requests — neither
  of which triggers the block, since authenticated requests never redirect
  off-domain and the daily sync hits Canvas's JSON API directly with no
  rendered page for Cloudflare's browser checks to see.

## [1.1.0] — 2026-07-04

### Added
- **Canvas re-login button in the control panel.** The "Canvas session
  expired" ntfy alert previously just told you to run
  `python scripts/canvas_login.py` from a terminal — no way to act on it
  from your phone. Added an **Accounts** card to the control panel
  (`#accounts`) with a live status badge (connected / session expired / not
  set up) and a "🔑 re-login" button that launches a real, visible Chrome
  window on the PC using Canvas's persistent sign-in profile.
  - New `scripts/canvas_relogin.py`: same profile/flow as the manual
    `canvas_login.py`, but polls for a successful login in the background
    instead of blocking on `input()`, since it's spawned by the web server
    with no attached console.
  - New `POST /account/canvas/relogin` endpoint in `lifeops/web.py`.
  - Status badge reads the existing alert-dedup log
    (`logs/alert_state.json`) rather than launching a browser on every page
    load, so rendering the panel stays cheap.
  - The ntfy alert's Click link now deep-links straight to `#accounts`
    instead of the panel root.

## [1.0.0] — 2026-07-03

First consolidated release: everything below (control panel, all 9 domains,
Canvas sync, PWA install, and this pre-merge review's fixes) merged from
`worktree-web-ui` + `worktree-mobile-app` onto `master` in one pass.

### Pre-merge review fixes
Before consolidating, ran a code review over the full accumulated diff and
fixed every confirmed finding:
- **Tier rebalance had silently dropped the `"signal"` TIERS key** while
  `register_task.ps1` still registers a 2-minute scheduled task that runs
  `runner.py signal` — restored it. Without this, the low-latency "catchup"
  phone-tap response silently degraded from ~2 min to the 10-min tick, with
  no error anywhere.
- **Canvas sync crashed on any unnumbered module** (`re.search(r"\d+", ...).group()`
  on a module like "Start Here: Welcome and Course Overview" — a real module
  in the actual course) — now skips unnumbered utility modules instead of
  raising every single day.
- **Canvas task dedup broke past Module 9** — a `query="M0"` substring filter
  silently stopped matching `M10`/`M11`/`M12` titles. Dropped the filter;
  `listId` scoping to the course list is already sufficient.
- **Canvas auth failures were inconsistent** — a revoked/expired browser
  session alerted at high priority, but a revoked `CANVAS_TOKEN` degraded to
  a `print()` that's silently discarded under `pythonw`. Both paths now
  alert identically.
- **`run_meal` moved to the 10-min tick tier but still polled ntfy
  unconditionally** before checking due-ness — reintroducing the exact
  redundant-fetch pattern this same changelog claims to have fixed for
  `spend` (moved the opposite direction for exactly this reason). Now checks
  the free, local due-date first.
- **`gym_engine`'s `viable_left` fix (below) was incomplete** — it checked
  each remaining candidate against the fixed `busy` set independently, so
  candidates mutually adjacent to *each other* (not to `busy`) were each
  individually counted as viable even though the consecutive-day cap means
  not all of them could be booked together. Now simulates the same greedy
  booking order to get the true count.
- **`canvas_engine._spread()`'s today-clamping (below) collapsed
  dependency-chained phases onto the identical date** on a late sync — e.g.
  three chained tasks (each blocked by the previous) all due the same day,
  sequentially impossible. Now clamps relative to the previous phase,
  preserving order until there's genuinely no more calendar to spread across.
- 2 new regression tests (106 total).
- **Known limitation, unverified:** whether an installed PWA's standalone
  launch shares a cookie jar with the browser session that authenticated it
  under `WEB_TOKEN` is platform-dependent (particularly on iOS) and couldn't
  be confirmed from source alone — needs a manual check on a real device. If
  the home-screen icon 401s while the browser tab works fine, this is why.

### Installable PWA + notification deep links
The control panel is now a proper installable app: `manifest.json`, generated
icons, a service worker (network-first — the dashboard is live data, not a
static shell to cache aggressively), and an in-panel "Add to Home Screen"
hint (iOS has no install-prompt API, so the panel surfaces the Share-sheet
instructions itself; Android/Chrome gets a one-tap custom install button via
`beforeinstallprompt`). Kept **ntfy** as the actual push mechanism rather
than building custom Web Push — it's already proven-reliable and fully
wired into every alert path, whereas Web Push on iOS only works from an
already-installed PWA and silently drops expired subscriptions. Instead,
added a `PANEL_URL` config value + a `click` URL on every ntfy alert so
tapping a notification deep-links straight into the relevant panel section
(e.g. a gym alert opens directly to Gym Controls) — one integrated
experience across the two apps instead of two disconnected ones.

### Canvas sync via browser session, no token/cookie needed
JHU disables self-service Canvas API tokens, and Canvas's session cookie is
  `httpOnly` (unreadable from a page, confirmed by direct test — not a
  permissions issue, a browser security boundary). Added `lifeops/canvas_browser.py`: a
  dedicated, persistent Chrome profile (`data/browser_profiles/canvas/`) that
  Playwright drives, hitting Canvas's own JSON REST API through the
  authenticated session's cookie jar — same response shapes as the
  token-based client, so `canvas_engine.py` and `runner.py`'s sync logic
  don't change at all. One-time interactive login: `scripts/canvas_login.py`
  (SSO + Duo can never be automated — that step stays manual by design). An
  expired session triggers an ntfy alert with the exact re-login command
  instead of failing silently. Runs under the same `pythonw` cron as every
  other domain — no dependency on a Claude session or browser extension.

### 2026-07-01
- **Full engine audit — 7 correctness bugs fixed:**
  - `canvas_engine`: an assignment with a missing/unparseable due date
    crashed the entire planner (`TypeError` in date-spread math on `None`);
    now emits a single unsplit task with no invented deadline instead.
    Phase due-dates clamp to `today` so a close deadline never creates a
    task that's already overdue at creation. `classify()` now catches
    "Required Replies" (plural — Canvas's actual title format). Fixed
    `final_paper` having 5 date-spread gaps for 4 phases, which left the
    last phase landing 2 days short of the real deadline.
  - `ynab_engine`: a transaction pre-categorized straight into a protected
    fund (e.g. Savings) skipped the `no_assign` guard and got auto-approved;
    now held for review like any other flagged transaction. A single
    historical payee sighting no longer becomes an auto-categorization rule.
  - `gym_engine`: the consecutive-day cap only checked *scheduled* blocks,
    not days actually trained — could book a real 3rd straight day after
    completed (unscheduled) sessions. Now counts the last 7 days of real
    history. The floor-alert's viable-day count no longer includes days the
    cap would reject anyway (was under-warning).
  - `chore_engine` / `load_engine` / `spend_engine`: malformed or missing
    fields (bad date, missing id/title, null cost) no longer crash the
    engine — bad items are skipped, not batch-aborting.
  - `adherence.py`: timestamp parsing now handles timezone offsets and `Z`
    suffixes instead of a fixed string-slice that silently misread anything
    non-canonical. `streak(now=...)` reports 0 for a streak that already
    broke instead of its stale length.
  - `runner.run_canvas`: fixed a `today` used-before-assignment bug that
    guaranteed a `NameError` the moment Canvas sync ran with a real
    credential (shipped broken, caught before it ever fired for real).
- **Run auditability.** Every domain's per-run summary is now captured
  (`pythonw` silently discards stdout) into `logs/last_run.json` and an
  append-only `logs/runs.jsonl`. Canvas task creations write to
  `logs/history.jsonl` (`course_task`), and completed coursework check-offs
  now classify into history — coursework previously had zero durable audit
  trail, unlike gym.
- **Tier rebalance.** `meal` moved to the 10-min tick so a "have leftovers,
  skip" button tap is honored within minutes instead of next-morning;
  `spend` moved to daily since it only ever alerts once/day (was making 143
  redundant YNAB+FlowSavvy fetches/day on the tick).
- **Web panel hardening** for eventual Tailscale exposure: optional
  `WEB_TOKEN` shared-secret gate, `/run` validates the domain name instead
  of passing arbitrary argv through, `/config` strips newlines so a value
  can't inject extra `.env` lines, FlowSavvy failures during day-blocking
  now surface a flash banner instead of silently pretending the day was
  blocked.
- **50 new tests** (104 total, was 54): full coverage for `ynab_engine` and
  `canvas_engine` — previously the two most complex engines and the only
  ones with zero tests — plus a regression test for every bug above.

### 2026-06-28 – 2026-06-30
- **Web UI rewrite.** Replaced inline HTML string generation with a proper
  Jinja2 dark-mode dashboard (`lifeops/web.py` + `lifeops/templates/`): live
  status bar, gym stats, per-domain controls, history view.
- **Gym scheduling controls**: block a specific date, sick/rest week,
  manual re-plan, "don't count today."
- **General day-blocking**, generalized beyond gym: creates a FlowSavvy busy
  event for the whole day (via `BLOCK_CAL`) and blocks the gym engine for
  the same date in one action.
- **Canvas domain** (`lifeops/engines/canvas_engine.py`, `lifeops/canvas.py`):
  syncs newly-unlocked Canvas modules into FlowSavvy — readings become
  individual tasks, assignments split into dependency-chained phases
  (outline → draft → revise, etc.) with spread-out due dates, deduped
  against both open and completed FlowSavvy tasks plus a 20-day local
  completion cache.

## Earlier (on `master`)
- Adherence loop: learns gym slot-completion rate and preferred time,
  suppresses slots he doesn't actually honor, records misses; weekly LLM
  accountability digest (Sundays).
- Real sleep duration from a watch (Health Connect → ntfy `sleep:<minutes>`),
  preferred over the unreliable phone-motion heuristic.
- Deliberate priority hierarchy (hard deadlines > gym > meal/coordination >
  chores/tentative social), config-driven.
- Reliability pass: single `recalculate()` per run instead of per-domain
  churn, fail-loud error + healthcheck heartbeat + resume-gap alert, global
  run-lock with stale-lock breaking, UTC-correct `modifiedAfter` filtering.
- Two-stage social planning: propose a tentative slot + a separate "Plan X"
  task; completing the latter locks the hangout in.
- Durable append-only completion history (`logs/history.jsonl`) — the
  system's real memory, since ntfy signals expire and FlowSavvy task state
  is lossy. Every engine reads cadence from here.
- YNAB: never auto-assign a transaction to a pure savings/fund category;
  overspend gets covered by draining discretionary "wants" in priority
  order, never savings, never below zero.
- Tiered cadence: `tick` (deterministic, ~10 min) vs `daily`
  (LLM-touching/heavy), both under one global lock.
- Initial scaffold: FlowSavvy/YNAB/ntfy/Anthropic clients, config, gym +
  chore engines ported from the original Claude-driven prototype, first
  live end-to-end run creating real FlowSavvy tasks.