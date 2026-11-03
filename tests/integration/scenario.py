# SPDX-License-Identifier: Apache-2.0
"""The integration scenarios' moving parts, ported from the spike's driver and scripts.

- `make_worktrees` builds the fixture: a scratch main checkout and two linked worktrees, all at
  one commit, with identical Maven coordinates (the spike's make-fixture.sh).
- `JdtlsRun` launches one JDT LS, initializes it with every root as a workspace folder, drives
  the steps (import, edits, reimport) and snapshots what it sees through LSP and its workspace.
- `DiagnosticLog` reads the agent's diagnostic log (design §6).

Everything a run needs is passed in: the command, the folders, the Maven settings. Nothing
comes from the environment, the real home or a hand-made install.
"""

import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path
from xml.sax.saxutils import escape

from lsp_client import LanguageServer

FIXTURE = Path(__file__).resolve().parent / "fixture"
ROOTS = ("main", "wt-a", "wt-b")
MODULES = ("harness-contract", "harness-app")
CONTRACT = "harness-contract/src/main/java/org/fractal/harness/contract"
APP = "harness-app/src/main/java/org/fractal/harness/app"

# Git without the user's or the system's configuration: no hooks, signing or templates.
GIT_ENV = {"GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1"}


def git(*args, cwd):
    return subprocess.run(
        ["git", *args], cwd=cwd, env={**os.environ, **GIT_ENV}, check=True, capture_output=True, text=True
    ).stdout


def make_worktrees(folder: Path) -> Path:
    """Create <folder>/{main,wt-a,wt-b}: a Git main checkout of the fixture and two linked worktrees."""
    main = folder / "main"
    shutil.copytree(
        FIXTURE, main, ignore=shutil.ignore_patterns("target", ".project", ".classpath", ".settings", ".factorypath")
    )
    # The POMs are stored as pom.xml.fixture so nothing imports them from this checkout
    # (design §8); only these scratch copies are real POMs.
    for stored in main.rglob("pom.xml.fixture"):
        stored.rename(stored.with_name("pom.xml"))
    git("init", "-q", "-b", "main", cwd=main)
    git("add", "-A", cwd=main)
    git("-c", "user.name=fractal-jdtls tests", "-c", "user.email=tests@localhost", "commit", "-q", "-m", "fixture", cwd=main)
    for name in ROOTS[1:]:
        git("worktree", "add", "-q", "--detach", str(folder / name), cwd=main)
    return folder


def verify(release: Path) -> subprocess.CompletedProcess:
    """The install's own install.py --verify."""
    return subprocess.run(
        [sys.executable, "-I", "-B", str(release / "bin" / "install.py"), "--verify"], capture_output=True, text=True
    )


def maven_settings(path: Path, local_repository: Path) -> Path:
    """A Maven settings.xml whose only change is the local repository (design §8)."""
    path.write_text(
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">\n'
        f"  <localRepository>{escape(str(local_repository))}</localRepository>\n"
        "</settings>\n",
        encoding="utf-8",
    )
    return path


def uri(path) -> str:
    return Path(path).resolve().as_uri()


def local_path(location: str) -> str:
    """A filesystem path from a file URI (percent escapes decoded) or from a path, which is kept as it is."""
    if location.startswith("file:"):
        return urllib.request.url2pathname(urllib.parse.urlparse(location).path)
    return location


def relative(location: str, worktrees: Path) -> str:
    """A path or file URI, relative to the folder holding the worktrees (unchanged if it's outside)."""
    path = local_path(location).rstrip("/")
    prefix = str(worktrees) + "/"
    return path[len(prefix):] if path.startswith(prefix) else path


def error_diagnostics(diagnostics: dict, worktrees: Path) -> dict:
    """Error-severity diagnostics of files in the worktrees, by relative path: sorted "<line>: <message>"."""
    errors = {}
    for document, items in diagnostics.items():
        path = local_path(document)
        if not path.startswith(str(worktrees) + "/"):
            continue
        messages = sorted(f"{d['range']['start']['line'] + 1}: {d['message']}" for d in items if d.get("severity") == 1)
        if messages:
            errors[relative(document, worktrees)] = messages
    return dict(sorted(errors.items()))


