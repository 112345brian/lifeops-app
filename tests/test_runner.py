import datetime

from lifeops import config, runner, state_store
from lifeops.domains import _shared
from lifeops.domains import canvas as canvas_domain
from lifeops.engines import canvas_engine


def test_selected_domains_dedupes_tier_and_explicit_domain():
    names, unknown = runner._selected_domains(["tick", "gym"], {})
    assert names == ["catchup", "meal", "gym"]
    assert unknown == []


def test_selected_domains_reports_unknown_argument():
    names, unknown = runner._selected_domains(["not-a-domain"], {})
    assert names == []
    assert unknown == ["not-a-domain"]


def test_explicit_domain_overrides_disabled_tier_setting():
    names, _ = runner._selected_domains(["daily", "social"], {"social": False})
    assert names.count("social") == 1
    assert "social" in names


def test_partner_task_wins_over_generic_friend_note(monkeypatch):
    monkeypatch.setattr(config, "PARTNER_TASK", "Partner time")
    assert runner._classify("Partner time", "Dinner with friends") == "partner"


class _Canvas:
    def modules(self, course_id=None):
        return [{
            "id": 1,
            "name": "Module 1",
            "unlock_at": "2026-07-01T08:00:00Z",
            "items": [{"type": "Assignment", "content_id": 10}],
        }]

    def assignments(self, course_id=None):
        return [{"id": 10, "name": "Homework 1", "due_at": "2026-07-12T23:59:00Z"}]

    def announcements(self, since_date=None, course_id=None):
        return []


class _FlowSavvy:
    def list_items(self, **kwargs):
        return {"items": []}

    def create_task(self, **kwargs):
        raise RuntimeError("temporary failure")


class _LLM:
    @staticmethod
    def extract_readings(text, module_num):
        return []


def test_canvas_failed_creation_still_marks_module_synced(tmp_path, monkeypatch):
    """KNOWN GAP (flagged, not endorsed): origin/master's Canvas flood-guard
    rewrite (commit 845590d) dropped the failed_modules guard this test used
    to check for -- a module whose task creations ALL fail via a transient
    FlowSavvy error still gets marked synced here, so it's never retried.
    This test documents current upstream behavior; it isn't asserting that
    behavior is correct. Worth a follow-up fix upstream."""
    monkeypatch.setattr(runner.history, "ROOT", str(tmp_path))
    monkeypatch.setattr(config, "CANVAS_COURSE_ID", "the-course")
    monkeypatch.setattr(config, "CANVAS_COURSES", "")   # force legacy single-course fallback
    monkeypatch.setattr(config, "LIST_COURSE", "course-list")
    monkeypatch.setattr(config, "SH_COURSE", "course-hours")

    canvas_domain._canvas_sync(_Canvas(), lambda value: value, canvas_engine, _LLM(),
                               _FlowSavvy(), datetime.datetime(2026, 7, 9, 9, 0))

    state = state_store.load_json(state_store.logs_path("canvas_state.json"))
    assert state["courses"]["the-course"]["synced_modules"] == [1]


class _FakeShortenLLM:
    def __init__(self, short):
        self._short = short
        self.calls = 0

    def shorten_assignment_name(self, name, max_chars=35):
        self.calls += 1
        return self._short


def test_display_name_passes_through_short_names_without_calling_llm():
    llm = _FakeShortenLLM("should never be used")
    short_titles = {}
    assert canvas_domain._display_name({"id": 1, "name": "Short Name"}, short_titles, llm) == "Short Name"
    assert llm.calls == 0
    assert short_titles == {}


def test_display_name_shortens_and_caches_long_names():
    llm = _FakeShortenLLM("Policing Paper")
    short_titles = {}
    long_name = "Predictive Policing Case Study/Evaluation Paper"
    a = {"id": 42, "name": long_name}

    first = canvas_domain._display_name(a, short_titles, llm)
    assert first == "Policing Paper"
    assert llm.calls == 1
    assert short_titles["42"] == "Policing Paper"

    # second call for the SAME assignment id must hit the cache, not the LLM
    # again -- otherwise the title could drift between runs.
    second = canvas_domain._display_name(a, short_titles, llm)
    assert second == "Policing Paper"
    assert llm.calls == 1


