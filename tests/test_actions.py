"""lifeops.actions — the 'what did LifeOps do?' audit feed + undo tracking,
plus runner._logged_create wiring."""
import json, os
import pytest

from lifeops import actions, history, runner


@pytest.fixture(autouse=True)
def _root(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    os.makedirs(os.path.join(str(tmp_path), "logs"), exist_ok=True)
    return tmp_path


def test_log_and_recent_newest_first():
    actions.log("gym", "scheduled gym", "Gym", item_id="a1", undoable=True)
    actions.log("canvas", "created course task", "M08 Reading", item_id="c1", undoable=True)
    r = actions.recent(10)
    assert [a["op"] for a in r] == ["created course task", "scheduled gym"]  # newest first
    assert all(not a["undone"] for a in r)


def test_undoable_requires_item_id():
    actions.log("gym", "removed stale block", "Gym", item_id=None, undoable=True)
    assert actions.recent(1)[0]["undoable"] is False   # no id → not undoable


def test_mark_undone_flags_the_feed():
    actions.log("canvas", "created course task", "M08 Reading", item_id="c1", undoable=True)
    actions.mark_undone("c1")
    a = actions.recent(1)[0]
    assert a["undone"] is True and a["undoable"] is True


def test_recent_empty_when_no_log():
    assert actions.recent() == []


class _FakeFS:
    def __init__(self):
        self.created = []

    def create_task(self, **kwargs):
        self.created.append(kwargs)
        return {"id": "new-123"}


def test_logged_create_records_an_undoable_action():
    fs = _FakeFS()
    r = runner._logged_create(fs, "meal", op="added groceries",
                              title="Groceries", listId="x")
    assert r == {"id": "new-123"}
    assert fs.created and fs.created[0]["title"] == "Groceries"   # still creates
    a = actions.recent(1)[0]
    assert a["domain"] == "meal" and a["op"] == "added groceries"
    assert a["item_id"] == "new-123" and a["undoable"] is True
