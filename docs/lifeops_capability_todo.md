# LifeOps Capability TODO

Purpose: this is a product/architecture backlog for making LifeOps feel fully
capable. It is intentionally opinionated. Before implementing, audit each item
for fit, risk, and overlap with the existing codebase.

## Core Product Model

- Add deterministic `attention_state`: `ok`, `watch`, `risk`, `fucked`.
- Add deterministic `attention_reasons`: short structured reasons with domain,
  severity, title, due/start time, and recommended action.
- Make the LLM briefing consume `attention_state` and `attention_reasons`, but
  never decide them.
- Define cross-domain priority ordering:
  overdue/deadline, system broken, today event conflict, money risk,
  gym/social cadence, routine reminders.
- Add tests for each attention state and priority tie-break.

## Widget

- Put a status badge first: symbol plus label.
  Suggested mapping:
  - `● OK`
  - `▲ WATCH`
  - `◆ RISK`
  - `■ FUCKED`
- Treat the widget more like a watch face/instrument panel than a small web
  page: communicate state through symbols, color, compact meters, and progress
  indicators before adding text.
- Use compact visual primitives where they improve glanceability:
  - severity icon/color for the top-level attention state
  - progress ring/bar for gym target
  - budget/cash buffer bar for discretionary risk
  - coursework load bar for next-seven-days pressure
  - stale/sync indicator for data freshness
  - due/overdue glyphs for tasks
- Show one headline sentence: the next move, not a generic summary.
- Show today's next relevant event.
- Show at most three stats: gym, money, coursework.
- Show at most two or three tasks.
- Hide low-priority stats when `risk` or `fucked` needs the room.
- Add stale-state display, such as `stale 2h` or a warning symbol.
- Add intentional empty/config states.
- Add widget size variants if Glance supports them cleanly.
- Add Android widget tests for parsing/rendering status, headline, and tasks.
- Align the implementation closer to `docs/mockups/lifeops_widget_mockup.html`.

## Full App Home

- Redesign home around `Today`, `Attention`, `Next Actions`, `Situation`, and
  `System`.
- Move domains/tiers lower or into System/Settings.
- Add an Attention card explaining why the widget is `watch`, `risk`, or
  `fucked`.
- Add quick actions: run catchup, log gym, skip gym, block today/tomorrow,
  refresh widget/briefing.
- Show today's schedule/events.
- Show next tasks with complete actions.
- Show stale/error status clearly.
- Keep History, Settings, Recurring, and detailed controls as separate pages.

## Actions API

Shipped (confirmed 2026-07-30 against `lifeops/web.py`/`tests/test_web.py`, this
section was stale): `POST /api/tasks/{id}/complete`, `/api/gym/log`,
`/api/gym/skip`, `/api/schedule/block-day`, `/api/domains/{name}/run` all
exist, are tested, and return fresh attention/next-actions state after
mutating. Direct-call + ntfy-fallback plumbing (`SimpleHttp.kt`) exists on
Android too, but only `/api/tasks/{id}/complete` has an Android caller
(`CompleteTaskAction.kt`) — the other four have no client yet because there's
no Android UI surface to trigger them from (see "Android App"'s Today-app
item below; this isn't a backend gap, it's a client-UI gap).

## Notification Architecture

Shipped (2026-07-30): `notify.alert` now takes an optional `msg_type`
(`"system_health"` in active use; `"urgent_alert"`/`"action_result"` are
available names, not yet called by any domain) threaded onto ntfy's tags.
`notify.push_next_tasks` falls back to a deduped ntfy alert when FCM
no-ops, so a broken/unconfigured FCM no longer silently drops the
next-tasks push (`push_briefing` deliberately has no fallback --
`runner.py`'s `run_briefing` already sends the full briefing text over
ntfy unconditionally, so a second alert would just be redundant noise).

