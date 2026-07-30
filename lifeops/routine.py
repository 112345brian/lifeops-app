"""Shared "is this recurring thing due" primitive.

v1 consolidation: gym_engine.py, chore_engine.py, social_engine.py, and
runner.py's inline meal logic each independently reinvented this cadence
math (a trailing-window count, or a days-since-last-completion check).
This module is deliberately *only* that shared math -- no on_due action,
no cross-routine gating, no scripting escape hatch. See
docs/lifeops_capability_todo.md's "Recurring Items as User-Configured
Data" section for the fuller design this is a first slice of; those
pieces are explicit future work, not omissions here.

Each domain's actual ACTION when due (gym's slot-picking/consecutive-day
cap, meal's grocery-then-cook dependency chain, chore's per-task tag
discovery, social's nudge text) stays domain-specific code -- only the
due-check itself is shared.
"""
import datetime
from dataclasses import dataclass


@dataclass(frozen=True)
class Routine:
    id: str
    times: int
    per_days: int
    anchor: str   # "window" | "since_last"


def _parse(ts):
    return datetime.datetime.fromisoformat(ts)


def _due_since_last(days_since_last, per_days):
    return days_since_last is None or days_since_last >= per_days


def _due_window(times_this_window, times):
    return times_this_window < times


def status_since_last(routine, days_since_last):
    """anchor="since_last", for callers that already have a precomputed
    days-since-last-completion int rather than raw timestamps (e.g.
    gather.social_input's `ago(history.last(...))`, which already collapses
    to a day count) -- avoids a lossy round-trip through a synthetic
    timestamp just to reuse status()'s timestamp-based path."""
    return {"due": _due_since_last(days_since_last, routine.per_days),
            "days_since_last": days_since_last}


def status(routine, event_timestamps, now):
    """`event_timestamps`: iterable of ISO timestamp strings, already
    filtered to this routine's own completions (e.g. history.events('gym')
    timestamps, or a single chore's own completion date). Returns:
      anchor="window":     {"due": bool, "times_this_window": int}
      anchor="since_last":  {"due": bool, "days_since_last": int | None}
    """
    if routine.anchor == "since_last":
        # max() over parsed datetimes, not over the raw strings -- string
        # comparison happens to agree with chronological order for
        # consistently-formatted ISO-8601 timestamps (which is all this
        # module has ever been fed so far), but that's an implicit
        # assumption this shared primitive shouldn't quietly depend on.
        parsed = [_parse(ts) for ts in event_timestamps]
        last = max(parsed) if parsed else None
        days_since = None if last is None else (now - last).days
        return status_since_last(routine, days_since)

    if routine.anchor == "window":
        window_start = now - datetime.timedelta(days=routine.per_days)
        # Dedup by date -- source data (e.g. history.days_with) is often
        # already date-grained, but callers passing raw timestamps
        # shouldn't double-count two events on the same day.
        dates = {_parse(ts).date() for ts in event_timestamps if _parse(ts) >= window_start}
        count = len(dates)
        return {"due": _due_window(count, routine.times), "times_this_window": count}

    raise ValueError(f"unknown Routine.anchor: {routine.anchor!r}")


def next_due_date(routine, last_completed_date):
    """anchor="since_last" only: the date this routine is next due, N days
    after a specific completion date (chore_engine's `comp + n * DAY`,
    routed through the shared primitive instead of ad hoc date math)."""
    if routine.anchor != "since_last":
        raise ValueError('next_due_date only applies to anchor="since_last"')
    return last_completed_date + datetime.timedelta(days=routine.per_days)
