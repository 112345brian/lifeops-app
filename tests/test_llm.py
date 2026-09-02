"""lifeops/llm.py -- the only place the LLM is used. Previously had zero
direct test coverage (callers' tests all mock llm.* out entirely); these
exercise the JSON-boundary parsing, error handling, and usage logging
against a fake Anthropic client."""
import json

from lifeops import config, history, llm, state_store


class _FakeMessage:
    def __init__(self, text, input_tokens=10, output_tokens=20, stop_reason="end_turn"):
        self.content = [_FakeContent(text)]
        self.usage = _FakeUsage(input_tokens, output_tokens)
        self.stop_reason = stop_reason


class _FakeContent:
    def __init__(self, text):
        self.text = text


class _FakeUsage:
    def __init__(self, input_tokens, output_tokens):
        self.input_tokens = input_tokens
        self.output_tokens = output_tokens


class _FakeMessages:
    def __init__(self, responses):
        self._responses = list(responses)
        self.calls = []

    def create(self, **kwargs):
        self.calls.append(kwargs)
        return self._responses.pop(0) if len(self._responses) > 1 else self._responses[-1]


class _FakeClient:
    def __init__(self, responses):
        self.messages = _FakeMessages(responses)


def _install_fake_client(monkeypatch, *texts, stop_reasons=None):
    stop_reasons = stop_reasons or ["end_turn"] * len(texts)
    responses = [_FakeMessage(t, stop_reason=sr) for t, sr in zip(texts, stop_reasons)]
    fake = _FakeClient(responses)
    monkeypatch.setattr(llm, "_c", lambda: fake)
    return fake


def _configure(monkeypatch, tmp_path):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))
    monkeypatch.setattr(config, "ANTHROPIC_API_KEY", "x")


# ---- categorize_unknown ----

def test_categorize_unknown_returns_valid_category(tmp_path, monkeypatch):
    _configure(monkeypatch, tmp_path)
    _install_fake_client(monkeypatch, '{"category": "Groceries"}')

    assert llm.categorize_unknown("Trader Joe's", 42.10, ["Groceries", "Dining"]) == "Groceries"


def test_categorize_unknown_rejects_category_not_in_allowed_list(tmp_path, monkeypatch):
    """The model inventing a category outside the allowed list must not
    leak through -- category_names is the actual budget's category set."""
    _configure(monkeypatch, tmp_path)
    _install_fake_client(monkeypatch, '{"category": "Made Up Category"}')

    assert llm.categorize_unknown("Weird Payee", 5.0, ["Groceries", "Dining"]) is None


def test_categorize_unknown_returns_none_for_null_category(tmp_path, monkeypatch):
    _configure(monkeypatch, tmp_path)
    _install_fake_client(monkeypatch, '{"category": null}')

    assert llm.categorize_unknown("???", 1.0, ["Groceries"]) is None


def test_categorize_unknown_returns_none_on_malformed_json(tmp_path, monkeypatch):
    _configure(monkeypatch, tmp_path)
    _install_fake_client(monkeypatch, "not json at all")

    assert llm.categorize_unknown("Payee", 1.0, ["Groceries"]) is None


def test_categorize_unknown_tolerates_prose_around_the_json(tmp_path, monkeypatch):
    """Finds the {...} span even if the model wraps it in commentary."""
    _configure(monkeypatch, tmp_path)
    _install_fake_client(monkeypatch, 'Sure, here you go: {"category": "Dining"} hope that helps!')

    assert llm.categorize_unknown("Cafe", 12.0, ["Groceries", "Dining"]) == "Dining"


# ---- extract_readings ----

def test_extract_readings_parses_valid_array(tmp_path, monkeypatch):
    _configure(monkeypatch, tmp_path)
    payload = json.dumps([{"author": "Smith, J.", "title": "Intro", "type": "article", "estimated_minutes": 25}])
    _install_fake_client(monkeypatch, payload)

    out = llm.extract_readings("some page text", 3)

    assert out == [{"author": "Smith, J.", "title": "Intro", "type": "article", "estimated_minutes": 25}]


def test_extract_readings_returns_empty_list_on_malformed_json(tmp_path, monkeypatch):
    _configure(monkeypatch, tmp_path)
    _install_fake_client(monkeypatch, "not an array")

    assert llm.extract_readings("page text", 1) == []


def test_extract_readings_returns_empty_list_when_client_raises(tmp_path, monkeypatch):
    _configure(monkeypatch, tmp_path)

    class _Raising:
        class messages:
            @staticmethod
            def create(**kwargs):
                raise ConnectionError("network down")

    monkeypatch.setattr(llm, "_c", lambda: _Raising())

    assert llm.extract_readings("page text", 1) == []


# ---- propose_assignment_phases ----

def test_propose_assignment_phases_parses_valid_array(tmp_path, monkeypatch):
    _configure(monkeypatch, tmp_path)
    payload = json.dumps([
        {"name": "Pull NYC Open Data", "minutes": 40},
        {"name": "Clean & Explore", "minutes": 90},
        {"name": "Write Findings", "minutes": 60},
    ])
    _install_fake_client(monkeypatch, payload)

    out = llm.propose_assignment_phases("NYC Open Data Analysis", "Use the NYC open data portal...",
                                        "assignment", 3)
    assert out == [
        {"name": "Pull NYC Open Data", "minutes": 40},
        {"name": "Clean & Explore", "minutes": 90},
        {"name": "Write Findings", "minutes": 60},
    ]


