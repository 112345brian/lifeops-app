"""FlowSavvy REST client.

The MCP connector we used is a thin wrapper over this REST API, so the method
set + payload shapes below mirror exactly what we already saw working.

ENDPOINT PATHS ARE INFERRED from the connector's tool set and REST convention.
Verify them against my.flowsavvy.app/api/docs and adjust _paths below if needed
(base URL + token go in .env).
"""
import requests
from . import config

class FlowSavvy:
    def __init__(self):
        self.base = config.FLOWSAVVY_BASE_URL.rstrip("/")
        self.h = {"Authorization": f"Bearer {config.FLOWSAVVY_TOKEN}",
                  "Content-Type": "application/json"}

    def _get(self, path, **params):
        r = requests.get(f"{self.base}{path}", headers=self.h, params=params, timeout=30)
        r.raise_for_status()
        return r.json()

    def _post(self, path, body=None):
        r = requests.post(f"{self.base}{path}", headers=self.h, json=body or {}, timeout=30)
        r.raise_for_status()
        return r.json() if r.content else {}

    def _put(self, path, body):
        r = requests.put(f"{self.base}{path}", headers=self.h, json=body, timeout=30)
        r.raise_for_status()
        return r.json() if r.content else {}

    def _delete(self, path, **params):
        r = requests.delete(f"{self.base}{path}", headers=self.h, params=params, timeout=30)
        r.raise_for_status()
        return {}

    # --- reads (mirror connector: list_calendars / list_items / get_schedule) ---
    def list_calendars(self):              return self._get("/calendars")
    def list_lists(self):                  return self._get("/lists")
    def list_scheduling_hours(self):       return self._get("/scheduling-hours")
    def list_items(self, **params):        return self._get("/items", **params)
    def get_schedule(self, start, end):    return self._get("/schedule", startDate=start, endDate=end)
    def get_month(self, month="current"):  return self._get(f"/months/{month}")

    # --- writes ---
    def create_task(self, **body):         return self._post("/tasks", body)
    def update_task(self, task_id, **body):return self._put(f"/tasks/{task_id}", body)
    def complete_task(self, task_id):      return self._post(f"/tasks/{task_id}/complete")
    def create_event(self, **body):        return self._post("/events", body)
    def delete_item(self, item_id, **p):   return self._delete(f"/items/{item_id}", **p)
    def recalculate(self, reschedule_past=False):
        return self._post("/recalculate", {"reschedulePastTasks": reschedule_past})
