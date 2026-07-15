# Changelog

Notable changes, newest first. Personal project, versioned simply (see
`VERSION` / `lifeops.__version__`) — dates and the reasoning behind each
change matter more here than semver strictness.

## [1.18.3] — 2026-07-15

### Changed
- **Briefing construction moved out of `runner.py`.** New
  `briefing_service.py` owns deterministic fact assembly and text
  formatting, leaving `run_briefing()` to orchestrate alerting, FCM push,
  and persistence.
- **FCM push ack state moved into a dedicated helper.** New
  `push_state.py` owns version hashing, ack persistence, and retry/skip
  behavior while `runner.py` keeps compatibility wrappers for existing
  signal handling and tests.
- **JSON state persistence now has one shared helper.** New
  `state_store.py` centralizes log-path resolution, corruption-tolerant
  loading, atomic JSON writes, and JSONL-style appends; notable events,
  deadline-risk tracking, LLM usage logging, and runner state writes now
  use it.

### Fixed
- **The full test suite no longer fails solely because Firebase Admin is not
  installed locally.** The FCM message-shape integration test now skips
  cleanly when `firebase_admin` is unavailable, while requirements still
  declare the dependency for real installs.

## [1.18.2] — 2026-07-15

### Added
- **Daily briefing text is now deterministic instead of LLM-written.**
  The morning briefing leads with `attention.compute()`'s headline, adds
  concrete deadline phrases from the same load-engine inputs that already
  drive risk alerts, names same-day events even when they cost $0, and
  includes upcoming notable events from calendar data without asking the
  model to rephrase facts it did not compute.
- **Notable events now have a local deterministic filter.** New
  `notable_events.py` records observed event occurrence dates, filters
  manually configured routine titles, treats close recurrences as routine,
  but still surfaces rare recurring events such as annual or infrequent
  commitments.
- **Deadline-risk phrasing is tracked by exact due datetime.** New
  `risk_tracking.py` prevents repeat narration of the same unresolved risk
  while still surfacing a same-title deadline again when its due date or due
  time changes.
- **LLM calls now have explicit timeout/retry settings and usage logging.**
  The remaining judgment calls append best-effort token usage records to
  `logs/llm_usage.jsonl`, while `LLM_TIMEOUT_SECONDS` and
  `LLM_MAX_RETRIES` bound Anthropic client behavior.

### Fixed
- **Discretionary spending facts now distinguish today from future plans.**
  `spend_input()` returns `today_budget` separately from net future spend so
  widget and briefing surfaces can show money earmarked for today's plans
  without making future commitments read as spendable cash.

## [1.18.1] — 2026-07-15

### Fixed
- **LifeOps Combo's 4x3 layout now uses the full block without showing
  empty events.** The tall combo renderer keeps the highest-priority cells
  in an explicit grid, reserves the larger events region only when notable
  events exist, and otherwise reclaims that space for the next priority
  cells so the widget does not show "Nothing scheduled" or stretch a single
  column across the whole card.
- **Combo priority and compacting now match the intended information
  hierarchy.** The default order is weather, gym, notable events,
  discretionary spending, then social; spending and social show their
  richer two-part summaries in the roomy 4x3 placement, while compact
  placements choose the most actionable single value.

## [1.18.0] — 2026-07-15

### Added
- **LifeOps Combo widget now follows the same widget design system as the
  standalone cards.** The combo preset uses the shared solo-card
  label/value/status/accent model for money, social, and coursework
  instead of duplicating one-off presentation logic, and its setup preview
  now consumes the same presentation data as the real Glance widget so the
  two surfaces do not drift.
- **Weather now follows your phone's actual location, and no longer
  depends on the LifeOps server being reachable at all.** Previously
  `weather.py` only ever ran once/day (inside the morning briefing) at a
  fixed `WEATHER_LAT`/`WEATHER_LON`, so a widget checked mid-afternoon
  showed a stale morning temperature, and any weather refresh required the
  PC to be on and reachable. Now:
  - The Android widget reports the phone's GPS location a few times a day
    (`LocationReporter.kt`, piggybacked on the existing periodic worker —
    a one-shot fix, not continuous tracking) to a new `POST /api/location`
    endpoint; `weather.py` prefers that over the static config when it's
    on file and fresh (`location.py`), falling back to the static
    `WEATHER_LAT`/`WEATHER_LON` otherwise.
  - `GET /api/next-tasks` (already polled every ~15 min by the widget, the
    same pull as the gym ring) now also returns live weather, so the
    temperature refreshes far more often than once/day even when
    everything still goes through the server.
  - The phone can now fetch weather directly from NOAA/NWS itself
    (`PhoneWeather.kt`), reusing the same public, no-API-key
    `api.weather.gov` endpoints and grid-cell caching `weather.py` uses —
    with zero dependency on the LifeOps server. This is the primary
    source; the server-provided values above are progressively staler
    fallbacks for when the phone hasn't fetched yet (e.g. no location
    permission granted).

## [1.17.1] — 2026-07-14

### Fixed
- High-effort code review of the v1.17.0 batch (cross-confirmed by 5
  independent finder passes) surfaced and fixed: the social lock-in
  mechanism (converting a completed "Plan X" task into a real scheduled
  hangout) had been deleted with nothing replacing it -- completing
  "Plan Partner time"/"Plan Friends" silently did nothing, forever, and
  the orphaned tentative placeholder kept satisfying the weekly cadence
  check so it was never re-proposed either. Restored. Also fixed:
  `PROPOSE_AHEAD_DAYS <= 3` (user-editable via Settings) silently emptied
  the weekly hangout-proposal candidate window with no error; the
  wind-down task pruning pass hardcoded "Tue" independently of
  `gym_engine`'s own configurable exempt-weekday rule; a redundant
  FlowSavvy API call in `social_input` that already had a per-tick cache
  available; a missing CSS accent for the social summary card's "ok"
  tone; and (Android) `SoloMoneyTile`/`SocialFocusTile` were ~50 lines of
  hand-copied identical composables, consolidated into one shared
  `SoloStatCard`.

