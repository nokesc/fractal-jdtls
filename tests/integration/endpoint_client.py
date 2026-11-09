# SPDX-License-Identifier: Apache-2.0
"""Drive JDT LS in HTTP mode (design §11) through the installed bin/jdtls, over HTTP only.

`EndpointProcess` starts the launcher with --http-port-file and the token in its environment,
waits for the port file, and sends requests with exactly the headers a test chooses. It records
the PID it started, and stops only that process. `Contract` validates bodies against the
OpenAPI description the endpoint serves.
"""

import copy
import http.client
import json
import os
import secrets
import subprocess
import time
from pathlib import Path

from jsonschema import Draft202012Validator
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

from scenario import maven_settings

TOKEN_ENV = "FRACTAL_JDTLS_HTTP_TOKEN"
# The release the suite builds and installs, from pin.json (design §2): it names the endpoint's folder under -data.
RELEASE = json.loads((Path(__file__).resolve().parents[2] / "pin.json").read_text())["release"]
BASE = "urn:fractal-jdtls:openapi"
# How the agent's reports start, on stderr and in JDT LS's log (design §6).
AGENT_ERROR = "fractal-jdtls agent: ERROR"


def http_command(release: Path, jvm_home: Path, data: Path, port_file: Path, settings: Path | None, *jvm_args: str):
    """The installed launcher in HTTP mode, on a scratch -data and user.home, with any more --jvm-arg values."""
    cmd = [str(release / "bin" / "jdtls"), f"--jvm-arg=-Duser.home={jvm_home}", *(f"--jvm-arg={a}" for a in jvm_args)]
    cmd += ["-data", str(data), "--http-port-file", str(port_file)]
    return cmd + (["--http-settings", str(settings)] if settings else [])


def write_settings(path: Path, maven_repository: Path, **java) -> Path:
    """The harness's LSP settings: Maven reads the suite's own local repository (design §8)."""
    user_settings = maven_settings(path.with_name("maven-settings.xml"), maven_repository)
    settings = {"java": {"import": {"gradle": {"enabled": False}}, "configuration": {"maven": {"userSettings": str(user_settings)}}, **java}}
    path.write_text(json.dumps(settings, indent=1) + "\n")
    return path


class EndpointProcess:
    def __init__(self, cmd, *, env, run_dir: Path, port_file: Path, token: str | None = None):
        self.cmd = list(cmd)
        self.token = token if token is not None else secrets.token_urlsafe(32)
        self.env = {**env, TOKEN_ENV: self.token}
        self.run_dir = run_dir
        self.port_file = port_file
        self.process = None
        self.port = None
        self.started = None
        run_dir.mkdir(parents=True, exist_ok=True)

    @property
    def pid(self) -> int:
        return self.process.pid  # the launcher execs java, so this is the JVM's PID

    def start(self):
        (self.run_dir / "command.json").write_text(json.dumps(self.cmd, indent=1) + "\n")
        self.started = time.monotonic()
        self.process = subprocess.Popen(
            self.cmd,
            env=self.env,
            stdin=subprocess.DEVNULL,
            stdout=open(self.run_dir / "stdout", "wb"),
            stderr=open(self.run_dir / "stderr", "wb"),
        )
        (self.run_dir / "pid").write_text(f"{self.process.pid}\n")
        return self

    def wait_port_file(self, timeout=120) -> dict:
        deadline = time.monotonic() + timeout
        while not self.port_file.exists():
            if self.process.poll() is not None:
                raise AssertionError(f"JDT LS exited with {self.process.returncode} before the port file appeared:\n{self.stderr()}")
            if time.monotonic() > deadline:
                raise AssertionError(f"no port file {self.port_file} after {timeout} s:\n{self.stderr()}")
            time.sleep(0.05)
        self.port_file_after = round(time.monotonic() - self.started, 2)
        info = json.loads(self.port_file.read_text())
        self.port = info["port"]
        return info

    def request(self, method, path, body=None, headers=None, auth=True, timeout=300):
        """(status, headers, body text); headers are the test's, plus Authorization and Host unless given."""
        all_headers = {"Host": f"127.0.0.1:{self.port}"}
        if auth:
            all_headers["Authorization"] = f"Bearer {self.token}"
        all_headers.update(headers or {})
        all_headers = {k: v for k, v in all_headers.items() if v is not None}
        data = None if body is None else (body if isinstance(body, bytes) else json.dumps(body).encode())
        if data is not None:
            all_headers.setdefault("Content-Type", "application/json")
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=timeout)
        try:
            connection.request(method, path, body=data, headers=all_headers)
            response = connection.getresponse()
            return response.status, {k.lower(): v for k, v in response.getheaders()}, response.read().decode("utf-8")
        finally:
            connection.close()

    def wait_exit(self, timeout=120) -> int:
        return self.process.wait(timeout)

    def stderr(self) -> str:
        path = self.run_dir / "stderr"
        return path.read_text(errors="replace") if path.exists() else ""

    def stop(self):
        """Kill the process this object started, if it's still running."""
        if self.process is not None and self.process.poll() is None:
            self.process.kill()
            self.process.wait(30)

    def __enter__(self):
        return self.start()

    def __exit__(self, *exc):
        self.stop()


