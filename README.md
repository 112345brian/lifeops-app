# LifeOps (standalone)

A headless personal-ops scheduler. Runs as a plain Python cron job — **no Claude
app, no permission prompts, no spawned agents** for the day-to-day work.
Deterministic logic lives in engines; the LLM is called only for genuine
judgment slivers (categorizing a novel payee, writing the weekly digest,
parsing a Canvas readings page).

## Architecture
```
Windows Task Scheduler (signal ~2min, tick ~10min, daily 7:10am)  ->  python -m lifeops.runner <tier>
   gather (clients)  ->  DECIDE (engines, deterministic)  ->  apply (clients)
   clients: FlowSavvy API · YNAB API · ntfy · Canvas (token or browser session)
   LLM (Anthropic): only llm.py — categorize_unknown, extract_readings, weekly_digest
```
FlowSavvy already syncs all your Google calendars, so deadlines + social events
are read **through the FlowSavvy API** — no Google OAuth needed.

Three tiers, all under one global run-lock so overlapping fires can't race FlowSavvy:
- **signal** (~2 min) — interactive path: a phone tap (`catchup`) re-packs the
  day fast. register_task.ps1 must keep this key registered or the scheduled
  task silently becomes a no-op.
- **tick** (~10 min) — `catchup`, `meal`, `gym`. Gym lives here so a slot
  blocked mid-day gets re-planned the same day; meal lives here so a "Have
  leftovers — skip" tap is honored within minutes. Engines only write on real
  change and meal checks due-ness locally first, so frequent runs don't churn
  the calendar or waste API calls.
- **daily** (7:10am) — `ynab`, `homework`, `social`, `chore`, `meal`, `spend`,
  `digest`, `canvas` — planning/scheduling work + anything LLM-touching.
  `spend`/`canvas` are NOT in `tick`: spend only ever alerts once/day, and
  canvas modules unlock at most daily.

Every run's per-domain output is captured to `logs/last_run.json` and
`logs/runs.jsonl` (stdout is otherwise silently discarded under `pythonw`).
Completions are logged durably to `logs/history.jsonl` — that's the system's
memory of what actually happened, not FlowSavvy's lossy task state.

## Layout
- `lifeops/flowsavvy.py`, `ynab.py`, `ntfy.py`, `canvas.py` — REST clients
- `lifeops/canvas_browser.py` — Canvas access via an authenticated Playwright
  session, for schools (like JHU) that disable self-service API tokens
- `lifeops/llm.py` — the ONLY LLM use; every call is a bounded judgment slice
- `lifeops/engines/` — deterministic decision engines: gym, ynab, chore, load
  (homework), spend, social, canvas — pure functions, fully unit tested
- `lifeops/adherence.py` — reads history to measure what actually happened
  (gym completion rate by slot, streaks) and feeds it back into scheduling
- `lifeops/gather.py` — turns live FlowSavvy/YNAB/history data into engine inputs
- `lifeops/runner.py` — orchestrator (the cron entrypoint)
- `lifeops/web.py` + `lifeops/templates/` — local control panel (gym/schedule
  blocking, sick-week, domain toggles, recurring tasks, config, history)
- `scripts/register_task.ps1` — registers the tick/daily Windows scheduled tasks
- `scripts/register_web.ps1` — registers the control panel as an always-on
  service, reachable privately over Tailscale
- `scripts/canvas_login.py` — one-time interactive Canvas login (see below)
- `tests/` — 100+ unit tests over every engine (`pytest`)

## Setup
1. `pip install -r requirements.txt`
2. Copy `.env.example` to `private/.env` when the private submodule is present,
   or to root `.env` otherwise, and fill it in. The control panel edits the
   same file the runtime selected:
   - **FlowSavvy**: base URL + token from `my.flowsavvy.app/api/docs`, plus
     the list/scheduling-hours ids for your account
   - **YNAB**: token (Account Settings > Developer Settings)
   - **Anthropic**: API key
   - **ntfy**: your two topic names
   - **Canvas** (optional): course id + `CANVAS_TOKEN` if your school issues
     API tokens; otherwise leave `CANVAS_TOKEN` blank and see below
3. `python -m lifeops.runner gym` to test one domain
4. `powershell scripts/register_task.ps1` to schedule tick + daily
5. `powershell scripts/register_web.ps1` to run the control panel at
   `http://127.0.0.1:8765` (proxy it privately via `tailscale serve 8765`)

### Canvas sync without an API token
If your school disables self-service Canvas tokens (JHU does), lifeops falls
back to reading Canvas through a real, authenticated browser session instead —
no token, no cookie extraction (Canvas's session cookie is `httpOnly` and
can't be read from a page anyway). One-time setup:
```
python scripts\canvas_login.py
```
This opens a visible Chrome window against a dedicated automation profile
(`data/browser_profiles/canvas/` — separate from your everyday Chrome). Log in
with your school SSO (Duo etc. — this step can never be automated, by design),
wait for the course modules page to load, then press Enter in the terminal.
The session persists to disk and every future `daily` run reuses it
automatically. If it eventually expires, you'll get an ntfy alert telling you
to re-run the same command — it never fails silently.

## Control panel
`python -m lifeops.web` (or via the registered service) serves a dark-mode
dashboard: live status, gym stats, per-domain enable/run, gym scheduling
(block a date, sick-week, re-plan), general day-blocking (creates a FlowSavvy
busy event + blocks gym for that date), recurring `[cycle:Nd]` tasks, editable
config, and history. Set `WEB_TOKEN` in `.env` before exposing it beyond
localhost — every request then needs `/?token=<secret>` once (a cookie keeps
you in after).

### Installing it as an app + notifications
The panel is a PWA — over your Tailscale HTTPS URL, add it to your phone's
home screen and it opens standalone (no browser chrome), with its own icon:
- **iOS**: open the URL in Safari → Share → **Add to Home Screen** (the panel
  shows this instruction itself the first time you visit, since iOS Safari
  has no automatic install prompt)
- **Android/Chrome**: the panel offers a one-tap **Install** banner
  automatically once the manifest + service worker are detected

Notifications still go through **ntfy** (a dedicated, reliable push app —
already wired into every alert path: gym urgency, YNAB holds, Canvas session
expiry, run errors, the weekly digest). Install the ntfy app and subscribe to
your `NTFY_ALERTS_TOPIC`. Set `PANEL_URL` in `.env` to your Tailscale hostname
and those notifications deep-link straight into the relevant panel section
when tapped (e.g. a gym alert opens directly to Gym Controls) — so the two
apps work as one experience even though push delivery and the dashboard are
technically separate pieces. (Real in-app Web Push was considered instead of
ntfy, but ntfy is already proven-reliable and fully wired; Web Push on iOS is
finicky — subscriptions expire silently and only work from an already-installed
PWA — so it wasn't worth the fragility for a personal single-user app.)

## Testing
```
python -m pytest tests/ -q
```
Covers every deterministic engine (gym, ynab, chore, load, spend, social,
canvas) plus adherence — malformed-input, edge-case, and regression coverage,
not just happy paths.

## Status
Fully wired: all domains implemented and tested, control panel live, Canvas
sync working via token or browser session. See `CHANGELOG.md` for recent work.
