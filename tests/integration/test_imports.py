# SPDX-License-Identifier: Apache-2.0
"""Imports stay healthy (design §11 "Worktrees"): the large tree imported while requests for another worktree keep
arriving, each held until the import and its health check are done.

Each import runs in a fresh process, on a fresh -data and a freshly generated tree (large_tree.py, 51 Maven projects
and 10,001 Java files): add the fixture's main checkout, then add the large tree, while a new `modules/check` on
main arrives every second. In T002.01, 5 of 22 such imports, with requests running during them, silently broke a
module; imports now run alone, and every add ends with the health check, and at most one repair.

The gate runs one import (`test_a_large_import_holds_other_requests_and_stays_healthy`), and on its tree measures
the fixed point's costs and cancels real work at a request's deadline (T002.11): a references search to `Base.touch`
(10,000 references) through `$/cancelRequest`, after which no JDT LS thread is left in its handler, and a clean build
of the 51 projects through the cancelled progress monitor; the next build has no errors. The 20 imports of the
acceptance are `mise run test:imports` (marker `imports`, about 10 minutes); each run's numbers are written to
runs/imports-*/summary.json.
"""

import json
import os
import re
import shutil
import statistics
import subprocess
import sys
import threading
import time
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

from endpoint_client import AGENT_ERROR, RELEASE, Client, Contract, EndpointProcess, http_command, write_settings  # noqa: E402
from large_tree import generate, module_names  # noqa: E402
from scenario import make_worktrees  # noqa: E402

ARRIVAL_S = 1.0
REPEATS = 10  # of each sync measurement on the large tree
METADATA = {".project", ".classpath", ".settings", ".factorypath"}


def sync_costs(c: Client, big: Path) -> dict:
    """The fixed point's costs on the large tree (design §11 "Keeping in step with the disk"), every sync report kept:
    the first build; problems and build with nothing changed (the fast path, and a refresh that finds nothing); then
    problems after each of ten edits to one file of m25, and after it's restored."""
    def call(op):
        status, value, ms = c.post(f"/v1/{op}", {"worktree": str(big), "module": "m25", "scope": "worktree", "timeoutMs": 1_800_000})
        assert status == 200, value
        return {"ms": ms, "errors": value["counts"]["error"], **value["sync"]}

    out = {"firstBuild": call("build")}
    out["problemsUnchanged"] = [call("problems") for _ in range(REPEATS)]
    # T002.05's review, R3: the fast path's check takes longer than this timeoutMs, so the answer is refused.
    status, value, ms = c.post("/v1/problems", {"worktree": str(big), "module": "m25", "scope": "worktree", "timeoutMs": 100})
    out["fastPathPastItsDeadline"] = {"status": status, "code": value.get("code"), "phase": value.get("phase"), "ms": ms}
    out["buildUnchanged"] = [call("build") for _ in range(REPEATS)]
    edited = big / "m25/src/main/java/org/gen/m25/C25_100.java"
    original = edited.read_text()
    out["problemsAfterOneEdit"] = []
    for i in range(REPEATS):
        edited.write_text(original.replace("return sum * ", f"return {i + 2} * sum * "))
        out["problemsAfterOneEdit"].append(call("problems"))
    edited.write_text(original)
    out["restored"] = call("problems")
    for runs in out.values():
        for r in runs if isinstance(runs, list) else []:
            r.pop("filesChanged", None)  # the first build's are kept
    return out


