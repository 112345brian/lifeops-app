from lifeops.engines import load_engine

def _assignment(title, due_in_h, remaining_min, progress=0):
    return {"title": title, "due_in_h": due_in_h, "due_in_days": due_in_h / 24,
            "remaining_min": remaining_min, "progress": progress}

def test_no_assignments_no_alerts():
    out = load_engine.plan([])
    assert out["alerts"] == []

def test_heavy_soon_triggers_alert():
    a = _assignment("Problem Set 4", due_in_h=24, remaining_min=180)
    out = load_engine.plan([a])
    assert len(out["alerts"]) == 1
    assert out["alerts"][0][1] == "high"

def test_in_progress_not_flagged():
    # has progress — not a cold start anymore
    a = _assignment("Problem Set 4", due_in_h=24, remaining_min=180, progress=60)
    out = load_engine.plan([a])
    assert out["alerts"] == []

def test_short_assignment_not_flagged():
    # < 120 min remaining — not "heavy"
    a = _assignment("Reading quiz", due_in_h=24, remaining_min=60)
    out = load_engine.plan([a])
    assert out["alerts"] == []

def test_far_deadline_not_flagged():
    # due in 72h — not imminent
    a = _assignment("Final project", due_in_h=72, remaining_min=300)
    out = load_engine.plan([a])
    heavy_alerts = [al for al in out["alerts"] if "soon" in al[0].lower()]
    assert heavy_alerts == []

def test_overbooked_week_triggers_alert():
    # > 25h of work due in 7 days
    assignments = [_assignment(f"HW {i}", due_in_h=100, remaining_min=360) for i in range(5)]
    out = load_engine.plan(assignments)
    overbooked = [al for al in out["alerts"] if "Overbooked" in al[0]]
    assert overbooked

def test_within_capacity_no_overbooked_alert():
    assignments = [_assignment("HW 1", due_in_h=100, remaining_min=300)]  # 5h
    out = load_engine.plan(assignments)
    overbooked = [al for al in out["alerts"] if "Overbooked" in al[0]]
    assert overbooked == []

def test_both_alerts_can_fire():
    heavy = _assignment("Exam prep", due_in_h=24, remaining_min=240)
    bulk = [_assignment(f"HW {i}", due_in_h=100, remaining_min=360) for i in range(5)]
    out = load_engine.plan([heavy] + bulk)
    assert len(out["alerts"]) >= 2
