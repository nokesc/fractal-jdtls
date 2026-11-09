# SPDX-License-Identifier: Apache-2.0
"""HTTP mode (design §11 "Inside JDT LS", "Launching", "Security", "Errors", "Status and shutdown").

The installed release, started through its bin/jdtls with --http-port-file, a token and a
settings file, on a scratch -data and user.home. Everything is observed from outside: the port
file, HTTP, /proc, `ss`, the logs and the install's files. Each run's files stay in pytest's
temporary folder under runs/http-*/.
"""

import hashlib
import json
import os
import re
import shutil
import socket
import sys
import time
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

from endpoint_client import (  # noqa: E402
    AGENT_ERROR,
    Contract,
    EndpointProcess,
    cmdline_of,
    descriptor_targets,
    environ_of,
    http_command,
    listening_sockets,
    write_settings,
)
from scenario import verify  # noqa: E402


def snapshot(folder: Path) -> dict[str, str]:
    """Every file and folder under folder, with each file's hash."""
    return {
        path.relative_to(folder).as_posix(): (hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else "dir")
        for path in sorted(folder.rglob("*"))
    }


def java_shim(folder: Path) -> Path:
    """A java that records, for every run the launcher starts, its first argument and whether the token or a
    client-socket variable is in its environment, then becomes the real java."""
    java_home = os.environ.get("JAVA_HOME", "")
    real = Path(java_home, "bin", "java") if java_home and Path(java_home, "bin", "java").is_file() else Path(shutil.which("java"))
    shim = folder / "java-shim" / "java"
    shim.parent.mkdir(parents=True)
    log = folder / "java-runs.log"
    shim.write_text(
        "#!/bin/sh\n"
        f"printf '%s token=%s CLIENT_PORT=%s CLIENT_HOST=%s\\n' \"$1\" \"${{FRACTAL_JDTLS_HTTP_TOKEN:+set}}\" "
        f"\"${{CLIENT_PORT:+set}}\" \"${{CLIENT_HOST:+set}}\" >> '{log}'\n"
        f"exec '{real}' \"$@\"\n"
    )
    shim.chmod(0o755)
    return shim


def hold(port: int, request: bytes) -> socket.socket:
    s = socket.create_connection(("127.0.0.1", port))
    s.sendall(request)
    return s


def attack_then_status(endpoint, count=8):
    """R2 of the review: connections without the token that withhold their body, and connections that never
    finish their headers, all held open, then one authenticated GET /v1/status."""
    port = endpoint.port
    held = [hold(port, f"POST /v1/shutdown HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nContent-Length: 1\r\n\r\n".encode())
            for _ in range(count)]  # fmt: skip
    held += [hold(port, f"GET /v1/status HTTP/1.1\r\nHost: 127.0.0.1:{port}".encode()) for _ in range(count)]
    time.sleep(0.5)
    started = time.monotonic()
    status = endpoint.request("GET", "/v1/status", timeout=30)
    status_s = round(time.monotonic() - started, 3)
    # How long the server lets a connection that never finishes its headers hold its thread.
    slow = held[-1]
    slow.settimeout(60)
    closed_after = None
    try:
        if slow.recv(1) == b"":
            closed_after = round(time.monotonic() - started, 1)
    except OSError:
        closed_after = round(time.monotonic() - started, 1)
    answers = []
    for s in held[:count]:
        s.settimeout(5)
        try:
            answers.append(s.recv(4096).split(b"\r\n", 1)[0].decode())
        except OSError as error:
            answers.append(repr(error))
    for s in held:
        s.close()
    return {"status": status, "status_s": status_s, "slow_closed_after_s": closed_after, "withheld_body_answers": answers}


