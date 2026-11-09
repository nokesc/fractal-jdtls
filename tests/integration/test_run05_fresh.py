# SPDX-License-Identifier: Apache-2.0
"""Run 05 through `build`, the same every time (design §11 "Keeping in step with the disk"): each repetition on a
fresh copy of the fixture, in a fresh JDT LS on a fresh -data. Add main, wt-a and wt-b; write run 05's edits to disk,
without telling the endpoint; `build` each worktree, and compare every problem, positions and all.

The gate runs 2 repetitions (`test_run05_on_fresh_copies_in_the_gate`). The acceptance's 20 are `mise run
test:run05` (marker `run05`; `FRACTAL_JDTLS_RUN05` sets the count), whose summary is runs/run05-summary.json. Each
repetition's numbers stay in runs/run05-*/result.json; its tree and -data are deleted once they're written, unless
the result differs.
"""

import json
import os
import shutil
import statistics
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

from endpoint_client import AGENT_ERROR, RELEASE, write_settings  # noqa: E402
from scenario import make_worktrees  # noqa: E402
from sync_scenario import EXPECTED, errors, start, write_run05_edits  # noqa: E402


def fresh_run05(folder: Path, release: Path, jvm_home: Path, launch_env: dict, settings: Path) -> dict:
    folder.mkdir(parents=True)
    wt = make_worktrees(folder / "worktrees")
    result = {"adds": {}, "builds": {}}
    endpoint, c = start(folder, "jdtls", release, jvm_home, launch_env, settings)
    try:
        for name in EXPECTED:
            status, value, ms = c.post("/v1/worktrees/add", {"worktree": str(wt / name)})
            assert status == 200, value
            result["adds"][name] = ms
        write_run05_edits(wt)
        for name in EXPECTED:
            status, value, ms = c.post("/v1/build", {"worktree": str(wt / name), "module": "harness-app", "scope": "worktree"})
            result["builds"][name] = {"status": status, "ms": ms, "sync": value.get("sync"), "problems": value.get("problems", value)}
        assert endpoint.request("POST", "/v1/shutdown", body={})[0] == 202
        result["exit"] = endpoint.wait_exit(120)
        result["agentErrors"] = [line for line in endpoint.stderr().splitlines() if AGENT_ERROR in line]
    finally:
        endpoint.stop()
    result["errors"] = {name: errors({"problems": b["problems"]}) if b["status"] == 200 else b for name, b in result["builds"].items()}
    result["asRun05"] = all(result["errors"][name] == expected for name, expected in EXPECTED.items())
    (folder / "result.json").write_text(json.dumps(result, indent=1) + "\n")
    if result["asRun05"]:
        # The test scratch folder keeps six sessions (design §8): keep the numbers and the logs, not the trees.
        logs = folder / "logs"
        logs.mkdir()
        for log in (folder / f"data/.fractal-jdtls/{RELEASE}/endpoint.log", folder / "data/.metadata/.log"):
            if log.exists():
                shutil.copy(log, logs / log.name.lstrip("."))
        for tree in (folder / "data", folder / "worktrees"):
            shutil.rmtree(tree, ignore_errors=True)
    return result


def repeat(scratch, release_dir, launch_env, jvm_home, maven_repository, count: int, prefix: str) -> dict:
    (scratch / "runs" / f"{prefix}-settings").mkdir(parents=True, exist_ok=True)
    settings = write_settings(scratch / "runs" / f"{prefix}-settings" / "settings.json", maven_repository)
    results = []
    for i in range(count):
        r = fresh_run05(scratch / "runs" / f"{prefix}-{i:02d}", release_dir, jvm_home, launch_env, settings)
        results.append(r)
        print(f"{prefix} {i:02d}: {'= run 05' if r['asRun05'] else r['errors']}, builds "
              f"{[r['builds'][n]['ms'] for n in EXPECTED]} ms", flush=True)  # fmt: skip
    outcomes = {json.dumps({n: r["builds"][n]["problems"] for n in EXPECTED}, sort_keys=True) for r in results}
    build_ms = {n: sorted(r["builds"][n]["sync"]["totalMs"] for r in results) for n in EXPECTED}
    summary = {
        "repetitions": count,
        "asRun05": sum(r["asRun05"] for r in results),
        "distinctOutcomes": len(outcomes),
        "errorsEachTime": {n: [list(e) for e in expected] for n, expected in EXPECTED.items()},
        "buildSyncTotalMs": {n: {"min": v[0], "median": statistics.median(v), "max": v[-1]} for n, v in build_ms.items()},
        "addMs": {n: sorted(r["adds"][n] for r in results) for n in EXPECTED},
        "exits": sorted({r["exit"] for r in results}),
        "agentErrors": sum(len(r["agentErrors"]) for r in results),
    }
    (scratch / "runs" / f"{prefix}-summary.json").write_text(json.dumps(summary, indent=1) + "\n")
    print(json.dumps(summary, indent=1))
    return summary


def assert_same_every_time(summary: dict, count: int):
    assert summary["asRun05"] == count and summary["distinctOutcomes"] == 1, summary
    assert summary["exits"] == [0] and summary["agentErrors"] == 0


def test_run05_on_fresh_copies_in_the_gate(scratch, release_dir, launch_env, jvm_home, maven_repository):
    assert_same_every_time(repeat(scratch, release_dir, launch_env, jvm_home, maven_repository, 2, "run05-gate"), 2)


@pytest.mark.run05
def test_run05_on_twenty_fresh_copies(scratch, release_dir, launch_env, jvm_home, maven_repository):
    count = int(os.environ.get("FRACTAL_JDTLS_RUN05", "20"))
    assert_same_every_time(repeat(scratch, release_dir, launch_env, jvm_home, maven_repository, count, "run05"), count)
