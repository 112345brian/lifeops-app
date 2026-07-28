import pytest

from lifeops import history


@pytest.fixture(autouse=True)
def _isolate_lifeops_state(tmp_path, monkeypatch):
    """Safety net, not a substitute for tests patching history.ROOT
    themselves where it matters to the test's own logic: redirects every
    test's state I/O into a fresh per-test tmp_path by default. Before this
    existed, a test that forgot to patch history.ROOT (e.g. test_web.py's
    `sandbox` fixture, which never did) would silently read/write the real
    private/logs/ state on whatever machine ran the suite -- invisible
    under the old design since each state key was its own small JSON file,
    but glaringly obvious once everything lives in one shared state.db
    (confirmed 2026-07-28: two real test runs against this real checkout
    left fabricated "gym"/"gym_skip" history_events rows and several
    kv_state keys in the actual private/logs/state.db).

    A test that explicitly monkeypatches history.ROOT itself to a
    *different* tmp_path (its own fixture's tmp_path, which pytest gives a
    distinct directory per test node id regardless) just overrides this
    fixture's patch harmlessly -- same effect, still isolated.
    """
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
