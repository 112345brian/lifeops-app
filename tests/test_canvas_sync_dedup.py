"""Regression tests for _canvas_sync dedup keying on the STABLE Canvas module
id rather than the number scraped from the (renameable) module name.

Two historical failure modes these lock down:
  1. A rename/renumber of an already-synced module changed the scraped `num`,
     so `num in synced` no longer matched and the SAME module (same id) got
     re-synced from scratch — re-creating all its tasks (the 2026-07-06 dup
     signature, since reading re-extraction isn't byte-stable).
  2. Two distinct modules can scrape the same `num` ("Module 6" and the
     interstitial "Module 6.5", or "Supplementary readings for Module 5" and
     "Module 5"). Keyed on num, the second one is silently skipped forever.
"""
import os, json, datetime
import pytest

from lifeops import runner, history
from lifeops.engines import canvas_engine


class _FakeCanvas:
    def __init__(self, modules):
        self._modules = modules
    def modules(self):
        return self._modules
    def assignments(self):
        return []
    def page(self, slug):
        return {"body": ""}
    def announcements(self, since_date=None):
        return []


class _FakeFlowSavvy:
    """No pre-existing tasks; every create succeeds."""
    def list_items(self, **kwargs):
        return {"items": []}
    def create_task(self, **kwargs):
        return {"id": "created"}
    def update_task(self, *a, **k):
        return {}


class _FakeLLM:
    def extract_readings(self, text, num):
        return []


def _module(mod_id, name, unlock="2026-01-01"):
    return {"id": mod_id, "name": name, "unlock_at": unlock, "items": []}


def _run_sync(tmp_path, monkeypatch, modules, state):
    """Run _canvas_sync against fakes, returning the list of module ids that
    were actually selected for syncing this run (i.e. handed to plan())."""
    # Point history's durable-file root at a throwaway dir.
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    monkeypatch.setattr(history, "HIST", str(tmp_path / "logs" / "history.jsonl"))

    sp = tmp_path / "logs" / "canvas_state.json"
    sp.parent.mkdir(parents=True, exist_ok=True)
    sp.write_text(json.dumps(state), encoding="utf-8")

    # Spy on plan() to capture which modules survived dedup, without running
    # the real task-planning/creation machinery.
    synced_this_run = []
    def _spy_plan(modules_data, existing_titles, today):
        synced_this_run.extend(m["_mod_id"] for m in modules_data)
        return {"creates": [], "report": ""}
    monkeypatch.setattr(canvas_engine, "plan", _spy_plan)

    now = datetime.datetime(2026, 7, 8, 9, 0, 0)
    runner._canvas_sync(_FakeCanvas(modules), lambda s: s, canvas_engine,
                        _FakeLLM(), _FakeFlowSavvy(), now)

    saved = json.loads(sp.read_text(encoding="utf-8"))
    return synced_this_run, saved


def test_renamed_module_same_id_is_not_resynced(tmp_path, monkeypatch):
    # id 100 was already synced; a mid-term rename/renumber changed the scraped
    # number (6 -> 7) but the Canvas id is unchanged. Must NOT re-sync.
    state = {"synced_module_ids": [100], "synced_modules": [6], "task_titles": []}
    modules = [_module(100, "Week 6 / Module 7: Ethics")]
    synced, saved = _run_sync(tmp_path, monkeypatch, modules, state)
    assert synced == []                         # not re-synced
    assert 100 in saved["synced_module_ids"]    # still remembered by id


def test_two_modules_same_num_different_ids_both_sync(tmp_path, monkeypatch):
    # Fresh state (id-based). "Module 6" and interstitial "Module 6.5" both
    # scrape num 6 but are distinct modules — both must be synced.
    state = {"synced_module_ids": [], "synced_modules": [], "task_titles": []}
    modules = [_module(100, "Module 6"),
               _module(200, "Module 6.5: Ethics")]
    synced, saved = _run_sync(tmp_path, monkeypatch, modules, state)
    assert set(synced) == {100, 200}
    assert set(saved["synced_module_ids"]) == {100, 200}


def test_legacy_num_migration_lets_num_collider_through(tmp_path, monkeypatch):
    # Legacy state knows only num 6 was synced (no ids yet). On first run under
    # id-based code: the first module bearing num 6 is adopted (id recorded,
    # not re-synced), and the genuine collider "Module 6.5" is synced.
    state = {"synced_modules": [6], "task_titles": []}   # no synced_module_ids
    modules = [_module(100, "Module 6"),
               _module(200, "Module 6.5: Ethics")]
    synced, saved = _run_sync(tmp_path, monkeypatch, modules, state)
    assert synced == [200]                               # collider synced, M6 not re-synced
    assert set(saved["synced_module_ids"]) == {100, 200}  # both ids now recorded


def test_steady_state_new_collider_syncs_after_id_known(tmp_path, monkeypatch):
    # id 100 already recorded. A new interstitial scraping the same num 6 must
    # still be synced (the known id claims the num, freeing the collider).
    state = {"synced_module_ids": [100], "synced_modules": [6], "task_titles": []}
    modules = [_module(100, "Module 6"),
               _module(200, "Module 6.5: Ethics")]
    synced, saved = _run_sync(tmp_path, monkeypatch, modules, state)
    assert synced == [200]
    assert set(saved["synced_module_ids"]) == {100, 200}
