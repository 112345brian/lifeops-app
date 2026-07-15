"""Deterministic "upcoming notable events" for the daily briefing -- no LLM.

Surfaces genuinely infrequent/one-off calendar events (a haircut, a doctor's
appointment, a BBQ) while filtering out routine ones (a weekly class, a
standing dinner) that recur often enough you don't need a reminder. Routine
vs. notable is decided by three signals, checked in order of confidence:

  1. FlowSavvy's own occurrenceDate field -- confirmed live (2026-07-15) to
     be populated on instances of a real repeating Google Calendar event
     ("PAY DAY", a birthday, a daily reminder all carried it) and null on
     genuine one-off itemType=event entries ("Dermatologist", a concert).
     This tells us the item belongs to a series, NOT that it is routine:
     annual/rare recurring events can be exactly what should surface. When
     an occurrenceDate-backed series exposes multiple visible occurrences,
     the same interval rule below still decides whether it is routine.
     (Note: a task like "Plan dentist checkup" is itemType=task, not event,
     so it never reaches this function at all -- see the itemType filter
     below. That's a deliberate distinction: such a task means "go book
     this," not "this is happening now"; the real appointment, once
     scheduled, becomes its own itemType=event entry.)
  2. A known-routine title, cross-checked case-insensitively against
     config.ROUTINE_EVENT_TITLES (manually curated) -- passed in by the
     caller as [known_routine_titles].
  3. Observed recurrence interval, tracked locally over time in this
     module's own history file -- the original, still-useful fallback for
     a title neither signal above catches yet (e.g. a new-to-us standing
     meeting whose occurrenceDate FlowSavvy doesn't populate for some
     other reason). Distinct occurrences of "the same thing" are matched
     by exact title, same limitation as before.
"""
import datetime, json, os
from . import history

# An event recurring faster than this is routine -- you don't need a
# reminder for something that happens every week or more often. Anything
# rarer (a haircut every 5 weeks, a one-off BBQ) clears the bar.
ROUTINE_INTERVAL_DAYS = 21

# How far ahead the rolling lookahead reaches for the returned list -- a
# genuine rolling window anchored to `now` on every call (see
# upcoming_notable_events), NOT a fixed Mon-Sun calendar week: an event 6
# days out today is still 5 days out tomorrow, not reset/dropped at a week
# boundary.
LOOKAHEAD_DAYS = 7

# How long a distinct occurrence date is kept in local history before being
# pruned -- long enough to still catch an infrequent (e.g. every-5-week)
# recurrence's previous occurrence, short enough not to grow unbounded.
_HISTORY_DAYS = 180


def _history_file():
    # A function, not a module-level constant -- history.ROOT is
    # monkeypatched per-test (see fcm.py's _token_file for the same
    # pattern).
    return os.path.join(history.ROOT, "logs", "event_frequency.json")


def _load_history():
    try:
        return json.load(open(_history_file(), encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError):
        return {}


def _save_history(data):
    path = _history_file()
    os.makedirs(os.path.dirname(path), exist_ok=True)
    json.dump(data, open(path, "w", encoding="utf-8"))


def _record_occurrences(hist, schedule_items, today):
    """Merges every distinct (title -> occurrence date) seen in this pull
    into the persisted history, then prunes dates older than _HISTORY_DAYS.
    Mutates and returns `hist`."""
    cutoff = (today - datetime.timedelta(days=_HISTORY_DAYS)).isoformat()
    for i in schedule_items:
        if i.get("itemType") != "event" or i.get("allDay"):
            continue
        title = i.get("title") or ""
        start = i.get("startTime") or ""
        date = start[:10]
        if not title or not date:
            continue
        dates = set(hist.get(title, []))
        dates.add(date)
        hist[title] = sorted(d for d in dates if d >= cutoff)
    return hist


def upcoming_notable_events(schedule_items, now, known_routine_titles=frozenset()):
    """[schedule_items]: raw FlowSavvy scheduleItems (from
    gather._upcoming_schedule) -- itemType/title/startTime/allDay/
    occurrenceDate. [known_routine_titles]: lowercased title set from
    OTHER known-routine sources (see this module's docstring, signal 2) --
    checked before the inferred-history fallback (signal 3), since a
    title you or the chore list already know is routine shouldn't need to
    wait for local history to catch up.

    Records every distinct event occurrence into local history (so
    infrequent recurrences can be recognized even when only one occurrence
    is visible in the current pull), then returns the ones landing in the
    next LOOKAHEAD_DAYS days (a rolling window anchored to `now`,
    recomputed fresh on every call -- NOT a fixed calendar week) that are
    NOT routine by any of the three signals above. A title that clears all
    three (no occurrenceDate, not known-routine, no other observed
    occurrence within ROUTINE_INTERVAL_DAYS) is notable by default --
    better to mention something once too often than silently drop a new
    commitment before its recurrence pattern is known.

    Returns [{"title", "date", "weekday", "start"}] ("start" is the raw
    startTime timestamp, same field TodayEvent already carries in
    today_events_input -- lets the Android widget format a real "@ 6:00 PM"
    the same way formatEventLine already does for today's events, instead
    of only ever showing the day name with no time at all), sorted by
    date."""
    today = now.date()
    hist = _record_occurrences(_load_history(), schedule_items, today)
    _save_history(hist)

    lookahead_end = today + datetime.timedelta(days=LOOKAHEAD_DAYS)
    out = []
    seen_this_run = set()
    for i in schedule_items:
        if i.get("itemType") != "event" or i.get("allDay"):
            continue
        title = i.get("title") or ""
        start = i.get("startTime") or ""
        date_str = start[:10]
        if not title or not date_str or (title, date_str) in seen_this_run:
            continue
        try:
            date = datetime.date.fromisoformat(date_str)
        except ValueError:
            continue
        if not (today <= date <= lookahead_end):
            continue
        seen_this_run.add((title, date_str))

        # Signal 2: known-routine from config/chore-list, not inferred.
        if title.strip().lower() in known_routine_titles:
            continue

        # Signals 1 + 3: occurrenceDate means "this belongs to a recurring
        # series", but only a close observed interval means "routine". Rare
        # recurring events (annual birthdays, occasional appointments)
        # remain notable until a close interval proves otherwise.
        other_dates = [d for d in hist.get(title, []) if d != date_str]
        routine = any(
            abs((datetime.date.fromisoformat(d) - date).days) < ROUTINE_INTERVAL_DAYS
            for d in other_dates
        )
        if routine:
            continue
        out.append({"title": title, "date": date_str, "weekday": date.strftime("%A"), "start": start})

    out.sort(key=lambda e: e["date"])
    return out