@pytest.fixture(scope="module")
def served(scratch, release_dir, maven_repository, launch_env, jvm_home):
    """One HTTP-mode run, start to shutdown, with everything the tests check recorded on the way."""
    folder = scratch / "runs" / "http-served"
    folder.mkdir(parents=True)
    settings = write_settings(folder / "settings.json", maven_repository)
    port_file = folder / "port.json"
    data = folder / "data"
    before = snapshot(release_dir)
    seen = {"install_before": before}
    shim = java_shim(folder)
    cmd = http_command(release_dir, jvm_home, data, port_file, settings) + [f"--java-executable={shim}"]
    # A client's own client-socket variables: HTTP mode removes them (review R1).
    env = {**launch_env, "CLIENT_PORT": "5036", "CLIENT_HOST": "localhost"}
    endpoint = EndpointProcess(cmd, env=env, run_dir=folder, port_file=port_file)
    with endpoint:
        seen["port_file"] = endpoint.wait_port_file()
        seen["port_file_mode"] = oct(port_file.stat().st_mode & 0o777)
        seen["port_file_after_s"] = endpoint.port_file_after
        seen["pid"] = endpoint.pid
        seen["status_ready"] = endpoint.request("GET", "/v1/status?wait=ready&timeoutMs=120000")
        seen["ready_after_s"] = round(time.monotonic() - endpoint.started, 2)
        seen["openapi"] = endpoint.request("GET", "/v1/openapi.json")
        token = endpoint.token
        seen["refused"] = {
            "no token": endpoint.request("GET", "/v1/status", auth=False),
            "wrong token": endpoint.request("GET", "/v1/status", headers={"Authorization": "Bearer " + "w" * 43}),
            "wrong Host": endpoint.request("GET", "/v1/status", headers={"Host": f"localhost:{endpoint.port}"}),
            "an Origin header": endpoint.request("GET", "/v1/status", headers={"Origin": "http://example.test"}),
            "no such route": endpoint.request("POST", "/v1/no-such-operation", body={}),  # /v1/definition until T002.06 made it
            "wrong method": endpoint.request("GET", "/v1/shutdown"),
        }
        seen["attack"] = attack_then_status(endpoint)
        seen["environ"] = environ_of(endpoint.pid)
        seen["stdio"] = [os.path.realpath(f"/proc/{endpoint.pid}/fd/{fd}") for fd in (0, 1, 2)]
        seen["cmdline"] = cmdline_of(endpoint.pid)
        seen["descriptors"] = descriptor_targets(endpoint.pid)
        seen["sockets"] = listening_sockets(endpoint.pid)
        seen["status_after_checks"] = endpoint.request("GET", "/v1/status")
        seen["shutdown"] = endpoint.request("POST", "/v1/shutdown", body={})
        seen["exit_code"] = endpoint.wait_exit(120)
    seen["port_file_exists_after"] = port_file.exists()
    seen["install_after"] = snapshot(release_dir)
    seen["verify"] = verify(release_dir)
    seen["token"] = token
    seen["java_runs"] = (folder / "java-runs.log").read_text().splitlines()
    seen["endpoint"] = endpoint
    seen["data"] = data
    seen["folder"] = folder
    keys = ("port_file", "port_file_mode", "port_file_after_s", "pid", "ready_after_s", "sockets", "exit_code", "stdio", "java_runs")
    record = {k: v for k, v in seen.items() if k in keys}
    record["attack"] = {**seen["attack"], "status": seen["attack"]["status"][0]}
    record["refused"] = {k: v[:1] + v[2:] for k, v in seen["refused"].items()}
    record["status_ready"] = seen["status_ready"][2]
    (folder / "seen.json").write_text(json.dumps(record, indent=1) + "\n")
    return seen


@pytest.fixture(scope="module")
def contract(served):
    status, _, body = served["openapi"]
    assert status == 200
    return Contract(json.loads(body))


def test_it_starts_writes_the_port_file_and_reports_ready(served, contract):
    info = served["port_file"]
    assert served["port_file_mode"] == "0o600"
    assert set(info) == {"port", "pid", "release", "api"}
    assert info["pid"] == served["pid"]
    assert info["release"] == served["endpoint"].cmd[0].split("/")[-3]
    status, headers, body = served["status_ready"]
    assert status == 200, body
    value = json.loads(body)
    assert value["ready"] is True and value["release"] == info["release"] and value["api"] == info["api"] == "1.0.0"
    assert value["jdtls"] == "1.63.0" and value["pid"] == served["pid"]
    assert not contract.errors("GET", "/v1/status", 200, value)
    assert headers["content-type"].startswith("application/json")


def test_the_served_description_is_the_committed_one(served):
    committed = (Path(__file__).resolve().parents[2] / "endpoint/src/main/resources/openapi.json").read_bytes()
    assert served["openapi"][2].encode("utf-8") == committed


