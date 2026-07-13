from lifeops import attention


def test_ok_has_a_clear_next_move():
    result = attention.compute({"gym_last_7d": 4, "gym_target": 4,
                                "discretionary_dollars": 200})
    assert result["state"] == "ok"
    assert result["label"] == "OK"
    assert result["reasons"] == []


def test_watch_collects_due_money_and_gym_reasons():
    result = attention.compute({"due_today": ["Submit paper"],
                                "discretionary_dollars": 50,
                                "gym_last_7d": 2, "gym_target": 4})
    assert result["state"] == "watch"
    assert {r["domain"] for r in result["reasons"]} == {"coursework", "money", "gym"}


def test_recent_overdue_is_risk_and_beats_watch():
    result = attention.compute({"overdue": [{"title": "Reading", "due_in_h": -3}],
                                "due_today": ["Quiz"]})
    assert result["state"] == "risk"
    assert result["reasons"][0]["title"] == "Overdue: Reading"


def test_day_overdue_or_system_errors_is_fucked():
    overdue = attention.compute({"overdue": [{"title": "Paper", "due_in_h": -25}]})
    broken = attention.compute({}, {"errors": {"canvas": "expired"}, "age_mins": 2})
    assert overdue["state"] == "fucked"
    assert broken["state"] == "fucked"
    assert broken["reasons"][0]["domain"] == "system"


def test_stale_system_state_is_deterministic():
    assert attention.compute({}, {"age_mins": 31})["state"] == "watch"
    assert attention.compute({}, {"age_mins": 121})["state"] == "risk"


def test_deadline_wins_cross_domain_tie_break():
    result = attention.compute(
        {"overdue": [{"title": "Paper", "due_in_h": -25}]},
        {"errors": {"canvas": "expired"}, "age_mins": 2},
    )
    assert [r["domain"] for r in result["reasons"][:2]] == ["coursework", "system"]


def test_coursework_flood_does_not_crowd_out_other_domains():
    """A pile of overdue coursework (all "fucked", the worst severity) used
    to fill the entire 6-slot cap by itself, silently dropping a genuinely
    urgent reason from any other domain -- confirmed with a real -$125
    discretionary balance producing zero "money" reason. Every domain that
    produced a reason must keep at least its single worst one."""
    overdue = [{"title": f"Reading {i}", "due_in_h": -25} for i in range(8)]
    result = attention.compute({
        "overdue": overdue,
        "discretionary_dollars": -125,
        "gym_last_7d": 0, "gym_target": 4,
    })

    domains = {r["domain"] for r in result["reasons"]}
    assert "money" in domains
    assert "gym" in domains
    money_reason = next(r for r in result["reasons"] if r["domain"] == "money")
    assert money_reason["severity"] == "risk"
    assert len(result["reasons"]) <= 6