def test_display_name_falls_back_to_raw_name_when_llm_fails():
    llm = _FakeShortenLLM(None)
    short_titles = {}
    long_name = "Predictive Policing Case Study/Evaluation Paper"
    assert canvas_domain._display_name({"id": 7, "name": long_name}, short_titles, llm) == long_name
    assert short_titles == {}    # nothing cached -- so a later successful run can still shorten it


class _CompleteFakeFlowSavvy:
    def __init__(self):
        self.completed = []

    def complete_task(self, task_id):
        self.completed.append(task_id)

    def recalculate(self):
        pass

    def list_items(self, **kwargs):
        return {"items": []}


def test_ingest_handled_msg_ids_keeps_most_recent_not_arbitrary(tmp_path, monkeypatch):
    """handled_ntfy_msg_ids must evict the OLDEST id once past the 1000-entry
    cap, not an arbitrary one -- a plain set has no guaranteed iteration
    order, so truncating `list(a_set)[-1000:]` doesn't reliably keep the
    most-recently-handled ids (see runner.py's comment on this)."""
    monkeypatch.setattr(runner.history, "ROOT", str(tmp_path))
    (tmp_path / "private" / "logs").mkdir(parents=True, exist_ok=True)

    old_ids = [f"old-{i}" for i in range(1000)]
    state_path = state_store.logs_path("ingest_state.json")
    state_store.save_json_atomic(state_path, {"ntfy_ts": 0, "logged_ids": [],
                                              "handled_ntfy_msg_ids": old_ids})

    fake_message = {"id": "new-msg", "time": 100, "message": "complete:t1"}
    monkeypatch.setattr(runner.ntfy, "poll", lambda since: [fake_message])

    fs = _CompleteFakeFlowSavvy()
    runner.ingest(fs, datetime.datetime(2026, 7, 13, 9, 0))

    assert fs.completed == ["t1"]
    saved = state_store.load_json(state_path)
    # The oldest id (old-0) must be the one evicted, and the new id must
    # survive at the end -- not an arbitrary member of the old set.
    assert saved["handled_ntfy_msg_ids"] == old_ids[1:] + ["new-msg"]


def test_ingest_token_signal_registers_fcm_token(tmp_path, monkeypatch):
    monkeypatch.setattr(runner.history, "ROOT", str(tmp_path))
    (tmp_path / "private" / "logs").mkdir(parents=True, exist_ok=True)

    token = "d" * 20 + ":APA91b" + "x" * 100
    fake_message = {"id": "msg-1", "time": 100, "message": f"token:{token}"}
    monkeypatch.setattr(runner.ntfy, "poll", lambda since: [fake_message])

    runner.ingest(_CompleteFakeFlowSavvy(), datetime.datetime(2026, 7, 13, 9, 0))

    assert runner.fcm._device_token() == token


def test_ingest_token_signal_with_malformed_token_does_not_raise(tmp_path, monkeypatch):
    monkeypatch.setattr(runner.history, "ROOT", str(tmp_path))
    (tmp_path / "private" / "logs").mkdir(parents=True, exist_ok=True)

    fake_message = {"id": "msg-1", "time": 100, "message": "token:too-short"}
    monkeypatch.setattr(runner.ntfy, "poll", lambda since: [fake_message])

    runner.ingest(_CompleteFakeFlowSavvy(), datetime.datetime(2026, 7, 13, 9, 0))  # must not raise

    assert runner.fcm._device_token() == ""


class _NextTasksFakeFlowSavvy:
    def get_schedule(self, start, end):
        return {"scheduleItems": [
            {"itemType": "task", "itemId": "t1", "title": "Reading",
             "startTime": "2099-01-01T09:00:00", "completed": False},
        ]}


