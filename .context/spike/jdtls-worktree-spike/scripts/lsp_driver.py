#!/usr/bin/env python3
"""Drives one JDT LS process over stdio against several worktree roots and snapshots what it sees.

Each run writes to $EVIDENCE/<run>/: one JSON snapshot per step, the agent trace (agent.jsonl),
LSP notifications (lsp.jsonl) and a copy of the JDT LS workspace log.

Steps (in order, each optional except import):
  import    initialize, wait for ServiceReady and quiet, snapshot
  edits     divergent sibling change + per-worktree type error (needs roots wt-a and wt-b), snapshot
  reimport  java/projectConfigurationUpdate for every module POM in wt-b, snapshot
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import threading
import time
from pathlib import Path

HOME = Path.home()
SPIKE_HOME = Path(os.environ.get("SPIKE_HOME", HOME / ".local/state/fractal-harness/jdtls-worktree-spike"))
EVIDENCE = Path(os.environ.get("EVIDENCE", HOME / ".local/state/fractal-harness/evidence/jdtls-worktree-spike"))
JDTLS = os.environ.get("JDTLS", str(HOME / ".local/bin/jdtls"))


class Server:
    def __init__(self, cmd, env, cwd, lsp_log):
        self.proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=open(lsp_log.with_suffix(".stderr"), "wb"), env=env, cwd=cwd)
        self.lsp_log = open(lsp_log, "w")
        self.next_id = 1
        self.pending = {}
        self.diagnostics = {}
        self.status = []
        self.errors = []
        self.last_activity = time.monotonic()
        self.lock = threading.Lock()
        threading.Thread(target=self._read, daemon=True).start()

    def _send(self, msg):
        body = json.dumps(msg).encode()
        self.proc.stdin.write(b"Content-Length: %d\r\n\r\n" % len(body) + body)
        self.proc.stdin.flush()

    def request(self, method, params, timeout=300):
        with self.lock:
            rid = self.next_id
            self.next_id += 1
            event = threading.Event()
            self.pending[rid] = [event, None]
        self._send({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
        if not event.wait(timeout):
            raise TimeoutError(method)
        response = self.pending.pop(rid)[1]
        if "error" in response:
            raise RuntimeError(f"{method}: {response['error']}")
        return response.get("result")

    def notify(self, method, params):
        self._send({"jsonrpc": "2.0", "method": method, "params": params})

    def _read(self):
        stream = self.proc.stdout
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
            msg = json.loads(stream.read(int(headers["content-length"])))
            self._handle(msg)

    def _handle(self, msg):
        method = msg.get("method")
        if method and "id" in msg:  # server → client request
            result = None
            if method == "workspace/configuration":
                result = [None for _ in msg["params"]["items"]]
            elif method == "workspace/applyEdit":
                result = {"applied": False}
            self._send({"jsonrpc": "2.0", "id": msg["id"], "result": result})
            return
        if not method:
            with self.lock:
                if msg.get("id") in self.pending:
                    self.pending[msg["id"]][1] = msg
                    self.pending[msg["id"]][0].set()
            return
        params = msg.get("params", {})
        if method != "window/logMessage":
            self.lsp_log.write(json.dumps({"t": time.time(), "method": method, "params": params}) + "\n")
            self.lsp_log.flush()
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

    def wait_quiet(self, quiet=8, limit=600, ready=False):
        start = time.monotonic()
        while time.monotonic() - start < limit:
            if (not ready or "ServiceReady" in self.status) and time.monotonic() - self.last_activity > quiet:
                return time.monotonic() - start
            time.sleep(0.5)
        raise TimeoutError("server never went quiet")


def uri(path):
    return Path(path).resolve().as_uri()


def head(root):
    return subprocess.run(["git", "-C", str(root), "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()


def workspace_projects(data):
    """Eclipse project name → location, read from the workspace metadata."""
    result = {}
    projects = Path(data) / ".metadata/.plugins/org.eclipse.core.resources/.projects"
    for d in sorted(projects.glob("*")) if projects.exists() else []:
        loc = d / ".location"
        match = re.search(rb"URI//(file:[^\x00]+)", loc.read_bytes()) if loc.exists() else None
        result[d.name] = match.group(1).decode() if match else "(workspace root)"
    return result


def snapshot(server, args, roots, step):
    def rel(s):
        return re.sub(r"^file:(//)?", "", s).replace(str(SPIKE_HOME / "worktrees") + "/", "")

    project_uris = server.request("workspace/executeCommand", {"command": "java.project.getAll", "arguments": []})
    classpaths = {}
    for p in sorted(project_uris):
        try:
            cp = server.request("workspace/executeCommand",
                                {"command": "java.project.getClasspaths",
                                 "arguments": [p, json.dumps({"scope": "test"})]})
            classpaths[rel(p)] = [rel(e) for e in cp["classpaths"] if str(SPIKE_HOME) in e]
        except RuntimeError as e:
            classpaths[rel(p)] = f"error: {e}"
    errors = {rel(u): [f"{d['range']['start']['line'] + 1}: {d['message']}" for d in ds if d.get("severity") == 1]
              for u, ds in server.diagnostics.items() if str(SPIKE_HOME) in u}
    snap = {
        "run": args.run, "step": step, "mode": args.mode, "data": args.data,
        "roots": {rel(str(r)): head(r) for r in roots},
        "eclipseProjects": {k: rel(v) for k, v in workspace_projects(args.data).items()},
        "javaProjects": [rel(p) for p in sorted(project_uris)],
        "classpaths": classpaths,
        "errorDiagnostics": {k: v for k, v in sorted(errors.items()) if v},
        "serverErrorMessages": server.errors,
    }
    out = EVIDENCE / args.run / f"{step}.json"
    out.write_text(json.dumps(snap, indent=2) + "\n")
    print(json.dumps(snap, indent=2))
    return snap


def write(path, text, server, created):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)
    server.notify("workspace/didChangeWatchedFiles", {"changes": [{"uri": uri(path), "type": 1 if created else 2}]})


def edits(server, wt):
    a, b = wt / "wt-a", wt / "wt-b"
    contract = "harness-contract/src/main/java/org/fractal/harness/contract"
    app = "harness-app/src/main/java/org/fractal/harness/app"
    # Divergent sibling change: WorkType exists only in wt-b's contract; both worktrees' apps use it.
    write(b / contract / "WorkType.java", "package org.fractal.harness.contract;\n\npublic enum WorkType { CODER }\n",
          server, True)
    uses = ("package org.fractal.harness.app;\n\nimport org.fractal.harness.contract.WorkType;\n\n"
            "public class Dispatch {\n\n    public WorkType type() {\n        return WorkType.CODER;\n    }\n}\n")
    write(b / app / "Dispatch.java", uses, server, True)
    write(a / app / "Dispatch.java", uses, server, True)
    # A type error that exists only in wt-b.
    write(b / app / "Broken.java", "package org.fractal.harness.app;\n\npublic class Broken {\n\n"
          "    int value = \"not an int\";\n}\n", server, True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", required=True)
    parser.add_argument("--mode", choices=["none", "trace", "names", "fix"], required=True)
    parser.add_argument("--roots", nargs="+", default=["main", "wt-a", "wt-b"])
    parser.add_argument("--data", help="JDT LS workspace (-data); default $SPIKE_HOME/data/<run>")
    parser.add_argument("--steps", nargs="+", default=["import"])
    parser.add_argument("--no-maven-requester", action="store_true",
                        help="disable the requester around Maven parent resolution")
    args = parser.parse_args()

    wt = SPIKE_HOME / "worktrees"
    roots = [wt / r for r in args.roots]
    args.data = args.data or str(SPIKE_HOME / "data" / args.run)
    run_dir = EVIDENCE / args.run
    run_dir.mkdir(parents=True, exist_ok=True)

    cmd = [JDTLS]
    if args.mode != "none":
        agent = SPIKE_HOME / "agent/spike-agent.jar"
        cmd += [f"--jvm-arg=-javaagent:{agent}", f"--jvm-arg=-Dspike.mode={args.mode}",
                f"--jvm-arg=-Dspike.log={run_dir / 'agent.jsonl'}",
                f"--jvm-arg=-Dspike.worktrees={','.join(str(r) for r in roots)}",
                "--jvm-arg=-Dorg.osgi.framework.bootdelegation=org.fractal.spike.jdtls.boot"]
        if args.no_maven_requester:
            cmd.append("--jvm-arg=-Dspike.mavenRequester=false")
    env = dict(os.environ, JDTLS_DATA=args.data)
    server = Server(cmd, env, str(roots[0]), run_dir / "lsp.jsonl")

    folders = [{"uri": uri(r), "name": r.name} for r in roots]
    started = time.monotonic()
    server.request("initialize", {
        "processId": os.getpid(), "rootUri": uri(roots[0]), "workspaceFolders": folders,
        "capabilities": {
            "workspace": {"workspaceFolders": True, "configuration": True,
                          "didChangeWatchedFiles": {"dynamicRegistration": True}},
            "textDocument": {"publishDiagnostics": {}, "synchronization": {"didSave": True}},
            "window": {"workDoneProgress": True}},
        "initializationOptions": {
            "workspaceFolders": [f["uri"] for f in folders],
            "settings": {"java": {"import": {"gradle": {"enabled": False}},
                                  "configuration": {"updateBuildConfiguration": "automatic"}}},
            "extendedClientCapabilities": {"progressReportProvider": False}}})
    server.notify("initialized", {})
    print(f"# {args.run}: ready after {server.wait_quiet(ready=True):.0f}s (mode={args.mode}, data={args.data})")
    timings = {"import": round(time.monotonic() - started, 1)}
    snapshot(server, args, roots, "import")

    if "edits" in args.steps:
        edits(server, wt)
        t = server.wait_quiet()
        timings["edits"] = round(t, 1)
        snapshot(server, args, roots, "edits")
    if "reimport" in args.steps:
        for pom in sorted((wt / "wt-b").glob("**/pom.xml")):
            server.notify("java/projectConfigurationUpdate", {"uri": uri(pom)})
        timings["reimport"] = round(server.wait_quiet(quiet=10), 1)
        snapshot(server, args, roots, "reimport")

    try:
        server.request("shutdown", None, timeout=60)
        server.notify("exit", None)
        server.proc.wait(30)
    except Exception:
        server.proc.kill()
    log = Path(args.data) / ".metadata/.log"
    if log.exists():
        shutil.copy(log, run_dir / "jdtls.log")
    (run_dir / "timings.json").write_text(json.dumps(timings) + "\n")


if __name__ == "__main__":
    main()
