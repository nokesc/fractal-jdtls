# SPDX-License-Identifier: Apache-2.0
"""Several sessions at once (design §11 "Threading and queuing", T002.11): four clients, one per worktree, in one
JDT LS.

Each session runs the same steps in a loop, one request at a time, as a harness session would:

- navigation: definition, references, implementations, describe, symbols, type and call hierarchies, an outline;
- an edit on disk, a file with a type error, then `build`; the file deleted, then `build` again;
- a rename applied, then `problems`, then reverted with `git checkout`, then `problems`;
- a rename's dry run, then its apply with the dry run's `expectWriteSetDigest`, then reverted the same way.

main, wt-a and wt-b start together; wt-c is added during the run by its own session, which removes it and adds it again
at the end of every iteration, while the others' requests wait for each import (imports run alone).

First each session's steps run once alone, a single client: those answers are the expected ones. Every answer of the
concurrent run, with what can't be the same removed (`sync`, `syncAfter`, operation IDs, times), must equal the
single-client answer of the same step. No answer is `internal`, and the endpoint logs no error. The latency of each
operation type, and JDT LS's heap at the start and the end, are kept in runs/sessions-*/result.json. The operation
folders are deleted at the end.

The gate runs it for about a minute (`test_four_sessions_for_a_minute`); `mise run test:soak` runs it for 10 minutes
(marker `soak`, `FRACTAL_JDTLS_SOAK_SECONDS` sets the time).
"""

import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

from endpoint_client import RELEASE, Client, write_settings  # noqa: E402
from scenario import git, make_worktrees  # noqa: E402
from sync_scenario import start  # noqa: E402

NAMES = "org.fractal.harness.contract.Names"
STEP = "org.fractal.harness.contract.Step"
GREETER = "src/main/java/org/fractal/harness/app/Greeter.java"
NAMES_FILE = "src/main/java/org/fractal/harness/contract/Names.java"
EDIT = "harness-app/src/main/java/org/fractal/harness/app/SessionEdit.java"
BROKEN = 'package org.fractal.harness.app;\n\npublic class SessionEdit {\n\n    int value = "not an int";\n}\n'
NAVIGATION = [
    ("definition", "harness-app", {"target": {"file": GREETER, "text": "String clean = Names.normalize(who);", "select": "normalize"}}),
    ("references", "harness-contract", {"target": f"{NAMES}#normalize(String)"}),
    ("implementations", "harness-contract", {"target": STEP}),
    ("describe", "harness-contract", {"target": NAMES}),
    ("symbols", "harness-app", {"query": "describe", "kinds": ["method"]}),
    ("type-hierarchy", "harness-contract", {"target": STEP, "direction": "subtypes"}),
    ("call-hierarchy", "harness-contract", {"target": f"{NAMES}#normalize", "depth": 2}),
    ("outline", "harness-contract", {"file": NAMES_FILE}),
]
VARYING = {"sync", "syncAfter", "operation", "operationFolder", "instance", "importMs", "waitedMs", "uptimeMs"}
SESSIONS = ("main", "wt-a", "wt-b", "wt-c")


def normalized(value, root: Path):
    """The answer without what differs from run to run, its worktree's path as <worktree>."""
    def strip(v):
        if isinstance(v, dict):
            return {k: strip(x) for k, x in v.items() if k not in VARYING and not k.endswith("Ms")}
        if isinstance(v, list):
            return [strip(x) for x in v]
        return v
    return json.loads(json.dumps(strip(value)).replace(str(root), "<worktree>"))


