"""Build the daily briefing facts and deterministic display text."""
from . import attention, config, gather, notable_events, risk_tracking, weather
from .engines import load_engine


def compose_text(headline, today_event_names, newly_at_risk, notable):
    sections = [headline]
    today_names = {name.strip().lower() for name in today_event_names}
    if today_event_names:
        sections.append("Also today: " + ", ".join(today_event_names) + ".")
    if newly_at_risk:
        sections.append("\n".join(item["phrase"] for item in newly_at_risk))
    notable = [e for e in notable if (e.get("title") or "").strip().lower() not in today_names]
    if notable:
        sections.append("Coming up: " + ", ".join(
            f"{e['title']} ({e['weekday']})" for e in notable))
    return "\n\n".join(sections)


def build(fs, yn, now):
    today = now.date()

    hw = gather.homework_input(fs, now)
    at_risk_items = load_engine.at_risk_assignments(hw)
    crunch_item = load_engine.deadline_crunch_item(gather.deadline_input(fs, now))
    if crunch_item is not None:
        at_risk_items = at_risk_items + [crunch_item]
    newly_at_risk = risk_tracking.newly_at_risk(at_risk_items, now)

    due_today = [a["title"] for a in hw if 0 <= a.get("due_in_h", 1e9) <= 24]
    overdue = [{"title": a["title"], "due_in_h": a.get("due_in_h"),
                "due": a.get("due_iso")}
               for a in hw if a.get("due_in_h", 0) < 0 and a.get("remaining_min", 0) > 0]
    load_7d_h = round(sum(a.get("remaining_min", 0) for a in hw
                          if a.get("due_in_days", 99) <= 7) / 60.0, 1)

    gym_ring = gather.gym_ring_now(fs, now)
    gym_last_7d = gym_ring["gym_last_7d"]
    trained_today = gym_ring["today_done"]

    try:
        sp = gather.spend_input(fs, yn, now)
        current_fun_money = round(sp.get("fun_money", 0))
        fun_money = round(sp.get("net_fun_money", sp.get("fun_money", 0)))
        today_budget = round(sp.get("today_budget", 0))
        ynab_category_balances = {name: round(value)
                                  for name, value in sp.get("ynab_category_balances", {}).items()}
        near = sorted(sp.get("events", []), key=lambda e: e.get("days_until", 99))[:2]
        upcoming = [f"{e['label']} in {e['days_until']}d" for e in near]
        today_event_names = [e["label"] for e in sp.get("events", []) if e.get("days_until") == 0]
    except Exception:
        current_fun_money, fun_money, today_budget, ynab_category_balances, upcoming, today_event_names = (
            None, None, None, {}, [], []
        )

    w = weather.current(now) or {}

    try:
        sleep_min = gather.sleep_minutes_last_night(now)
    except Exception:
        sleep_min = None

    try:
        social = gather.social_input(fs, now)
        partner_days_since = social.get("partner_days")
        friend_days_since = social.get("friend_days")
        partner_days_until = social.get("partner_days_until")
        friend_days_until = social.get("friend_days_until")
    except Exception:
        partner_days_since, friend_days_since = None, None
        partner_days_until, friend_days_until = None, None

    try:
        schedule_items = gather._upcoming_schedule(fs, now)
        known_routine = {t.lower() for t in config.ROUTINE_EVENT_TITLES}
        notable = notable_events.upcoming_notable_events(schedule_items, now, known_routine)
    except Exception as e:
        print(f"[notable_events] fetch/compute failed, showing none this run: {e}")
        notable = []

    facts = {"date": today.isoformat(), "weekday": now.strftime("%A"),
             "coursework_at_risk": [a.get("title") or "" for a in at_risk_items],
             "due_today": due_today,
             "notable_events": notable,
             "overdue": overdue,
             "coursework_hours_next_7d": load_7d_h,
             "gym_last_7d": gym_last_7d, "gym_target": 4,
             "trained_today": trained_today, "gym_ring": gym_ring,
             "discretionary_dollars": fun_money, "discretionary_today_dollars": today_budget,
             "discretionary_current_dollars": current_fun_money,
             "ynab_category_balances": ynab_category_balances,
             "upcoming_paid_events": upcoming,
             "temperature_f": w.get("temp_f"), "weather_high_f": w.get("high_f"),
             "weather_low_f": w.get("low_f"), "weather_condition": w.get("condition"),
             "sleep_minutes": sleep_min,
             "partner_days_since": partner_days_since, "friend_days_since": friend_days_since,
             "partner_days_until": partner_days_until, "friend_days_until": friend_days_until}
    facts["attention"] = attention.compute(facts)
    text = compose_text(facts["attention"]["headline"], today_event_names, newly_at_risk, notable)
    return {"date": today.isoformat(), "text": text, "facts": facts}
