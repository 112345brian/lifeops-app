"""Orchestrator — the cron entrypoint. Replaces the Claude 'daily-ops' routine.

Flow per domain: GATHER (clients) -> DECIDE (deterministic engine) -> APPLY (clients).
The LLM (lifeops.llm) is touched only for the judgment slivers.

Run:  python -m lifeops.runner            # full pass
      python -m lifeops.runner gym        # one domain

Schedule it ~3x/day via Windows Task Scheduler (see scripts/register_task.ps1).
"""
import sys, json, datetime
from . import config, ntfy
from .flowsavvy import FlowSavvy
from .engines import gym_engine, chore_engine

def _today():
    # NOTE: real run uses the machine clock; kept in one place for testing.
    return datetime.datetime.now()

def run_gym(fs: FlowSavvy):
    """Gather week situation from FlowSavvy -> gym_engine -> apply fixed-time blocks."""
    # TODO(wire): build the engine input from fs.get_schedule()/list_items() +
    # calendar reads (fs.list_calendars/items already include the synced Google cals).
    # inp = {...}; plan = gym_engine.plan(inp)
    # for a in plan["actions"]: fs.create_task(...) / fs.delete_item(...)
    # fs.recalculate(); ntfy.alert(plan["alert"]) if level != none
    raise NotImplementedError("wire after FlowSavvy base URL/token confirmed")

def run_chore(fs: FlowSavvy):
    """Gather completed [cycle] chores -> chore_engine -> create next ones."""
    raise NotImplementedError("wire after FlowSavvy base URL/token confirmed")

# run_homework / run_spend / run_social / run_catchup / run_ynab -> next engines

DOMAINS = {"gym": run_gym, "chore": run_chore}

def main():
    fs = FlowSavvy()
    which = sys.argv[1:] or list(DOMAINS)
    for name in which:
        fn = DOMAINS.get(name)
        if not fn:
            print(f"unknown domain: {name}"); continue
        try:
            fn(fs)
        except NotImplementedError as e:
            print(f"[{name}] not wired yet: {e}")
        except Exception as e:
            print(f"[{name}] ERROR: {e}")  # one domain failing must not kill the rest

if __name__ == "__main__":
    main()
