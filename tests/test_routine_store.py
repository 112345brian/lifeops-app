import pytest

from lifeops import history, routine_store


def test_load_routine_defaults_when_nothing_persisted(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    routine, extra = routine_store.load_routine("gym")

    assert routine.times == 4
    assert routine.per_days == 7
    assert routine.anchor == "window"
    assert extra == {"floor": 3, "max_consecutive": 2}


def test_load_routine_defaults_for_partner_and_friends(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    partner, partner_extra = routine_store.load_routine("partner")
    friends, friends_extra = routine_store.load_routine("friends")

    assert (partner.times, partner.per_days, partner.anchor) == (1, 7, "since_last")
    assert (friends.times, friends.per_days, friends.anchor) == (1, 7, "since_last")
    assert partner_extra == {}
    assert friends_extra == {}


def test_load_routine_defaults_for_meal(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    meal, extra = routine_store.load_routine("meal")

    assert (meal.times, meal.per_days, meal.anchor) == (1, 6, "since_last")
    assert extra == {}


def test_save_routine_overrides_a_single_field_without_resetting_others(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    routine_store.save_routine("gym", times=5)
    routine, extra = routine_store.load_routine("gym")

    assert routine.times == 5
    assert routine.per_days == 7          # untouched default preserved
    assert extra == {"floor": 3, "max_consecutive": 2}


def test_save_routine_twice_merges_rather_than_replaces(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    routine_store.save_routine("partner", per_days=3)
    routine_store.save_routine("partner", times=2)
    routine, _ = routine_store.load_routine("partner")

    assert routine.times == 2
    assert routine.per_days == 3


def test_save_routine_does_not_affect_other_routines(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    routine_store.save_routine("partner", per_days=3)
    friends, _ = routine_store.load_routine("friends")

    assert friends.per_days == 7


def test_load_routine_rejects_unknown_id(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    with pytest.raises(ValueError, match="unknown routine id"):
        routine_store.load_routine("gymm")


def test_save_routine_rejects_unknown_id_instead_of_silently_writing_it(tmp_path, monkeypatch):
    """A typo'd routine id must fail loudly, not silently create an
    override nothing will ever read -- that would look like it worked."""
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    with pytest.raises(ValueError, match="unknown routine id"):
        routine_store.save_routine("gymm", times=5)

    overrides = routine_store.state_store.load_json(
        routine_store.state_store.logs_path("routines.json"), default={})
    assert overrides == {}


@pytest.mark.parametrize("bad_value", [0, -1, "4", 4.0, True])
def test_save_routine_rejects_invalid_times(tmp_path, monkeypatch, bad_value):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    with pytest.raises(ValueError, match="times must be a positive int"):
        routine_store.save_routine("gym", times=bad_value)


def test_save_routine_rejects_invalid_anchor(tmp_path, monkeypatch):
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    with pytest.raises(ValueError, match="anchor must be one of"):
        routine_store.save_routine("gym", anchor="bogus")


def test_save_routine_validates_against_merged_config_not_just_the_partial_update(tmp_path, monkeypatch):
    """A partial save_routine(times=5) call has no `anchor` of its own --
    validation must check it against the EXISTING persisted/default value,
    not reject a perfectly valid partial update for "missing" fields it
    never touched."""
    monkeypatch.setattr(history, "ROOT", str(tmp_path))

    routine_store.save_routine("gym", times=5)   # must not raise
    routine, _ = routine_store.load_routine("gym")
    assert routine.times == 5
