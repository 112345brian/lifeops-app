from lifeops import config


def test_env_int_falls_back_for_invalid_value(monkeypatch):
    monkeypatch.setenv("LIFEOPS_TEST_INT", "not-a-number")
    assert config._env_int("LIFEOPS_TEST_INT", 17) == 17


def test_env_int_accepts_valid_value(monkeypatch):
    monkeypatch.setenv("LIFEOPS_TEST_INT", "42")
    assert config._env_int("LIFEOPS_TEST_INT", 17) == 42
