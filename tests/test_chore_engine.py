from lifeops.engines import chore_engine

BASE = {"id": "c1", "title": "Laundry", "notes": "[cycle:7d]",
        "completed_date": "2026-07-06", "durationMinutes": 90,
        "minLengthMinutes": 30, "listId": "6784", "priority": "low",
        "schedulingHoursId": "427988", "dueTime": "20:00"}

def test_creates_next_occurrence():
    out = chore_engine.plan({"completed": [BASE], "processed": []})
    assert len(out["creates"]) == 1
    c = out["creates"][0]
    assert c["title"] == "Laundry"
    assert c["dueDateTime"].startswith("2026-07-13")  # +7 days

def test_due_time_preserved():
    out = chore_engine.plan({"completed": [BASE], "processed": []})
    assert "20:00" in out["creates"][0]["dueDateTime"]

def test_lead_time_capped_at_3():
    # 7-day cycle → lead = min(3, 7-1) = 3
    out = chore_engine.plan({"completed": [BASE], "processed": []})
    assert out["creates"][0]["canBeStartedAt"].startswith("2026-07-10")  # 13 - 3

def test_lead_time_short_cycle():
    # 2-day cycle → lead = min(3, 2-1) = 1
    c = {**BASE, "id": "c2", "notes": "[cycle:2d]"}
    out = chore_engine.plan({"completed": [c], "processed": []})
    # due = 2026-07-08, canBeStartedAt = 2026-07-07
    assert out["creates"][0]["canBeStartedAt"].startswith("2026-07-07")

def test_skips_already_processed():
    out = chore_engine.plan({"completed": [BASE], "processed": ["c1"]})
    assert out["creates"] == []

def test_skips_no_cycle_tag():
    c = {**BASE, "notes": "no tag here"}
    out = chore_engine.plan({"completed": [c], "processed": []})
    assert out["creates"] == []

def test_malformed_date_skips_item_not_batch():
    bad  = {**BASE, "id": "bad", "completed_date": "not-a-date"}
    none = {**BASE, "id": "none", "completed_date": None}
    out = chore_engine.plan({"completed": [bad, none, BASE], "processed": []})
    assert len(out["creates"]) == 1            # good one still cycles
    assert out["creates"][0]["title"] == "Laundry"
    assert "bad" not in out["processed"]       # bad items can retry when fixed

def test_missing_id_or_title_skipped():
    no_id    = {**BASE}; no_id.pop("id")
    no_title = {**BASE, "id": "c9"}; no_title.pop("title")
    out = chore_engine.plan({"completed": [no_id, no_title], "processed": []})
    assert out["creates"] == []

def test_missing_title_marked_processed_not_retried_forever():
    # regression: a record missing "title" (valid cycle tag + completed_date,
    # so not caught by the malformed-date guard) was skipped WITHOUT being
    # added to processed -- meaning it would be refetched and re-skipped on
    # every single future run indefinitely instead of being handled once.
    no_title = {**BASE, "id": "c9"}; no_title.pop("title")
    out = chore_engine.plan({"completed": [no_title], "processed": []})
    assert out["creates"] == []
    assert "c9" in out["processed"], "malformed record must be marked processed, not retried forever"

def test_marks_as_processed():
    out = chore_engine.plan({"completed": [BASE], "processed": []})
    assert "c1" in out["processed"]

def test_multiple_chores():
    c2 = {**BASE, "id": "c2", "title": "Clean bathroom", "notes": "[cycle:21d]"}
    out = chore_engine.plan({"completed": [BASE, c2], "processed": []})
    assert len(out["creates"]) == 2

def test_default_due_time_when_missing():
    c = {**BASE, "dueTime": None}
    out = chore_engine.plan({"completed": [c], "processed": []})
    assert "20:00" in out["creates"][0]["dueDateTime"]