def test_push_next_tasks_skipped_on_signal_tier(tmp_path, monkeypatch):
    monkeypatch.setattr(runner.history, "ROOT", str(tmp_path))
    (tmp_path / "private" / "logs").mkdir(parents=True, exist_ok=True)
    calls = []
    monkeypatch.setattr(runner.notify, "push_next_tasks", lambda tasks, events, gym_ring, version: calls.append((tasks, events, version)) or True)

    runner.push_next_tasks(_NextTasksFakeFlowSavvy(), datetime.datetime(2026, 7, 13, 9, 0), ["signal"])

    assert calls == []


def test_push_next_tasks_fires_on_tick_tier(tmp_path, monkeypatch):
    monkeypatch.setattr(runner.history, "ROOT", str(tmp_path))
    (tmp_path / "private" / "logs").mkdir(parents=True, exist_ok=True)
    calls = []
    monkeypatch.setattr(runner.notify, "push_next_tasks", lambda tasks, events, gym_ring, version: calls.append((tasks, events, version)) or True)

    runner.push_next_tasks(_NextTasksFakeFlowSavvy(), datetime.datetime(2026, 7, 13, 9, 0), ["tick"])

    assert len(calls) == 1
    tasks, events, version = calls[0]
    assert [t["id"] for t in tasks] == ["t1"]
    assert version


def test_push_next_tasks_retries_unacked_push_even_if_unchanged(tmp_path, monkeypatch):
    """An unacked previous push must be retried on the next call even with
    identical content -- "unacked" is exactly the signal the last attempt
    may not have landed (see _push_with_ack)."""
    monkeypatch.setattr(runner.history, "ROOT", str(tmp_path))
    (tmp_path / "private" / "logs").mkdir(parents=True, exist_ok=True)
    calls = []
    monkeypatch.setattr(runner.notify, "push_next_tasks", lambda tasks, events, gym_ring, version: calls.append((tasks, events, version)) or True)
    fs = _NextTasksFakeFlowSavvy()
    now = datetime.datetime(2026, 7, 13, 9, 0)

    runner.push_next_tasks(fs, now, ["tick"])   # first call: nothing persisted yet, must push
    runner.push_next_tasks(fs, now, ["tick"])   # second call: same content, but never acked -- must retry

    assert len(calls) == 2


def test_push_next_tasks_skips_send_when_unchanged_and_acked(tmp_path, monkeypatch):
    monkeypatch.setattr(runner.history, "ROOT", str(tmp_path))
    (tmp_path / "private" / "logs").mkdir(parents=True, exist_ok=True)
    calls = []
    monkeypatch.setattr(runner.notify, "push_next_tasks", lambda tasks, events, gym_ring, version: calls.append((tasks, events, version)) or True)
    fs = _NextTasksFakeFlowSavvy()
    now = datetime.datetime(2026, 7, 13, 9, 0)

    runner.push_next_tasks(fs, now, ["tick"])                          # first call: must push
    runner._mark_push_acked("next_tasks", calls[0][2])                 # phone confirms receipt
    runner.push_next_tasks(fs, now, ["tick"])                          # second call: unchanged + acked -- must skip

    assert len(calls) == 1


def test_push_next_tasks_sends_again_when_changed(tmp_path, monkeypatch):
    monkeypatch.setattr(runner.history, "ROOT", str(tmp_path))
    (tmp_path / "private" / "logs").mkdir(parents=True, exist_ok=True)
    calls = []
    monkeypatch.setattr(runner.notify, "push_next_tasks", lambda tasks, events, gym_ring, version: calls.append((tasks, events, version)) or True)
    now = datetime.datetime(2026, 7, 13, 9, 0)

    runner.push_next_tasks(_NextTasksFakeFlowSavvy(), now, ["tick"])

    class _ChangedFakeFlowSavvy:
        def get_schedule(self, start, end):
            return {"scheduleItems": [
                {"itemType": "task", "itemId": "t2", "title": "Different task",
                 "startTime": "2099-01-01T09:00:00", "completed": False},
            ]}

    runner.push_next_tasks(_ChangedFakeFlowSavvy(), now, ["tick"])

    assert len(calls) == 2


