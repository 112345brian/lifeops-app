import os

from lifeops import lock


def test_release_only_removes_lock_owned_by_this_process(tmp_path, monkeypatch):
    path = tmp_path / "lifeops.lock"
    path.write_text("another-owner", encoding="utf-8")
    monkeypatch.setattr(lock, "LOCK", str(path))
    monkeypatch.setattr(lock, "_owner", "our-old-owner")

    lock.release()

    assert path.read_text(encoding="utf-8") == "another-owner"


def test_acquire_and_release_round_trip(tmp_path, monkeypatch):
    path = tmp_path / "lifeops.lock"
    monkeypatch.setattr(lock, "LOCK", str(path))
    monkeypatch.setattr(lock, "_owner", None)

    lock.acquire()
    owner = path.read_text(encoding="utf-8")
    assert owner.startswith(str(os.getpid()) + ":")

    lock.release()
    assert not path.exists()
