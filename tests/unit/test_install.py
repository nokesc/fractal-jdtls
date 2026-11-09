# SPDX-License-Identifier: Apache-2.0
"""bin/install.py (design §4) and tools/release_zip.py (design §2).

Each test installs a small fake release, whose pin.json points at a local fake download
server, into a pytest scratch root. Nothing touches the real ~/.local/share.
"""

import ast
import hashlib
import http.server
import importlib.util
import io
import json
import os
import shutil
import signal
import subprocess
import sys
import tarfile
import threading
import time
import urllib.parse
import zipfile
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]


def load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


install = load("fractal_jdtls_install", ROOT / "bin" / "install.py")
release_zip = load("fractal_jdtls_release_zip", ROOT / "tools" / "release_zip.py")

RELEASE = "9.9.9-1"
BB_VERSION = "1.2.3"
TARBALL = "jdt-language-server-9.9.9-202601020304.tar.gz"
ECLIPSE_FILES = {
    "bin/jdtls": (b"#!/bin/sh\necho stock\n", 0o755),
    "bin/jdtls.py": (b"if java_major_version < 21:\n    pass\n", 0o644),
    "config_linux/config.ini": (b"osgi.bundles=...\n", 0o644),
    "features/org.eclipse.feature_1.0.0.jar": (b"feature jar\n", 0o644),
    "plugins/org.eclipse.a_1.0.0.jar": (b"plugin a\n" * 1000, 0o644),
    "plugins/org.eclipse.b_1.0.0.jar": (b"plugin b\n" * 1000, 0o644),
}


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def make_tarball(files: dict[str, tuple[bytes, int]], extra=()) -> bytes:
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:gz") as archive:
        folders = sorted({str(Path(name).parent) for name in files} - {"."})
        for folder in folders:
            info = tarfile.TarInfo(folder)
            info.type, info.mode = tarfile.DIRTYPE, 0o755
            archive.addfile(info)
        for name, (data, mode) in sorted(files.items()):
            info = tarfile.TarInfo(name)
            info.size, info.mode = len(data), mode
            archive.addfile(info, io.BytesIO(data))
        for info in extra:
            archive.addfile(info)
    return buffer.getvalue()