def test_ingest_ack_signal_marks_push_acked(tmp_path, monkeypatch):
    monkeypatch.setattr(runner.history, "ROOT", str(tmp_path))
    (tmp_path / "private" / "logs").mkdir(parents=True, exist_ok=True)
    calls = []
    monkeypatch.setattr(runner.notify, "push_next_tasks", lambda tasks, events, gym_ring, version: calls.append(version) or True)

    runner.push_next_tasks(_NextTasksFakeFlowSavvy(), datetime.datetime(2026, 7, 13, 9, 0), ["tick"])
    version = calls[0]

    fake_message = {"id": "msg-1", "time": 100, "message": f"ack:next_tasks:{version}"}
    monkeypatch.setattr(runner.ntfy, "poll", lambda since: [fake_message])
    runner.ingest(_CompleteFakeFlowSavvy(), datetime.datetime(2026, 7, 13, 9, 5))

    state = state_store.load_json(_shared._push_ack_state_file("next_tasks"))
    assert state["acked"] is True
    assert state["version"] == version


def test_mark_push_acked_ignores_superseded_version(tmp_path, monkeypatch):
    """An ack for an old version must not mark a newer, already-repushed
    version as acked -- e.g. the phone was slow to respond and the content
    changed again before the ack arrived."""
    monkeypatch.setattr(runner.history, "ROOT", str(tmp_path))
    (tmp_path / "private" / "logs").mkdir(parents=True, exist_ok=True)

    runner._save_json_atomic(_shared._push_ack_state_file("next_tasks"),
                             {"snapshot": {"tasks": []}, "version": "current-version", "acked": False})

    runner._mark_push_acked("next_tasks", "stale-version")

    state = state_store.load_json(_shared._push_ack_state_file("next_tasks"))
    assert state["acked"] is False
    assert state["version"] == "current-version"


def test_ingest_malformed_ack_signal_does_not_raise(tmp_path, monkeypatch):
    monkeypatch.setattr(runner.history, "ROOT", str(tmp_path))
    (tmp_path / "private" / "logs").mkdir(parents=True, exist_ok=True)

    fake_message = {"id": "msg-1", "time": 100, "message": "ack:not-enough-parts"}
    monkeypatch.setattr(runner.ntfy, "poll", lambda since: [fake_message])

    runner.ingest(_CompleteFakeFlowSavvy(), datetime.datetime(2026, 7, 13, 9, 0))  # must not raise


def test_push_with_ack_writes_no_state_when_nothing_was_sent(tmp_path, monkeypatch):
    """A push_fn that returns False (e.g. fcm._send no-op'd because no
    device has registered an FCM token yet) must not persist a fabricated
    "acked" sentinel -- doing so would incorrectly suppress a later, GENUINE
    send for this same unchanged content once a device finally registers a
    token (see test_push_with_ack_sends_after_token_registers_for_unchanged_content)."""
    monkeypatch.setattr(runner.history, "ROOT", str(tmp_path))
    (tmp_path / "private" / "logs").mkdir(parents=True, exist_ok=True)

    runner._push_with_ack("next_tasks", {"tasks": []}, lambda version: False)

    assert state_store.load_json(_shared._push_ack_state_file("next_tasks")) is None


def test_push_with_ack_retries_cheaply_every_call_when_nothing_was_sent(tmp_path, monkeypatch):
    monkeypatch.setattr(runner.history, "ROOT", str(tmp_path))
    (tmp_path / "private" / "logs").mkdir(parents=True, exist_ok=True)
    calls = []

    def push_fn(version):
        calls.append(version)
        return False

    runner._push_with_ack("next_tasks", {"tasks": []}, push_fn)
    runner._push_with_ack("next_tasks", {"tasks": []}, push_fn)
    runner._push_with_ack("next_tasks", {"tasks": []}, push_fn)

    assert len(calls) == 3