Hardened the same day (2026-07-30, in direct response to finding this
exact redundant-alert bug): every notification in the codebase -- both
`runner.py`'s ~15 tick-driven per-domain alerts (gym, homework, spend,
social, meal, briefing, deadlines, canvas, digest, health, resume-gap) and
`notify.py`'s own push-unavailable fallback -- now goes through one single
dedup mechanism: `notify.alert(..., dedup_key=...)`, once/day per key,
storage-backed by `db.local_get`/`local_set` (previously `runner.py` kept
its own separate file-based dedup log while `notify.py` kept a second,
independent db-based one -- two mechanisms is exactly how the redundant
briefing alert happened, since neither had any idea the other existed).
`runner.py`'s `_alert_once` is now a thin wrapper that forwards its `key`
straight into `notify.alert`'s `dedup_key`, so every call site and its
tests are unaffected. `web.py`'s `_canvas_status` (needs_relogin) was
migrated to read the same shared `alert_dedup:<key>` storage instead of
its own copy of the old file. Tests cover the routing, the fallback, the
once/day dedup, and dedup-only-on-a-successful-send (`tests/test_notify.py`,
`tests/test_web.py`).

Still open:
- Web Push as a third channel (only an aspirational docstring mention
  today, nothing implemented).
- Real per-domain use of `urgent_alert`/`action_result` types beyond the
  new fallback's `system_health` — today only one call site exists.

## Android App

- Turn the Settings-only launcher into a tiny Today app.
- Add Today screen: status badge, briefing, events, tasks, quick actions.
- Move URL/token config into a Settings section.
- Show connection status: panel reachable, last sync, FCM token registered.
- Improve Settings UI polish and validation.
- Add direct API completion path with ntfy fallback.
- Add an Open Full Panel button.
- Add a Force Refresh button.

## Reliability

- Add panel/widget health summary endpoint.
- Track widget last fetch and last FCM token registration.
- Surface FCM token status in web Settings/System.
- Add retries/backoff where token registration fails.
- Ensure all durable state writes are atomic.
- Add stale state detection to widget and app.
- Keep Canvas/FlowSavvy duplicate task protections visible and actionable.
- Keep the Canvas flood guard visible and actionable in the UI.

## Tests

- Keep the Python suite green.
- Add tests for attention-state computation.
- Add tests for `/api/next-tasks` events/tasks shape.
- Add tests for auth behavior: browser redirect versus API direct response.
- Add tests for notification facade behavior.
- Add Android unit tests for JSON parsing and widget status rendering.
- Add at least one fake-FlowSavvy integration-style test for complete-task API.

## Recurring Items as User-Configured Data

**v1 shipped 2026-07-30**: `lifeops/routine.py` — a shared `Routine`
(id/times/per_days/anchor) + `status()`/`status_since_last()`/
`next_due_date()` primitive, consolidating the duplicated "is this due"
math out of `social_engine.py`, `runner.py`'s `run_meal`, and
`chore_engine.py`'s due-date computation. Deliberately does NOT include
`on_due`, cross-routine gating, variable contracts, or a scripting escape
hatch — everything below this point is still future work, not done.
`gym_engine.py` was left untouched (its "due" concept is inseparable from
slot-picking/consecutive-day-cap, not a simple cadence check, and has 19
tests locking down exact behavior) — the optional gather.py count-source
swap discussed during planning was skipped for v1 rather than risking that
suite for marginal consolidation value.

Context: `gym_engine.py`, `chore_engine.py`, `social_engine.py`, and the
meal-planning logic in `runner.py` are each a bespoke Python module with
domain names, cadence targets, and task titles hardcoded (env config, not
user-editable data). Researched how mainstream apps model user-defined
recurring items, to move toward one generic "routine" record + one shared
engine instead of one module per domain.