class Client:
    """Calls on a running endpoint, each answer checked against the served description, and kept in `calls`."""

    def __init__(self, endpoint: EndpointProcess, contract: "Contract"):
        self.endpoint = endpoint
        self.contract = contract
        self.calls = []

    def post(self, path: str, body: dict, timeout=1800):
        started = time.monotonic()
        status, headers, text = self.endpoint.request("POST", path, body=body, timeout=timeout)
        ms = round((time.monotonic() - started) * 1000)
        value = json.loads(text)
        errors = self.contract.errors("POST", path, status, value)
        assert not errors, f"POST {path} {body} -> {status} {value}: not as the description says: {errors}"
        if status >= 400:
            assert headers["content-type"].startswith("application/problem+json"), headers
        self.calls.append({"path": path, "body": body, "status": status, "ms": ms, "response": value})
        return status, value, ms

    def status(self):
        status, _, text = self.endpoint.request("GET", "/v1/status")
        assert status == 200, text
        value = json.loads(text)
        assert not self.contract.errors("GET", "/v1/status", 200, value), value
        return value


class Contract:
    """The served OpenAPI description, to validate responses against its schemas."""

    def __init__(self, document: dict):
        self.document = document
        self.registry = Registry().with_resource(BASE, Resource.from_contents(copy.deepcopy(document), default_specification=DRAFT202012))

    def errors(self, method: str, path: str, status: int, value) -> list[str]:
        response = self.document["paths"][path][method.lower()]["responses"][str(status)]
        (media,) = response["content"].values()
        schema = media["schema"]
        schema = {"$ref": BASE + schema["$ref"]} if "$ref" in schema else schema
        validator = Draft202012Validator(schema, registry=self.registry)
        return [f"{'/'.join(map(str, e.absolute_path))}: {e.message}" for e in validator.iter_errors(value)]


def listening_sockets(pid: int) -> list[str]:
    """Every listening socket of the process, TCP, UDP and Unix, as `ss` shows them."""
    result = subprocess.run(["ss", "-H", "-l", "-n", "-p", "-t", "-u", "-x", "-w"], capture_output=True, text=True, check=True)
    return [" ".join(line.split()) for line in result.stdout.splitlines() if f"pid={pid}," in line]


def environ_of(pid: int) -> bytes:
    return Path(f"/proc/{pid}/environ").read_bytes()


def cmdline_of(pid: int) -> bytes:
    return Path(f"/proc/{pid}/cmdline").read_bytes()


def descriptor_targets(pid: int) -> dict[str, str]:
    folder = Path(f"/proc/{pid}/fd")
    targets = {}
    for fd in folder.iterdir():
        try:
            targets[fd.name] = os.readlink(fd)
        except OSError:
            pass
    return targets