## [1.17.0] — 2026-07-14

### Changed
- **Social tracking now separates tentative scheduling holds from real
  hangout plans.** LifeOps can still create `Friends (proposed)` /
  `Plan Friends`-style holds so future social time is accounted for in
  scheduling, but those placeholders no longer make the widget say a
  hangout is actually planned. Old generated `Locked in (LifeOps)` social
  tasks are also ignored for widget `next` dates, so only real/manual
  commitments, configured social calendar events, friend-name matches, or
  `type: friends` notes count as actual upcoming social time.
- **Social proposals now respect the weekly cadence.** The engine only
  proposes a new partner/friend hold when that cadence is due and no hold
  or actual plan already exists; candidate proposal dates start in the next
  weekly cycle rather than inside the current week.
- **Social 1x1 widget now follows the Money widget visual pattern.** The
  standalone Social preset picks the most actionable cadence and renders it
  as a dark solo card with a compact label (`FRIENDS` / `PARTNER`), large
  value (`8d` / `2d`), and bottom status bar (`AGO` / `NEXT`) instead of a
  compressed two-row emoji chip layout. The full widget still shows both
  partner and friends when there is room.
- **`type:` note overrides now support multiple comma-separated types.**
  Notes such as `type: friends, concerts` or `type: concerts, friends`
  are parsed into a full type list for social detection while preserving
  the first normalized type for existing spend classification.

## [1.16.1] — 2026-07-14

### Fixed
- **"Restart server" button appeared broken.** `_restart_server()`'s
  detached helper waited only 800ms before `schtasks /end` killed the
  process -- too tight over a real network hop. Confirmed live: the
  caller's browser was still sitting on the bare `POST /system/restart`
  URL when the process died mid-response (before its 303 redirect could
  arrive), so a reload/retry of that URL hit it as a `GET` and 405'd --
  reading as "the button doesn't work" when the restart had actually
  already fired. Bumped the delay to 3000ms so the redirect reliably
  reaches the browser first.

## [1.16.0] — 2026-07-13

### Changed
- **Weather rendering consolidated into one composable.** `WeatherSection`
  and `CompactWeatherTile` were two near-identical hand-copied layouts with
  independently tuned font-size constants — exactly the pattern
  [Google's own Glance docs](https://developer.android.com/develop/ui/compose/glance/build-ui)
  warn against, and the direct cause of several bugs this session where a
  fix landed in one copy and was forgotten in the other (hi/lo alignment,
  `maxLines`, `textAlign`, condition-text sizing). Replaced with a single
  `WeatherCard` that reads `LocalSize.current` directly and picks
  `BASE_WEATHER_*` (roomy) vs `COMPACT_WEATHER_*` (fits a true 2x1 without
  clipping) inline, matching the idiomatic pattern from the docs. One
  source of truth for the layout structure; only the size constants differ.
- **All widget presets now declare `android:widgetFeatures="reconfigurable"`.**
  Without it, some launchers (confirmed on Samsung One UI) never show a
  settings/gear option on long-press after initial placement — the only way
  to reopen a placed widget's config screen was removing and re-adding it.

## [1.15.0] — 2026-07-13

### Changed
- **Weather and Social single-stat widgets redesigned for a true 2x1
  footprint.** Both previously defaulted wider (180dp/3 cols) or taller
  (150dp/2 rows) than every other single-stat preset. Weather now renders a
  `CompactWeatherTile` at 2x1 — temp+unit, hi/lo, icon, and a shrunk
  condition label, all sized to fit without clipping — instead of the full
  weather-app-style card, which still renders once the widget's resized
  bigger. Social's partner/friends chips now stack vertically instead of
  side by side for this preset, with tighter font sizes/padding, so both
  fit the same 110dp x 56dp floor as Gym/Money/Coursework/Sleep.
- **A single-stat preset's sole content now fills its actual placed space.**
  Previously every compact tile (StatTile, CompactWeatherTile, the stacked
  Social chips) sized itself to wrap its own content and left dead space
  around it whenever the real widget was bigger than that content —
  most visible as a tiny chip floating in a large empty box. When a
  widget's badge is hidden and exactly one section is visible (the
  single-stat case), that section now gets `fillMaxSize()` instead of
  wrapping its own size.

### Fixed
- **The attention badge ("FUCKED"/"WATCH"/etc.) rendered unconditionally**,
  ignoring the "Severity dots" toggle — a single-stat preset that hides
  every section (including severity dots) still showed the badge, with no
  dots to explain what it meant. Now gated behind the same toggle.
- **Widget setup preview hid toggled-on sections** based on the widget's
  transient placed size (Samsung's launcher drops new widgets at a small
  default footprint) instead of the user's actual Sections toggles.
- **Weather card's high/low arrows didn't align**, and the condition text
  (in the setup preview) had an unwanted gap above it from a missing
  explicit `lineHeight`. Both fixed; arrow alignment settled on
  Alignment.Top after trying Bottom, closest available approximation of a
  superscript unit given neither Compose nor Glance's TextStyle support
  baselineShift.

## [1.14.0] — 2026-07-13

### Fixed
- **Widget setup screen's Save button was unreachable, for real this time.**
  Two earlier attempts this same day both relied on Compose's
  `navigationBarsPadding()`/`WindowInsets` to clear the 3-button nav bar and
  neither worked on a real Samsung device — insets weren't dispatching
  correctly to this Activity's window even with edge-to-edge enabled. Fixed
  by reading the nav bar's real height straight from the platform's
  `navigation_bar_height` dimen resource and applying it as literal padding,
  bypassing Compose's insets APIs entirely.