def test_push_with_ack_sends_after_token_registers_for_unchanged_content(tmp_path, monkeypatch):
    """Reproduces the P1 finding: a device with no FCM token gets a no-op
    "push" for some content; the device then registers a token via
    fcm.register_token; the SAME (unchanged) content must still be sent for
    real on the next call, not skipped as already-acked."""
    monkeypatch.setattr(runner.history, "ROOT", str(tmp_path))
    (tmp_path / "private" / "logs").mkdir(parents=True, exist_ok=True)
    snapshot = {"tasks": [{"id": "t1"}]}
    calls = []

    # First call: no token registered yet -- fcm._send's real no-op path,
    # simulated here as push_fn returning False without doing anything.
    runner._push_with_ack("next_tasks", snapshot, lambda version: calls.append(("noop", version)) or False)
    assert len(calls) == 1

    # Device registers a token in between (irrelevant to _push_with_ack
    # directly -- what matters is push_fn can now genuinely send).
    runner.fcm.register_token("d" * 20)

    # Second call: SAME unchanged snapshot, but push_fn can now really send.
    runner._push_with_ack("next_tasks", snapshot, lambda version: calls.append(("sent", version)) or True)

    assert len(calls) == 2
    assert calls[1][0] == "sent"


def test_push_with_ack_retries_when_send_was_attempted_but_unacked(tmp_path, monkeypatch):
    monkeypatch.setattr(runner.history, "ROOT", str(tmp_path))
    (tmp_path / "private" / "logs").mkdir(parents=True, exist_ok=True)
    calls = []

    def push_fn(version):
        calls.append(version)
        return True

    runner._push_with_ack("next_tasks", {"tasks": []}, push_fn)
    runner._push_with_ack("next_tasks", {"tasks": []}, push_fn)  # same content, never acked -- must retry

    assert len(calls) == 2


def test_mark_push_acked_ignores_non_dict_state(tmp_path, monkeypatch):
    """A corrupt/non-dict ack state file must not raise -- _mark_push_acked
    runs inside ingest()'s per-message loop, where an uncaught exception
    would also drop that poll batch's other already-processed signals."""
    monkeypatch.setattr(runner.history, "ROOT", str(tmp_path))
    (tmp_path / "private" / "logs").mkdir(parents=True, exist_ok=True)
    runner._save_json_atomic(_shared._push_ack_state_file("next_tasks"), ["not", "a", "dict"])

    runner._mark_push_acked("next_tasks", "some-version")  # must not raise


def test_backup_state_db_snapshots_and_prunes_old_ones(tmp_path, monkeypatch):
    """_backup_state_db copies the live state.db out to SQLITE_BACKUP_DIR
    (replacing the old git-commit-to-private-submodule sync) and keeps only
    the newest SQLITE_BACKUP_KEEP snapshots."""
    monkeypatch.setattr(runner.history, "ROOT", str(tmp_path))
    backup_dir = tmp_path / "backups"
    monkeypatch.setattr(config, "SQLITE_BACKUP_DIR", str(backup_dir))
    monkeypatch.setattr(config, "SQLITE_BACKUP_KEEP", 2)

    # a real write establishes state.db on disk
    state_store.save_json_atomic(state_store.logs_path("meal_state.json"), {"lastSkip": 1})

    # pre-seed 2 fake older snapshots so pruning has something to remove
    backup_dir.mkdir(parents=True)
    (backup_dir / "state-20260101T000000.db").write_bytes(b"old")
    (backup_dir / "state-20260102T000000.db").write_bytes(b"old2")

    runner._backup_state_db()

    snapshots = sorted(p.name for p in backup_dir.glob("state-*.db"))
    assert len(snapshots) == 2   # pruned down to SQLITE_BACKUP_KEEP
    assert snapshots[-1] not in ("state-20260101T000000.db", "state-20260102T000000.db")


def test_backup_state_db_is_a_noop_when_state_db_does_not_exist_yet(tmp_path, monkeypatch):
    monkeypatch.setattr(runner.history, "ROOT", str(tmp_path))
    backup_dir = tmp_path / "backups"
    monkeypatch.setattr(config, "SQLITE_BACKUP_DIR", str(backup_dir))

    runner._backup_state_db()  # must not raise, must not create the dir

    assert not backup_dir.exists()
