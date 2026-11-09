# SPDX-License-Identifier: Apache-2.0
"""The test scratch folder (design §8): every pytest session's folders, on disk beside the main checkout.

`<main checkout>__scratch/`, the main checkout being the first entry of `git worktree list --porcelain`,
so a linked worktree uses the same folder; FRACTAL_JDTLS_TEST_SCRATCH overrides it. Before anything is
made or pruned it must be owned by the user, not writable by anyone else, and have no `.git` in it or
above it. A run holds a kernel lock (flock) on `suite.lock` at its top for its whole duration, so one
suite runs at a time; the kernel releases it when the process dies. Under it, pytest's own numbered
sessions and retention (`pytest-of-<user>/pytest-<n>/`, `pytest-current`), through
PYTEST_DEBUG_TEMPROOT, never --basetemp, which empties its folder.

tests/conftest.py applies this to every session, unit and integration.
"""

import datetime
import fcntl
import json
import os
import stat
import subprocess
import sys
from pathlib import Path

ENV = "FRACTAL_JDTLS_TEST_SCRATCH"
LOCK = "suite.lock"
SUFFIX = "__scratch"


class ScratchError(Exception):
    """The scratch folder can't be used: the message says why and what to do."""


def main_checkout_from(porcelain: str) -> Path:
    """The main checkout in `git worktree list --porcelain`'s output: its first entry, unless that's bare."""
    first = porcelain.strip("\n").split("\n\n")[0].splitlines()
    if not first or not first[0].startswith("worktree "):
        raise ScratchError(f"git worktree list --porcelain listed no worktree ({porcelain!r}). Set {ENV} to the test scratch folder.")
    if "bare" in first[1:]:
        raise ScratchError(
            f"this repository's main entry, {first[0].removeprefix('worktree ')}, is bare, so there's no main checkout "
            f"to put the test scratch folder beside. Set {ENV} to a folder outside any Git checkout."
        )
    return Path(first[0].removeprefix("worktree "))


def main_checkout(checkout: Path) -> Path:
    try:
        listed = subprocess.run(["git", "-C", str(checkout), "worktree", "list", "--porcelain"], capture_output=True, text=True)
    except FileNotFoundError:
        raise ScratchError(f"git isn't on PATH, so the main checkout of {checkout} can't be found. Set {ENV}.") from None
    if listed.returncode != 0:
        raise ScratchError(f"git worktree list in {checkout} failed ({listed.stderr.strip()}). Set {ENV}.")
    return main_checkout_from(listed.stdout)


def chosen(checkout: Path, environ) -> Path:
    """The scratch folder: FRACTAL_JDTLS_TEST_SCRATCH, else the main checkout's sibling <name>__scratch."""
    given = environ.get(ENV)
    if given:
        folder = Path(given).expanduser()
        if not folder.is_absolute():
            raise ScratchError(f"{ENV} must be an absolute path, not {given!r}.")
        return folder
    main = main_checkout(checkout)
    return main.parent / (main.name + SUFFIX)


def git_at_or_above(folder: Path):
    """The nearest `.git` in folder or a folder above it, if any (a link counts, even dangling)."""
    for parent in (folder, *folder.parents):
        git = parent / ".git"
        if git.is_symlink() or git.exists():
            return git
    return None


def stray_git_message(stray: Path, folder: Path) -> str:
    return (
        f"found {stray}, which makes {stray.parent} and every folder below it, including the test scratch folder {folder}, "
        f"look like part of a Git checkout: projects the fixture means to be outside a worktree would then be inside one, and a "
        f"language server on that checkout could import the fixture's POMs. If it's a stray (if it's an empty folder: "
        f"rmdir {stray}), remove it. If the scratch folder is inside a checkout, set {ENV} to a folder outside every "
        f"Git checkout (the default is <main checkout>{SUFFIX}, beside the main checkout). Then run the suite again."
    )


def prepare(folder: Path) -> Path:
    """Check the scratch folder, making it (mode 700) if it's missing. Returns its real path."""
    real = folder.resolve()
    stray = git_at_or_above(real)
    if stray is not None:
        raise ScratchError(stray_git_message(stray, real))
    if not real.exists():
        real.mkdir(mode=0o700, parents=True)
    st = os.stat(real)
    if not stat.S_ISDIR(st.st_mode):
        raise ScratchError(f"the test scratch folder {real} isn't a folder. Remove it, or set {ENV} to another folder.")
    if st.st_uid != os.getuid():
        raise ScratchError(f"the test scratch folder {real} is owned by UID {st.st_uid}, not you (UID {os.getuid()}). Set {ENV} to a folder of your own.")
    if st.st_mode & (stat.S_IWGRP | stat.S_IWOTH):
        raise ScratchError(
            f"the test scratch folder {real} is writable by group or others (mode {stat.filemode(st.st_mode)}), so another user could "
            f"place files the suite would run. Run chmod go-w {real}, or set {ENV} to another folder."
        )
    return real


def describe(holder: str) -> str:
    try:
        h = json.loads(holder)
        return f"PID {h['pid']} ({' '.join(h['argv'])}, in {h['cwd']}, since {h['since']})"
    except (ValueError, KeyError, TypeError):
        return f"a process that hasn't written its PID yet ({holder!r})"


def lock(folder: Path) -> int:
    """Take the suite's kernel lock at the scratch folder's top, or fail at once naming its holder. Returns the descriptor.

    The descriptor is close-on-exec, so JDT LS and the suite's other children never hold it.
    """
    path = folder / LOCK
    fd = os.open(path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW | os.O_CLOEXEC, 0o600)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        holder = os.pread(fd, 4096, 0).decode(errors="replace")
        os.close(fd)
        raise ScratchError(
            f"another test run holds the test scratch folder's lock, {path}: {describe(holder)}. One suite runs at a time "
            f"(design §8): wait for it to finish, then run this again."
        ) from None
    os.ftruncate(fd, 0)
    since = datetime.datetime.now().astimezone().isoformat(timespec="seconds")
    argv = [Path(sys.orig_argv[0]).name, *sys.orig_argv[1:]]
    os.pwrite(fd, (json.dumps({"pid": os.getpid(), "argv": argv, "cwd": os.getcwd(), "since": since}) + "\n").encode(), 0)
    return fd


def clear_stale_session_locks(folder: Path) -> list:
    """Remove the `.lock` pytest leaves in a session folder when its run is killed.

    Under the suite's lock no other session can be using one, so each is stale. Left, it would keep pytest
    from pruning that session for three days (its lock timeout), and retention would keep more than six.
    """
    removed = []
    for session in sorted(folder.glob("pytest-of-*/pytest-*")):
        if session.is_symlink() or not session.name.removeprefix("pytest-").isdigit():
            continue
        stale = session / ".lock"
        if stale.is_file():
            stale.unlink()
            removed.append(stale)
    return removed