- **Widget setup preview silently hid toggled-on sections.** It gated
  content by the widget's *current placed size*, but Samsung's launcher
  drops a freshly-added widget at a small default footprint before the user
  ever touches it — so toggling "Briefing text" or "Weather" on did nothing
  visible, contradicting the user's own choice. The preview now renders
  everything the Sections list says is on, full stop; the size-bucket
  gating stays real-widget-only.
- **Weather card's high/low arrows didn't line up.** `°F↑85°` / `↓67°` put
  the up-arrow on the same line as `°F`, offset from the down-arrow on the
  line below by `°F`'s width. `°F` now sits with the temperature; high and
  low get their own column so both arrows are flush left against each
  other.
- **Weather card's low-temp line floated with a large unwanted gap above
  it** in the setup preview specifically — overriding `fontSize` alone
  without `lineHeight` kept Material3's much taller inherited line height.
  Both lines now set `lineHeight` explicitly.
- **Social widget picker showed a different footprint than Weather's** (2
  columns vs. 3) despite both declaring the same `targetCellWidth`. Samsung's
  launcher recomputes the picker footprint from `minWidth`/`minHeight`
  rather than honoring `targetCellWidth`/`targetCellHeight` — bumped
  Social's `minWidth` to match Weather's 180dp exactly.
- **`WEATHER_LAT`/`WEATHER_LON` were never set** in the private secrets repo,
  so every weather figure (current temp included, not just high/low) was
  silently absent on any machine syncing from it. Added Anaheim Hills
  coordinates.

## [1.13.0] — 2026-07-13

### Added
- **Social widget now shows "days until" alongside "days since."** `💜 2d`
  alone didn't say whether that 2 days was heading toward radio silence or
  something already booked — `social_input` now also reports the soonest
  scheduled/proposed date for a partner or friend hangout, and the widget
  (and its config-screen preview) render it as `💜 2d→4d` when a plan
  exists, `💜 2d` (unchanged) when nothing's on the calendar yet.

### Fixed
- **Widget setup screen's Save button was hidden behind the nav bar.**
  Scaffold's `bottomBar` isn't padded above the system nav bar/gesture bar
  automatically — targetSdk 35+ enforces edge-to-edge by default, so the
  Save button rendered directly underneath the 3-button nav bar's Home
  button, unreachable on a real device. Fixed with `navigationBarsPadding()`.
- **Widget visual consistency pass.** The Social section's chips (`SocialStat`)
  had their own near-identical composable with an inverted font-size
  hierarchy (emoji bigger than the number) versus every other stat tile —
  deleted in favor of reusing `StatTile` directly. The Weather card's
  background color (`#3B4A78`) was an arbitrary blue lifted from the
  original reference mockup and didn't tie into the app's actual palette —
  changed to `#2F4D80`, the same accent blue used by the web panel and the
  widget config screen.

## [1.12.0] — 2026-07-13

### Added
- **"Upcoming events" card on the panel home page.** Shows what's coming up
  in the next few weeks (label, days-until, cost) — the exact event list
  `run_cashflow` was already sweeping and persisting to `logs/cashflow.json`
  for the discretionary projection, just never rendered anywhere on its own.

### Changed
- **Settings page config section redesigned.** The 14 raw `.env` variable
  names, each with its own text field and its own "save" button, are now 5
  logical groups (Partner, Friends, Scheduling, Money, Calendars) with
  human-readable labels and a one-line explanation per field, saved all at
  once via a single "Save changes" button (`/config` now takes a bulk POST
  and only writes keys that actually changed). "Restart server" moved out
  of the save form into its own standalone one-click button — it never
  needed a confirm() dialog or to sit inside the same flow as editing a
  setting.

### Fixed
- A classic Jinja gotcha while building the grouped config UI: a dict key
  named `items` silently resolved to the dict's own `.items()` method
  instead of the actual list (`group.items` → bound method, not the data) —
  renamed to `fields`.

## [1.11.1] — 2026-07-13

### Fixed
- `attention.compute()`'s `reasons` list was capped at a flat top-6 sorted
  purely by (severity, domain priority) -- a pile of overdue coursework
  (all "fucked", the worst severity) could fill the entire cap by itself,
  silently dropping every other domain's reason regardless of its own
  severity. Confirmed live: a real -$125 discretionary balance produced
  zero "money" reason -- not cosmetic, since the widget's severity dots
  and money-tile background color both source their per-domain severity
  from this exact list, so they'd have shown green/ok despite the
  negative balance. Now guarantees each domain that produced at least one
  reason keeps its single worst one before filling remaining slots with
  the next-worst reasons overall.

## [1.11.0] — 2026-07-13

### Added
- **Single-stat widget presets.** Six more pickable entries in the Android
  widget tray beyond the full "LifeOps Briefing" widget — Gym, Money,
  Coursework, Weather, Sleep, and Social — each its own
  `AppWidgetProviderInfo`/receiver defaulting to showing just that one
  stat, but sharing the exact same `BriefingWidget`/`BriefingContent`
  rendering code. A `WidgetPresets` registry is the single source of truth
  mapping each receiver to its default `WidgetDisplayConfig`, consulted by
  the widget itself, the configure screen, and the reference-counted
  next-tasks work scheduling — adding a future preset means one new entry
  there instead of four independently-drifting lists.
- **Weather** (NOAA/NWS `api.weather.gov`, free, no API key). Current temp,
  today's high/low, and condition, via a new `lifeops/weather.py` client
  (grid-location cached to disk after first lookup). `WEATHER_LAT`/
  `WEATHER_LON`/`WEATHER_USER_AGENT` in `.env`; blank = feature off. Its own
  widget card (temp+hi/lo on the left, condition glyph+label on the right).
- **Sleep** tile — last night's real duration from Health Connect watch
  data (`gather.sleep_minutes_last_night`), not the unreliable phone-sensor
  heuristic.
