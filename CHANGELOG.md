# Changelog

Notable changes, newest first. Personal project — no version numbers, just
dates and the reasoning behind each change.

## Unreleased (branch: `worktree-mobile-app`, based on `worktree-web-ui`)

### 2026-07-03
- **Installable PWA + notification deep links.** The control panel is now a
  proper installable app: `manifest.json`, generated icons, a service worker
  (network-first — the dashboard is live data, not a static shell to cache
  aggressively), and an in-panel "Add to Home Screen" hint (iOS has no
  install-prompt API, so the panel surfaces the Share-sheet instructions
  itself; Android/Chrome gets a one-tap custom install button via
  `beforeinstallprompt`). Kept **ntfy** as the actual push mechanism rather
  than building custom Web Push — it's already proven-reliable and fully
  wired into every alert path, whereas Web Push on iOS only works from an
  already-installed PWA and silently drops expired subscriptions. Instead,
  added a `PANEL_URL` config value + a `click` URL on every ntfy alert so
  tapping a notification deep-links straight into the relevant panel section
  (e.g. a gym alert opens directly to Gym Controls) — one integrated
  experience across the two apps instead of two disconnected ones.

## Unreleased (branch: `worktree-web-ui`)

### 2026-07-03
- **Canvas sync via browser session, no token/cookie needed.** JHU disables
  self-service Canvas API tokens, and Canvas's session cookie is `httpOnly`
  (unreadable from a page, confirmed by direct test — not a permissions
  issue, a browser security boundary). Added `lifeops/canvas_browser.py`: a
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
