# SPDX-License-Identifier: Apache-2.0
"""The import health check's one repair (design §11 "Worktrees"), through HTTP only.

- **Repaired once, and reported.** The broken imports of T002.01 left a module's project without m2e's facade or
  the Java nature. That's forced here the way the governor allowed for T002.04, with no switch in the product: a
  stopped workspace's project description under -data loses its Java nature, then JDT LS restarts on it, so the
  restart's health check fails, and the repair (removing and adding the worktree again) fixes it.
- **Still broken after the repair: import-failed.** A module whose parent POM can't be resolved never gets an m2e
  facade, however often it's imported.
"""

import json
import re
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

from endpoint_client import RELEASE, Client, Contract, EndpointProcess, http_command, write_settings  # noqa: E402
from scenario import git, make_worktrees  # noqa: E402

JAVA_NATURE = "<nature>org.eclipse.jdt.core.javanature</nature>"


def start(folder: Path, name: str, release, jvm_home, launch_env, settings):
    port_file = folder / f"{name}-port.json"
    endpoint = EndpointProcess(http_command(release, jvm_home, folder / "data", port_file, settings), env=launch_env,
                               run_dir=folder / name, port_file=port_file).start()  # fmt: skip
    endpoint.wait_port_file()
    status, _, text = endpoint.request("GET", "/v1/openapi.json")
    assert status == 200
    assert endpoint.request("GET", "/v1/status?wait=ready&timeoutMs=300000")[0] == 200
    return endpoint, Client(endpoint, Contract(json.loads(text)))


@pytest.fixture(scope="module")
def repair(scratch, release_dir, maven_repository, launch_env, jvm_home):
    folder = scratch / "runs" / "http-repair"
    folder.mkdir(parents=True)
    wt = make_worktrees(folder / "worktrees")
    settings = write_settings(folder / "settings.json", maven_repository)
    # wt-b's harness-app names a parent version that exists nowhere.
    app = wt / "wt-b" / "harness-app" / "pom.xml"
    app.write_text(app.read_text().replace("<version>0.1.0-SNAPSHOT</version>\n    </parent>", "<version>9.9.9-NOPE</version>\n    </parent>"))
    # T002.04's review, R1: POMs m2e can't parse at all, at a worktree's root and in a reactor's child.
    for name, broken in (("wt-badroot", "pom.xml"), ("wt-badchild", "harness-app/pom.xml")):
        git("worktree", "add", "-q", "--detach", str(wt / name), cwd=wt / "main")
        pom = wt / name / broken
        pom.write_text(pom.read_text().replace("</project>", ""))  # no closing tag
    seen = {"worktrees": wt}
    endpoint, c = start(folder, "first", release_dir, jvm_home, launch_env, settings)
    try:
        seen["add_badroot"] = c.post("/v1/worktrees/add", {"worktree": str(wt / "wt-badroot")})
        seen["add_badchild"] = c.post("/v1/worktrees/add", {"worktree": str(wt / "wt-badchild")})
        seen["list_bad"] = c.post("/v1/worktrees/list", {})
        for name in ("wt-badroot", "wt-badchild"):
            seen[f"remove_{name}"] = c.post("/v1/worktrees/remove", {"worktree": str(wt / name)})
        seen["add_main"] = c.post("/v1/worktrees/add", {"worktree": str(wt / "main")})
        seen["add_broken"] = c.post("/v1/worktrees/add", {"worktree": str(wt / "wt-b")})
        seen["list_broken"] = c.post("/v1/worktrees/list", {"worktree": str(wt / "wt-b")})
        seen["check_broken"] = c.post("/v1/modules/check", {"worktree": str(wt / "wt-b"), "module": "harness-contract"})
        seen["status_broken"] = c.status()
        seen["remove_broken"] = c.post("/v1/worktrees/remove", {"worktree": str(wt / "wt-b")})
        assert endpoint.request("POST", "/v1/shutdown", body={})[0] == 202
        seen["first_exit"] = endpoint.wait_exit(120)
    finally:
        endpoint.stop()
    # Damage the stopped workspace: main's harness-contract loses its Java nature.
    description = folder / "data/.metadata/.plugins/org.eclipse.core.resources/.projects/main.harness-contract/.project"
    text = description.read_text()
    assert JAVA_NATURE in text, text
    description.write_text(re.sub(r"\s*" + re.escape(JAVA_NATURE), "", text))
    endpoint, c = start(folder, "restart", release_dir, jvm_home, launch_env, settings)
    try:
        seen["check_after_restart"] = c.post("/v1/modules/check", {"worktree": str(wt / "main"), "module": "harness-contract"})
        seen["status_after_restart"] = c.status()
        seen["list_after_restart"] = c.post("/v1/worktrees/list", {})
        assert endpoint.request("POST", "/v1/shutdown", body={})[0] == 202
        seen["restart_exit"] = endpoint.wait_exit(120)
    finally:
        endpoint.stop()
    seen["description_after"] = description.read_text()
    seen["log"] = (folder / f"data/.fractal-jdtls/{RELEASE}/endpoint.log").read_text().splitlines()
    record = {k: v for k, v in seen.items() if k != "worktrees"}
    record["log"] = [line for line in seen["log"] if "REPAIR" in line or "restart:" in line]
    (folder / "seen.json").write_text(json.dumps(record, indent=1, default=str) + "\n")
    return seen