def cancels(c: Client, big: Path, pid: int, log: Path) -> dict:
    """Real work stopped at a request's deadline (design §11 "Threading and queuing", T002.11), on the large tree."""
    jcmd = str(Path(f"/proc/{pid}/exe").resolve().parent / "jcmd")

    def handlers():
        dump = subprocess.run([jcmd, str(pid), "Thread.print"], capture_output=True, text=True, timeout=60).stdout
        return [b.split('"')[1] for b in dump.split("\n\n") if b.startswith('"') and "ReferencesHandler" in b]

    def lines(pattern):
        return [line.split("] ", 1)[1] for line in log.read_text().splitlines() if re.search(pattern, line)]

    touch = {"worktree": str(big), "module": "m00", "target": "org.gen.m00.Base#touch(int)", "limit": 1}
    out = {}
    status, value, ms = c.post("/v1/references", {**touch, "timeoutMs": 600_000})
    out["references"] = {"status": status, "ms": ms, "total": value.get("total"), "truncated": value.get("truncated")}
    status, value, ms = c.post("/v1/references", {**touch, "timeoutMs": 1500})
    out["referencesCancelled"] = {"status": status, "ms": ms, "code": value.get("code"), "phase": value.get("phase")}
    time.sleep(1.0)
    out["referencesHandlersAfter"] = handlers()
    out["cancelLog"] = lines(r"\$/cancelRequest|no longer waits for|timeout: stopped")
    body = {"worktree": str(big), "module": "m00", "scope": "worktree"}
    status, value, ms = c.post("/v1/build", {**body, "clean": True, "timeoutMs": 2500})
    out["cleanBuildCancelled"] = {"status": status, "ms": ms, "code": value.get("code"), "phase": value.get("phase")}
    out["buildLog"] = lines(r"build: timeout: stopped")
    status, value, ms = c.post("/v1/build", {**body, "timeoutMs": 1_800_000})
    out["buildAfter"] = {"status": status, "ms": ms, "errors": value.get("counts", {}).get("error"), "buildMs": value.get("sync", {}).get("buildMs")}
    return out


def large_import(folder: Path, release: Path, jvm_home: Path, launch_env: dict, settings: Path, measure_sync: bool = False) -> dict:
    """One import of a fresh large tree, with requests on main arriving throughout. Everything measured."""
    folder.mkdir(parents=True)
    main = make_worktrees(folder / "worktrees") / "main"
    generated = time.monotonic()
    big = generate(folder / "big" / "big")
    result = {"generateS": round(time.monotonic() - generated, 1)}
    port_file = folder / "port.json"
    endpoint = EndpointProcess(http_command(release, jvm_home, folder / "data", port_file, settings), env=launch_env,
                               run_dir=folder / "jdtls", port_file=port_file).start()  # fmt: skip
    try:
        endpoint.wait_port_file()
        status, _, text = endpoint.request("GET", "/v1/openapi.json")
        c = Client(endpoint, Contract(json.loads(text)))
        assert endpoint.request("GET", "/v1/status?wait=ready&timeoutMs=300000")[0] == 200
        status, value, ms = c.post("/v1/worktrees/add", {"worktree": str(main)})
        assert status == 200 and value["worktree"]["state"] == "imported", value
        result["mainAddMs"] = ms
        result["cmdline"] = Path(f"/proc/{endpoint.pid}/cmdline").read_bytes().split(b"\0")

        held, statuses, threads = [], [], []
        done = threading.Event()
        lock = threading.Lock()

        def one():
            arrived = time.monotonic()
            status, value, ms = c.post("/v1/modules/check", {"worktree": str(main), "module": "harness-app", "timeoutMs": 1_800_000})
            with lock:
                held.append({"arrivedS": round(arrived - add_start, 2), "answeredS": round(time.monotonic() - add_start, 2),
                             "status": status, "waitedMs": value.get("waitedMs"), "ms": ms})  # fmt: skip

        def arrivals():
            while not done.is_set():
                t = threading.Thread(target=one)
                t.start()
                threads.append(t)
                s = c.status()
                statuses.append({"atS": round(time.monotonic() - add_start, 2), "importing": s["importing"],
                                 "inFlight": [(o["path"], o["phase"]) for o in s["inFlight"]]})  # fmt: skip
                done.wait(ARRIVAL_S)

        expired = []

        def short():
            # T002.04's review, R3: a request whose timeoutMs passes while it waits, for a worker or the import,
            # is never run. Ten at once, 5 s into the import, when the held requests and the add fill 6 of the 8
            # workers, so most of the ten wait for a worker.
            time.sleep(5)
            def one_short():
                status, value, ms = c.post("/v1/modules/check", {"worktree": str(main), "module": "harness-app", "timeoutMs": 1000})
                with lock:
                    expired.append({"status": status, "code": value.get("code"), "reason": value.get("reason"), "ms": ms})
            shorts = [threading.Thread(target=one_short) for _ in range(10)]
            for t in shorts:
                t.start()
            for t in shorts:
                t.join()

        add_start = time.monotonic()
        arriving = threading.Thread(target=arrivals)
        arriving.start()
        shorts = threading.Thread(target=short)
        shorts.start()
        try:
            status, value, ms = c.post("/v1/worktrees/add", {"worktree": str(big)})
        finally:
            add_end = round(time.monotonic() - add_start, 2)
            done.set()
            arriving.join()
            shorts.join()
            for t in threads:
                t.join()
        result["add"] = {"status": status, "ms": ms, "endS": add_end, "importMs": value.get("importMs"),
                         "state": value.get("worktree", {}).get("state", value.get("code")),
                         "repair": value.get("worktree", {}).get("repair"), "problem": value if status != 200 else None}  # fmt: skip
        modules = value.get("worktree", {}).get("modules", [])
        result["modules"] = len(modules)
        result["modulesNotImported"] = [m for m in modules if m["state"] != "imported"]
        result["moduleNames"] = [m["module"] for m in modules]
        during = [h for h in held if h["arrivedS"] < add_end]
        result["held"] = {
            "count": len(during),
            "allAnsweredAfterTheImport": all(h["answeredS"] >= add_end - 0.05 and h["status"] == 200 for h in during),
            "heldS": sorted(round(h["answeredS"] - h["arrivedS"], 2) for h in during),
            "waitedMs": sorted(h["waitedMs"] for h in during),
        }
        held_s = result["held"]["heldS"]
        if held_s:
            result["held"]["maxS"], result["held"]["medianS"] = max(held_s), statistics.median(held_s)
        result["expired"] = expired
        result["statusDuringImport"] = [s for s in statuses if s["atS"] < add_end - 0.5]
        result["after"] = c.post("/v1/modules/check", {"worktree": str(main), "module": "harness-app"})[1]
        if measure_sync:
            result["sync"] = sync_costs(c, big)
            result["cancels"] = cancels(c, big, endpoint.pid, folder / "data" / ".fractal-jdtls" / RELEASE / "endpoint.log")
        result["metadataFiles"] = sorted(str(p.relative_to(big)) for p in big.rglob("*") if p.name in METADATA)
        result["shutdown"] = endpoint.request("POST", "/v1/shutdown", body={})[0]
        result["exit"] = endpoint.wait_exit(120)
        result["agentErrors"] = [line for line in endpoint.stderr().splitlines() if AGENT_ERROR in line]
    finally:
        endpoint.stop()
    log = (folder / "data" / ".fractal-jdtls" / RELEASE / "endpoint.log")
    result["repairLog"] = [line for line in log.read_text().splitlines() if "REPAIR" in line] if log.exists() else []
    result["generatesMetadataFilesAtProjectRoot"] = [a.decode() for a in result.pop("cmdline")
                                                     if a.startswith(b"-Djava.import.generatesMetadataFilesAtProjectRoot")]  # fmt: skip
    (folder / "result.json").write_text(json.dumps(result, indent=1) + "\n")
    broken = result["add"]["repair"] or result["add"]["state"] != "imported"
    if not broken:
        # The test scratch folder keeps six sessions (design §8): keep the numbers and the logs, not the tree and its
        # -data (about 200 MB an import). A broken import keeps everything, to look at.
        result["kept"] = False
        logs = folder / "logs"
        logs.mkdir()
        for log in (folder / f"data/.fractal-jdtls/{RELEASE}/endpoint.log", folder / "data/.metadata/.log"):
            if log.exists():
                shutil.copy(log, logs / log.name.lstrip("."))
        for tree in (folder / "data", folder / "big", folder / "worktrees"):
            shutil.rmtree(tree, ignore_errors=True)
    return result


