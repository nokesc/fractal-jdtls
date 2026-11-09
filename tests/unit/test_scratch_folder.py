# SPDX-License-Identifier: Apache-2.0
"""The test scratch folder (tests/scratch_folder.py, tests/conftest.py; design §8).

Where it is, its checks, the suite's lock and pytest's retention under it. The nested pytest runs here
each get their own scratch folder through FRACTAL_JDTLS_TEST_SCRATCH, so they never meet this session's lock.
"""

import json
import os
import signal
import subprocess
import sys
import textwrap
from pathlib import Path

import pytest

TESTS = Path(__file__).resolve().parents[1]
ROOT = TESTS.parent
sys.path.insert(0, str(TESTS))

import scratch_folder  # noqa: E402
from scratch_folder import ENV, ScratchError  # noqa: E402

GIT = ["git", "-c", "user.name=t", "-c", "user.email=t@example.invalid", "-c", "init.defaultBranch=main"]


def git(*args, cwd):
    subprocess.run([*GIT, *args], cwd=cwd, check=True, capture_output=True)


# --- Where -------------------------------------------------------------------------------------


def test_the_main_checkout_is_the_first_entry():
    porcelain = "worktree /w/main\nHEAD 27f3\nbranch refs/heads/main\n\nworktree /w/main__worktrees/t\nHEAD b777\nbranch refs/heads/t\n\n"
    assert scratch_folder.main_checkout_from(porcelain) == Path("/w/main")


def test_a_bare_repository_has_no_main_checkout():
    with pytest.raises(ScratchError, match=f"is bare.*Set {ENV}"):
        scratch_folder.main_checkout_from("worktree /w/repo.git\nbare\n\nworktree /w/wt\nHEAD 1\nbranch refs/heads/x\n")


def test_a_linked_worktree_uses_the_main_checkouts_sibling(tmp_path):
    main = tmp_path / "proj"
    main.mkdir()
    git("init", "-q", cwd=main)
    git("commit", "-q", "--allow-empty", "-m", "c", cwd=main)
    git("worktree", "add", "-q", "-b", "t", str(tmp_path / "proj__worktrees" / "t"), cwd=main)
    for checkout in (main, tmp_path / "proj__worktrees" / "t"):
        assert scratch_folder.chosen(checkout, {}) == tmp_path / "proj__scratch"
    assert scratch_folder.chosen(main, {ENV: str(tmp_path / "elsewhere")}) == tmp_path / "elsewhere"
    with pytest.raises(ScratchError, match="absolute"):
        scratch_folder.chosen(main, {ENV: "relative/scratch"})
    # A bare repository's worktree: no main checkout, so the override is needed.
    git("clone", "-q", "--bare", str(main), str(tmp_path / "bare.git"), cwd=tmp_path)
    git("worktree", "add", "-q", str(tmp_path / "from-bare"), "main", cwd=tmp_path / "bare.git")
    with pytest.raises(ScratchError, match=f"bare.*Set {ENV}"):
        scratch_folder.chosen(tmp_path / "from-bare", {})
    assert scratch_folder.chosen(tmp_path / "from-bare", {ENV: str(tmp_path / "s")}) == tmp_path / "s"


def test_outside_a_repository_the_override_is_needed(tmp_path):
    with pytest.raises(ScratchError, match=f"git worktree list .* failed .*Set {ENV}"):
        scratch_folder.chosen(tmp_path, {})


# --- Checks ------------------------------------------------------------------------------------


def test_a_missing_folder_is_made_private(tmp_path):
    folder = scratch_folder.prepare(tmp_path / "scratch")
    assert folder == (tmp_path / "scratch").resolve() and oct(folder.stat().st_mode & 0o777) == "0o700"
    assert scratch_folder.prepare(tmp_path / "scratch") == folder  # and an existing one is kept


@pytest.mark.parametrize("mode", [0o777, 0o772, 0o702, 0o770, 0o720])
def test_a_folder_writable_by_others_is_refused(tmp_path, mode):
    folder = tmp_path / "scratch"
    folder.mkdir()
    folder.chmod(mode)
    with pytest.raises(ScratchError, match=f"writable by group or others .*chmod go-w {folder}"):
        scratch_folder.prepare(folder)
    assert list(folder.iterdir()) == []  # nothing made


def test_a_git_in_the_folder_or_above_it_is_refused(tmp_path):
    inside = tmp_path / "inside"
    (inside / ".git").mkdir(parents=True)
    with pytest.raises(ScratchError, match=f"found {inside / '.git'}.*set {ENV} to a folder outside every Git checkout"):
        scratch_folder.prepare(inside)
    above = tmp_path / "above"
    above.mkdir()
    (above / ".git").write_text("gitdir: /elsewhere\n")  # a linked worktree's .git is a file
    with pytest.raises(ScratchError, match=f"found {above / '.git'}"):
        scratch_folder.prepare(above / "a" / "scratch")
    assert not (above / "a").exists(), "checked before anything is made"
    dangling = tmp_path / "dangling"
    dangling.mkdir()
    (dangling / ".git").symlink_to(tmp_path / "nothing")
    with pytest.raises(ScratchError, match="found"):
        scratch_folder.prepare(dangling / "scratch")


def test_a_file_is_not_a_folder(tmp_path):
    (tmp_path / "scratch").write_text("")
    with pytest.raises(ScratchError, match="isn't a folder"):
        scratch_folder.prepare(tmp_path / "scratch")


