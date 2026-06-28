"""YNAB REST client (api.ynab.com/v1). Token from .env."""
import requests
from . import config

BASE = "https://api.ynab.com/v1"

class YNAB:
    def __init__(self):
        self.h = {"Authorization": f"Bearer {config.YNAB_TOKEN}"}
        self.b = config.YNAB_BUDGET

    def _get(self, path, **params):
        r = requests.get(f"{BASE}{path}", headers=self.h, params=params, timeout=30)
        r.raise_for_status(); return r.json()["data"]

    def _patch(self, path, body):
        r = requests.patch(f"{BASE}{path}", headers=self.h, json=body, timeout=30)
        r.raise_for_status(); return r.json()["data"]

    def _post(self, path, body):
        r = requests.post(f"{BASE}{path}", headers=self.h, json=body, timeout=30)
        r.raise_for_status(); return r.json()["data"]

    def categories(self):
        return self._get(f"/budgets/{self.b}/categories")["category_groups"]

    def transactions(self, since_date=None, ttype=None):
        p = {}
        if since_date: p["since_date"] = since_date
        if ttype: p["type"] = ttype
        return self._get(f"/budgets/{self.b}/transactions", **p)["transactions"]

    def update_transactions(self, updates):  # [{"id":..,"category_id":..,"approved":..}]
        return self._patch(f"/budgets/{self.b}/transactions", {"transactions": updates})

    def create_transactions(self, txns):
        return self._post(f"/budgets/{self.b}/transactions", {"transactions": txns})

    def month(self, month="current"):
        return self._get(f"/budgets/{self.b}/months/{month}")

    def set_budgeted(self, category_id, milliunits, month="current"):
        return self._patch(f"/budgets/{self.b}/months/{month}/categories/{category_id}",
                           {"category": {"budgeted": milliunits}})