def heap(pid: int) -> dict:
    """JDT LS's heap as it is, then after a full GC: jcmd beside its own java, on the PID this test started."""
    jcmd = str(Path(f"/proc/{pid}/exe").resolve().parent / "jcmd")

    def used():
        out = subprocess.run([jcmd, str(pid), "GC.heap_info"], capture_output=True, text=True, timeout=60).stdout
        m = re.search(r"total reserved (\d+)K, committed (\d+)K, used (\d+)K", out)
        return {"reservedMB": int(m.group(1)) // 1024, "committedMB": int(m.group(2)) // 1024, "usedMB": int(m.group(3)) // 1024}

    before = used()
    subprocess.run([jcmd, str(pid), "GC.run"], capture_output=True, text=True, timeout=120)
    return {"asIs": before, "afterFullGc": used()}


def percentiles(values: list) -> dict:
    xs = sorted(values)

    def at(p):
        return xs[min(len(xs) - 1, round(p / 100 * (len(xs) - 1)))]
    return {"n": len(xs), "p50": at(50), "p90": at(90), "p99": at(99), "max": xs[-1]}


class Session:
    """One harness session: a worktree, its own client, and its steps."""

    def __init__(self, name: str, root: Path, endpoint, contract, adds: bool):
        self.name, self.root, self.adds = name, root, adds
        self.client = Client(endpoint, contract)
        self.latency = {}

    def call(self, op, module, **body):
        status, value, ms = self.client.post(f"/v1/{op}", {"worktree": str(self.root), "module": module, **body})
        self.latency.setdefault(op, []).append(ms)
        self.client.calls.clear()
        return status, value

    def worktree(self, op):
        status, value, ms = self.client.post(f"/v1/worktrees/{op}", {"worktree": str(self.root)})
        self.latency.setdefault(f"worktrees/{op}", []).append(ms)
        self.client.calls.clear()
        return status, value

    def revert(self):
        git("checkout", "--", ".", cwd=self.root)

    def iteration(self) -> list:
        """The steps, each as (key, status, answer)."""
        out = []

        def step(key, result):
            out.append((key, result[0], result[1]))
            return result[1]

        for op, module, body in NAVIGATION:
            step(op, self.call(op, module, **body))
        (self.root / EDIT).write_text(BROKEN)
        step("build-broken", self.call("build", "harness-app", scope="worktree"))
        (self.root / EDIT).unlink()
        step("build-fixed", self.call("build", "harness-app", scope="worktree"))
        rename = {"target": f"{NAMES}#normalize(String)", "newName": "canonical"}
        step("rename-applied", self.call("refactor/rename", "harness-contract", **rename))
        step("problems-renamed", self.call("problems", ".", scope="worktree"))
        self.revert()
        step("problems-reverted", self.call("problems", ".", scope="worktree"))
        explain = {"target": f"{NAMES}#describe(int)", "newName": "explain"}
        dry = step("rename-dry-run", self.call("refactor/rename", "harness-contract", dryRun=True, **explain))
        step("rename-expected", self.call("refactor/rename", "harness-contract", expectWriteSetDigest=dry.get("writeSetDigest"), **explain))
        self.revert()
        step("problems-reverted-again", self.call("problems", ".", scope="worktree"))
        if self.adds:
            step("remove", self.worktree("remove"))
            step("add", self.worktree("add"))
        return out


def run_sessions(folder: Path, release_dir, maven_repository, launch_env, jvm_home, seconds: float) -> dict:
    folder.mkdir(parents=True)
    wt = make_worktrees(folder / "worktrees")
    git("worktree", "add", "-q", "--detach", str(wt / "wt-c"), cwd=wt / "main")
    settings = write_settings(folder / "settings.json", maven_repository)
    log = folder / f"data/.fractal-jdtls/{RELEASE}/endpoint.log"
    endpoint, c = start(folder, "jdtls", release_dir, jvm_home, launch_env, settings)
    result = {"seconds": seconds}
    try:
        sessions = {n: Session(n, wt / n, endpoint, c.contract, adds=(n == "wt-c")) for n in SESSIONS}

        # --- the single-client run: each session's steps alone, its answers the expected ones --------------
        expected = {}
        for n, s in sessions.items():
            status, value = s.worktree("add")
            assert status == 200 and value["worktree"]["state"] == "imported", value
            s.call("build", ".", scope="worktree")
            expected[n] = [(key, status, normalized(answer, s.root)) for key, status, answer in s.iteration()]
            assert all(status < 500 for _, status, _ in expected[n]), expected[n]
        for n, steps in expected.items():  # the expected answers say what they should
            by = {key: answer for key, _, answer in steps}
            assert by["build-broken"]["counts"]["error"] == 1 and by["build-fixed"]["counts"]["error"] == 0, by["build-broken"]
            assert by["rename-applied"]["applied"] is True and by["rename-dry-run"]["applied"] is False and by["rename-expected"]["applied"] is True
            assert by["problems-renamed"]["counts"]["error"] == 0 and by["problems-reverted-again"]["counts"]["error"] == 0
            assert by["references"]["total"] >= 1 and by["definition"]["definitions"], by["definition"]
        assert sessions["wt-c"].worktree("remove")[0] == 200
        for s in sessions.values():
            s.latency.clear()
        result["expected"] = {n: [(k, st) for k, st, _ in steps] for n, steps in expected.items()}
        result["heapStart"] = heap(endpoint.pid)

        # --- the concurrent run ----------------------------------------------------------------------
        mismatches, answers, iterations, failures = [], {}, {n: 0 for n in SESSIONS}, []
        lock = threading.Lock()
        until = time.monotonic() + seconds

        def run(s: Session):
            try:
                if s.adds:
                    time.sleep(min(5.0, seconds / 6))  # added during the run
                    status, value = s.worktree("add")
                    assert status == 200 and value["worktree"]["state"] == "imported", value
                while time.monotonic() < until:
                    got = s.iteration()
                    with lock:
                        iterations[s.name] += 1
                        for (key, status, answer), (ekey, estatus, eanswer) in zip(got, expected[s.name]):
                            answers[(s.name, key)] = answers.get((s.name, key), 0) + 1
                            mine = normalized(answer, s.root)
                            if (key, status, mine) != (ekey, estatus, eanswer):
                                mismatches.append({"session": s.name, "iteration": iterations[s.name], "step": key, "status": status,
                                                   "expectedStatus": estatus, "answer": mine, "expected": eanswer})  # fmt: skip
                        if len(got) != len(expected[s.name]):
                            mismatches.append({"session": s.name, "steps": len(got), "expectedSteps": len(expected[s.name])})
            except BaseException as e:  # noqa: BLE001 - reported, and fails the test
                with lock:
                    failures.append(f"{s.name}: {e!r}")

        started = time.monotonic()
        threads = [threading.Thread(target=run, args=(s,), name=f"session-{n}") for n, s in sessions.items()]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        result["concurrentS"] = round(time.monotonic() - started, 1)
        result["heapEnd"] = heap(endpoint.pid)
        result["iterations"] = iterations
        result["answers"] = sum(answers.values())
        result["mismatches"] = mismatches[:20]
        result["mismatchCount"] = len(mismatches)
        result["failures"] = failures
        latency = {}
        for s in sessions.values():
            for op, ms in s.latency.items():
                latency.setdefault(op, []).extend(ms)
        result["latencyMs"] = {op: percentiles(ms) for op, ms in sorted(latency.items())}
        result["internal"] = sum(1 for line in log.read_text().splitlines() if "-> 500 " in line)
        result["errors"] = [line for line in log.read_text().splitlines() if "] ERROR " in line][:10]
        result["inFlightAtEnd"] = c.status()["inFlight"]
        result["shutdown"] = endpoint.request("POST", "/v1/shutdown", body={})[0]
        result["exit"] = endpoint.wait_exit(120)
    finally:
        endpoint.stop()
        for n in SESSIONS:
            shutil.rmtree(wt / n / "temp" / "fractal-jdtls", ignore_errors=True)
        result["operationFoldersLeft"] = sorted(str(p) for n in SESSIONS for p in (wt / n).glob("temp/fractal-jdtls/*"))
        (folder / "result.json").write_text(json.dumps(result, indent=1) + "\n")
    return result


def assert_sessions(r: dict):
    assert r["failures"] == [], r["failures"]
    assert r["mismatchCount"] == 0, r["mismatches"]
    assert all(r["iterations"][n] >= 1 for n in SESSIONS), r["iterations"]
    assert r["iterations"]["wt-c"] >= 1, "wt-c was removed and added again at least once"
    assert r["internal"] == 0 and r["errors"] == [], r["errors"]
    assert r["inFlightAtEnd"] == [] and r["shutdown"] == 202 and r["exit"] == 0
    assert r["operationFoldersLeft"] == []
    for key in ("heapStart", "heapEnd"):
        assert r[key]["afterFullGc"]["usedMB"] > 0


def test_four_sessions_for_a_minute(scratch, release_dir, maven_repository, launch_env, jvm_home):
    r = run_sessions(scratch / "runs" / "sessions-gate", release_dir, maven_repository, launch_env, jvm_home, 60)
    print(json.dumps({k: r[k] for k in ("concurrentS", "iterations", "answers", "heapStart", "heapEnd", "latencyMs")}, indent=1), flush=True)
    assert_sessions(r)


@pytest.mark.soak
def test_four_sessions_for_ten_minutes(scratch, release_dir, maven_repository, launch_env, jvm_home):
    seconds = float(os.environ.get("FRACTAL_JDTLS_SOAK_SECONDS", "600"))
    r = run_sessions(scratch / "runs" / "sessions-soak", release_dir, maven_repository, launch_env, jvm_home, seconds)
    print(json.dumps({k: r[k] for k in ("concurrentS", "iterations", "answers", "heapStart", "heapEnd", "latencyMs")}, indent=1), flush=True)
    assert_sessions(r)
