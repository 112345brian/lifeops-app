"""Persistence for routine definitions (targets/thresholds) -- the I/O
layer on top of lifeops/routine.py's pure Routine/status math, kept
separate so routine.py itself stays side-effect-free.

Prep work for an eventual settings UI: a UI needs something persisted to
point at. This module only adds the persistence + a save() entry point --
nothing calls save_routine() yet.

Chore is deliberately not represented here: its `[cycle:Nd]` note-tag
already IS the user-editable mechanism (edit the FlowSavvy task's note
text), so there's no separate "chore routine record" to persist.
"""
from . import state_store
from .routine import Routine

# Matches today's hardcoded values exactly, so loading with nothing
# persisted yet reproduces current behavior bit-for-bit:
#   gym: gym_engine.py's `r = {"target": 4, "floor": 3, "max_consecutive": 2}`
#   partner/friends: social_engine.py's old PARTNER_ROUTINE/FRIENDS_ROUTINE
#   meal: runner.py's old _MEAL_ROUTINE
_DEFAULTS = {
    "gym":     {"times": 4, "per_days": 7, "anchor": "window",
                "floor": 3, "max_consecutive": 2},
    "partner": {"times": 1, "per_days": 7, "anchor": "since_last"},
    "friends": {"times": 1, "per_days": 7, "anchor": "since_last"},
    "meal":    {"times": 1, "per_days": 6, "anchor": "since_last"},
}

_VALID_ANCHORS = {"window", "since_last"}


def _validate(routine_id, cfg):
    if routine_id not in _DEFAULTS:
        raise ValueError(f"unknown routine id: {routine_id!r} (known: {sorted(_DEFAULTS)})")
    if cfg.get("anchor") not in _VALID_ANCHORS:
        raise ValueError(f"routine {routine_id!r}: anchor must be one of {sorted(_VALID_ANCHORS)}, "
                         f"got {cfg.get('anchor')!r}")
    for field in ("times", "per_days"):
        value = cfg.get(field)
        if not isinstance(value, int) or isinstance(value, bool) or value < 1:
            raise ValueError(f"routine {routine_id!r}: {field} must be a positive int, got {value!r}")


def load_routine(routine_id):
    """Returns (Routine, extra) -- extra is whatever persisted/default
    fields aren't part of the core Routine shape (gym's floor/
    max_consecutive today). Persisted overrides merge over defaults, so
    editing just one field doesn't require repeating the rest.

    Raises ValueError for an unrecognized routine_id or an invalid
    times/per_days/anchor value -- fails loud rather than a confusing raw
    KeyError (unknown id) or silently running with nonsense state."""
    overrides = state_store.load_json(state_store.logs_path("routines.json"), default={})
    cfg = {**_DEFAULTS.get(routine_id, {}), **overrides.get(routine_id, {})}
    _validate(routine_id, cfg)
    routine = Routine(id=routine_id, times=cfg["times"], per_days=cfg["per_days"], anchor=cfg["anchor"])
    extra = {k: v for k, v in cfg.items() if k not in ("times", "per_days", "anchor")}
    return routine, extra


def save_routine(routine_id, **fields):
    """Persists a partial override -- e.g. save_routine("gym", times=5) --
    merged with whatever's already saved for this routine.

    Raises ValueError for an unrecognized routine_id (silently writing an
    override nothing will ever read is worse than a load-time KeyError --
    it looks like it worked) or an invalid times/per_days/anchor value,
    validated against the fully-merged config so a partial override can't
    be checked against incomplete data."""
    if routine_id not in _DEFAULTS:
        raise ValueError(f"unknown routine id: {routine_id!r} (known: {sorted(_DEFAULTS)})")
    overrides = state_store.load_json(state_store.logs_path("routines.json"), default={})
    merged = {**_DEFAULTS[routine_id], **overrides.get(routine_id, {}), **fields}
    _validate(routine_id, merged)
    overrides.setdefault(routine_id, {}).update(fields)
    state_store.save_json_atomic(state_store.logs_path("routines.json"), overrides)
