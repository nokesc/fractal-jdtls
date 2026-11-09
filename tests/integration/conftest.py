# SPDX-License-Identifier: Apache-2.0
"""The integration suite's shared setup (design §8).

Once per session it builds the release zip from the jars of `mise run build`, and installs it
with its own install.py into a scratch root, downloading JDT LS and Byte Buddy as a user would.
Every run gets its own scratch worktrees, -data and diagnostic log.

Maven: every run's settings.xml points at one local repository that persists between sessions,
under the suite's cache: $FRACTAL_JDTLS_TEST_CACHE, else .cache/integration in this checkout
(git-ignored). The first session downloads the fixture's plugins from Maven Central, so the suite
needs network access. JDT LS's JVM also gets a scratch user.home, so a Maven default can never
reach the real ~/.m2.
"""

import os
import subprocess
import sys
import zipfile
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import scratch_folder  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
CACHE_ENV = "FRACTAL_JDTLS_TEST_CACHE"


@pytest.fixture(scope="session")
def scratch(tmp_path_factory) -> Path:
    """This session's folder, under the test scratch folder that tests/conftest.py checked and locked (design §8)."""
    base = tmp_path_factory.getbasetemp()
    # tests/conftest.py checked the scratch folder and everything above it; this covers pytest's own levels below.
    stray = scratch_folder.git_at_or_above(base)
    assert stray is None, scratch_folder.stray_git_message(stray, base)
    return base


@pytest.fixture(scope="session")
def cache() -> Path:
    folder = Path(os.environ.get(CACHE_ENV) or ROOT / ".cache" / "integration").resolve()
    folder.mkdir(parents=True, exist_ok=True)
    return folder


@pytest.fixture(scope="session")
def maven_repository(cache) -> Path:
    return cache / "m2" / "repository"


@pytest.fixture(scope="session")
def release_dir(scratch) -> Path:
    """Build the release zip, unzip it and install it with its own install.py into a scratch root."""
    out = scratch / "release"
    built = subprocess.run(
        [sys.executable, "-B", str(ROOT / "tools" / "release_zip.py"), "--output-dir", str(out / "zip")],
        capture_output=True, text=True,
    )
    assert built.returncode == 0, f"building the release zip failed (run mise run build first):\n{built.stdout}{built.stderr}"
    zips = list((out / "zip").glob("*.zip"))
    assert len(zips) == 1, zips
    with zipfile.ZipFile(zips[0]) as archive:
        archive.extractall(out / "unzipped")
    (top,) = (out / "unzipped").iterdir()
    root = out / "root"
    installed = subprocess.run(
        [sys.executable, "-I", "-B", str(top / "bin" / "install.py"), "--root", str(root)],
        capture_output=True, text=True,
    )
    (out / "install.log").write_text(installed.stdout + installed.stderr)
    assert installed.returncode == 0, f"install.py failed:\n{installed.stdout}{installed.stderr}"
    release = root / top.name.removeprefix("fractal-jdtls-")
    assert (release / "bin" / "jdtls").is_file(), installed.stdout
    return release


@pytest.fixture(scope="session")
def launch_env(scratch) -> dict:
    """The launcher's environment: the caller's, with a scratch XDG_CACHE_HOME (it's never used: -data is explicit)."""
    env = {k: v for k, v in os.environ.items() if k != "JDTLS_DATA"}
    env["XDG_CACHE_HOME"] = str(scratch / "xdg-cache")
    return env


@pytest.fixture(scope="session")
def jvm_home(scratch) -> Path:
    """JDT LS's user.home: empty, so a Maven default would show up here, not in ~/.m2."""
    home = scratch / "jvm-home"
    home.mkdir(exist_ok=True)
    return home