- **Social** tile — days since partner/friends were actually seen, reusing
  `social_input`'s existing tracking so it can never disagree with the
  hangout-nagging engine about what counts as "seen."
- **Live preview in the widget configure screen.** Toggling sections,
  reordering, or dragging the scale slider now updates a rendered preview
  in place, using real synced data when available (sample data otherwise).
  Resolves the instance's actual placed size
  (`GlanceAppWidgetManager.getAppWidgetSizes`, falling back to the
  provider's declared minimums for a first-time placement) and gates
  content through the widget's own `bucketFor`/`TILE_SECTIONS`/
  `groupSectionsForRendering`/`TEXT_GATED_SECTIONS` — not a re-derived
  approximation — so it can't silently show content a given size will
  never actually render.
- **Discretionary balance now nets out known upcoming spend.**
  `gather.spend_input` sweeps calendar events (including ones on calendars
  never mapped in `EVENT_CALS`, via `"type: friends"` / `"cost: 30"` note
  overrides) and returns `net_fun_money` — raw balance minus every swept
  event's cost — which the briefing/widget now shows instead of the raw
  YNAB balance.

### Fixed
- Single-stat preset widgets couldn't actually be resized down to their
  own declared minimum size — `BriefingContent`'s SMALL bucket used to
  hard-stop right after the attention badge, discarding every tile.
  Compact tile sections (gym/money/coursework/sleep) now render at SMALL
  too; the four presets' XML minimums dropped from an artificial 150dp to
  Android's real 2×1 floor (56dp).
- The "as of ..." freshness line could get silently clipped off a resized
  widget as its task count grew to fill the space — `maxTasksForHeight`
  now reserves room for it (only when it'll actually render).
- Weather's high/low used to grab "whichever daytime/nighttime period
  comes first," which in the evening returned *tomorrow's* high mislabeled
  as today's. Now matched against the real calendar date.
- A malformed sleep-history record could throw uncaught and abort the
  *entire* daily briefing, including already-gathered gym/money/weather
  facts. Wrapped like every other external pull in `run_briefing`.
- `spend_input`'s cross-calendar sweep was refetched independently by
  `run_spend`, `run_briefing`, and `run_cashflow` every day — cached
  per-process instead.
- Two rounds of review (8-angle each) on this batch found and fixed: an
  id-less event double-counting bug in the note-override sweep; an
  unvalidated negative `cost:` override silently inflating the free-to-
  spend figure; the widget preview grouping every tile section into one
  row regardless of actual position (now uses the real contiguous-run
  grouping); duplicated formatting logic between the preview and the real
  widget (now shared); and `BriefingState`'s JSON parsing settling on one
  consistent null-safe idiom instead of two.

## [1.10.0] — 2026-07-13

### Added
- **Per-widget-instance display customization.** Full control over which of
  7 sections show (severity dots, gym ring, money tile, coursework tile,
  briefing text, today's events, up-next tasks), their display order, a
  font/icon scale (0.85-1.3x), and an "Up next" task-count override --
  configured per placed widget instance via Android's standard AppWidget
  configure flow (`android:configure` -> `WidgetConfigActivity`, launched on
  add/edit). Gym ring/money/coursework tiles stay merged into one row when
  left adjacent in the default order, and split apart automatically the
  moment one is reordered away from the others -- no separate "grouped vs.
  independent" toggle needed. Severity dots stay inline on the attention
  badge's row only in their default position; moved elsewhere, they detach
  into their own standalone row. Persisted in the same per-instance Glance
  Preferences DataStore as the briefing/next-tasks state, so it's cleaned
  up automatically when a widget instance is removed.

### Fixed
- The widget couldn't be resized at all -- `maxResizeWidth`/`maxResizeHeight`
  were never set in `briefing_widget_info.xml`, so the launcher computed a
  max resize bound of `(0,0)` (confirmed via logcat). Added explicit
  min/max resize bounds.
- Code review on the display-customization batch surfaced and fixed 5
  issues: the default section order silently reversed the briefing-
  paragraph/tiles order for every never-configured widget; a "No briefing
  yet" placeholder's early return also suppressed today's events/up-next
  tasks (which were always meant to be independent of briefing text);
  severity-dots-inline detection used the wrong (unfiltered) section
  order; the gym-ring fallback never received the new font/icon scale;
  and a corrupted persisted scale value had no clamping to the config
  screen's slider range.

## [1.9.0] — 2026-07-13

### Added
- **Hardened optimistic task completion.** `PendingRemovals` already
  masked an optimistically-completed task from fresh next-tasks snapshots
  for a fixed 3-min TTL, but never actively restored it if completion
  genuinely failed and no fresh snapshot happened to arrive -- it just
  stopped masking, leaving the task hidden indefinitely. Pending records
  now also store the tap time plus the task's title/start, enabling three
  outcomes: a fresh snapshot missing the id clears the pending record
  immediately (confirmed complete); a fresh snapshot that still has it,
  once past a ~3-min grace window (margin over the ~2-min ntfy→ingest
  cycle), also clears it and shows the task normally (confirmed failed,
  without waiting the full timeout or flickering on every snapshot that
  lands mid-flight); and `NextTasksRefreshWorker`'s existing 15-min
  periodic cadence now also sweeps for entries past a 10-min hard timeout
  and restores them from their stored title/start, independent of any
  network call succeeding (stuck/offline).

## [1.8.0] — 2026-07-13

### Added
- **Widget glyph redesign catch-up.** `attention.compute()` already
  produces a per-domain (coursework/system/money/gym) reasons list with a
  deterministic severity per domain, but only the single overall headline
  reached the widget. Added a per-domain severity-dot row under the
  attention badge (one glyph per domain, colored by that domain's worst
  open severity, green when nothing's open) at every widget size, and
  moved money/coursework from plain text to icon+monospace-number tiles
  matching the gym ring's icon-first visual language -- a step toward an
  instrument-panel that reads at a glance instead of like a text message.

## [1.7.0] — 2026-07-13

### Added
- **Gym ring widget indicator.** The single N/target ratio tried to answer
  two questions at once -- "how healthy is recent adherence" and "do I
  need to go today" -- and they don't move together. Split into two
  decoupled channels: `fill` is the pure trailing-7-day adherence ratio
  (only grows via real logged sessions, never inflated by completing
  today's session), `color` is a same-day action signal (red = zero
  sessions in 7d, yellow = still need to go today, green = today's
  session done or nothing scheduled while at/above target).
  `gather.gym_ring_now()` is now shared by the daily briefing, the
  ~10-min `next_tasks` FCM push, and the direct task-completion API, so
  a checkbox tap on the tailnet gets an instantly fresh ring. Rendered
  on Android as a bitmap-drawn ring (Glance has no native arc primitive)
  with the gym emoji layered on top, falling back to the old plain bar
  if a ring hasn't loaded yet.

## [1.6.0] — 2026-07-13

### Added
- **FCM push delivery confirmation.** `messaging.send()` succeeding only
  confirms Firebase accepted a message for delivery, not that the phone
  ever received it -- data messages can be silently dropped (Doze, a
  force-stopped app, etc.) with zero signal back to the server. Both push
  types (briefing, next-tasks) now get a real receipt confirmation:
  - Every push carries a short content-hash `version` in its FCM data.
  - Android's persist workers echo it back as an `ack:<type>:<version>`
    ntfy signal once the payload is successfully persisted.
  - `runner.py`'s `ingest()` handles that signal; a push is skipped only
    when the content is unchanged **and** the previous push was acked --
    an unacked push keeps retrying every tick even with no content
    change, since "unacked" is exactly the signal a prior attempt may
    not have landed.

### Fixed
- `RegisterTokenWorker` always reported success to WorkManager even when
  FCM token registration failed on both its direct-call and ntfy-relay
  paths (e.g. the phone fully offline, not just off-tailnet) -- silently
  dropping the registration for good instead of retrying. Now returns
  `Result.retry()` with explicit backoff, matching the rest of the
  codebase's WorkManager conventions.
- `push_next_tasks()` sent a fresh FCM message every tick even when
  nothing had changed since the last (now-acked) push; skips the send in
  that case.
- `_push_with_ack` marked every push "unacked" even when nothing was
  actually sent (e.g. no FCM token registered yet on a fresh install),
  so a not-yet-configured device would retry forever. Send functions now
  report whether a send was actually attempted; nothing-to-send is
  treated as trivially acked.
- `_mark_push_acked` could raise on a corrupt/non-dict ack state file --
  since it runs inside `ingest()`'s per-message loop, that would have
  dropped the rest of that poll batch's already-processed signals too.
- **Retroactive review of the v1.5.0 batch below** (it shipped without
  one, unlike the batches before and after it): `fcm.register_token()`
  wrote to a fixed temp filename with no fsync, and is called from two
  separate OS processes (the web server and the runner subprocess via
  the ntfy relay) -- a genuine concurrent-write collision risk, not just
  a single-writer durability nit. Now uses a unique temp file + fsync,
  matching the rest of the codebase's durable writes. Also: 
  `push_next_tasks()` ran before domain dispatch/`recalculate()` in
  `_run()`, so a pushed snapshot could miss a change the same tick's
  domains just made -- moved to run after.
- `_push_with_ack` wrote `{"acked": true}` whenever a send was skipped
  because there was nothing to send yet (no FCM token registered) --
  correct in the moment, but it fabricated a permanent "acked" sentinel
  for that content's hash. Once a token was later registered, the same
  unchanged snapshot hashed to the same version and got skipped forever
  by the unchanged-and-acked check, so a fresh install's first real
  content never actually delivered even after configuration finished.
  Fixed: write no state at all when nothing was sent, so an
  unconfigured device just cheaply retries until a real send succeeds.
- Four Android call sites (`NextTasksRefreshWorker`, `CompleteTaskAction`,
  `BriefingFcmService`) appended raw, unencoded tokens and task IDs into
  request URLs -- inconsistent with `OpenPanelAction`'s existing
  `Uri.encode`, and a real bug since `WEB_TOKEN` is free-text editable
  via the panel's own Settings page (`&`, `#`, `+`, `%`, spaces all
  realistically reachable). Now routed through a shared
  `authenticatedUrl()` helper that encodes the query token, plus
  `Uri.encode()` on the task-id path segment.

## [1.5.0] — 2026-07-13

### Added
- **Tailscale-independent ambient operation.** Previously, the widget's
  day-to-day freshness depended on being reachable over the tailnet:
  `NextTasksRefreshWorker`'s 15-min periodic pull was the only way the task
  list stayed current, and FCM token (re-)registration was a direct-API-only
  call. Both now follow the same push/relay pattern already proven by the
  briefing:
  - `runner.py` pushes a fresh next-tasks + today's-events snapshot via FCM
    on every tick (~10 min) and daily run (not the ~2-min signal tier, to
    avoid 5x the sends for no real freshness gain). `fcm.py`'s messages now
    carry a `type` field (`briefing` / `next_tasks`) so
    `BriefingFcmService` can dispatch to the right persist worker
    (new `NextTasksPersistWorker`, mirrors `BriefingPersistWorker`'s
    guaranteed-execution shape).
  - FCM token registration now falls back to a `token:<value>` ntfy signal
    (handled by `runner.py`'s `ingest()`, shared validation/persist via new
    `fcm.register_token()`) when the direct `/api/register-fcm-token` call
    fails -- same hybrid shape `CompleteTaskAction` already uses for task
    completion.
  - The periodic pull stays in place, unchanged, as a self-heal fallback for
    the rare dropped push -- it's just no longer the only path, and it's now
    the last remaining Tailscale-dependent piece of the widget's ambient
    (non-panel) operation. Opening the full panel in a browser is still
    Tailscale-gated, deliberately -- that's a use case where asking for a
    live connection is reasonable.

## [1.4.0] — 2026-07-13

### Added
- **Deterministic `attention_state`** (`lifeops/attention.py`) — a pure,
  side-effect-free `compute(facts, system=None)` that turns coursework/money/
  gym facts plus optional runner/panel health into one ordered `ok < watch <
  risk < fucked` verdict with symbol/label/headline/reasons. The LLM briefing
  now defers to this object rather than deciding severity itself. Wired into
  `run_briefing` (persisted into `logs/briefing.json`), the panel home page
  (`#attention` card, escalates independently using live panel health), and
  the Android widget (colored status line).
- **Notification-transport facade** (`lifeops/notify.py`) — domains ask for
  product-level messages (`alert`, `push_briefing`) instead of knowing
  whether ntfy or FCM carries them; `runner.py`'s alert call sites now go
  through this instead of `ntfy`/`fcm` directly.
- **Actions API** — `POST /api/tasks/{id}/complete`, `/api/gym/log`,
  `/api/gym/skip`, `/api/schedule/block-day`, `/api/domains/{name}/run`.
  Direct JSON mutation endpoints mirroring the existing panel form routes,
  for the widget/quick-actions to call without a page load; each returns
  fresh deterministic attention state where practical.
- **Widget visual language**: a compact proportional gym-progress bar
  (teal at/above target, amber short of it), a stale-data warning glyph on
  the "as of" line past 2h, and **responsive size-bucketed layout**
  (`SizeMode.Responsive`/`LocalSize`) — the widget now renders progressively
  less as it's resized down (status badge + headline only at the smallest
  size) instead of always rendering its full layout regardless of placed
  size.
- Widget task-completion is now a hybrid: tries the direct
  `/api/tasks/{id}/complete` call first (fast, needs the tailnet), falls
  back to the ntfy `complete:<id>` signal (works from anywhere, ~2 min via
  `runner.py`'s `ingest()` cycle) if that fails for any reason.

### Fixed
- `_current_attention()` (panel + Actions API) was coercing a missing
  `logs/last_run.json` into `{}` instead of passing `None` through, which
  made `attention.compute` misread "no system data yet" as "system data
  present but stale" -- a fresh install would show a false `risk: LifeOps
  data is stale` reading.
- `runner.py`'s `ingest()` ntfy-message and FlowSavvy-completion dedup sets
  were truncated via `list(a_set)[-1000:]` -- Python sets have no guaranteed
  order, so this didn't reliably keep the most-recently-handled entries past
  the 1000-entry cap, risking a real redelivery going unrecognized and
  double-completing a task.
- Android `CompleteTaskAction`'s direct-completion path only caught
  `IOException`; a malformed-but-reachable response threw an uncaught
  `JSONException` instead of falling back to the ntfy signal.
- Android `BriefingFcmService.registerToken` built its POST body via raw
  string interpolation with no escaping; restored `JSONObject`-based
  construction.
- Removed `BriefingSyncWorker.kt` (dead code, superseded by
  `BriefingPersistWorker` + `RegisterTokenWorker` + the extended
  `NextTasksRefreshWorker`).

## [1.3.0] — 2026-07-10

### Added
- **Android home-screen widget** for the daily briefing + next-tasks, backed
  by a new `android/` Glance app. The briefing is push-delivered via Firebase
  Cloud Messaging (an earlier ntfy-broadcast design couldn't reliably wake a
  stopped app), routed through a `BriefingSyncWorker` (WorkManager) so
  delivery survives process death and gets retry/backoff instead of running
  in a bare coroutine — the same worker also does an immediate pull on
  first setup so a freshly-placed widget populates right away rather than
  waiting for the next push. The next-tasks list is a real 15-minute
  periodic pull (`NextTasksRefreshWorker`), and tapping a task's checkbox
  completes it straight from the widget via `/api/tasks/{id}/complete`,
  which completes it in FlowSavvy and returns the fresh list in the same
  round trip. New backend surface: `/api/briefing`, `/api/next-tasks`,
  `/api/tasks/{id}/complete`, `/api/register-fcm-token`, and the FCM send
  path in `run_briefing`.
- Android unit test coverage (Robolectric/Glance) for the widget's markdown
  rendering.

### Fixed
- `/api/*` requests authenticated via a `?token=` query param (as the widget
  does, with no cookie jar) now return JSON directly instead of being
  redirected into the browser cookie-auth flow.

## [1.2.0] — 2026-07-09

### Added
- **Generalized deadline-risk watchdog** (`deadlines` domain) — the second
  research feature (Motion's "At Risk / won't fit"). `load_engine.deadline_risk`
  walks ALL deadline-bearing tasks (not just coursework, via
  `gather.deadline_input`) in due-date order and flags the earliest deadline
  where cumulative remaining work exceeds the hours realistically free before it
  (days × `DAILY_CAPACITY_H`). Pushes at most one crunch alert per day; also
  feeds the daily briefing. Uses *estimated* durations FlowSavvy already has —
  no manual time-tracking required.
- **Forward cash-flow projection** (`cashflow` domain) — Monarch-style running
  discretionary-balance curve. **Panel-only, no notifications by design**:
  `run_cashflow` projects the next 4 weeks from the current discretionary
  balance minus known upcoming paid social events and persists it to
  `logs/cashflow.json`; the panel renders a weekly bar (`#cashflow`) that turns
  red if the balance goes negative, with the outings that cause it. (Deliberately
  dropped the estimate-calibration and chore-bundling ideas from the research —
  the former needs actual durations you'd have to hand-enter; the latter wasn't
  worth it.)
- **Daily morning briefing** (`briefing` domain) — the daily counterpart to the
  weekly Sunday digest, and the first feature from a competitor-research pass
  (inspired by Motion's deadline-risk surfacing + Sunsama's morning plan). Once
  a day it assembles facts the engines *already* compute — at-risk coursework +
  today's due items + total load in the next 7 days (via `load_engine`), gym
  sessions this week vs. target, discretionary balance + nearest paid social
  events (via `spend_input`) — and asks the LLM for one short, dry heads-up:
  what's genuinely at risk, today's shape, one suggestion. Delivered as a
  once/day ntfy (deep-links to `#briefing`) and a "Today's briefing" panel card
  with the raw numbers underneath. Wired into the `daily` tier (`_alert_once`
  dedups so only the morning run sends) and the panel domain toggles. New
  `llm.daily_briefing()`; persisted to `logs/briefing.json`. The point: a
  looming deadline or a dwindling budget surfaces proactively instead of only
  when you go looking.
- **Backfill a gym session by dropping it on the calendar.** Add a gym
  event/task (title starting "Gym") on a past slot — e.g. from your phone —
  and the next `run_gym` tick logs it as attendance (`history` `gym`, source
  `manual`), so you don't have to open the control panel. Detection is
  **hybrid**: an item you created is recognized because it lacks the
  `"Auto-scheduled by LifeOps"` marker the engine stamps on its own blocks, so
  a *past* one counts automatically; adding `completed`/`went`/`✅` to the
  title or notes forces it regardless of date (log a session you'll do later
  today, or a future-dated slot you actually attended). A future gym item with
  no keyword is treated as a *plan* and left alone.
  - Runs **before** the stale-block cleanup, which is the whole trick: without
    this, a past "Gym" item you added would be deleted and recorded as a
    *miss* (the exact opposite of "I went"). Handled items are dropped from the
    cleanup pass.
  - Logged backfills are tracked in `gym_state.json` (`logged_backfills`) and
    kept ~2 weeks as a visible receipt (tasks are renamed `✅ … (logged)` so you
    can see it registered), then auto-pruned (`_GYM_BACKFILL_TTL_DAYS`).
    Idempotent — an id already logged is never re-counted, and a day already in
    history isn't double-logged.
- **"Recent activity" feed + one-tap undo in the control panel.** A new
  `lifeops/actions.py` audit log (`logs/actions.jsonl`) records every mutation
  LifeOps makes to your calendar — distinct from `history.jsonl` (completions)
  and `runs.jsonl` (per-run summaries). Domains create tasks through a
  `_logged_create` wrapper, so canvas/gym/social/meal/chore creations (and stale
  gym-block deletions) all show up as "canvas: created course task · M08
  Reading" etc., newest first, in an `#activity` card. Reversible ones (a
  created task) get an **undo** button (`POST /action/undo` → deletes the task,
  marks it undone so the feed greys it out and won't double-undo). Directly
  answers "what did LifeOps just do, and can I take it back" without digging
  through FlowSavvy. Remaining domains adopt it with a one-line `actions.log`.
- **Canvas sync flood guard.** A healthy incremental sync creates a handful of
  tasks; the two duplicate-flood incidents (2026-07-03/06) each tried to create
  ~59 in one run after the sync state was lost. `_canvas_sync` now HOLDS when a
  run would create more than `_CANVAS_FLOOD_MAX` (8) tasks — it writes the
  intended creates to `logs/canvas_pending.json`, fires a high-priority ntfy,
  and skips both creation and the state save (so nothing is marked synced and an
  unapproved re-run re-triggers the guard). The control panel shows a "Canvas
  sync held" card (`#canvas`) listing what would be created, with **approve**
  (`POST /canvas/approve-sync` sets a one-shot `flood_ack` and re-runs canvas
  through the normal path — no replay logic) and **dismiss**
  (`POST /canvas/dismiss-pending`). Turns the state-loss re-sync from
  "warn, then flood" into "hold, then one tap."
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
- **Split the control panel into separate pages.** The single-page dashboard
  had grown to ~10 stacked cards; replaced `index.html` with a shared
  `base.html` layout (nav bar, styles, PWA/status-poll JS) plus six focused
  pages — Home, Gym, Schedule, Recurring, History, Settings. Every mutating
  POST handler now redirects back to the page it belongs to instead of
  always bouncing to `/`. ntfy notification deep-links updated to match —
  `panel_url()` now takes a page path optionally followed by `#anchor` (e.g.
  `settings#accounts`), instead of assuming everything lives on one page
  with a bare anchor.
- **Generalized the gym calendar into a Gym / partner / friends activity
  calendar** on the Home page. Tabs (client-side, no reload) switch between
  three history-backed grids; tapping a partner/friends day cell logs it via
  a new `POST /log/cycle-date` (`action=partner|friends`) — no skip/blocked
  states, those are gym-scheduling concepts that don't apply to just logging
  a hangout.
- **Friend-hangout tracking beyond the literal "Friends" task title.** New
  `FRIEND_NAMES` config (comma-separated names, e.g. `Jarod,Alex`) — a task
  titled with one of those names, or tagged "friend"/"friends" anywhere in
  its title/notes, now counts as a friend hangout for history logging
  (`runner._classify`) *and* for `gather.social_input`'s "already have a
  plan" check, so a hangout scheduled under someone's actual name doesn't go
  unrecognized and get double-proposed.
- **"Wind down" reminders now get pruned like stale gym blocks.** A
  window-of-opportunity task ("go to bed early — gym at 5am") that isn't
  done in its time window has genuinely lost its chance, unlike a normal
  to-do; `run_gym`'s stale-cleanup pass now also strikes elapsed "Wind down"
  items instead of leaving them to sit as permanently-overdue tasks.
- **Per-entry "undo" on the History page.** Each row gets a button that
  strikes just that one log record (`history.remove_at`, keyed by file
  position plus a `ts`/`action` fingerprint so a stale page load can't
  delete the wrong entry after another tab/tick has logged something in
  between). Most actions are pure completion records — removing the log
  line is the full undo — except entries whose `meta.creates_task=True`
  (e.g. Canvas sync creations), where undo also deletes the FlowSavvy task
  the log entry created.

### Fixed
- **The other 6 runner.py state files (`ingest`, `alert_state`, `chore`,
  `catchup`, `social`, `meal`) had the same non-atomic write as
  `canvas_state.json` below.** Same crash-mid-write corruption risk, just
  hadn't bitten yet. All now go through `_save_json_atomic()`.
- **Found the actual cause of the M07 Canvas duplicates the fix below didn't
  stop: `canvas_state.json` losing its `synced_modules` silently triggers a
  full course re-sync.** `logs/history.jsonl` shows `_canvas_sync`
  re-extracted and re-created ~59 tasks for modules 1–7 on 2026-07-06 — one
  day after the previous state-loss incident (2026-07-03) — because
  `synced_modules` had reset to empty again. Most re-extracted titles came
  back byte-identical from the LLM and got deduped; 5 M07 readings didn't
  (different author attribution, different truncation — e.g. "Read
  Crawford, ... Toward a Framework to Re" vs "Read Crawford & Schultz,
  ..."), similar enough for a human to recognize as the same reading but
  different enough that the similarity-ratio dedup below didn't catch them
  either. The state file is gitignored (never versioned) and was written
  non-atomically (`open(path, "w")` truncates before the JSON body is fully
  written) — a kill/crash mid-write (e.g. a task-scheduler timeout under
  `pythonw`) leaves it empty or corrupt, and the loader silently treats that
  as "first sync ever." `_canvas_sync` now fires a high-priority alert when
  it sees 0 synced modules but FlowSavvy's course list already has tasks,
  instead of quietly re-extracting the whole course; state saves go through
  a new `_save_json_atomic()` (temp file + `os.replace`) instead of the
  truncate-in-place write. Manually deleted the 5 duplicate tasks created by
  the 07-06 incident (ids 20019757–20019761) from FlowSavvy.
- **Canvas sync could create a duplicate task for an assignment/reading that
  already existed under a slightly different title.** `canvas_engine.plan()`
  deduped against `existing_titles` with raw string equality, but FlowSavvy
  decorates course-list task titles with a trailing `[COURSE.CODE]` suffix
  (e.g. ` [AS.470.703.81.SU26]`) that the engine never generates — confirmed
  in `logs/canvas_state.json`, where "M02: NYC Open Data Analysis" and
  "M02: NYC Open Data Analysis [AS.470.703.81.SU26]" both exist as separate
  completed tasks for the same assignment. Added `_normalize_title()` (strips
  the bracket suffix, casefolds) plus a similarity-ratio fallback
  (`difflib.SequenceMatcher`, threshold 0.93) for near-identical titles that
  survive normalization. Skipped duplicates now show up in the sync report
  instead of vanishing silently. **Known limitation** (see the entry above):
  this only helps when a re-extracted title survives close to byte-identical
  — it's not a substitute for `synced_modules` staying intact.
- **A transient network blip to FlowSavvy failed the whole domain tick and
  paged a false-alarm health alert.** `⚠️ LifeOps errors — gym:
  HTTPSConnectionPool(...) SSLEOFError` — a one-off TLS handshake failure
  that never reached the server, confirmed self-resolved by the very next
  tick (`logs/runs.jsonl` shows exactly one occurrence). `FlowSavvy`'s HTTP
  methods now retry up to twice (0.5s/1s backoff) on
  `requests.exceptions.ConnectionError` specifically — i.e. only when the
  request never reached the server. Does **not** retry on an actual HTTP
  error response (4xx/5xx), since that means the server already saw the
  request; blindly retrying a `POST`/`PUT` there risks creating a duplicate
  task server-side, the same class of bug as the Canvas dedup fix above.
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
- **A blank `PARTNER_TASK` (settable via the Settings page) silently
  misclassified every completed task as "partner."** `"" in t` is `True` for
  any string in Python, so clearing the field made `_classify()` match
  everything before the gym/laundry/etc. keyword loop even ran. Now guards
  on a non-empty value.
- **A failed/rate-limited ntfy alert during `run_catchup` could trigger a
  reschedule storm.** `ntfy.alert()` now raises on non-2xx; `run_catchup`
  called it *before* persisting `catchup_state.json`'s `lastHandled`, so a
  transient ntfy failure left the same trigger message "unhandled,"
  re-firing a full `fs.recalculate(reschedule_past=True)` on every
  subsequent tick until an alert happened to succeed. The alert call is now
  wrapped in try/except so a notification failure can't block state
  persistence.
- **The auth cookie's `Secure` flag was set inconsistently between the two
  places it's issued** — the GET/HEAD redirect path checked
  `X-Forwarded-Proto` (needed behind a TLS-terminating proxy like a
  Tailscale funnel), the other cookie-setting path didn't. Deduped into one
  `secure` expression used by both.
- **`SameSite=Strict` on the auth cookie could block the exact "tap an ntfy
  alert to open the panel" flow it exists to support** — that's a top-level
  GET navigation from outside the app, and Strict cookies are withheld on
  that kind of navigation on some OS/browser notification plumbing. Changed
  to `SameSite=Lax`, which still blocks the cross-site POST/embed cases
  Strict guards against.
- **Restored the `digest` domain to the panel's toggle list** — it was still
  wired into `runner.DOMAINS` and the `daily` tier, but had quietly dropped
  out of `web.ALL_DOMAINS` during the recent feature work, so it couldn't be
  enabled/disabled or manually run from the panel.

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
