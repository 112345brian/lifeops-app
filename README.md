# LifeOps (standalone)

A headless personal-ops scheduler. Runs as a plain Python cron job — **no Claude
app, no permission prompts, no spawned agents.** Deterministic logic lives in
engines; the LLM is called only for genuine judgment slivers.

## Architecture
```
Windows Task Scheduler (3x/day)  ->  python -m lifeops.runner
   gather  ->  DECIDE (engines, deterministic)  ->  apply
   clients: FlowSavvy API · YNAB API · ntfy · Anthropic (judgment only)
```
FlowSavvy already syncs all your Google calendars, so deadlines + social events
are read **through the FlowSavvy API** — no Google OAuth needed.

## Layout
- `lifeops/flowsavvy.py`, `ynab.py`, `ntfy.py` — REST clients
- `lifeops/llm.py` — the ONLY LLM use (e.g. categorizing a novel payee)
- `lifeops/engines/` — deterministic decision engines (gym, chore ported; rest WIP)
- `lifeops/runner.py` — orchestrator (the cron entrypoint)
- `scripts/register_task.ps1` — registers the Windows scheduled task

## Setup
1. `pip install -r requirements.txt`
2. `cp .env.example .env` and fill it in:
   - **FlowSavvy**: base URL + token from `my.flowsavvy.app/api/docs`
   - **YNAB**: token (Account Settings > Developer Settings)
   - **Anthropic**: API key
   - **ntfy**: your two topic names
3. `python -m lifeops.runner gym` to test one domain
4. `powershell scripts/register_task.ps1` to schedule it

## Status
Scaffold + clients + ported gym/chore engines. **Blocked on**: the FlowSavvy API
base URL + auth header format (from the docs) to finalize `flowsavvy.py` endpoint
paths and wire `runner.py`. Remaining engines (homework, spend, social, catchup,
ynab) port from the same logic already written in the Claude prototype.
