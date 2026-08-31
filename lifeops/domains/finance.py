"""Finance domain: YNAB categorization, discretionary-spend alerts, cashflow projection."""
import os
from .. import config, gather, history, notify, state_store
from ..engines import ynab_engine
from ._shared import _save_json_atomic, _alert_once, _touch


def run_ynab(fs, yn, now):
    import datetime as _dt
    groups = yn.categories()
    cats = [c for g in groups for c in g["categories"]
            if not c.get("hidden") and not c.get("deleted")]
    since = (now.date() - _dt.timedelta(days=120)).isoformat()
    out = ynab_engine.plan(cats, yn.transactions(since_date=since),
                           yn.transactions(ttype="unapproved"), yn.month(),
                           cover_order=config.YNAB_COVER_ORDER, no_assign=config.YNAB_NO_ASSIGN)
    # novel payees: the ONLY LLM call, and only if a key is configured
    if config.ANTHROPIC_API_KEY and out["novel"]:
        from .. import llm
        skip = set(config.YNAB_NO_ASSIGN)
        names = [c["name"] for c in cats if c["name"] not in skip]
        nid = {c["name"]: c["id"] for c in cats}
        for nv in out["novel"]:
            cat = llm.categorize_unknown(nv["payee"], nv["amount"], names)
            if cat in nid:
                out["categorize"].append({"id": nv["id"], "category_id": nid[cat]})
                if abs(nv["amount"]) * 1000 < ynab_engine.REVIEW:
                    out["approve"].append(nv["id"])
    catmap = {c["id"]: c["category_id"] for c in out["categorize"]}
    appr = set(out["approve"])
    updates = []
    for tid in set(catmap) | appr:
        u = {"id": tid}
        if tid in catmap: u["category_id"] = catmap[tid]
        if tid in appr:   u["approved"] = True
        updates.append(u)
    if updates:
        yn.update_transactions(updates)
    for mv in out["cover"]:
        yn.set_budgeted(mv["category_id"], mv["budgeted"])
    msg = (f"YNAB: categorized {len(out['categorize'])}, approved {len(appr)}, "
           f"{len(out['novel'])} novel, {len(out['holds'])} held, covered {len(out['cover'])} cat(s)")
    print("[ynab] " + msg)
    if appr or out["holds"]:
        notify.alert(msg)


def run_spend(fs, yn, now):
    from ..engines import spend_engine
    inp = gather.spend_input(fs, yn, now)
    out = spend_engine.plan(inp["events"], inp["fun_money"])
    if out["level"] != "none":
        _alert_once("spend", out["text"], out["level"])
    print(f"[spend] {out['level']} (fun=${inp['fun_money']:.0f}, {len(inp['events'])} events)")


def run_cashflow(fs, yn, now):
    """Panel-ONLY forward discretionary-balance projection (Monarch-style). No
    notifications by design — persists a 4-week curve the panel renders, from the
    current discretionary balance minus known upcoming paid social events."""
    try:
        sp = gather.spend_input(fs, yn, now)
    except Exception as e:
        print(f"[cashflow] gather error: {e}"); return
    bal = round(sp.get("fun_money", 0))
    events = sorted(sp.get("events", []), key=lambda e: e.get("days_until", 999))
    running, weeks = bal, []
    for w in range(4):
        spent = sum(e.get("cost", 0) for e in events
                    if w * 7 <= e.get("days_until", 999) < (w + 1) * 7)
        running -= spent
        weeks.append({"week": w + 1, "spent": spent, "balance": round(running)})
    proj = {"date": now.date().isoformat(), "start_balance": bal, "weeks": weeks,
            "dips_below_zero": any(wk["balance"] < 0 for wk in weeks),
            "events": [{"label": e.get("label"), "days_until": e.get("days_until"),
                        "cost": e.get("cost"), "item_id": e.get("item_id"),
                        "item_type": e.get("item_type")} for e in events[:6]]}
    bp = os.path.join(history.ROOT, "private", "logs", "cashflow.json")
    os.makedirs(os.path.dirname(bp), exist_ok=True)
    _save_json_atomic(bp, proj)
    print(f"[cashflow] 4wk projected; dips={proj['dips_below_zero']}")
