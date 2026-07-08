"""runner._canvas_sync — canvas_state.json persistence.

Guards the two bugs behind persisting the live-fetched FlowSavvy titles into
`task_titles`:

  1. the 20-day completed_cache eviction was a no-op (evicted titles lived on
     forever inside task_titles, so the "rolling" cache never forgot anything);
  2. task_titles grew without bound across the whole multi-semester course, and
     canvas_engine's 0.93 fuzzy dedup then silently dropped a legitimately-new
     task as a near-duplicate of a long-gone one.

The fix: persist ONLY engine-created titles; keep the live-fetched
incomplete/completed titles and completed_cache in the run-local dedup set only.
"""
import datetime, json, os
import pytest

from lifeops import runner, history, config
from lifeops.engines import canvas_engine

NOW = datetime.datetime(2026, 7, 8, 9, 0, 0)
TODAY = NOW.date().isoformat()


def _strip_html(s):
    return s or ""


class _FakeCanvas:
    """One newly-unlocked module carrying a single reading page."""
    def __init__(self, module_num, reading):
        self._num = module_num
        self._reading = reading

    def modules(self):
        return [{
            "id": 1000 + self._num,   # stable Canvas module id, distinct from the scraped number
            "name": f"Module {self._num}",
            "unlock_at": "2026-01-01T00:00:00Z",   # long unlocked
            "items": [{"type": "Page", "title": "Readings",
                       "page_url": "readings-page"}],
        }]

    def assignments(self):
        return []

    def page(self, slug):
        return {"body": "some html"}

    def announcements(self, since_date=None):
        return []


class _FakeLLM:
    def __init__(self, reading):
        self._reading = reading

    def extract_readings(self, text, module_num):
        return [self._reading]


class _FakeFS:
    def __init__(self, incomplete, completed):
        self._incomplete = incomplete
        self._completed = completed
        self._next_id = 1000

    def list_items(self, itemType=None, listId=None, completed=False, query=None):
        return {"items": self._completed if completed else self._incomplete}

    def create_task(self, **kwargs):
        self._next_id += 1
        return {"id": self._next_id}

    def update_task(self, *a, **k):
        return {}


@pytest.fixture
def sandbox(tmp_path, monkeypatch):
    """Point history.ROOT at a tmp dir and silence side-effecting helpers."""
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    monkeypatch.setattr(config, "LIST_COURSE", "list-course")
    monkeypatch.setattr(runner, "_alert_once", lambda *a, **k: None)
    monkeypatch.setattr(runner, "_touch", lambda *a, **k: None)
    monkeypatch.setattr(history, "append", lambda *a, **k: None)
    sp = os.path.join(str(tmp_path), "logs", "canvas_state.json")
    os.makedirs(os.path.dirname(sp), exist_ok=True)
    return sp


def _write_state(sp, state):
    with open(sp, "w", encoding="utf-8") as f:
        json.dump(state, f)


def _read_state(sp):
    with open(sp, encoding="utf-8") as f:
        return json.load(f)


def _run(fs, llm, cv):
    runner._canvas_sync(cv, _strip_html, canvas_engine, llm, fs, NOW)


def test_live_fetched_titles_are_not_persisted_into_task_titles(sandbox):
    sp = sandbox
    _write_state(sp, {"synced_modules": [2],
                      "task_titles": ["M02: Read Perry, Old Reading"],
                      "completed_cache": {}})

    reading = {"author": "Doe", "title": "Fresh M12 Reading", "type": "article"}
    fs = _FakeFS(
        incomplete=[{"title": "Some Manually Added Task"}],
        completed=[{"title": "M02: Read Perry, NYC Open Data [AS.470.703.81.SU26]",
                    "lastModified": f"{TODAY}T00:00:00Z"}],
    )
    _run(fs, _FakeLLM(reading), _FakeCanvas(12, reading))

    st = _read_state(sp)
    titles = set(st["task_titles"])

    # prior engine-created title survives + this run's creation is recorded
    assert "M02: Read Perry, Old Reading" in titles
    assert "M12: Read Doe, Fresh M12 Reading" in titles
    # live-fetched FlowSavvy titles must NOT bleed into the persisted set
    assert "Some Manually Added Task" not in titles
    assert not any("NYC Open Data" in t for t in titles)
    # but the completed title IS retained in the bounded cache for run-local dedup
    assert any("NYC Open Data" in t for t in st["completed_cache"])


def test_completed_cache_eviction_actually_forgets_across_runs(sandbox):
    # End-to-end proof that the 20-day cache eviction is no longer a no-op.
    #
    # A recurring reading is completed, then months later legitimately recurs.
    # Under the old code the completed title was folded into task_titles on the
    # first run and lived there forever, so the eviction never mattered and the
    # recurrence was silently dropped as a duplicate. With the fix, the title
    # only lives in the bounded completed_cache and is genuinely forgotten after
    # 20 days, so the recurrence is created again.
    sp = sandbox
    reading = {"author": "A", "title": "Recurring Topic", "type": "article"}
    created_title = "M06: Read A, Recurring Topic"

    # ── run 1: the task exists in FlowSavvy as recently completed ──
    fs1 = _FakeFS(incomplete=[],
                  completed=[{"title": created_title, "lastModified": "2026-06-01T00:00:00Z"}])
    # module 6 unlocks and is planned, but its one reading is already present as
    # a completed task, so nothing is created — the run just caches the
    # completed title and marks module 6 synced.
    _write_state(sp, {"synced_modules": [], "task_titles": [], "completed_cache": {}})
    runner._canvas_sync(_FakeCanvas(6, reading), _strip_html, canvas_engine,
                        _FakeLLM(reading), fs1,
                        datetime.datetime(2026, 6, 2, 9, 0, 0))

    st = _read_state(sp)
    assert created_title in st["completed_cache"]      # cached, bounded
    assert created_title not in st["task_titles"]      # NOT persisted permanently

    # ── run 2: 40 days later. The completed task has aged out of FlowSavvy's
    # live completed list, and module 14 legitimately re-issues the same
    # reading. The stale cache entry (2026-06-01) is past the 20-day cutoff. ──
    fs2 = _FakeFS(incomplete=[], completed=[])
    runner._canvas_sync(_FakeCanvas(14, reading), _strip_html, canvas_engine,
                        _FakeLLM(reading), fs2,
                        datetime.datetime(2026, 7, 12, 9, 0, 0))

    st = _read_state(sp)
    assert created_title not in st["completed_cache"]  # forgotten, as intended
    # the recurrence was created (would be suppressed forever under old code)
    assert "M14: Read A, Recurring Topic" in st["task_titles"]