def assert_healthy(r: dict):
    assert r["add"]["status"] == 200 and r["add"]["state"] == "imported", r["add"]
    assert r["modules"] == 51 and r["modulesNotImported"] == [] and r["moduleNames"] == module_names()
    assert r["generatesMetadataFilesAtProjectRoot"] == ["-Djava.import.generatesMetadataFilesAtProjectRoot=false"]
    assert r["metadataFiles"] == []
    assert r["held"]["count"] >= 2 and r["held"]["allAnsweredAfterTheImport"], r["held"]
    assert any(s["importing"] for s in r["statusDuringImport"]), "GET /v1/status answered during the import, and showed it"
    assert r["after"]["module"]["state"] == "imported"
    # Requests with a 1 s timeoutMs, sent while the import ran: none was run (T002.04's review, R3).
    assert len(r["expired"]) == 10 and all(e["status"] == 503 and e["code"] in ("not-ready", "busy") for e in r["expired"]), r["expired"]
    assert r["exit"] == 0 and r["agentErrors"] == []
    if r["add"]["repair"]:  # a broken import was repaired, and reported
        assert r["add"]["repair"]["result"] == "repaired" and r["repairLog"], r


@pytest.fixture(scope="module")
def settings(scratch, maven_repository):
    folder = scratch / "runs" / "imports-settings"
    folder.mkdir(parents=True, exist_ok=True)
    return write_settings(folder / "settings.json", maven_repository)