Findings:
- RFC 5545 RRULE (Google/Apple/Outlook's standard) expresses calendar-day
  patterns (`FREQ`, `INTERVAL`, `BYDAY`, `COUNT`/`UNTIL`) but has no concept
  of "N times per rolling window, any day" — not a good fit for gym/social/
  chores, which are exactly that.
- Loop Habit Tracker (OSS, Kotlin) is the closest real match: one `Habit`
  record + a tiny `Frequency(numerator, denominator)` value object (e.g.
  `Frequency(4, 7)` = "4x per 7 days"), evaluated by one shared
  recompute/scoring engine against a completion-history list. LifeOps's
  existing `history.events`/`history.days_with` log is already
  structurally Loop's `EntryList`.
- Todoist/Things anchor recurrence to user-facing text, but the one
  semantic lesson worth keeping: due-again date can anchor to the
  *original* due date or to the *completion* date — a real behavioral
  switch, not something RRULE/Loop expose, and something LifeOps's
  per-domain code likely already hardcodes one way per domain today.
- Habitica's task types (Habit/Daily/Todo) each carry their own recurrence
  shape rather than one shared schema — the anti-pattern to avoid here.

Case study: Liftosaur/Liftoscript (workout tracker + its progression DSL) —
directly validates the escape-hatch shape below, so researched it
specifically rather than by analogy:
- A Liftosaur "program" is stored as plain text and regenerates itself
  after each workout by re-running its embedded scripts — a workout isn't
  a separate synced record, it's the current rendered output of a stateful
  generator. Same relationship LifeOps wants between a `Routine` and its
  next scheduled instance.
- Liftoscript itself is a real, small, Turing-complete scripting language
  (JS-like syntax, loops, `if/else`, variables) — not a config schema and
  not a constrained expression grammar. It exposes reps/weights/RPE
  (current + completed), 1RM, bodyweight, and week/day position as
  readable state; scripts write future weeks' weights/reps/set-counts,
  which is how deloads and periodization get expressed. It runs entirely
  client-side (~51KB TypeScript evaluator shipped in the app, parsed via a
  formal Lezer grammar) — no server needed to compute the next session.
- The creator's own reasoning for a full scripting language over dropdowns:
  a fixed-field schema is closed-world (only covers progressions the
  author anticipated); a small Turing-complete language is open-world by
  construction. He needed it once his own program outgrew what dropdowns
  could express.
- The mitigation that matters most here: Liftosaur ships built-in canned
  progressions (linear, double progression) so most users never touch raw
  Liftoscript at all — it's reserved as an escape hatch for the tail of
  genuinely custom cases, not the primary interface. Simple fields cover
  the common case; a script covers the rest.
- Sources: [liftosaur.com/doc/liftoscript](https://www.liftosaur.com/doc/liftoscript),
  [Liftosaur overview blog](https://www.liftosaur.com/blog/posts/liftosaur-overview/),
  [Indie Hackers interview](https://www.indiehackers.com/post/liftosaur-weightlifting-tracker-app-for-coders-0f2c1d3837),
  [github.com/astashov/liftosaur](https://github.com/astashov/liftosaur) (AGPL-3.0,
  interpreter fully inspectable: `src/liftoscript.grammar`,
  `src/liftoscriptEvaluator.ts`, `src/liftoscriptFns.ts`).

Proposed shape (not yet implemented):

```
Routine {
  id, title,                        # user-editable — "Gym", "See Sarah", "Vacuum"
  frequency: {times, per_days},     # Loop's model; covers gym/social/chores uniformly
  anchor: "window" | "since_last_completion",
  on_due: "notify" | "schedule_task",  # nudge-only (social) vs. create a task (gym/chore)
  constraints: {...}                # simple fields for the common case; an optional
                                     # small script escape hatch (Liftoscript-style, not
                                     # a vague dict) for the genuinely custom tail: gym's
                                     # consecutive-day cap/slot-picking, meal's
                                     # grocery-then-cook dependency chain
}
```

One `evaluate(routine, history_for_it, now) -> {due, next_due, action}`
engine replaces `gym_engine.plan`/`chore_engine.plan`/`social_engine.plan`'s
separate "is this due" implementations. The few genuinely unique behaviors
(gym's slot-picking algorithm, meal's dependency chain) become small
pluggable pieces keyed off `constraints` — most routines never need more
than the simple fields, matching Liftosaur's canned-progressions-first
approach — not top-level modules. Titles, cadence, and nudge-vs-task
choice move from `config.py` constants (`PARTNER_TASK`, `FRIENDS_TASK`,
hardcoded gym target) into user-editable `Routine` records.

Settled UI principle for whenever a routine editor gets built (settled
2026-07-30, not yet implemented — `lifeops/routine_store.py`'s `load_routine`
returning `(Routine, extra)` already draws this exact boundary at the data
layer): default every routine editor to a **basic view** — the plain
`times`/`per_days`/`anchor` fields plus known simple `extra` keys (gym's
`floor`/`max_consecutive`) — with an opt-in **advanced view** for actual
code (the `constraints` script escape hatch above, and eventually the
cross-routine gate expressions below). Same reasoning as Liftosaur shipping
canned progressions so most users never touch raw Liftoscript: simple
fields cover the common case, a script covers the rest, and the UI should
not force everyone through the scripting surface to change a number.

### Cross-routine gating ("meta logic")

Real gap in the model above: every recurrence system researched (Loop,
Habitica, Liftosaur) evaluates each recurring item independently — none
has a concept of "don't surface B while A is behind" (e.g. don't propose
a friend hangout while gym is behind target). This is a genuinely
different problem than recurrence: cross-routine *gating*, not "is this
one thing due."

LifeOps already has the seed of this in `attention.py`'s
`_DOMAIN_PRIORITY = {"coursework": 0, "system": 1, "money": 2, "gym": 3}`
— a cross-domain priority ranking — but it's wired only for *display*
(which reason wins the headline/badge color), not for *suppressing
actions*. The gym-behind/suppress-friends case needs the same kind of
cross-routine awareness, extended from ranking to actually withholding a
nudge/task.

Settled direction: genuinely Liftosaur-shaped, not a fixed enum-comparison
gate. The point of referencing another routine isn't one hardcoded
comparison — it's that a routine's condition can reference *other routines
or conditions* generally (`friends.caught_up AND gym.days_behind <= 2`,
or whatever a given routine actually needs). That requires each routine
*type* (gym, chore, social) to declare a fixed **variable contract** — the
named variables it computes and exposes (`caught_up`, `days_behind`,
`times_this_week`) — exactly Liftoscript's model of exposing a defined set
of variables per exercise (`reps[n]`, `week`, `RPE[n]`), just applied to
routines instead of exercises. Any expression — a routine's own scheduling
logic, or another routine's gate — reads `other_routine.variable` by name
against that contract.

One load-bearing distinction from Liftoscript, worth keeping the scope
honest: Liftoscript has to be Turing-complete (loops, assignment) because
it **writes** future state — it assigns weights to arbitrary future weeks.
A routine's gate only ever **reads** other routines' exposed variables to
produce one boolean. That's a much smaller thing to build — a read-only
boolean expression grammar (`AND`/`OR`/comparisons over named variables),
not a full interpreter with loops and mutation. Get Liftosaur's actual
idea (typed variable exposure + referential composability) without its
actual implementation scale.

References aren't only single-routine-by-name — a gate needs to quantify
over a *set*, and the set selector shouldn't be a special-cased "tags"
mechanism: it's the same expression language filtering on *any* exposed
variable, including arbitrary user-defined ones (`all(routine =>
routine.is_important == true).caught_up`, not a hardcoded `tags` field
with its own bolted-on syntax). A "tag" is just a convention some routines
might expose as a boolean/string variable like any other (`has_tag ==
"fitness"`) — not a distinct first-class mechanism. One expression
grammar does both the per-routine logic and the set-selection filtering;
`all()`/`any()` are quantifiers over that same language, not a separate
feature.

Two requirements that fall directly out of "routines can reference other
routines (or sets of them)," not optional hardening:
- **Circularity protection.** Routine A's gate reads B's variable, B's
  gate reads A's — needs real cycle detection over the reference graph at
  definition time (reject/flag a cycle when a gate is saved, not discover
  it via infinite recursion at evaluation time). A predicate-selected set
  (`all(routine => routine.is_important == true)`) makes this a dynamic
  fan-out, not a fixed pairwise edge — which routines match a predicate
  can change whenever any routine is added, removed, or edited, so the
  cycle check has to re-run against current matches, not just at save
  time for the one routine being edited. Default `all()`/`any()` to
  excluding the routine being evaluated from its own matched set (a
  routine matching its own gate's predicate is the common case, not a
  cycle).
- **Auditing.** When a routine is suppressed, the *reason* (which gate,
  which variable, what it evaluated to) needs to be visible somewhere a
  user can see it — otherwise "why didn't LifeOps schedule this?" is
  undebuggable. Same spirit as `actions.py`'s existing "what did LifeOps
  do" audit log; a suppressed routine needs an equivalent "why didn't
  LifeOps do this" trail, not just silence. For an `all()`/`any()` gate
  over a predicate-selected set specifically, the trail needs to name
  *which member of the set* failed the condition (e.g. "held:
  gym.caught_up was false"), not just log the aggregate boolean —
  otherwise a multi-routine set hits the same silent-failure problem one
  level up.

**Shipped (2026-07-30/31), ahead of the rest of this section**: the
read-only expression grammar — `LifeScript`
(`android/app/src/main/kotlin/com/lifeops/briefing/LifeScript.kt`),
researched and designed against Liftosaur's actual Liftoscript
documentation (liftosaur.com/doc/liftoscript), not just the earlier
paraphrase. Arithmetic/comparison/logical/ternary operators, `all()`/
`any()` quantifiers over a caller-supplied named set of element contexts
(the direct analog of Liftoscript's bounded `for`-loop-over-a-known-array
— the actual reason it stays non-Turing-complete: no user-defined
functions, no open recursion, only iteration over an already-finite
collection), a small built-in function set. 65 tests, parse-once/
evaluate-repeatedly API. LifeOps's capability model (Sleep, Calendar, YNAB
as genuinely separate external data sources with real fetch/auth cost) is
a deliberate *superset* of Liftoscript's flat always-available variable
space, not a deviation from it — Liftoscript never needed a "which
capabilities does this exercise depend on" concept because it only ever
has one local database to read from.

Also shipped: `Routine.kt` gained a generic scheduling layer (`TimeSlot`,
`slotFor`, `runLength`, `scheduleRoutine`) extracted from `gym_engine.py`'s
actual algorithm with every gym-specific concept removed — proven generic
against a synthetic non-gym routine (`RoutineSchedulingTest.kt`). Gym
itself was re-derived as data: a `Routine` + two `TimeSlot`s whose
conditions are LifeScript expression strings, evaluated by the engine
above, verified against all 19 of the original `GymEngineTest.kt`
scenarios reproduced through the new path (`GymSchedule.kt`,
`GymScheduleTest.kt`). `GymEngine.kt` no longer exists. This is the
concrete proof-of-concept the rest of this section's "variable contract /
predicate-based set selection" design was describing in the abstract —
gym was the hardest case (real algorithm, not just a threshold check), and
it reduced cleanly to data.

**Resolved (2026-08-02)**: chore and social were re-examined against the
same question gym's migration left open ("thin enough that they plausibly
should" get the windows+LifeScript treatment). Finding: neither needs it.
Gym's bespoke code decomposed into `TimeSlot`/`LifeScript` because it had a
genuine per-day decision problem (which of several candidate days has a
clock-time slot available, and which subset to book under a
consecutive-day cap). Chore and social have no such problem — each already
routed its one due-check per call directly through `Routine.kt`'s shared
primitives (`nextDueDate` for chore, `statusSinceLast` for social) with zero
bespoke recurrence math of their own, before this pass even started.
Forcing a `TimeSlot` in would mean inventing a fake always-true condition
and a fake single-candidate-day list just to route through machinery built
for a problem neither domain has. `ChoreEngine.kt`/`SocialEngine.kt` were
renamed to `ChoreSchedule.kt`/`SocialSchedule.kt` (same `plan()` behavior,
all 12/7 original test scenarios reproduced unchanged as
`ChoreScheduleTest.kt`/`SocialScheduleTest.kt`) to match gym's naming
pattern and to write this finding down where it can't silently drift stale,
but no `TimeSlot`/`LifeScript` usage was added to either — their remaining
code (chore's `[cycle:Nd]` tag regex + output-field assembly; social's
null-days guard + nudge-text templates) is domain-specific discovery/
formatting, not scheduling logic, the same category `GymSchedule.kt` itself
keeps as bespoke (alert wording, wind-down attachment, sick-week rule).

Still not scoped: where `Routine` records (and their variable contracts)
live (local SQLite/Room on-device vs. synced list) — `Routine`/`TimeSlot`
are still plain in-memory data classes, nothing persists them yet; the
migration path for the OTHER three original engines (chore/social/ynab —
ynab stays genuinely bespoke, see the "On-Device Migration" section
above); where the audit trail is surfaced (panel page? part of the
existing actions feed?); and whether any of this ships to a real
widget/worker before or after the on-phone execution move discussed
elsewhere in this doc/CHANGELOG. Cross-routine gating itself (a routine's
condition referencing *other* routines by name or by predicate-selected
set, circularity protection, the audit trail) is not yet implemented —
LifeScript's `all()`/`any()` quantifiers are the mechanism it would use,
but nothing wires a routine's own gate through them yet.

## Docs / Cleanup

- Keep Android README current.
- Add a short architecture doc: engines vs runner vs web vs Android vs
  transports.
- Document ntfy's intended role as fallback/signal bus.
- Document required local Android config: `ntfy.signalTopic`, panel URL/token,
  Firebase files.
- Remove or ignore local UI dumps/logcat artifacts.
- Consider moving long incident comments into CHANGELOG entries or regression
  tests when they stop being useful inline.

## Design Principles

- Widget equals status plus next move.
- Full app equals status plus reasons plus controls.
- Make the widget glanceable in under two seconds.
- Prefer symbolic/visual state where it is faster than reading: color, shape,
  icons, bars, rings, and small counters.
- Keep symbols deterministic and consistent; do not let the LLM invent status
  visuals.
- Do not make the widget a mini dashboard.
- Make the full app dense but calm.
- Avoid marketing-page composition; this is an operational tool.
- Any editor for routines/recurring items defaults to a basic view (simple
  fields); scripting/expressions are an opt-in advanced view, never the
  only way to change a plain number.

## Suggested Sequence

1. Add the deterministic attention-state model.
2. Add widget status badge and reason-aware layout.
3. Redesign the full app home around Today/Attention/Actions.
4. Add the direct action API.
5. Turn the Android launcher into a tiny Today app.
6. Build a routine editor (basic view: simple fields over
   `routine_store.load_routine`/`save_routine`; advanced view: the
   `constraints` script escape hatch, later cross-routine gate
   expressions) — not yet scoped, no UI/page/endpoint exists today.

## On-Device Migration (reduce Tailscale/home-server dependency)

Motivation (established 2026-07-30): the driving reason to keep moving logic
onto the phone isn't "Tailscale could be more reliable" — it's that Tailscale
is *not* trusted as reliably reachable, so the goal is to minimize how much
depends on reaching the home server at all, over any transport (Tailscale,
ntfy, FCM). This is NOT yet scoped as an implementation task — the below is
a research-backed plan (6 parallel research passes, 2026-07-30) to inform
that scoping conversation, not a commitment to build all of it.

### Already true today (confirmed by research, not new work)

The phone already bypasses the home server for several things, proving this
direction works in production: `PhoneWeather.kt`/`LocationReporter.kt` (NOAA
+ GPS, direct from device) and `YnabRefresh.kt` (direct bearer-token YNAB
category-balance reads, gated by `WidgetConfigStore`'s existing
`EncryptedSharedPreferences`, which already holds both `WEB_TOKEN` and
`YNAB_TOKEN` side by side). `NextTasksRefreshWorker` is a proven 15-min
WorkManager tick already doing exactly this kind of direct-from-phone,
self-gated background fetch for those three things.

### What's genuinely portable vs. what must stay server-side

**Must stay server-side: Canvas only.** `canvas_browser.py` needs a real
Playwright browser session for two stacked reasons: JHU disables
self-service Canvas API tokens (institutional policy, not a technical gap —
`CANVAS_TOKEN`/`canvas.py` is real, load-bearing code, just dormant for this
institution), and Canvas's session cookie is `httpOnly` (confirmed
unreadable from JS in an earlier attempt, per commit `89d81d9`). The login
step additionally sits behind Cloudflare Bot Fight Mode, which blocks any
CDP-attached browser (verified against vanilla Playwright, patchright, and
manual anti-detection args) — only a bare non-debugged `chrome.exe`
subprocess survives it (`4db0552`). None of this is Android-portable: JHU
SSO+Duo login can't be automated on any platform, and an embedded WebView is
plausibly *worse* against Cloudflare's detection, not better. `weather.py`/
`location.py` have zero browser-automation dependency and are already
migrated. No other hidden server-only constraints were found.

**FlowSavvy and YNAB are both clean, portable, stateless bearer-token REST
clients** — no session/cookie state, no OAuth dance, directly portable to
OkHttp/Retrofit. Specific porting hazards to get right, not blockers:
- `flowsavvy.py`'s retry policy is deliberately asymmetric (retries a bare
  `ConnectionError` and 429s, but NOT other HTTP errors, specifically to
  avoid double-submitting non-idempotent POST/PUT after an ambiguous
  response) — a naive Kotlin retry-everything port would recreate the
  duplicate-task bug this was built to prevent.
- FlowSavvy endpoint paths are self-admittedly *inferred*, not confirmed
  against official docs (`flowsavvy.py`'s own header comment) — verify
  before hardcoding into Kotlin.
- `ynab_engine.py`'s cover/assign logic is fully pure and portable, but the
  full pipeline also calls out to the Anthropic API mid-flow (novel-payee
  categorization, `runner.py:539-549`) — a second network dependency to
  design around (port it too, defer novel payees to a future server sync,
  or drop it for the on-device path).
- The YNAB client never uses YNAB's `server_knowledge` delta-sync — it does
  a rolling 120-day refetch every time. Nothing to "port" here since it
  isn't used today, but worth doing properly (real delta-sync) rather than
  copying the existing full-refetch behavior.
- FlowSavvy is write-capable (`complete_task`, `create_task`, `update_task`,
  `delete_item`), unlike YNAB's current read-mostly phone usage — porting
  `complete_task` means also re-designing `CompleteTaskAction.kt`'s
  optimistic-local-removal/`PendingRemovals` reconciliation for a direct
  on-device call instead of the current ntfy-fallback round trip. **This is
  the one that actually resolves the original ntfy question**: once task
  completion calls FlowSavvy directly, neither the home server nor ntfy
  needs to be reachable for it at all — there's no more "phone not on
  Tailscale" case to fall back for.

**Secrets**: adding `FLOWSAVVY_TOKEN` is mechanical, not new
infrastructure — `WidgetConfigStore.kt`/`WidgetKeys.kt`/`SettingsActivity.kt`
already prove out this exact pattern for `YNAB_TOKEN`, same file, same
`MasterKey`-backed `EncryptedSharedPreferences`, same backup-exclusion via
`data_extraction_rules.xml`, same wipe-and-retry corrupt-file defense. Real,
not theoretical, risk to weigh: a leaked `YNAB_TOKEN`/`FLOWSAVVY_TOKEN`
grants full read/write against a real financial/task account (YNAB has no
scoped/read-only token option), and a phone is a meaningfully larger
loss/theft/unlock-attack surface than one hardened home server — this is a
real increase in exposure, not just a storage-mechanism question. A
biometric gate before each API call was considered and rejected for
background-worker reads specifically (breaks unattended refresh); reserve
that idea for a future user-initiated write action instead.

**Core engine logic is mostly pure and cleanly portable to Kotlin — 6/7
ported (2026-07-30).** Portability order (easiest/highest-leverage first,
since gym/chore/social/meal/deadline all build on the shared primitive):
1. **Shipped**: `lifeops/routine.py` → `Routine.kt` — fully pure, no I/O,
   the shared due-check math everything else depends on. 11 tests.
2. **Shipped**: `lifeops/engines/ynab_engine.py` → `YnabEngine.kt` — fully
   pure and fully self-contained. 16 tests.
3. **Shipped**: `lifeops/engines/gym_engine.py`, `chore_engine.py`,
   `social_engine.py` → `GymEngine.kt`/`ChoreEngine.kt`/`SocialEngine.kt` —
   all pure `plan()` functions, I/O dropped. 19/12/7 tests respectively.
   (Confirmed during porting: `gym_engine.py` has zero dependency on
   `routine.py` — its due concept is inseparable from slot-picking, not a
   simple cadence check — so `GymEngine.kt` correctly has none on
   `Routine.kt` either, while chore/social genuinely do and use it.)
4. **Not yet ported**: `runner.py`'s meal-prep due-check
   (`routine_store.load_routine("meal")` + simple date math) is a small
   pure core buried inside a larger function that's otherwise genuinely
   I/O-entangled (FlowSavvy create/delete, ntfy polling for skip signals)
   — extract the core, redesign the rest.
5. **Shipped**: deadline-risk watchdog (`load_engine.py`'s `deadline_risk`/
   `_deadline_crunch` family) → `DeadlineRisk.kt` — fully pure, shared by
   two call sites so they can't disagree. 18 tests.
6. **Not yet ported, deliberately last**: `lifeops/attention.py` — fully
   pure compute, but it's the capstone: it only becomes useful once
   everything above is actually wired into a real on-device data-fetch +
   compute tick (see "actual bottleneck" below — none of that wiring
   exists yet, only the standalone decision functions), AND its
   `system.errors`/`age_mins` inputs assume a *server automation process*
   being monitored, which has no on-device meaning as-is. Needs a
   genuinely new concept ("when did I last successfully sync with
   FlowSavvy/YNAB/Canvas") designed, not a line-for-line port.

All 6 shipped ports (83 new JUnit tests total, one-to-one against the
existing Python test suites) live under
`android/app/src/main/kotlin/com/lifeops/briefing/` — `Routine.kt`,
`YnabEngine.kt`, `GymEngine.kt`, `ChoreEngine.kt`, `SocialEngine.kt`,
`DeadlineRisk.kt` (+ matching `*Test.kt` files). **None of them are wired
into any widget/worker/persistence path yet** — they're standalone, tested,
unused Kotlin equivalents of the Python decision logic, deliberately
scoped as pure ports before any integration work.

**The actual bottleneck isn't the engines — it's `gather.py`.** None of the
engines fetch their own data today; a separate server-side `gather.py`
assembles each engine's input dict from FlowSavvy/YNAB/Canvas/calendar/
weather once per run and hands it over. Porting an engine to Kotlin also
means porting (or newly writing) its `gather`-equivalent data assembly on
the phone — that's the real work, not the pure decision functions.

**Local persistence gap**: no Room/SQLite exists on Android today (grep
confirmed zero hits) — everything is `EncryptedSharedPreferences`/plain
`SharedPreferences`/Glance's `PreferencesGlanceStateDefinition`, all
key-value. Gym/chore/social history (needed for adherence math) and
`routine_store`'s override records would need a real local store (Room is
the natural fit, extending established Android conventions here, not a new
architecture). WorkManager (`NextTasksRefreshWorker`'s existing 15-min
periodic pattern) is the natural home for an on-device compute tick once
data + persistence exist.

### Not yet decided (needs a product decision, not more research)

- Does the home server keep running non-Canvas domains as a redundant
  backup/fallback, or fully retire them once the phone can compute
  independently?
- Do LLM calls (Anthropic API: briefing text, weekly digest, YNAB
  novel-payee categorization) move on-device too (another secret + direct
  API cost on the phone), or stay server-side (meaning some domains keep a
  server round-trip regardless)?
- Staged rollout order and how to run old+new in parallel without breaking
  daily use of a system that's actively relied on every day.
- What's left for ntfy/FCM once task completion and app→phone alerts both
  move fully on-device: likely nothing beyond signals tied to the
  still-server-side Canvas domain (e.g. "Canvas session needs re-login").
