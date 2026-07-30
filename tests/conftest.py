import pytest

from lifeops import config, history


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


@pytest.fixture(autouse=True)
def _block_real_outbound_notifications(monkeypatch):
    """config.py loads private/.env's REAL secrets into os.environ at import
    time (module-load, before any per-test monkeypatch can run), so
    ntfy.alert's real, unauthenticated POST to https://ntfy.sh/<real topic>
    and fcm._send's real Firebase send are both live by default in every
    test process on this machine -- they only no-op if the topic/service
    account happen to be unset, which they aren't here. This is the same
    class of leak _isolate_lifeops_state fixed for local state on
    2026-07-28, just for outbound network calls instead of on-disk state
    (confirmed 2026-07-30: a routine full-suite run fired ~10 real ntfy
    alerts to the real phone from tests that never intended to send
    anything real). Blank/nonexistent values make both transports' own
    no-op guards kick in (ntfy.py checks `if not config.NTFY_*_TOPIC`;
    fcm._send checks `if not token or not os.path.exists(...)`), so a test
    that wants to verify real-looking send behavior must still explicitly
    monkeypatch these back (or mock the transport directly) -- this fixture
    only removes the *unintentional* default exposure."""
    monkeypatch.setattr(config, "NTFY_ALERTS_TOPIC", "")
    monkeypatch.setattr(config, "NTFY_SIGNAL_TOPIC", "")
    monkeypatch.setattr(config, "FCM_SERVICE_ACCOUNT_FILE", "")
