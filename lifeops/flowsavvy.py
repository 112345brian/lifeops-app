"""FlowSavvy REST client.

The MCP connector we used is a thin wrapper over this REST API, so the method
set + payload shapes below mirror exactly what we already saw working.

ENDPOINT PATHS ARE INFERRED from the connector's tool set and REST convention.
Verify them against my.flowsavvy.app/api/docs and adjust _paths below if needed
(base URL + token go in .env).
"""
import time, requests
from . import config

# A transient TLS/TCP blip (connection refused, reset, SSL handshake EOF) means
# the request never reached the server -- retrying is safe regardless of verb.
# A response that came back (even a 5xx) means the server DID see the request,
# so we do NOT retry those here: blindly retrying a non-idempotent POST/PUT
# after an ambiguous server-side response risks creating a duplicate task
# exactly like the title-suffix dedup issue this codebase already had to fix.
_RETRIES = 2
_BACKOFF = 0.5

def _with_retry(fn):
    last = None
    for attempt in range(_RETRIES + 1):
        try:
            return fn()
        except requests.exceptions.ConnectionError as e:
            last = e
            if attempt < _RETRIES:
                time.sleep(_BACKOFF * (2 ** attempt))
    raise last

class FlowSavvy:
    def __init__(self):
        self.base = config.FLOWSAVVY_BASE_URL.rstrip("/")
        self.h = {"Authorization": f"Bearer {config.FLOWSAVVY_TOKEN}",
                  "Content-Type": "application/json"}

    def _get(self, path, **params):
        params = {k: (str(v).lower() if isinstance(v, bool) else v)
                  for k, v in params.items() if v is not None and v != ""}
        def call():
            r = requests.get(f"{self.base}{path}", headers=self.h, params=params, timeout=30)
            r.raise_for_status()
            return r.json()
        return _with_retry(call)

    def _post(self, path, body=None):
        def call():
            r = requests.post(f"{self.base}{path}", headers=self.h, json=body or {}, timeout=30)
            r.raise_for_status()
            return r.json() if r.content else {}
        return _with_retry(call)

    def _put(self, path, body):
        def call():
            r = requests.put(f"{self.base}{path}", headers=self.h, json=body, timeout=30)
            r.raise_for_status()
            return r.json() if r.content else {}
        return _with_retry(call)

    def _delete(self, path, **params):
        def call():
            r = requests.delete(f"{self.base}{path}", headers=self.h, params=params, timeout=30)
            r.raise_for_status()
            return {}
        return _with_retry(call)

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
