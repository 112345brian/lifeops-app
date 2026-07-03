# LifeOps (standalone)

A headless personal-ops scheduler. Runs as a plain Python cron job — **no Claude
app, no permission prompts, no spawned agents** for the day-to-day work.
Deterministic logic lives in engines; the LLM is called only for genuine
judgment slivers (categorizing a novel payee, writing the weekly digest,
parsing a Canvas readings page).

## Architecture
```
Windows Task Scheduler (tick every 10min, daily at 7:10am)  ->  python -m lifeops.runner
   gather (clients)  ->  DECIDE (engines, deterministic)  ->  apply (clients)
   clients: FlowSavvy API · YNAB API · ntfy · Canvas (token or browser session)
   LLM (Anthropic): only llm.py — categorize_unknown, extract_readings, weekly_digest
```
FlowSavvy already syncs all your Google calendars, so deadlines + social events
are read **through the FlowSavvy API** — no Google OAuth needed.

Two tiers, both run under the same lock so they never race:
- **tick** (every ~10 min): `catchup`, `meal` — cheap, signal-driven, reacts to
  ntfy button taps within minutes
- **daily** (7:10am): `gym`, `ynab`, `homework`, `social`, `chore`, `meal`,
  `spend`, `digest`, `canvas` — planning/scheduling work + anything LLM-touching

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
2. `cp .env.example .env` and fill it in:
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