class FakeServer:
    """Serves routes; can fail, truncate or stall a path, and logs every request."""

    def __init__(self, routes: dict[str, bytes]):
        self.routes = dict(routes)
        self.requests: list[str] = []
        self.fail: dict[str, list[int]] = {}  # path -> statuses to answer first, in order
        self.truncate: set[str] = set()  # send half the body, then close
        self.stall: set[str] = set()  # send half the body, then wait until released
        self.stalled = threading.Event()
        self.release_stall = threading.Event()
        server = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                path = urllib.parse.urlsplit(self.path).path
                server.requests.append(path)
                if server.fail.get(path):
                    self.send_error(server.fail[path].pop(0))
                    return
                if path not in server.routes:
                    self.send_error(404)
                    return
                body = server.routes[path]
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                if path in server.truncate:
                    self.wfile.write(body[: len(body) // 2])
                    self.close_connection = True
                    return
                if path in server.stall:
                    self.wfile.write(body[: len(body) // 2])
                    self.wfile.flush()
                    server.stalled.set()
                    server.release_stall.wait(30)
                    return
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.httpd.daemon_threads = True
        self.url = f"http://127.0.0.1:{self.httpd.server_port}"
        threading.Thread(target=self.httpd.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True).start()

    def close(self):
        self.release_stall.set()
        self.httpd.shutdown()
        self.httpd.server_close()


TARBALL_PATH = f"/jdtls/milestones/9.9.9/{TARBALL}"
BB_PATH = f"/maven2/net/bytebuddy/byte-buddy/{BB_VERSION}/byte-buddy-{BB_VERSION}.jar"
BYTE_BUDDY = b"PK fake byte buddy jar\n" * 100


def make_pin(url: str, tarball: bytes, files=None, byte_buddy: bytes = BYTE_BUDDY) -> dict:
    files = ECLIPSE_FILES if files is None else files
    return {
        "release": RELEASE,
        "jdtls": {
            "version": "9.9.9",
            "url": f"{url}{TARBALL_PATH}",
            "sha256": sha(tarball),
            "files": {name: sha(data) for name, (data, _) in sorted(files.items())},
        },
        "java": {"minimum": 21},
        "byte_buddy": {"version": BB_VERSION, "url": f"{url}{BB_PATH}", "sha256": sha(byte_buddy)},
    }


def make_release(folder: Path, pin: dict) -> Path:
    """An unzipped release: the real install.py, with fake jars and the given pin."""
    (folder / "bin").mkdir(parents=True)
    (folder / "lib").mkdir()
    shutil.copy(ROOT / "bin" / "install.py", folder / "bin" / "install.py")
    (folder / "bin" / "jdtls").write_text("#!/usr/bin/env python3\n# launcher\n")
    (folder / "lib" / "fractal-jdtls-agent.jar").write_bytes(b"agent jar\n")
    (folder / "lib" / "fractal-jdtls-boot.jar").write_bytes(b"boot jar\n")
    (folder / "lib" / "fractal-jdtls-endpoint.jar").write_bytes(b"endpoint jar\n")
    (folder / "lib" / "http-mode-preferences.ini").write_text("# preferences\n")
    (folder / "LICENSE").write_text("Apache License\n")
    (folder / "NOTICE").write_text("Fractal JDT LS\n")
    write_pin(folder, pin)
    return folder


def seal(folder: Path, release: str = RELEASE) -> None:
    """Write release-files.json for the release folder's current files, as release:zip does."""
    contents = {name: (folder / name).read_bytes() for name in install.HASHED_FILES}
    (folder / install.RELEASE_FILES_JSON).write_bytes(release_zip.release_files_json(release, contents))


def tamper(path: Path) -> None:
    """Change a file's bytes; pin.json stays valid JSON with the same content."""
    if path.name == "pin.json":
        path.write_text(json.dumps(json.loads(path.read_text())))
    else:
        path.write_bytes(path.read_bytes() + b"\n# tampered\n")


def write_pin(folder: Path, pin: dict) -> None:
    (folder / "pin.json").write_text(json.dumps(pin, indent=2) + "\n")
    seal(folder)


@pytest.fixture
def world(tmp_path, monkeypatch):
    monkeypatch.setattr(install, "RETRY_DELAYS", (0.0, 0.0))
    tarball = make_tarball(ECLIPSE_FILES)
    server = FakeServer({TARBALL_PATH: tarball, BB_PATH: BYTE_BUDDY})
    release = make_release(tmp_path / f"fractal-jdtls-{RELEASE}", make_pin(server.url, tarball))
    root = tmp_path / "root"

    class World:
        pass

    w = World()
    w.server, w.tarball, w.release, w.root, w.target = server, tarball, release, root, root / RELEASE
    yield w
    server.close()


def run_install(world, *args, release=None):
    return install.main(["--root", str(world.root), *args], release_dir=release or world.release)


def leftovers(root: Path) -> list[str]:
    return sorted(path.name for path in root.glob(".*.partial")) if root.exists() else []


def expected_tree() -> set[str]:
    return (
        set(install.RELEASE_FILES)
        | {f"lib/byte-buddy-{BB_VERSION}.jar"}
        | {f"eclipse/{name}" for name in ECLIPSE_FILES}
    )


def tree(folder: Path) -> set[str]:
    return {path.relative_to(folder).as_posix() for path in folder.rglob("*") if path.is_file()}


# --- installing --------------------------------------------------------------------


def test_installs_the_release_into_root_slash_release(world, capsys):
    assert run_install(world) == 0
    assert tree(world.target) == expected_tree()
    assert (world.target / "eclipse/plugins/org.eclipse.a_1.0.0.jar").read_bytes() == ECLIPSE_FILES[
        "plugins/org.eclipse.a_1.0.0.jar"
    ][0]
    assert (world.target / f"lib/byte-buddy-{BB_VERSION}.jar").read_bytes() == BYTE_BUDDY
    for name in ("pin.json", "lib/fractal-jdtls-agent.jar", "bin/install.py"):
        assert (world.target / name).read_bytes() == (world.release / name).read_bytes()
    for name in ("bin/install.py", "bin/jdtls", "eclipse/bin/jdtls"):
        assert os.access(world.target / name, os.X_OK), name
    assert not os.access(world.target / "eclipse/bin/jdtls.py", os.X_OK)
    assert leftovers(world.root) == []
    out = capsys.readouterr().out
    assert f"Installed fractal-jdtls {RELEASE} into {world.target}" in out
    assert world.server.requests == [TARBALL_PATH, BB_PATH]


def test_a_second_run_does_nothing(world, capsys):
    assert run_install(world) == 0
    before = {name: (world.target / name).stat().st_mtime_ns for name in tree(world.target)}
    world.server.requests.clear()
    capsys.readouterr()

    assert run_install(world) == 0
    out = capsys.readouterr().out
    assert f"fractal-jdtls {RELEASE} is already installed in {world.target} and verifies; nothing to do." in out
    assert world.server.requests == []
    assert {name: (world.target / name).stat().st_mtime_ns for name in tree(world.target)} == before


def test_the_installed_installer_also_finds_nothing_to_do(world, capsys):
    assert run_install(world) == 0
    capsys.readouterr()
    world.server.requests.clear()
    assert install.main([], release_dir=world.target) == 0
    assert "nothing to do" in capsys.readouterr().out
    assert world.server.requests == []


def test_a_tampered_tarball_is_refused_before_unpacking(world, capsys, monkeypatch):
    tampered = bytearray(world.tarball)
    tampered[len(tampered) // 2] ^= 0xFF
    world.server.routes[TARBALL_PATH] = bytes(tampered)
    unpacked = []
    monkeypatch.setattr(install, "unpack", lambda *args: unpacked.append(args))

    assert run_install(world) == 1
    err = capsys.readouterr().err
    assert f"the JDT LS tarball {TARBALL}" in err
    assert f"pin.json expects {sha(world.tarball)}" in err
    assert unpacked == []
    assert not world.target.exists()
    assert leftovers(world.root) == []


def test_a_tampered_unpacked_file_is_named_and_nothing_is_installed(world, capsys):
    # The tarball matches its pinned SHA-256, but one file in it doesn't match its own.
    files = dict(ECLIPSE_FILES)
    files["plugins/org.eclipse.b_1.0.0.jar"] = (b"tampered plugin b\n", 0o644)
    tarball = make_tarball(files)
    world.server.routes[TARBALL_PATH] = tarball
    pin = make_pin(world.server.url, tarball)  # per-file hashes still from ECLIPSE_FILES
    write_pin(world.release, pin)

    assert run_install(world) == 1
    err = capsys.readouterr().err
    assert "fails verification" in err
    assert "changed: eclipse/plugins/org.eclipse.b_1.0.0.jar" in err
    assert err.count("changed:") == 1
    assert not world.target.exists()
    assert leftovers(world.root) == []


def test_an_unpinned_file_in_the_tarball_is_named_as_extra(world, capsys):
    files = dict(ECLIPSE_FILES)
    files["plugins/org.evil_1.0.0.jar"] = (b"not in the pin\n", 0o644)
    tarball = make_tarball(files)
    world.server.routes[TARBALL_PATH] = tarball
    # The tarball matches its pinned SHA-256; the pin's file list doesn't name the new file.
    write_pin(world.release, make_pin(world.server.url, tarball))

    assert run_install(world) == 1
    assert "extra:   eclipse/plugins/org.evil_1.0.0.jar" in capsys.readouterr().err
    assert not world.target.exists()


def test_a_tampered_byte_buddy_jar_is_refused(world, capsys):
    world.server.routes[BB_PATH] = BYTE_BUDDY + b"tampered"

    assert run_install(world) == 1
    err = capsys.readouterr().err
    assert f"Byte Buddy {BB_VERSION} from {world.server.url}{BB_PATH}" in err
    assert f"pin.json expects {sha(BYTE_BUDDY)}" in err
    assert not world.target.exists()
    assert leftovers(world.root) == []


@pytest.mark.parametrize(
    "member",
    [
        ("symlink", tarfile.SYMTYPE, "plugins/link.jar", "org.eclipse.a_1.0.0.jar"),
        ("hard link", tarfile.LNKTYPE, "plugins/hard.jar", "plugins/org.eclipse.a_1.0.0.jar"),
        ("escape", tarfile.REGTYPE, "../escaped.txt", ""),
        ("absolute", tarfile.REGTYPE, "/tmp/absolute.txt", ""),
    ],
    ids=lambda member: member[0],
)
def test_unsafe_tarball_members_are_refused(world, capsys, member):
    _, kind, name, link = member
    info = tarfile.TarInfo(name)
    info.type, info.linkname, info.size = kind, link, 0
    tarball = make_tarball(ECLIPSE_FILES, extra=[info])
    world.server.routes[TARBALL_PATH] = tarball
    write_pin(world.release, make_pin(world.server.url, tarball))

    assert run_install(world) == 1
    err = capsys.readouterr().err
    assert name in err and "nothing was installed" in err
    assert not world.target.exists()
    assert leftovers(world.root) == []
    assert not (world.root.parent / "escaped.txt").exists()


def test_transient_download_failures_are_retried(world, monkeypatch):
    world.server.fail[TARBALL_PATH] = [503]
    world.server.truncate.add(BB_PATH)  # a short body, until Byte Buddy's first retry

    def sleep(seconds):
        if BB_PATH in world.server.requests:
            world.server.truncate.clear()

    monkeypatch.setattr(install.time, "sleep", sleep)

    assert run_install(world) == 0
    assert world.server.requests.count(TARBALL_PATH) == 2
    assert world.server.requests.count(BB_PATH) == 2


def test_a_download_that_keeps_failing_leaves_no_target(world, capsys):
    world.server.fail[BB_PATH] = [500, 500, 500]

    assert run_install(world) == 1
    err = capsys.readouterr().err
    assert f"downloading {world.server.url}{BB_PATH} failed: HTTP 500" in err
    assert "Nothing was installed" in err
    assert not world.target.exists()
    assert leftovers(world.root) == []


def test_an_interrupted_install_leaves_no_partial_target(world, capsys, monkeypatch):
    def interrupt(*args):
        raise KeyboardInterrupt

    monkeypatch.setattr(install, "unpack", interrupt)
    assert run_install(world) == 130
    assert "interrupted; nothing was installed" in capsys.readouterr().err
    assert not world.target.exists()
    assert leftovers(world.root) == []


@pytest.mark.parametrize("how", [signal.SIGINT, signal.SIGTERM, signal.SIGKILL], ids=lambda s: s.name)
def test_a_killed_install_leaves_no_target_and_the_next_run_completes(world, tmp_path, how):
    world.server.stall.add(TARBALL_PATH)
    env = {**os.environ, "HOME": str(tmp_path / "home"), "FRACTAL_JDTLS_ROOT": str(world.root)}
    process = subprocess.Popen(
        [sys.executable, str(world.release / "bin" / "install.py")],
        env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
    )
    try:
        assert world.server.stalled.wait(20), "the installer never started the download"
        deadline = time.monotonic() + 10
        while not list(world.root.glob(f".{RELEASE}.*.partial/.download/{TARBALL}")) and time.monotonic() < deadline:
            time.sleep(0.05)
        process.send_signal(how)
        process.wait(20)
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()
    world.server.release_stall.set()

    assert process.returncode != 0
    assert not world.target.exists()
    if how == signal.SIGKILL:
        assert len(leftovers(world.root)) == 1  # nothing can clean up after SIGKILL...
    else:
        assert leftovers(world.root) == [], process.stderr.read()

    world.server.stall.clear()
    result = subprocess.run(
        [sys.executable, str(world.release / "bin" / "install.py")], env=env, capture_output=True, text=True
    )
    assert result.returncode == 0, result.stderr
    assert leftovers(world.root) == []  # ...but the next install does
    if how == signal.SIGKILL:
        assert "left by an interrupted install" in result.stdout
    assert tree(world.target) == expected_tree()


def test_an_existing_target_that_fails_verification_is_left_alone(world, capsys):
    assert run_install(world) == 0
    (world.target / "eclipse/config_linux/config.ini").write_text("edited\n")
    capsys.readouterr()
    world.server.requests.clear()

    assert run_install(world) == 1
    err = capsys.readouterr().err
    assert "already exists but fails verification" in err
    assert "changed: eclipse/config_linux/config.ini" in err
    assert "fails verification (1 problem):" in err
    assert f"delete {world.target} and run install.py again" in err
    assert (world.target / "eclipse/config_linux/config.ini").read_text() == "edited\n"
    assert world.server.requests == []


@pytest.mark.parametrize("name", ["lib/fractal-jdtls-agent.jar", "bin/install.py", "pin.json", "LICENSE"])
def test_a_release_folder_that_doesnt_match_release_files_json_is_refused(world, capsys, name):
    tamper(world.release / name)

    assert run_install(world) == 1
    err = capsys.readouterr().err
    assert f"the release folder {world.release} doesn't match its release-files.json: changed {name}." in err
    assert "Nothing was installed" in err
    assert world.server.requests == []  # refused before any download
    assert not world.root.exists()

    assert run_install(world, "--verify") == 1  # an unzipped release that doesn't match can't verify either
    assert "doesn't match its release-files.json" in capsys.readouterr().err


def test_a_release_files_json_for_another_release_is_refused(world, capsys):
    seal(world.release, release="9.9.9-2")

    assert run_install(world) == 1
    err = capsys.readouterr().err
    assert "release-files.json is for release '9.9.9-2', but" in err
    assert f"pin.json is for '{RELEASE}': the two don't belong together" in err
    assert world.server.requests == []
    assert not world.root.exists()


def test_an_installed_release_files_json_for_another_release_is_reported(world, capsys):
    assert run_install(world) == 0
    seal(world.target, release="9.9.9-2")
    capsys.readouterr()
    assert install.main(["--verify"], release_dir=world.target) == 1
    err = capsys.readouterr().err
    assert "is for release '9.9.9-2'" in err
    assert f"The install is damaged: delete {world.target}" in err


@pytest.mark.parametrize(
    "edit, message",
    [
        (lambda doc: doc["files"].pop("NOTICE"), "no entry for NOTICE"),
        (lambda doc: doc["files"].update({"lib/extra.jar": "0" * 64}), "unexpected entries lib/extra.jar"),
        (lambda doc: doc["files"].update({"LICENSE": "not a hash"}), "invalid SHA-256 for LICENSE"),
    ],
    ids=["missing-entry", "extra-entry", "invalid-hash"],
)
def test_an_invalid_release_files_json_is_refused(world, capsys, edit, message):
    path = world.release / "release-files.json"
    document = json.loads(path.read_text())
    edit(document)
    path.write_text(json.dumps(document))
    assert run_install(world) == 1
    assert message in capsys.readouterr().err
    assert not world.root.exists()


def test_a_release_without_release_files_json_is_refused(world, capsys):
    (world.release / "release-files.json").unlink()
    assert run_install(world) == 1
    assert f"{world.release / 'release-files.json'} is missing. Unzip the release again" in capsys.readouterr().err
    assert not world.root.exists()


def test_an_incomplete_release_folder_is_refused(world, capsys):
    (world.release / "bin" / "jdtls").unlink()
    assert run_install(world) == 1
    assert "incomplete: missing bin/jdtls" in capsys.readouterr().err
    assert not world.root.exists()


def test_an_invalid_pin_is_refused(world, capsys):
    pin = json.loads((world.release / "pin.json").read_text())
    pin["release"] = "../escape"
    write_pin(world.release, pin)
    assert run_install(world) == 1
    assert "release '../escape' is not <jdtls-version>-<n>" in capsys.readouterr().err
    assert not world.root.exists()


def test_an_unwritable_root_is_a_clear_error(world, capsys, tmp_path):
    blocker = tmp_path / "blocker"
    blocker.write_text("a file, not a folder\n")
    assert install.main(["--root", str(blocker / "root")], release_dir=world.release) == 1
    assert f"can't create the install root {blocker / 'root'}" in capsys.readouterr().err


# --- the root ----------------------------------------------------------------------


def test_root_comes_from_the_flag_then_the_environment_then_xdg(monkeypatch, tmp_path):
    home = tmp_path / "home"
    monkeypatch.setenv("HOME", str(home))
    monkeypatch.delenv("XDG_DATA_HOME", raising=False)
    monkeypatch.delenv("FRACTAL_JDTLS_ROOT", raising=False)
    assert install.resolve_root(None) == home / ".local/share/fractal-jdtls"
    monkeypatch.setenv("XDG_DATA_HOME", "relative/ignored")
    assert install.resolve_root(None) == home / ".local/share/fractal-jdtls"
    monkeypatch.setenv("XDG_DATA_HOME", str(tmp_path / "data"))
    assert install.resolve_root(None) == tmp_path / "data/fractal-jdtls"
    monkeypatch.setenv("FRACTAL_JDTLS_ROOT", str(tmp_path / "env"))
    assert install.resolve_root(None) == tmp_path / "env"
    assert install.resolve_root(str(tmp_path / "flag")) == tmp_path / "flag"
    assert install.resolve_root("~/flag") == home / "flag"
    # An installed copy defaults to the root it's installed in, after the flag and variable.
    installed = tmp_path / "elsewhere" / RELEASE
    assert install.resolve_root(None, installed, RELEASE) == tmp_path / "env"
    monkeypatch.delenv("FRACTAL_JDTLS_ROOT")
    assert install.resolve_root(None, installed, RELEASE) == tmp_path / "elsewhere"
    assert install.resolve_root(None, tmp_path / f"fractal-jdtls-{RELEASE}", RELEASE) == tmp_path / "data/fractal-jdtls"


def test_the_environment_variable_sets_the_root_for_an_install(world, monkeypatch):
    monkeypatch.setenv("FRACTAL_JDTLS_ROOT", str(world.root))
    assert install.main([], release_dir=world.release) == 0
    assert tree(world.target) == expected_tree()


# --- --verify ----------------------------------------------------------------------


def test_verify_passes_on_a_good_install(world, capsys):
    assert run_install(world) == 0
    capsys.readouterr()
    assert run_install(world, "--verify") == 0
    out = capsys.readouterr().out
    assert f"{world.target} verifies: all {len(expected_tree())} files are present" in out
    assert f"this release's own 9 match {world.release / 'release-files.json'}" in out

    assert install.main(["--verify"], release_dir=world.target) == 0  # the installed copy
    assert f"this release's own 9 match {world.target / 'release-files.json'}" in capsys.readouterr().out
    assert (world.target / "release-files.json").read_bytes() == (world.release / "release-files.json").read_bytes()


def test_verify_names_missing_extra_and_changed_files(world, capsys):
    assert run_install(world) == 0
    (world.target / "eclipse/plugins/org.eclipse.a_1.0.0.jar").unlink()
    (world.target / "eclipse/plugins/stray.jar").write_bytes(b"stray\n")
    (world.target / "eclipse/configuration/org.eclipse.osgi").mkdir(parents=True)
    (world.target / "eclipse/configuration/org.eclipse.osgi/state.bin").write_bytes(b"runtime\n")
    (world.target / "eclipse/bin/jdtls").write_bytes(b"#!/bin/sh\necho tampered\n")
    (world.target / f"lib/byte-buddy-{BB_VERSION}.jar").write_bytes(b"other byte buddy\n")
    (world.target / "lib/fractal-jdtls-agent.jar").write_bytes(b"other agent\n")
    (world.target / "eclipse/features/org.eclipse.feature_1.0.0.jar").unlink()
    (world.target / "eclipse/features/org.eclipse.feature_1.0.0.jar").symlink_to("/etc/hostname")
    capsys.readouterr()

    assert run_install(world, "--verify") == 1
    err = capsys.readouterr().err
    lines = [line.strip() for line in err.splitlines() if line.startswith("  ")]
    assert lines == [
        "changed: eclipse/bin/jdtls",
        "extra:   eclipse/configuration",
        "changed: eclipse/features/org.eclipse.feature_1.0.0.jar",
        "missing: eclipse/plugins/org.eclipse.a_1.0.0.jar",
        "extra:   eclipse/plugins/stray.jar",
        f"changed: lib/byte-buddy-{BB_VERSION}.jar",
        "changed: lib/fractal-jdtls-agent.jar",
    ]
    assert f"{world.target} fails verification (7 problems)" in err

    # The installed copy reports exactly the same, through its own release-files.json.
    assert install.main(["--verify"], release_dir=world.target) == 1
    assert [line.strip() for line in capsys.readouterr().err.splitlines() if line.startswith("  ")] == lines


@pytest.mark.parametrize(
    "name", ["lib/fractal-jdtls-agent.jar", "lib/fractal-jdtls-boot.jar", "lib/fractal-jdtls-endpoint.jar", "bin/jdtls", "pin.json", "NOTICE"]
)
def test_verify_from_the_install_detects_a_changed_release_file(world, capsys, name):
    assert run_install(world) == 0
    tamper(world.target / name)
    capsys.readouterr()

    assert install.main(["--verify"], release_dir=world.target) == 1  # the installed copy
    err = capsys.readouterr().err
    assert f"{world.target} fails verification (1 problem):\n  changed: {name}\n" in err


def test_verify_from_the_release_catches_an_install_resealed_to_match_a_change(world, capsys):
    assert run_install(world) == 0
    tamper(world.target / "lib/fractal-jdtls-agent.jar")
    seal(world.target)  # the installed release-files.json rewritten to match
    capsys.readouterr()
    assert install.main(["--verify"], release_dir=world.target) == 0  # consistent with itself...
    capsys.readouterr()
    assert run_install(world, "--verify") == 1  # ...but not with the unzipped release
    err = capsys.readouterr().err
    assert "  changed: lib/fractal-jdtls-agent.jar\n  changed: release-files.json\n" in err


def test_verify_reports_a_missing_install(world, capsys):
    assert run_install(world, "--verify") == 1
    assert f"isn't installed in {world.target}. Run install.py to install it." in capsys.readouterr().err


# --- the shipped script ------------------------------------------------------------


def test_install_py_parses_as_python_3_10():
    ast.parse((ROOT / "bin" / "install.py").read_text(encoding="utf-8"), feature_version=(3, 10))


def test_install_py_imports_only_the_standard_library():
    tree_ = ast.parse((ROOT / "bin" / "install.py").read_text(encoding="utf-8"))
    imported = set()
    for node in ast.walk(tree_):
        if isinstance(node, ast.Import):
            imported.update(alias.name.split(".")[0] for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module and node.level == 0:
            imported.add(node.module.split(".")[0])
    assert imported - {"__future__"} <= sys.stdlib_module_names


def test_the_repository_pin_is_a_valid_installer_pin():
    pin = install.Pin(ROOT / "pin.json")
    assert pin.release == json.loads((ROOT / "pin.json").read_text())["release"]
    assert pin.byte_buddy_file == f"lib/byte-buddy-{pin.byte_buddy_version}.jar"
    assert len(pin.eclipse_files) > 0


# --- the release zip ---------------------------------------------------------------


def agent_jar(path: Path, class_path: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr(
            "META-INF/MANIFEST.MF",
            f"Manifest-Version: 1.0\r\nPremain-Class: org.fractal.jdtls.agent.Agent\r\nClass-Path: {class_path}\r\n\r\n",
        )


@pytest.fixture
def repo(world, tmp_path):
    """A repository checkout with built jars, whose pin is the fake world's."""
    repo = tmp_path / "repo"
    (repo / "bin").mkdir(parents=True)
    for name in ("bin/install.py", "bin/jdtls", "LICENSE", "NOTICE", "pom.xml"):
        shutil.copy(ROOT / name, repo / name)
    shutil.copy(world.release / "pin.json", repo / "pin.json")
    agent_jar(repo / "agent/agent/target/fractal-jdtls-agent.jar", f"byte-buddy-{BB_VERSION}.jar")
    (repo / "agent/boot/target").mkdir(parents=True)
    (repo / "agent/boot/target/fractal-jdtls-boot.jar").write_bytes(b"boot jar\n")
    (repo / "endpoint/target").mkdir(parents=True)
    (repo / "endpoint/target/fractal-jdtls-endpoint.jar").write_bytes(b"endpoint jar\n")
    (repo / "endpoint/src/main/preferences").mkdir(parents=True)
    shutil.copy(ROOT / "endpoint/src/main/preferences/http-mode-preferences.ini", repo / "endpoint/src/main/preferences")
    return repo


def test_release_zip_has_the_design_layout_and_is_reproducible(repo, tmp_path):
    first = release_zip.build(repo, tmp_path / "out1")
    second = release_zip.build(repo, tmp_path / "out2")
    assert first.name == f"fractal-jdtls-{RELEASE}.zip"
    assert first.read_bytes() == second.read_bytes()
    with zipfile.ZipFile(first) as archive:
        modes = {info.filename: info.external_attr >> 16 for info in archive.infolist()}
    top = f"fractal-jdtls-{RELEASE}"
    assert modes == {
        f"{top}/": 0o40755,
        f"{top}/bin/": 0o40755,
        f"{top}/lib/": 0o40755,
        f"{top}/LICENSE": 0o100644,
        f"{top}/NOTICE": 0o100644,
        f"{top}/bin/install.py": 0o100755,
        f"{top}/bin/jdtls": 0o100755,
        f"{top}/lib/fractal-jdtls-agent.jar": 0o100644,
        f"{top}/lib/fractal-jdtls-boot.jar": 0o100644,
        f"{top}/lib/fractal-jdtls-endpoint.jar": 0o100644,
        f"{top}/lib/http-mode-preferences.ini": 0o100644,
        f"{top}/pin.json": 0o100644,
        f"{top}/release-files.json": 0o100644,
    }
    with zipfile.ZipFile(first) as archive:
        listed = json.loads(archive.read(f"{top}/release-files.json"))
        others = {
            name.removeprefix(f"{top}/"): hashlib.sha256(archive.read(name)).hexdigest()
            for name in archive.namelist()
            if not name.endswith("/") and name != f"{top}/release-files.json"
        }
    assert listed == {"release": RELEASE, "files": others}
    assert sorted(others) == sorted(install.HASHED_FILES)


def test_release_zip_refuses_an_agent_jar_naming_another_byte_buddy(repo, tmp_path):
    agent_jar(repo / "agent/agent/target/fractal-jdtls-agent.jar", "byte-buddy-0.0.1.jar")
    with pytest.raises(release_zip.ZipError, match=f"pins byte-buddy-{BB_VERSION}.jar"):
        release_zip.build(repo, tmp_path / "out")


def test_release_zip_refuses_missing_jars(repo, tmp_path):
    (repo / "agent/boot/target/fractal-jdtls-boot.jar").unlink()
    with pytest.raises(release_zip.ZipError, match="run `mise run build` first"):
        release_zip.build(repo, tmp_path / "out")


def test_install_from_the_release_zip_then_verify(world, repo, tmp_path):
    archive = release_zip.build(repo, tmp_path / "out")
    unzipped = tmp_path / "unzipped"
    with zipfile.ZipFile(archive) as opened:
        opened.extractall(unzipped)  # zipfile drops the modes, as some unzip tools do
    script = unzipped / f"fractal-jdtls-{RELEASE}" / "bin" / "install.py"
    env = {**os.environ, "HOME": str(tmp_path / "home")}
    command = [sys.executable, str(script), "--root", str(world.root)]

    first = subprocess.run(command, env=env, capture_output=True, text=True)
    assert first.returncode == 0, first.stderr
    assert os.access(world.target / "bin/jdtls", os.X_OK)
    second = subprocess.run(command, env=env, capture_output=True, text=True)
    assert "nothing to do" in second.stdout and second.returncode == 0
    verified = subprocess.run(
        [sys.executable, str(world.target / "bin/install.py"), "--verify", "--root", str(world.root)],
        env=env, capture_output=True, text=True,
    )
    assert verified.returncode == 0, verified.stderr
    assert not (tmp_path / "home").exists()
    assert not (world.target / "bin" / "__pycache__").exists()