class JdtlsRun:
    """One JDT LS process on the fixture's roots, written up in `run_dir`."""

    def __init__(self, cmd, *, env, worktrees: Path, data: Path, settings: Path, run_dir: Path):
        self.worktrees = worktrees
        self.roots = [worktrees / name for name in ROOTS]
        self.data = data
        self.settings = settings
        self.run_dir = run_dir
        self.snapshots = {}
        self.timings = {}
        run_dir.mkdir(parents=True, exist_ok=True)
        (run_dir / "command.json").write_text(json.dumps(cmd, indent=1) + "\n")
        self.server = LanguageServer(cmd, env, str(self.roots[0]), run_dir)

    # --- steps ----------------------------------------------------------------------

    def start(self):
        """initialize with every root as a workspace folder; wait for ServiceReady and quiet; snapshot."""
        folders = [{"uri": uri(r), "name": r.name} for r in self.roots]
        started = time.monotonic()
        self.server.request(
            "initialize",
            {
                "processId": os.getpid(),
                "rootUri": uri(self.roots[0]),
                "workspaceFolders": folders,
                "capabilities": {
                    "workspace": {
                        "workspaceFolders": True,
                        "configuration": True,
                        "didChangeWatchedFiles": {"dynamicRegistration": True},
                    },
                    "textDocument": {"publishDiagnostics": {}, "synchronization": {"didSave": True}},
                    "window": {"workDoneProgress": True},
                },
                "initializationOptions": {
                    "workspaceFolders": [f["uri"] for f in folders],
                    "settings": {
                        "java": {
                            "import": {"gradle": {"enabled": False}},
                            "configuration": {
                                "updateBuildConfiguration": "automatic",
                                # The suite's own Maven local repository, never ~/.m2 (design §8).
                                "maven": {"userSettings": str(self.settings)},
                            },
                        }
                    },
                    "extendedClientCapabilities": {"progressReportProvider": False},
                },
            },
        )
        self.server.notify("initialized", {})
        self.server.wait_quiet(ready=True)
        self.timings["import"] = round(time.monotonic() - started, 1)
        self._record_process()
        return self.snapshot("import")

    def edits(self):
        """Run 05's edits: WorkType only in wt-b's contract, used by wt-a's and wt-b's apps; a type error in wt-b."""
        a, b = self.worktrees / "wt-a", self.worktrees / "wt-b"
        since = time.monotonic()
        self._write(b / CONTRACT / "WorkType.java", "package org.fractal.harness.contract;\n\npublic enum WorkType { CODER }\n")
        uses = (
            "package org.fractal.harness.app;\n\nimport org.fractal.harness.contract.WorkType;\n\n"
            "public class Dispatch {\n\n    public WorkType type() {\n        return WorkType.CODER;\n    }\n}\n"
        )
        self._write(b / APP / "Dispatch.java", uses)
        self._write(a / APP / "Dispatch.java", uses)
        self._write(b / APP / "Broken.java", 'package org.fractal.harness.app;\n\npublic class Broken {\n\n    int value = "not an int";\n}\n')
        self.timings["edits"] = round(self.server.wait_quiet(since=since), 1)
        return self.snapshot("edits")

    def reimport(self):
        """Run 05's reimport: java/projectConfigurationUpdate for every POM in wt-b."""
        since = time.monotonic()
        for pom in sorted((self.worktrees / "wt-b").glob("**/pom.xml")):
            self.server.notify("java/projectConfigurationUpdate", {"uri": uri(pom)})
        self.timings["reimport"] = round(self.server.wait_quiet(quiet=10, since=since), 1)
        return self.snapshot("reimport")

    def stop(self):
        self.server.shutdown()
        log = self.data / ".metadata/.log"
        if log.exists():
            shutil.copy(log, self.run_dir / "jdtls.log")
        (self.run_dir / "timings.json").write_text(json.dumps(self.timings) + "\n")

    def kill(self):
        self.server.kill()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        if exc[0] is not None:
            self.kill()
        self.stop()

    # --- observation -------------------------------------------------------------------

    def _write(self, path: Path, text: str):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        self.server.notify("workspace/didChangeWatchedFiles", {"changes": [{"uri": uri(path), "type": 1}]})

    def _record_process(self):
        """The launcher replaced itself with java, so the PID the suite started is the JVM."""
        pid = self.server.pid
        argv = Path(f"/proc/{pid}/cmdline").read_bytes().split(b"\0")[:-1]
        self.process = {"pid": pid, "exe": os.readlink(f"/proc/{pid}/exe"), "argv": [a.decode() for a in argv]}
        (self.run_dir / "process.json").write_text(json.dumps(self.process, indent=1) + "\n")

    def rel(self, s: str) -> str:
        return relative(s, self.worktrees)

    def snapshot(self, step):
        project_uris = sorted(self.server.execute("java.project.getAll"))
        classpaths = {}
        for project in project_uris:
            try:
                cp = self.server.execute("java.project.getClasspaths", project, json.dumps({"scope": "test"}))
                classpaths[self.rel(project)] = sorted(
                    self.rel(e) for e in cp["classpaths"] if local_path(e).startswith(str(self.worktrees) + "/")
                )
            except RuntimeError as error:
                classpaths[self.rel(project)] = f"error: {error}"
        snap = {
            "step": step,
            "roots": {r.name: git("rev-parse", "HEAD", cwd=r).strip() for r in self.roots},
            "eclipseProjects": {k: self.rel(v) for k, v in workspace_projects(self.data).items()},
            "javaProjects": [self.rel(p) for p in project_uris],
            "classpaths": classpaths,
            "errorDiagnostics": error_diagnostics(self.server.diagnostics, self.worktrees),
            "serverErrorMessages": list(self.server.errors),
        }
        (self.run_dir / f"{step}.json").write_text(json.dumps(snap, indent=2) + "\n")
        self.snapshots[step] = snap
        return snap


def workspace_projects(data: Path) -> dict:
    """Eclipse project name to location, read from the workspace metadata."""
    result = {}
    projects = data / ".metadata/.plugins/org.eclipse.core.resources/.projects"
    for d in sorted(projects.glob("*")) if projects.exists() else []:
        loc = d / ".location"
        match = re.search(rb"URI//(file:[^\x00]+)", loc.read_bytes()) if loc.exists() else None
        result[d.name] = match.group(1).decode() if match else "(workspace root)"
    return result


class DiagnosticLog:
    """The agent's diagnostic log: one JSON object per line (design §6)."""

    def __init__(self, path: Path):
        self.path = path
        self.events = [json.loads(line) for line in path.read_text().splitlines() if line.strip()]

    def of(self, kind):
        return [e for e in self.events if e["event"] == kind]

    def counts(self):
        counts = {}
        for e in self.events:
            counts[e["event"]] = counts.get(e["event"], 0) + 1
        return counts
