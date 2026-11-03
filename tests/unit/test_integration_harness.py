# SPDX-License-Identifier: Apache-2.0
"""The integration suite's own helpers (tests/integration), without JDT LS.

Paths with a space and an `&`, as a scratch root (TMPDIR, --basetemp or the Maven cache) may
have; and the LSP client's wait for a step's quiet, against a fake server.
"""

import json
import sys
import textwrap
import time
import xml.etree.ElementTree as ET
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "integration"))

from lsp_client import LanguageServer  # noqa: E402
from scenario import error_diagnostics, local_path, maven_settings, relative, uri, workspace_projects  # noqa: E402

ODD = "a b&c"


@pytest.fixture
def worktrees(tmp_path):
    folder = tmp_path / ODD / "worktrees"
    (folder / "wt-a" / "harness-app").mkdir(parents=True)
    return folder


# --- R3: settings.xml ---------------------------------------------------------------------------


def test_maven_settings_escapes_the_repository_path(tmp_path):
    repository = tmp_path / "cache <x> & 'y'" / "m2" / "repository"
    settings = maven_settings(tmp_path / "settings.xml", repository)
    root = ET.parse(settings).getroot()
    ns = {"s": "http://maven.apache.org/SETTINGS/1.0.0"}
    assert root.findtext("s:localRepository", namespaces=ns) == str(repository)


# --- R2: file URIs ------------------------------------------------------------------------------


def test_local_path_decodes_file_uris(worktrees):
    app = worktrees / "wt-a" / "harness-app"
    assert "%20" in uri(app) and "%26" in uri(app)
    assert local_path(uri(app)) == str(app)
    # Java's URI leaves & unescaped, and JDT LS writes file:/ as well as file:///.
    assert local_path("file:" + str(app).replace(" ", "%20")) == str(app)
    assert local_path(str(app)) == str(app)  # a plain path is kept


def test_relative_works_for_uris_and_paths_under_an_odd_root(worktrees):
    app = worktrees / "wt-a" / "harness-app"
    assert relative(uri(app) + "/", worktrees) == "wt-a/harness-app"
    assert relative(str(app / "target" / "classes"), worktrees) == "wt-a/harness-app/target/classes"
    assert relative("file:/elsewhere/x", worktrees) == "/elsewhere/x"


def test_error_diagnostics_keeps_files_under_an_odd_root(worktrees, tmp_path):
    error = {"range": {"start": {"line": 6}}, "message": "WorkType cannot be resolved to a type", "severity": 1}
    warning = {"range": {"start": {"line": 1}}, "message": "unused", "severity": 2}
    dispatch = worktrees / "wt-a" / "harness-app" / "Dispatch.java"
    outside = tmp_path / "Other.java"
    diagnostics = {
        uri(dispatch): [warning, error],
        uri(worktrees / "wt-a" / "pom.xml"): [warning],
        uri(outside): [error],
    }
    assert error_diagnostics(diagnostics, worktrees) == {
        "wt-a/harness-app/Dispatch.java": ["7: WorkType cannot be resolved to a type"]
    }


def test_workspace_projects_reads_encoded_locations(worktrees, tmp_path):
    data = tmp_path / ODD / "data"
    project = data / ".metadata/.plugins/org.eclipse.core.resources/.projects/wt-a.harness-app"
    project.mkdir(parents=True)
    # As Eclipse writes it: binary framing around an encoded URI.
    encoded = "file:" + str(worktrees / "wt-a" / "harness-app").replace(" ", "%20")
    (project / ".location").write_bytes(b"\x40\xb1\x8b\x81\x23\xbc\x00\x14\x00" + b"URI//" + encoded.encode() + b"\x00\x00")
    locations = workspace_projects(data)
    assert {name: relative(location, worktrees) for name, location in locations.items()} == {
        "wt-a.harness-app": "wt-a/harness-app"
    }


# --- R1: a step's wait ---------------------------------------------------------------------------

FAKE_SERVER = textwrap.dedent(
    """
    import json, sys, time
    # Read one notification, then answer it with $/progress after a delay, then stay silent.
    delay = float(sys.argv[1])
    headers = {}
    while True:
        line = sys.stdin.buffer.readline().strip()
        if not line:
            break
        key, value = line.decode().split(":", 1)
        headers[key.lower()] = value.strip()
    sys.stdin.buffer.read(int(headers["content-length"]))
    time.sleep(delay)
    body = json.dumps({"jsonrpc": "2.0", "method": "$/progress", "params": {}}).encode()
    sys.stdout.buffer.write(b"Content-Length: %d\\r\\n\\r\\n" % len(body) + body)
    sys.stdout.buffer.flush()
    time.sleep(60)
    """
)


@pytest.fixture
def fake_server(tmp_path):
    script = tmp_path / "fake_server.py"
    script.write_text(FAKE_SERVER)
    servers = []

    def start(delay):
        log_dir = tmp_path / f"run{len(servers)}"
        log_dir.mkdir()
        server = LanguageServer([sys.executable, str(script), str(delay)], None, str(tmp_path), log_dir)
        servers.append(server)
        return server

    yield start
    for server in servers:
        server.kill()


def test_a_step_waits_for_activity_after_it_starts(fake_server):
    """An earlier quiet spell doesn't end a new step's wait: the server's answer is awaited, then quiet."""
    server = fake_server(delay=0.5)
    server.last_activity = time.monotonic() - 60  # quiet for a minute already, as after an import
    since = time.monotonic()
    server.notify("workspace/didChangeWatchedFiles", {"changes": []})
    waited = server.wait_quiet(quiet=0.3, limit=10, since=since)
    assert server.last_activity > since
    assert waited >= 0.5 + 0.3, waited


def test_a_step_with_no_activity_times_out(fake_server):
    server = fake_server(delay=30)
    server.last_activity = time.monotonic() - 60
    since = time.monotonic()
    server.notify("workspace/didChangeWatchedFiles", {"changes": []})
    with pytest.raises(TimeoutError, match="had no activity"):
        server.wait_quiet(quiet=0.3, limit=1, since=since)


def test_without_since_an_earlier_quiet_spell_counts(fake_server):
    """The import's wait (no since) is unchanged: quiet since the last event is enough."""
    server = fake_server(delay=30)
    server.last_activity = time.monotonic() - 60
    assert server.wait_quiet(quiet=0.3, limit=1) < 0.3
    assert json.loads((server.stderr_path.parent / "pid").read_text()) == server.pid
