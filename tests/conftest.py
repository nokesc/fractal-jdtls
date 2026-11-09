# SPDX-License-Identifier: Apache-2.0
"""Every pytest session, unit and integration, runs in the test scratch folder (design §8, scratch_folder.py).

Before pytest makes or prunes anything: the folder is checked (and made if missing), the suite's lock is
taken for the whole session, stale session locks are cleared, and pytest's temporary root is set to it.
Then Python's temporary files and the children's TMPDIR go to the session's own tmp/ (session_tmpdir).
The lock's descriptor is never closed: the kernel releases it when the process exits, after pytest's own
pruning at the session's end.
"""

import os
import sys
import tempfile
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

import scratch_folder  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
_held = []  # the lock's descriptor, for the life of the process
FOLDER = pytest.StashKey[Path]()


def pytest_configure(config):
    if config.option.basetemp is not None:
        raise pytest.UsageError(
            f"--basetemp empties its folder before each run; this suite keeps pytest's numbered sessions in the test "
            f"scratch folder instead. Set {scratch_folder.ENV} to choose that folder (design §8)."
        )
    try:
        folder = scratch_folder.prepare(scratch_folder.chosen(ROOT, os.environ))
        _held.append(scratch_folder.lock(folder))
    except scratch_folder.ScratchError as e:
        raise pytest.UsageError(str(e)) from None
    scratch_folder.clear_stale_session_locks(folder)
    os.environ["PYTEST_DEBUG_TEMPROOT"] = str(folder)
    config.stash[FOLDER] = folder


def pytest_report_header(config):
    folder = config.stash.get(FOLDER, None)
    return f"test scratch folder: {folder} (lock {folder / scratch_folder.LOCK}, held by PID {os.getpid()})" if folder else None


@pytest.fixture(scope="session", autouse=True)
def session_tmpdir(tmp_path_factory):
    """Python's own temporary files, and TMPDIR for the suite's children, in this session's folder, not /tmp.

    tools/pin.py's TemporaryDirectory, for one, falls back to it. Java ignores TMPDIR: JDT LS's and the other JVMs'
    java.io.tmpdir stays /tmp (design §8; T002.08's evidence lists what they write there).
    """
    folder = tmp_path_factory.getbasetemp() / "tmp"
    folder.mkdir(mode=0o700, exist_ok=True)
    saved = os.environ.get("TMPDIR"), tempfile.tempdir
    os.environ["TMPDIR"] = tempfile.tempdir = str(folder)
    yield folder
    tempfile.tempdir = saved[1]
    if saved[0] is None:
        os.environ.pop("TMPDIR", None)
    else:
        os.environ["TMPDIR"] = saved[0]