@pytest.mark.parametrize(
    "case, status, code",
    [
        ("no token", 401, "unauthorized"),
        ("wrong token", 401, "unauthorized"),
        ("wrong Host", 403, "host-not-allowed"),
        ("an Origin header", 403, "origin-not-allowed"),
        ("no such route", 404, "route-not-found"),
        ("wrong method", 405, "method-not-allowed"),
    ],
)
def test_requests_that_fail_the_checks_are_refused_with_a_problem(served, contract, case, status, code):
    got, headers, body = served["refused"][case]
    assert got == status, body
    assert headers["content-type"].startswith("application/problem+json")
    problem = json.loads(body)
    assert problem["code"] == code and problem["status"] == status and problem["type"] == f"urn:fractal-jdtls:error:{code}"
    if code == "unauthorized":
        assert set(problem) == {"type", "title", "status", "code", "hint", "retryable"}
        assert headers["www-authenticate"] == "Bearer"
    if case in ("no token", "wrong token", "wrong Host", "an Origin header"):
        assert not contract.errors("GET", "/v1/status", status, problem)


def test_the_token_never_appears_in_any_output_or_log(served):
    token = served["token"].encode()
    folder, data = served["folder"], served["data"]
    outputs = [folder / "stdout", folder / "stderr", folder / "command.json", folder / "seen.json",
               data / ".fractal-jdtls" / served["port_file"]["release"] / "endpoint.log", data / ".metadata" / ".log"]  # fmt: skip
    for path in outputs:
        assert path.exists(), path
        assert token not in path.read_bytes(), path
    for _, _, body in served["refused"].values():
        assert served["token"] not in body
    assert token not in served["cmdline"]
    assert (folder / "stdout").read_bytes() == b"", "in HTTP mode JDT LS writes nothing to the real stdout"


def test_the_token_never_reaches_the_jvms_environment_and_still_authenticates(served):
    assert b"FRACTAL_JDTLS_HTTP_TOKEN" not in served["environ"]
    assert served["token"].encode() not in served["environ"]
    assert served["status_after_checks"][0] == 200
    fd = re.search(rb"-Dfractal\.jdtls\.http\.tokenFd=(\d+)", served["cmdline"]).group(1).decode()
    assert served["descriptors"][fd].startswith("pipe:["), served["descriptors"]


def test_no_java_the_launcher_starts_sees_the_token_or_a_client_socket(served):
    runs = served["java_runs"]
    assert [run.split()[0] for run in runs] == ["-version", "--list-modules", "-Djdk.xml.maxGeneralEntitySizeLimit=0"], runs
    assert all(run.endswith("token= CLIENT_PORT= CLIENT_HOST=") for run in runs), runs
    for name in (b"CLIENT_PORT=", b"CLIENT_HOST="):
        assert name not in served["environ"]
    err = served["endpoint"].stderr()
    assert "jdtls: HTTP mode: CLIENT_PORT removed from Java's environment" in err
    assert "jdtls: HTTP mode: CLIENT_HOST removed from Java's environment" in err


def test_jdtls_has_no_lsp_peer_on_stdin_or_stdout(served):
    stdin, stdout, stderr = served["stdio"]
    assert stdin == "/dev/null"
    assert stdout == stderr == str(served["folder"] / "stderr")


def test_connections_without_the_token_cant_block_status(served):
    attack = served["attack"]
    status, _, body = attack["status"]
    assert status == 200, body
    assert attack["status_s"] < 2, attack
    # The refused requests were answered at once, though their bodies never came.
    assert all(answer == "HTTP/1.1 401 Unauthorized" for answer in attack["withheld_body_answers"]), attack
    # And a connection that never finishes its headers is cut after sun.net.httpserver.maxReqTime (10 s),
    # checked every second.
    assert attack["slow_closed_after_s"] is not None and 9 <= attack["slow_closed_after_s"] <= 13, attack


@pytest.mark.parametrize(
    "extra, message",
    [
        (["--jvm-arg=-DCLIENT_PORT=5036"], "HTTP mode allows JDT LS no LSP transport but the endpoint's"),
        (["--pipe=/tmp/fractal-jdtls-lsp.sock"], "HTTP mode allows JDT LS no LSP transport but the endpoint's"),
        (["--jvm-arg=-Dsun.net.httpserver.maxReqTime=0"], "HTTP mode sets the JDK HTTP server's limits itself"),
    ],
)
def test_the_launcher_refuses_another_transport_or_other_server_limits(scratch, release_dir, launch_env, jvm_home, extra, message):
    folder = scratch / "runs" / f"http-refused-{len(list((scratch / 'runs').glob('http-refused-*')))}"
    port_file = folder / "port.json"
    endpoint = EndpointProcess(http_command(release_dir, jvm_home, folder / "data", port_file, None) + extra, env=launch_env,
                               run_dir=folder, port_file=port_file)  # fmt: skip
    with endpoint:
        assert endpoint.wait_exit(60) == 2
    assert message in endpoint.stderr()
    assert not port_file.exists() and not (folder / "data").exists()


