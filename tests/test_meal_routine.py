"""runner.run_meal -- weekly due-gate (now via the shared routine.py
primitive) plus the grocery-then-cook dependency chain and skip mechanism.
No dedicated test file existed for this domain before the v1 routine
consolidation; this locks down the existing behavior, not new behavior."""
import datetime

import pytest

from lifeops import config, history, runner
from lifeops.domains import household


NOW = datetime.datetime(2026, 7, 8, 9, 0, 0)


class _FakeFS:
    def __init__(self, open_titled=()):
        self._open_titled = set(open_titled)
        self.created = []   # kwargs per create_task call
        self.deleted = []   # ids

    def list_items(self, itemType=None, query=None, completed=False):
        if completed:
            return {"items": []}
        items = []
        if query == "Meal prep" and "Meal prep" in self._open_titled:
            items.append({"id": "mp1", "title": "Meal prep"})
        if query is None:
            for title in self._open_titled:
                items.append({"id": title.lower().replace(" ", "-"), "title": title,
                             "notes": "LifeOps"})
        return {"items": items}

    def create_task(self, **kwargs):
        tid = f"t{len(self.created)}"
        self.created.append(kwargs)
        return {"id": tid}

    def delete_item(self, item_id, **k):
        self.deleted.append(item_id)
        return {}


@pytest.fixture
def sandbox(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "LIST_PERSONAL", "list-personal")
    monkeypatch.setattr(config, "SH_PERSONAL", "sh-personal")
    monkeypatch.setattr(config, "PRIO_MEAL", "normal")
    monkeypatch.setattr(household, "_touch", lambda *a, **k: None)
    monkeypatch.setattr(household, "_alert_once", lambda *a, **k: None)
    monkeypatch.setattr(runner.ntfy, "poll", lambda since: [])
    return tmp_path


def test_not_due_when_recently_handled(sandbox, monkeypatch):
    monkeypatch.setattr(history, "last", lambda action: "2026-07-05T09:00:00")  # 3 days ago
    fs = _FakeFS()

    household.run_meal(fs, object(), NOW)

    assert fs.created == []


def test_due_at_six_days_creates_groceries_and_meal_prep(sandbox, monkeypatch):
    monkeypatch.setattr(history, "last", lambda action: "2026-07-02T09:00:00")  # 6 days ago
    fs = _FakeFS()

    household.run_meal(fs, object(), NOW)

    titles = [c["title"] for c in fs.created]
    assert titles == ["Groceries", "Meal prep"]


def test_due_when_never_logged(sandbox, monkeypatch):
    monkeypatch.setattr(history, "last", lambda action: None)
    fs = _FakeFS()

    household.run_meal(fs, object(), NOW)

    assert [c["title"] for c in fs.created] == ["Groceries", "Meal prep"]


def test_meal_prep_is_blocked_by_groceries_task_id(sandbox, monkeypatch):
    monkeypatch.setattr(history, "last", lambda action: None)
    fs = _FakeFS()

    household.run_meal(fs, object(), NOW)

    groceries, meal_prep = fs.created
    assert groceries["title"] == "Groceries"
    assert meal_prep["blockedByIds"] == ["t0"]


def test_already_planned_is_a_noop(sandbox, monkeypatch):
    monkeypatch.setattr(history, "last", lambda action: None)
    fs = _FakeFS(open_titled=["Meal prep"])

    household.run_meal(fs, object(), NOW)

    assert fs.created == []


def test_skip_deletes_open_lifeops_meal_tasks_and_counts_as_handled(sandbox, monkeypatch):
    monkeypatch.setattr(history, "last", lambda action: None)  # due
    monkeypatch.setattr(runner.ntfy, "poll", lambda since: [{"message": "meal-skip"}])
    logged = []
    monkeypatch.setattr(history, "append", lambda action, **k: logged.append((action, k)))
    fs = _FakeFS(open_titled=["Groceries", "Meal prep"])

    household.run_meal(fs, object(), NOW)

    assert set(fs.deleted) == {"groceries", "meal-prep"}
    assert logged == [("meal", {"source": "skipped"})]
    assert fs.created == []
