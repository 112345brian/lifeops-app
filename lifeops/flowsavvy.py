"""FlowSavvy REST client.

The MCP connector we used is a thin wrapper over this REST API, so the method
set + payload shapes below mirror exactly what we already saw working.

ENDPOINT PATHS ARE INFERRED from the connector's tool set and REST convention.
Verify them against my.flowsavvy.app/api/docs and adjust _paths below if needed
(base URL + token go in .env).
"""
import collections, threading, time, requests
from . import config

# A transient TLS/TCP blip (connection refused, reset, SSL handshake EOF) means
# the request never reached the server -- retrying is safe regardless of verb.
# A response that came back (even a 5xx) means the server DID see the request,
# so we do NOT retry those here: blindly retrying a non-idempotent POST/PUT
# after an ambiguous server-side response risks creating a duplicate task
# exactly like the title-suffix dedup issue this codebase already had to fix.
#
# FlowSavvy exposes NO rate-limit headers at all (no X-RateLimit-*, and 429
# responses carry no Retry-After either) -- a one-time bulk backfill (the
# whole semester's Canvas backlog, 50+ creates in one run) blew straight
# through whatever the real limit is and silently dropped 11 tasks
# (2026-09-03). A live read-only probe against the real account that day
# measured the actual behavior: roughly 30 requests/60s before a 429, and
# ~45-60s before it clears again once tripped -- both far worse than this
# client's old backoff (maxed out around 15s total) could survive.
#
# _throttle() below is the real fix: a global sliding-window limiter that
# paces EVERY outgoing request (across every FlowSavvy instance -- creating
# a fresh FlowSavvy() per call is common in this codebase, so the limiter
# state must live at module level, not per-instance) to stay under the
# measured limit with margin, so a burst self-paces instead of ever
# erroring. _RETRIES/_BACKOFF below are just the safety net for the
# remaining case the throttle can't cover -- another process/session
# sharing the same account token and consuming budget concurrently -- sized
# to the measured ~60s real cooldown instead of guessing short.
_RATE_LIMIT_MAX = 20        # requests allowed per rolling window (measured safe ceiling ~30/60s; keep margin)
_RATE_LIMIT_WINDOW = 60.0   # seconds
_request_times = collections.deque()
_rate_lock = threading.Lock()

def _throttle():
    while True:
        with _rate_lock:
            now = time.monotonic()
            while _request_times and now - _request_times[0] > _RATE_LIMIT_WINDOW:
                _request_times.popleft()
            if len(_request_times) < _RATE_LIMIT_MAX:
                _request_times.append(now)
                return
            wait = _RATE_LIMIT_WINDOW - (now - _request_times[0]) + 0.05
        time.sleep(wait)

_RETRIES = 5
_BACKOFF = 2.0

def _with_retry(fn):
    last = None
    for attempt in range(_RETRIES + 1):
        _throttle()
        try:
            return fn()
        except requests.exceptions.ConnectionError as e:
            last = e
            if attempt < _RETRIES:
                time.sleep(_BACKOFF * (2 ** attempt))
        except requests.exceptions.HTTPError as e:
            # A 429 is safe to retry even for POST/PUT (unlike other
            # non-2xx responses): the server is saying it REJECTED the
            # request before doing anything, not that it processed it
            # ambiguously -- the concern the ConnectionError-only policy
            # above exists for doesn't apply here. Every gather.py/runner.py
            # call site currently swallows a persistent failure to an empty
            # result with no distinction from genuine emptiness, so retrying
            # transient rate-limiting here (rather than at every call site)
            # is the cheapest place to reduce how often that happens.
            if e.response is not None and e.response.status_code == 429 and attempt < _RETRIES:
                last = e
                retry_after = e.response.headers.get("Retry-After")
                try:
                    delay = float(retry_after) if retry_after else _BACKOFF * (2 ** attempt)
                except ValueError:
                    delay = _BACKOFF * (2 ** attempt)
                time.sleep(delay)
                continue
            raise
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
    def list_items(self, **params):
        """/items is paginated (a nextPageToken means more results exist) --
        every caller in this codebase treats the returned "items" list as
        the COMPLETE answer for dedup/lookup purposes (seen_titles,
        seen_source_ids, due-date rechecks, ...), so silently returning only
        page 1 here is worse than returning nothing: on 2026-09-03 a course
        list crossing the page boundary made an already-created task look
        "missing" from a diff against live state, and the resulting "fix"
        created a real duplicate before the truncation was caught. Follow
        pageToken internally so every caller gets the true complete list
        without having to know pagination exists."""
        items = []
        page_params = dict(params)
        while True:
            r = self._get("/items", **page_params)
            items.extend(r.get("items", []))
            token = r.get("nextPageToken")
            if not token:
                break
            page_params["pageToken"] = token
        return {"items": items}
    def get_schedule(self, start, end):    return self._get("/schedule", startDate=start, endDate=end)
    def get_month(self, month="current"):  return self._get(f"/months/{month}")

    # --- writes ---
    def create_task(self, **body):         return self._post("/tasks", body)
    def update_task(self, task_id, **body):return self._put(f"/tasks/{task_id}", body)
    def complete_task(self, task_id):      return self._post(f"/tasks/{task_id}/complete")
    def create_event(self, **body):        return self._post("/events", body)
    def update_event(self, event_id, **body): return self._put(f"/events/{event_id}", body)
    def delete_item(self, item_id, **p):   return self._delete(f"/items/{item_id}", **p)
    def recalculate(self, reschedule_past=False):
        return self._post("/recalculate", {"reschedulePastTasks": reschedule_past})