def test_a_link_is_checked_where_it_leads(tmp_path):
    (tmp_path / "real").mkdir(mode=0o700)
    (tmp_path / "link").symlink_to(tmp_path / "real")
    assert scratch_folder.prepare(tmp_path / "link") == (tmp_path / "real").resolve()
    (tmp_path / "real").chmod(0o777)
    with pytest.raises(ScratchError, match="writable by group or others"):
        scratch_folder.prepare(tmp_path / "link")


# --- The lock ----------------------------------------------------------------------------------

HOLDER = textwrap.dedent("""
    import sys, time
    sys.path.insert(0, sys.argv[1])
    import scratch_folder
    from pathlib import Path
    scratch_folder.lock(Path(sys.argv[2]))
    print("locked", flush=True)
    time.sleep(600)
""")


def hold(folder: Path) -> subprocess.Popen:
    holder = subprocess.Popen([sys.executable, "-c", HOLDER, str(TESTS), str(folder)], stdout=subprocess.PIPE, text=True)
    assert holder.stdout.readline() == "locked\n"
    return holder


def test_a_second_run_is_refused_naming_the_holder_and_a_killed_holder_blocks_nothing(tmp_path):
    folder = scratch_folder.prepare(tmp_path / "scratch")
    holder = hold(folder)
    try:
        with pytest.raises(ScratchError, match=f"holds the test scratch folder's lock, {folder / 'suite.lock'}: PID {holder.pid} "):
            scratch_folder.lock(folder)
        assert json.loads((folder / "suite.lock").read_text())["pid"] == holder.pid
    finally:
        holder.send_signal(signal.SIGKILL)  # the PID this test started
        holder.wait()
    fd = scratch_folder.lock(folder)
    try:
        assert json.loads((folder / "suite.lock").read_text())["pid"] == os.getpid()
    finally:
        os.close(fd)


def test_the_lock_is_not_inherited_by_children(tmp_path):
    folder = scratch_folder.prepare(tmp_path / "scratch")
    fd = scratch_folder.lock(folder)
    child = subprocess.Popen(["sleep", "600"], close_fds=False)  # only close-on-exec keeps the lock from it
    try:
        os.close(fd)  # this process lets go; the child must not still hold it
        os.close(scratch_folder.lock(folder))
    finally:
        child.kill()  # the PID this test started
        child.wait()


def test_a_killed_sessions_pytest_lock_is_cleared(tmp_path):
    root = tmp_path / "scratch" / "pytest-of-someone"
    for name in ("pytest-3", "pytest-4"):
        (root / name).mkdir(parents=True)
    (root / "pytest-3" / ".lock").write_text("")
    (root / "pytest-current").symlink_to(root / "pytest-4")
    (root / "pytest-4" / "kept").mkdir()
    (root / "pytest-4" / "kept" / ".lock").write_text("")  # a test's own file, not pytest's
    assert scratch_folder.clear_stale_session_locks(tmp_path / "scratch") == [root / "pytest-3" / ".lock"]
    assert (root / "pytest-4" / "kept" / ".lock").exists()


# --- The suite's own sessions (nested pytest runs, each with its own scratch folder) -------------

PROBE = textwrap.dedent("""
    def test_probe(tmp_path):
        (tmp_path / "x").write_text("x")
""")


def nested(tmp_path, scratch, *args):
    """A pytest session of one probe test, with the suite's configuration and tests/conftest.py as its plugin."""
    probe = tmp_path / "probe" / "test_probe.py"
    probe.parent.mkdir(exist_ok=True)
    probe.write_text(PROBE)
    return subprocess.run(
        [sys.executable, "-m", "pytest", "-c", str(ROOT / "pyproject.toml"), "--rootdir", str(ROOT), "-p", "conftest",
         "-p", "no:cacheprovider", "-q", str(probe), *args],
        cwd=ROOT, capture_output=True, text=True, timeout=120,
        env={**os.environ, ENV: str(scratch), "PYTHONPATH": str(TESTS)},
    )


def test_sessions_are_numbered_and_six_are_kept(tmp_path):
    scratch = tmp_path / "scratch"
    for _ in range(7):
        run = nested(tmp_path, scratch)
        assert run.returncode == 0, run.stdout + run.stderr
    (root,) = scratch.glob("pytest-of-*")
    assert sorted(p.name for p in root.iterdir()) == ["pytest-1", "pytest-2", "pytest-3", "pytest-4", "pytest-5", "pytest-6", "pytest-current"]
    assert (root / "pytest-current").resolve() == root / "pytest-6"
    assert sorted(p.name for p in scratch.iterdir()) == [root.name, "suite.lock"]


def test_a_nested_run_is_refused_by_a_held_lock_and_by_basetemp(tmp_path):
    scratch = scratch_folder.prepare(tmp_path / "scratch")
    fd = scratch_folder.lock(scratch)
    try:
        run = nested(tmp_path, scratch)
        assert run.returncode == 4 and f"PID {os.getpid()} " in run.stderr, run.stdout + run.stderr
    finally:
        os.close(fd)
    run = nested(tmp_path, scratch, "--basetemp", str(tmp_path / "b"))
    assert run.returncode == 4 and "--basetemp empties its folder" in run.stderr, run.stdout + run.stderr
    assert not (tmp_path / "b").exists()


def test_temporary_files_go_to_the_sessions_folder_not_tmp(tmp_path_factory):
    import tempfile

    session = tmp_path_factory.getbasetemp()
    assert Path(tempfile.gettempdir()) == session / "tmp" and os.environ["TMPDIR"] == str(session / "tmp")
    with tempfile.TemporaryDirectory(prefix="probe-") as made:
        assert Path(made).parent == session / "tmp"
    child = subprocess.run([sys.executable, "-c", "import tempfile; print(tempfile.gettempdir())"], capture_output=True, text=True)
    assert child.stdout.strip() == str(session / "tmp")