def test_propose_assignment_phases_rounds_float_minutes_to_int(tmp_path, monkeypatch):
    _configure(monkeypatch, tmp_path)
    payload = json.dumps([{"name": "A", "minutes": 40.6}, {"name": "B", "minutes": 30},
                          {"name": "C", "minutes": 25.2}])
    _install_fake_client(monkeypatch, payload)

    out = llm.propose_assignment_phases("X", "instructions", "assignment", 3)
    assert [p["minutes"] for p in out] == [41, 30, 25]
    assert all(isinstance(p["minutes"], int) for p in out)


def test_propose_assignment_phases_rejects_wrong_count(tmp_path, monkeypatch):
    _configure(monkeypatch, tmp_path)
    _install_fake_client(monkeypatch, json.dumps([{"name": "Only", "minutes": 40}]))

    assert llm.propose_assignment_phases("X", "instructions", "assignment", 3) is None


def test_propose_assignment_phases_rejects_missing_or_bad_minutes(tmp_path, monkeypatch):
    _configure(monkeypatch, tmp_path)
    for bad_payload in (
        [{"name": "A"}, {"name": "B", "minutes": 30}, {"name": "C", "minutes": 20}],       # missing minutes
        [{"name": "A", "minutes": 0}, {"name": "B", "minutes": 30}, {"name": "C", "minutes": 20}],  # non-positive
        [{"name": "A", "minutes": "forty"}, {"name": "B", "minutes": 30}, {"name": "C", "minutes": 20}],  # non-numeric
    ):
        _install_fake_client(monkeypatch, json.dumps(bad_payload))
        assert llm.propose_assignment_phases("X", "instructions", "assignment", 3) is None


def test_propose_assignment_phases_rejects_non_object_items(tmp_path, monkeypatch):
    _configure(monkeypatch, tmp_path)
    # includes the OLD plain-string format (pre-minutes-estimate) -- must be
    # rejected, not silently accepted with a missing/default duration.
    _install_fake_client(monkeypatch, json.dumps(["Fine", 2, "Also fine"]))
    assert llm.propose_assignment_phases("X", "instructions", "assignment", 3) is None


def test_propose_assignment_phases_returns_none_on_malformed_json(tmp_path, monkeypatch):
    _configure(monkeypatch, tmp_path)
    _install_fake_client(monkeypatch, "not json")

    assert llm.propose_assignment_phases("X", "instructions", "assignment", 3) is None


def test_propose_assignment_phases_returns_none_on_client_error(tmp_path, monkeypatch):
    _configure(monkeypatch, tmp_path)

    class _Raising:
        class messages:
            @staticmethod
            def create(**kwargs):
                raise ConnectionError("network down")

    monkeypatch.setattr(llm, "_c", lambda: _Raising())

    assert llm.propose_assignment_phases("X", "instructions", "assignment", 3) is None


def test_propose_assignment_phases_skips_call_for_single_phase_or_empty_description(tmp_path, monkeypatch):
    _configure(monkeypatch, tmp_path)
    fake = _install_fake_client(monkeypatch, json.dumps(["should", "not", "be", "used"]))

    assert llm.propose_assignment_phases("X", "instructions", "assignment", 1) is None
    assert llm.propose_assignment_phases("X", "", "assignment", 3) is None
    assert fake.messages.calls == []   # never actually called the model


# ---- shorten_assignment_name ----

def test_shorten_assignment_name_returns_model_text(tmp_path, monkeypatch):
    _configure(monkeypatch, tmp_path)
    _install_fake_client(monkeypatch, "Policing Paper")

    out = llm.shorten_assignment_name("Predictive Policing Case Study/Evaluation Paper")
    assert out == "Policing Paper"


def test_shorten_assignment_name_strips_surrounding_quotes(tmp_path, monkeypatch):
    _configure(monkeypatch, tmp_path)
    _install_fake_client(monkeypatch, '"Policing Paper"')

    assert llm.shorten_assignment_name("Predictive Policing Case Study/Evaluation Paper") == "Policing Paper"


def test_shorten_assignment_name_rejects_still_too_long_output(tmp_path, monkeypatch):
    _configure(monkeypatch, tmp_path)
    _install_fake_client(monkeypatch, "A" * 100)

    assert llm.shorten_assignment_name("Some Long Name", max_chars=35) is None


def test_shorten_assignment_name_returns_none_on_client_error(tmp_path, monkeypatch):
    _configure(monkeypatch, tmp_path)

    class _Raising:
        class messages:
            @staticmethod
            def create(**kwargs):
                raise ConnectionError("network down")

    monkeypatch.setattr(llm, "_c", lambda: _Raising())

    assert llm.shorten_assignment_name("Some Long Assignment Name Here") is None


# ---- usage logging ----
# daily_briefing (the original driver for this logging) was retired
# 2026-07-15 -- categorize_unknown exercises the same _log_usage path.

def test_usage_is_logged_per_call(tmp_path, monkeypatch):
    _configure(monkeypatch, tmp_path)
    _install_fake_client(monkeypatch, '{"category": "Groceries"}')

    llm.categorize_unknown("Trader Joe's", 42.10, ["Groceries"])

    lines = state_store.recent_lines(state_store.logs_path("llm_usage.jsonl"), 10)
    assert len(lines) == 1
    rec = json.loads(lines[0])
    assert rec["fn"] == "categorize_unknown"
    assert rec["input_tokens"] == 10
    assert rec["output_tokens"] == 20


def test_usage_logging_failure_never_breaks_the_actual_call(tmp_path, monkeypatch):
    """_log_usage is explicitly best-effort -- a broken usage log must not
    take down the feature it's just observing."""
    _configure(monkeypatch, tmp_path)
    _install_fake_client(monkeypatch, '{"category": "Groceries"}')
    monkeypatch.setattr(llm.state_store, "append_line",
                        lambda *a, **k: (_ for _ in ()).throw(OSError("disk full")))

    assert llm.categorize_unknown("Trader Joe's", 42.10, ["Groceries"]) == "Groceries"
