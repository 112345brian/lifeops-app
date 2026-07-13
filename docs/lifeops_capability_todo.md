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

- Add `POST /api/tasks/{id}/complete`.
- Add `POST /api/gym/log`.
- Add `POST /api/gym/skip`.
- Add `POST /api/schedule/block-day`.
- Add `POST /api/domains/{name}/run`.
- Make Android use the direct API when reachable.
- Keep ntfy signal path as fallback when the phone is not on tailnet.
- Return fresh next-actions/attention state after mutations where practical.

## Notification Architecture

- Expand `lifeops/notify.py` into real channels: `ntfy`, `fcm`, and possibly
  Web Push later.
- Give notifications semantic types: `briefing`, `urgent_alert`,
  `action_result`, `system_health`.
- Keep ntfy as cross-platform fallback and signal bus, not the primary UX.
- Avoid leaking transport details into domain logic.
- Add tests for routing and fallback behavior.

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

## Suggested Sequence

1. Add the deterministic attention-state model.
2. Add widget status badge and reason-aware layout.
3. Redesign the full app home around Today/Attention/Actions.
4. Add the direct action API.
5. Turn the Android launcher into a tiny Today app.