def test_a_large_import_holds_other_requests_and_stays_healthy(scratch, release_dir, launch_env, jvm_home, settings):
    r = large_import(scratch / "runs" / "imports-gate", release_dir, jvm_home, launch_env, settings, measure_sync=True)
    assert_healthy(r)
    assert r["add"]["repair"] is None, f"the import broke, and was repaired: {r['add']['repair']}"
    s = r["sync"]
    assert s["firstBuild"]["errors"] == 0 and s["firstBuild"]["notSkippedBecause"] == "build"
    assert not [f for f in s["firstBuild"]["filesChanged"] if Path(f["path"]).name in METADATA or ".settings" in Path(f["path"]).parts], \
        "JDT LS's own project files, kept under -data, aren't reported as the worktree's"
    assert all(p["skipped"] for p in s["problemsUnchanged"]), s["problemsUnchanged"]
    late = s["fastPathPastItsDeadline"]
    assert (late["status"], late["code"], late["phase"]) == (504, "timeout", "syncing"), late
    assert all(not b["skipped"] and b["filesChangedCount"] == 0 for b in s["buildUnchanged"]), s["buildUnchanged"]
    assert all(p["notSkippedBecause"] == "disk-changed" and p["filesChangedCount"] == 1 for p in s["problemsAfterOneEdit"]), s["problemsAfterOneEdit"]
    assert all(p["errors"] == 0 for p in s["problemsAfterOneEdit"]) and s["restored"]["errors"] == 0
    x = r["cancels"]
    assert x["references"]["status"] == 200 and x["references"]["total"] == 10_000, x["references"]
    assert x["references"]["ms"] > 3000, "a search long enough to cancel 1.5 s in"
    assert (x["referencesCancelled"]["status"], x["referencesCancelled"]["code"]) == (504, "timeout"), x["referencesCancelled"]
    assert any("$/cancelRequest" in line and "textDocument/references" in line for line in x["cancelLog"]), x["cancelLog"]
    assert any("-32800" in line for line in x["cancelLog"]), x["cancelLog"]
    assert x["referencesHandlersAfter"] == [], "JDT LS's references search stopped at the cancel"
    assert (x["cleanBuildCancelled"]["status"], x["cleanBuildCancelled"]["code"], x["cleanBuildCancelled"]["phase"]) == (504, "timeout", "syncing"), x
    assert x["cleanBuildCancelled"]["ms"] < 4000, x["cleanBuildCancelled"]
    assert x["buildLog"] and float(re.search(r" ([0-9.]+) ms after the deadline", x["buildLog"][-1]).group(1)) < 1000, x["buildLog"]
    assert x["buildAfter"]["status"] == 200 and x["buildAfter"]["errors"] == 0, x["buildAfter"]


@pytest.mark.imports
def test_twenty_large_imports_stay_healthy(scratch, release_dir, launch_env, jvm_home, settings):
    count = int(os.environ.get("FRACTAL_JDTLS_IMPORTS", "20"))
    results = []
    for i in range(count):
        r = large_import(scratch / "runs" / f"imports-{i:02d}", release_dir, jvm_home, launch_env, settings)
        results.append(r)
        print(f"import {i:02d}: {r['add']['state']} in {r['add']['ms']} ms, repair {r['add']['repair']}, "
              f"{r['held']['count']} held, longest {r['held'].get('maxS')} s", flush=True)  # fmt: skip
    held = [s for r in results for s in r["held"]["heldS"]]
    summary = {
        "imports": count,
        "broken": sum(1 for r in results if r["add"]["repair"] or r["add"]["state"] != "imported"),
        "repaired": sum(1 for r in results if r["add"]["repair"] and r["add"]["repair"]["result"] == "repaired"),
        "importFailed": sum(1 for r in results if r["add"]["state"] != "imported"),
        "addMs": sorted(r["add"]["ms"] for r in results),
        "heldRequests": len(held),
        "heldS": {"min": min(held), "median": statistics.median(held), "max": max(held)} if held else None,
        "repairs": [r["add"]["repair"] for r in results if r["add"]["repair"]],
    }
    (scratch / "runs" / "imports-summary.json").write_text(json.dumps(summary, indent=1) + "\n")
    print(json.dumps(summary, indent=1))
    for r in results:
        assert_healthy(r)
