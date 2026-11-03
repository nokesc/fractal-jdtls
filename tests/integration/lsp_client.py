# SPDX-License-Identifier: Apache-2.0
"""A minimal LSP client over a child process's stdin and stdout, ported from the spike's driver.

It records what the integration suite asserts on: diagnostics, `language/status` (for
ServiceReady) and error messages shown to the user. Server-to-client requests get a minimal
answer: `workspace/configuration` all nulls, `workspace/applyEdit` not applied.
"""

import json
import subprocess
import threading
import time
from pathlib import Path


class LanguageServer:
    def __init__(self, cmd, env, cwd, log_dir: Path):
        self.stderr_path = log_dir / "jdtls.stderr"
        self._stderr = open(self.stderr_path, "wb")
        self.proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self._stderr, env=env, cwd=cwd)
        # The only process the suite ever stops: the one it started, by this PID.
        self.pid = self.proc.pid
        (log_dir / "pid").write_text(f"{self.pid}\n")
        self._lsp_log = open(log_dir / "lsp.jsonl", "w")
        self._next_id = 1
        self._pending = {}
        self._lock = threading.Lock()
        self._send_lock = threading.Lock()
        self.diagnostics = {}
        self.status = []
        self.errors = []
        self.last_activity = time.monotonic()
        self._reader = threading.Thread(target=self._read, daemon=True)
        self._reader.start()

    # --- protocol --------------------------------------------------------------

    def _send(self, msg):
        body = json.dumps(msg).encode()
        with self._send_lock:
            self.proc.stdin.write(b"Content-Length: %d\r\n\r\n" % len(body) + body)
            self.proc.stdin.flush()

    def request(self, method, params, timeout=300):
        with self._lock:
            rid = self._next_id
            self._next_id += 1
            event = threading.Event()
            self._pending[rid] = [event, None]
        self._send({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
        if not event.wait(timeout):
            raise TimeoutError(f"no response to {method} within {timeout} s")
        response = self._pending.pop(rid)[1]
        if response is None:
            raise ConnectionError(f"JDT LS exited before answering {method}; see {self.stderr_path}")
        if "error" in response:
            raise RuntimeError(f"{method}: {response['error']}")
        return response.get("result")

    def notify(self, method, params):
        self._send({"jsonrpc": "2.0", "method": method, "params": params})

    def execute(self, command, *arguments):
        return self.request("workspace/executeCommand", {"command": command, "arguments": list(arguments)})

    def _read(self):
        stream = self.proc.stdout
        try:
            while True:
                headers = {}
                while True:
                    line = stream.readline()
                    if not line:
                        return
                    line = line.strip()
                    if not line:
                        break
                    key, value = line.decode().split(":", 1)
                    headers[key.lower()] = value.strip()
                self._handle(json.loads(stream.read(int(headers["content-length"]))))
        finally:
            # Wake every waiting request: the server has gone.
            with self._lock:
                for entry in self._pending.values():
                    entry[0].set()

    def _handle(self, msg):
        method = msg.get("method")
        if method and "id" in msg:  # a server-to-client request
            result = None
            if method == "workspace/configuration":
                result = [None for _ in msg["params"]["items"]]
            elif method == "workspace/applyEdit":
                result = {"applied": False}
            self._send({"jsonrpc": "2.0", "id": msg["id"], "result": result})
            return
        if not method:
            with self._lock:
                if msg.get("id") in self._pending:
                    self._pending[msg["id"]][1] = msg
                    self._pending[msg["id"]][0].set()
            return
        params = msg.get("params", {})
        if method != "window/logMessage":
            self._lsp_log.write(json.dumps({"t": time.time(), "method": method, "params": params}) + "\n")
            self._lsp_log.flush()
        if method == "textDocument/publishDiagnostics":
            self.diagnostics[params["uri"]] = params["diagnostics"]
            self.last_activity = time.monotonic()
        elif method == "language/status":
            self.status.append(params.get("type"))
            self.last_activity = time.monotonic()
        elif method in ("window/showMessage", "window/showMessageRequest") and params.get("type") == 1:
            self.errors.append(params.get("message"))
        elif method in ("$/progress", "language/progressReport"):
            self.last_activity = time.monotonic()

    # --- waiting and stopping --------------------------------------------------------

    def wait_quiet(self, quiet=8, limit=300, ready=False, since=None):
        """Wait until nothing has happened for `quiet` seconds (after ServiceReady, if `ready`).

        With `since` (a time.monotonic() taken before the client's last notifications), the
        server must also have done something after `since`, and the quiet is counted from then
        at the earliest: an earlier quiet spell doesn't count for a new step.
        """
        start = time.monotonic()
        while time.monotonic() - start < limit:
            if self.proc.poll() is not None:
                raise ConnectionError(f"JDT LS exited with {self.proc.returncode}; see {self.stderr_path}")
            last = self.last_activity
            active = since is None or last > since
            if active and (not ready or "ServiceReady" in self.status) and time.monotonic() - last > quiet:
                return time.monotonic() - start
            time.sleep(0.05 if quiet < 1 else 0.5)
        what = "had no activity" if since is not None and self.last_activity <= since else "didn't go quiet"
        raise TimeoutError(f"JDT LS {what} within {limit} s (status so far: {self.status})")

    def shutdown(self):
        """LSP shutdown and exit; kill the started process only if it doesn't stop."""
        try:
            if self.proc.poll() is None:
                self.request("shutdown", None, timeout=60)
                self.notify("exit", None)
                self.proc.wait(30)
        except Exception:
            self.kill()
        finally:
            self._lsp_log.close()
            self._stderr.close()

    def kill(self):
        if self.proc.poll() is None:
            self.proc.kill()  # self.proc is the process this client started (self.pid)
            self.proc.wait(30)
