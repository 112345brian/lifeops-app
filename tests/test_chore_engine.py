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
