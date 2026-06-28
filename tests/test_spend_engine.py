from lifeops.engines import spend_engine

def _event(label, cost, days_until):
    import datetime
    date = (datetime.date.today() + datetime.timedelta(days=days_until)).isoformat()
    return {"label": label, "cost": cost, "date": date, "days_until": days_until}

def test_no_events_no_alert():
    out = spend_engine.plan([], fun_money=200)
    assert out["level"] == "none"

def test_within_budget_no_alert():
    events = [_event("Concert", 40, 5)]
    out = spend_engine.plan(events, fun_money=200)
    assert out["level"] == "none"

def test_over_budget_default_alert():
    events = [_event("Concert", 40, 7), _event("Party", 35, 10)]
    out = spend_engine.plan(events, fun_money=50)
    assert out["level"] == "default"
    assert "$75" in out["text"] or "75" in out["text"]

def test_over_budget_imminent_high_alert():
    events = [_event("Concert", 40, 2)]
    out = spend_engine.plan(events, fun_money=20)
    assert out["level"] == "high"

def test_exactly_at_budget_no_alert():
    events = [_event("Party", 35, 5)]
    out = spend_engine.plan(events, fun_money=35)
    assert out["level"] == "none"

def test_text_includes_labels():
    events = [_event("Madison Square Garden", 80, 5)]
    out = spend_engine.plan(events, fun_money=50)
    assert "Madison Square Garden" in out["text"]

def test_zero_fun_money_triggers_alert():
    events = [_event("Date night", 50, 4)]
    out = spend_engine.plan(events, fun_money=0)
    assert out["level"] != "none"