def test_a_module_that_fails_the_health_check_is_repaired_once_and_reported(repair):
    wt = repair["worktrees"]
    status, value, ms = repair["check_after_restart"]
    assert status == 200 and value["module"]["state"] == "imported", value
    assert value["waitedMs"] > 0, "the check waited for the restart's check and its repair"
    (main,) = repair["status_after_restart"]["worktrees"]
    expected = {"reason": "harness-contract: the project has no Java nature", "result": "repaired"}
    assert main["state"] == "imported" and {k: main["repair"][k] for k in expected} == expected and main["repair"]["ms"] > 0, main
    (listed,) = repair["list_after_restart"][1]["worktrees"]
    assert listed["repair"] == main["repair"] and all(m["state"] == "imported" for m in listed["modules"])
    repairs = [line for line in repair["log"] if f"REPAIR: {wt / 'main'} " in line]  # wt-b's are the first process's
    assert len(repairs) == 2, repairs
    assert f"REPAIR: {wt / 'main'} failed the import health check (harness-contract: the project has no Java nature)" in repairs[0]
    assert f"REPAIR: {wt / 'main'} repaired in " in repairs[1]
    assert JAVA_NATURE in repair["description_after"], "the repair made the project again"
    assert repair["restart_exit"] == 0


def test_a_module_that_stays_broken_reports_import_failed(repair):
    wt = repair["worktrees"]
    assert repair["add_main"][0] == 200
    status, value, _ = repair["add_broken"]
    assert status == 409 and value["code"] == "import-failed", value
    assert value["worktree"] == str(wt / "wt-b")
    (module,) = value["modules"]
    assert module["module"] == "harness-app"
    assert module["failure"].startswith("the project has no m2e facade and no Java nature (its pom.xml: Project build error: "
                                        "Non-resolvable parent POM for org.fractal:harness-app:9.9.9-NOPE")  # fmt: skip
    assert value["hint"] == "Fix the pom.xml errors that `modules` quotes, then remove the worktree and add it again."
    (listed,) = repair["list_broken"][1]["worktrees"]
    assert listed["state"] == "import-failed" and listed["repair"]["result"] == "failed"
    assert {m["module"]: m["state"] for m in listed["modules"]} == {".": "imported", "harness-app": "import-failed", "harness-contract": "imported"}
    status, value, _ = repair["check_broken"]
    assert status == 409 and value["code"] == "import-failed", "requests on the worktree's modules get it too"
    assert repair["remove_broken"][0] == 200 and repair["first_exit"] == 0
    failed = [line for line in repair["log"] if f"REPAIR: {wt / 'wt-b'} " in line]
    assert len(failed) == 2 and " failed in " in failed[1] and "still failing: [harness-app: " in failed[1], failed


@pytest.mark.parametrize("name, module", [("wt-badroot", "."), ("wt-badchild", "harness-app")])
def test_a_pom_m2e_cant_parse_still_counts_as_a_module_and_fails_the_check(repair, name, module):
    """T002.04's review, R1: m2e's scanner leaves such a POM out, and only logs it; the endpoint reads the modules
    itself too, so the module is listed and fails, and the repair can't help."""
    status, value, _ = repair[f"add_{name.removeprefix('wt-')}"]
    assert status == 409 and value["code"] == "import-failed", value
    failing = {m["module"]: m["failure"] for m in value["modules"]}
    assert module in failing, failing
    assert " (its pom.xml: " in failing[module] and "XML" in failing[module], failing[module]
    assert value["hint"] == "Fix the pom.xml errors that `modules` quotes, then remove the worktree and add it again."
    (listed,) = [w for w in repair["list_bad"][1]["worktrees"] if w["name"] == name]
    assert listed["state"] == "import-failed" and listed["repair"]["result"] == "failed"
    assert {m["module"] for m in listed["modules"]} >= {module}
    assert repair[f"remove_{name}"][0] == 200