def test_it_listens_only_on_127_0_0_1(served):
    port = served["port_file"]["port"]
    (socket,) = served["sockets"]
    local = socket.split()[4]
    assert local in (f"127.0.0.1:{port}", f"[::ffff:127.0.0.1]:{port}"), socket
    assert socket.startswith("tcp LISTEN")


def test_shutdown_answers_saves_the_workspace_and_exits_0(served, contract):
    status, _, body = served["shutdown"]
    assert status == 202 and json.loads(body) == {"shuttingDown": True}
    assert not contract.errors("POST", "/v1/shutdown", 202, json.loads(body))
    assert served["exit_code"] == 0, served["endpoint"].stderr()
    resources = served["data"] / ".metadata/.plugins/org.eclipse.core.resources"
    assert list((resources / ".root").glob("*.tree")) and (resources / ".safetable/org.eclipse.core.resources").is_file()
    assert not (resources / ".snap").exists(), "a .snap means the workspace wasn't saved"
    log = (served["data"] / ".metadata/.log").read_text()
    assert ">> shutdown" in log and ">> exit" in log
    assert not served["port_file_exists_after"]


def test_a_start_and_shutdown_with_no_worktrees_reports_no_agent_error(served):
    """T002.03 found the agent reporting populateClasspath as never loaded at shutdown, with no Maven project read.
    Design §6: a workspace with no Maven projects reports nothing (fixed in T002.04)."""
    assert served["exit_code"] == 0
    assert AGENT_ERROR not in served["endpoint"].stderr(), served["endpoint"].stderr()
    jdtls_log = served["data"] / ".metadata" / ".log"
    assert jdtls_log.is_file()
    assert AGENT_ERROR not in jdtls_log.read_text(errors="replace")


def test_the_install_stays_unchanged_and_verifies(served):
    assert served["install_after"] == served["install_before"]
    result = served["verify"]
    assert result.returncode == 0, result.stdout + result.stderr
    assert "own 9 match" in result.stdout


# --- refused starts ------------------------------------------------------------------


@pytest.mark.parametrize("token, message", [("", "HTTP mode needs a token"), ("short", "FRACTAL_JDTLS_HTTP_TOKEN has 5 characters")])
def test_the_launcher_refuses_to_start_without_a_good_token(scratch, release_dir, launch_env, jvm_home, token, message):
    folder = scratch / "runs" / f"http-token-{len(token)}"
    port_file = folder / "port.json"
    endpoint = EndpointProcess(http_command(release_dir, jvm_home, folder / "data", port_file, None), env=launch_env, run_dir=folder,
                               port_file=port_file, token=token)  # fmt: skip
    with endpoint:
        assert endpoint.wait_exit(60) == 1
    assert message in endpoint.stderr()
    assert not port_file.exists() and not (folder / "data").exists()


def test_a_settings_file_that_changes_a_fixed_setting_stops_jdtls_with_the_reason(scratch, release_dir, maven_repository, launch_env, jvm_home):
    folder = scratch / "runs" / "http-fixed-setting"
    folder.mkdir(parents=True)
    settings = write_settings(folder / "settings.json", maven_repository, autobuild={"enabled": True})
    port_file = folder / "port.json"
    endpoint = EndpointProcess(http_command(release_dir, jvm_home, folder / "data", port_file, settings), env=launch_env, run_dir=folder,
                               port_file=port_file)  # fmt: skip
    with endpoint:
        code = endpoint.wait_exit(120)
    err = endpoint.stderr()
    assert code == 1, err
    assert (
        f"fractal-jdtls endpoint: error: the endpoint can't start: the settings file {settings} sets java.autobuild.enabled to true, "
        "but HTTP mode fixes it to false." in err
    ), err
    assert not port_file.exists()
    assert endpoint.token not in err
