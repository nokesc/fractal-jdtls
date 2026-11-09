# SPDX-License-Identifier: Apache-2.0
"""Worktrees and modules in HTTP mode (design §11 "Worktrees", "The contract"), through HTTP only.

One story, on the fixture's main checkout and linked worktrees: start with no worktrees; add main, wt-a and wt-b, as
run 05 imports them; list and check every module; each refusal and each mapping error; remove a worktree, and one
already deleted from disk; restart on the same -data, then add the same worktrees again. The agent's diagnostic log
shows the names and the per-worktree resolution, as in run 05. Everything a test checks is recorded on the way, and
the run's files stay in pytest's temporary folder under runs/http-worktrees/.
"""

import hashlib
import json
import shutil
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

from endpoint_client import AGENT_ERROR, RELEASE, Client, Contract, EndpointProcess, http_command, write_settings  # noqa: E402
from scenario import DiagnosticLog, git, make_worktrees, verify, workspace_projects  # noqa: E402

MODULES = {".": ("org.fractal:fractal-harness:0.1.0-SNAPSHOT", "pom"),
           "harness-app": ("org.fractal:harness-app:0.1.0-SNAPSHOT", "jar"),
           "harness-contract": ("org.fractal:harness-contract:0.1.0-SNAPSHOT", "jar")}  # fmt: skip
NAMES = {".": "fractal-harness", "harness-app": "harness-app", "harness-contract": "harness-contract"}
METADATA = {".project", ".classpath", ".settings", ".factorypath"}


def files(folder: Path) -> dict[str, str]:
    """Every file of a worktree, but Git's own and build output, with its hash."""
    return {
        p.relative_to(folder).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
        for p in sorted(folder.rglob("*"))
        if p.is_file() and ".git" not in p.relative_to(folder).parts and "target" not in p.relative_to(folder).parts
    }


def metadata_files(folder: Path) -> list[str]:
    return sorted(str(p.relative_to(folder)) for p in folder.rglob("*") if p.name in METADATA)


class Story:
    def __init__(self, folder, release, jvm_home, launch_env, settings):
        self.folder, self.release, self.jvm_home, self.launch_env, self.settings = folder, release, jvm_home, launch_env, settings
        self.data = folder / "data"
        self.log = folder / "agent.jsonl"
        self.seen = {}

    def start(self, name):
        port_file = self.folder / f"{name}-port.json"
        cmd = http_command(self.release, self.jvm_home, self.data, port_file, self.settings, f"-Dfractal.jdtls.diagnosticLog={self.log}")
        endpoint = EndpointProcess(cmd, env=self.launch_env, run_dir=self.folder / name, port_file=port_file).start()
        endpoint.wait_port_file()
        status, _, text = endpoint.request("GET", "/v1/openapi.json")
        assert status == 200
        client = Client(endpoint, Contract(json.loads(text)))
        status, _, text = endpoint.request("GET", "/v1/status?wait=ready&timeoutMs=300000")
        assert status == 200, text
        return endpoint, client

    def log_lines(self) -> int:
        return len(self.log.read_text().splitlines()) if self.log.exists() else 0

    def events(self, start=0, end=None) -> DiagnosticLog:
        log = DiagnosticLog(self.log)
        log.events = log.events[start:end]
        return log


@pytest.fixture(scope="module")
def story(scratch, release_dir, maven_repository, launch_env, jvm_home):
    folder = scratch / "runs" / "http-worktrees"
    folder.mkdir(parents=True)
    wt = make_worktrees(folder / "worktrees")
    main, wt_a, wt_b = wt / "main", wt / "wt-a", wt / "wt-b"
    s = Story(folder, release_dir, jvm_home, launch_env, write_settings(folder / "settings.json", maven_repository))
    seen = s.seen
    seen["worktrees"] = wt

    # --- the first process ---------------------------------------------------------------------
    endpoint, c = s.start("first")
    try:
        seen["status_at_start"] = c.status()
        seen["adds"] = {name: c.post("/v1/worktrees/add", {"worktree": str(wt / name)}) for name in ("main", "wt-a", "wt-b")}
        seen["run05_log_end"] = s.log_lines()
        seen["list"] = c.post("/v1/worktrees/list", {})
        seen["checks"] = {(name, module): c.post("/v1/modules/check", {"worktree": str(wt / name), "module": module})
                          for name in ("main", "wt-a", "wt-b") for module in MODULES}  # fmt: skip
        seen["add_again"] = c.post("/v1/worktrees/add", {"worktree": str(main)})
        (folder / "link-to-wt-a").symlink_to(wt_a)
        seen["add_through_a_link"] = c.post("/v1/worktrees/add", {"worktree": str(folder / "link-to-wt-a")})

        # Refusals.
        other = folder / "elsewhere" / "wt-a"  # another repository whose folder is named wt-a
        other.mkdir(parents=True)
        git("init", "-q", "-b", "main", cwd=other)
        seen["refusals"] = {
            "a folder name in use": c.post("/v1/worktrees/add", {"worktree": str(other)}),
            "a module folder, not a worktree's root": c.post("/v1/worktrees/add", {"worktree": str(main / "harness-app")}),
            "a relative path": c.post("/v1/worktrees/add", {"worktree": "wt-a"}),
            "a folder that doesn't exist": c.post("/v1/worktrees/add", {"worktree": str(wt / "wt-z")}),
            "an unknown field": c.post("/v1/worktrees/add", {"worktree": str(main), "path": "x"}),
        }

        # Mapping errors.
        (wt_b / "harness-cli").mkdir()
        shutil.copy(wt_b / "harness-app" / "pom.xml", wt_b / "harness-cli" / "pom.xml")  # a module added after the import
        git("worktree", "add", "-q", "--detach", str(wt / "wt-stray"), cwd=main)
        (wt / "wt-stray" / "harness-app" / ".git").mkdir()  # an empty .git, the shape of the stray /tmp/.git
        stray_log_start = s.log_lines()
        seen["stray_add"] = c.post("/v1/worktrees/add", {"worktree": str(wt / "wt-stray")})
        seen["stray_log"] = (stray_log_start, s.log_lines())
        git("worktree", "add", "-q", "--detach", str(wt / "wt-c"), cwd=main)
        seen["wt_c_add"] = c.post("/v1/worktrees/add", {"worktree": str(wt / "wt-c")})
        seen["wt_c_files"] = files(wt / "wt-c")
        git("worktree", "remove", "--force", str(wt / "wt-c"), cwd=main)
        seen["mapping"] = {
            "a worktree that isn't registered": c.post("/v1/modules/check", {"worktree": str(wt / "wt-z"), "module": "."}),
            "a module path without a pom.xml": c.post("/v1/modules/check", {"worktree": str(wt_b), "module": "harness-api"}),
            "a module that hasn't been imported": c.post("/v1/modules/check", {"worktree": str(wt_b), "module": "harness-cli"}),
            "a module the agent placed in another worktree": c.post("/v1/modules/check", {"worktree": str(wt / "wt-stray"), "module": "harness-app"}),
            "a worktree deleted from disk": c.post("/v1/modules/check", {"worktree": str(wt / "wt-c"), "module": "."}),
            "a module path with ..": c.post("/v1/modules/check", {"worktree": str(wt_b), "module": "../wt-a/harness-app"}),
        }
        # Its lookups can't stay home: the agent put harness-app in the worktree wt-stray/harness-app. Removed now.
        seen["remove_stray"] = c.post("/v1/worktrees/remove", {"worktree": str(wt / "wt-stray")})

        # Nested worktrees are refused, in either direction (T002.04's review, R2).
        tools = main / "tools"  # a Git repository inside the registered main checkout
        tools.mkdir()
        git("init", "-q", "-b", "main", cwd=tools)
        outer, inner = folder / "outer", folder / "outer" / "inner"  # a repository around a registered one
        inner.mkdir(parents=True)
        git("init", "-q", "-b", "main", cwd=outer)
        git("init", "-q", "-b", "main", cwd=inner)
        seen["nested"] = {
            "inside": c.post("/v1/worktrees/add", {"worktree": str(tools)}),
            "inner": c.post("/v1/worktrees/add", {"worktree": str(inner)}),
            "containing": c.post("/v1/worktrees/add", {"worktree": str(outer)}),
        }
        seen["nested"]["remove inner"] = c.post("/v1/worktrees/remove", {"worktree": str(inner)})

        # Removal: wt-a, then wt-c, already deleted from disk.
        seen["wt_a_files_before"] = files(wt_a)
        seen["remove_wt_a"] = c.post("/v1/worktrees/remove", {"worktree": str(wt_a)})
        seen["wt_a_files_after"] = files(wt_a)
        seen["after_remove"] = {
            "list": c.post("/v1/worktrees/list", {}),
            "check": c.post("/v1/modules/check", {"worktree": str(wt_a), "module": "harness-app"}),
            "remove again": c.post("/v1/worktrees/remove", {"worktree": str(wt_a)}),
        }
        seen["remove_wt_c"] = c.post("/v1/worktrees/remove", {"worktree": str(wt / "wt-c")})
        # A later lookup: a new worktree's import resolves the fixture's GAVs among the registered worktrees' POMs.
        before_wt_d = s.log_lines()
        git("worktree", "add", "-q", "--detach", str(wt / "wt-d"), cwd=main)
        seen["wt_d_add"] = c.post("/v1/worktrees/add", {"worktree": str(wt / "wt-d")})
        seen["wt_d_log"] = (before_wt_d, s.log_lines())
        seen["status_before_shutdown"] = c.status()
        seen["metadata_files"] = {name: metadata_files(wt / name) for name in ("main", "wt-a", "wt-b", "wt-stray", "wt-d")}
        seen["projects_running"] = workspace_projects(s.data)
        seen["shutdown"] = endpoint.request("POST", "/v1/shutdown", body={})
        seen["exit"] = endpoint.wait_exit(120)
        seen["first_stderr"] = endpoint.stderr()
        seen["first_calls"] = c.calls
    finally:
        endpoint.stop()
    seen["projects_after_first"] = workspace_projects(s.data)
    seen["saved"] = json.loads((s.data / ".fractal-jdtls" / "worktrees.json").read_text())

    # --- a restart on the same -data, with wt-d deleted while JDT LS was stopped ---------------
    git("worktree", "remove", "--force", str(wt / "wt-d"), cwd=main)
    restart_log_start = s.log_lines()
    endpoint, c = s.start("restart")
    try:
        seen["restart_status"] = c.status()  # ready, and the restart's check of the saved worktrees may still run
        seen["restart_list_at_once"] = c.post("/v1/worktrees/list", {})  # doesn't wait for it
        seen["restart_checks"] = {name: c.post("/v1/modules/check", {"worktree": str(wt / name), "module": "harness-app"})
                                  for name in ("main", "wt-b")}  # fmt: skip
        seen["restart_list"] = c.post("/v1/worktrees/list", {})
        seen["restart_problems"] = c.post("/v1/problems", {"worktree": str(main), "module": "harness-app", "scope": "worktree"})
        seen["restart_problems_again"] = c.post("/v1/problems", {"worktree": str(main), "module": "harness-app", "scope": "worktree"})
        seen["restart_log"] = (restart_log_start, s.log_lines())
        seen["readds"] = {name: c.post("/v1/worktrees/add", {"worktree": str(wt / name)}) for name in ("main", "wt-a", "wt-b")}
        seen["readd_log"] = (seen["restart_log"][1], s.log_lines())
        seen["restart_shutdown"] = endpoint.request("POST", "/v1/shutdown", body={})
        seen["restart_exit"] = endpoint.wait_exit(120)
        seen["restart_stderr"] = endpoint.stderr()
        seen["restart_calls"] = c.calls
    finally:
        endpoint.stop()
    seen["projects_after_restart"] = workspace_projects(s.data)
    seen["endpoint_log"] = (s.data / f".fractal-jdtls/{RELEASE}/endpoint.log").read_text().splitlines()
    seen["verify"] = verify(release_dir)
    kept = ("nested", "saved", "projects_running", "projects_after_first", "projects_after_restart", "wt_a_files_before", "wt_a_files_after",
            "run05_log_end", "stray_log", "wt_d_log", "restart_log", "readd_log", "metadata_files")
    record = {k: v for k, v in seen.items() if k.endswith("calls") or k in kept}
    (folder / "seen.json").write_text(json.dumps(record, indent=1, default=str) + "\n")
    seen["story"] = s
    return seen


def problem(call, status, code):
    got_status, value, _ = call
    assert got_status == status and value["code"] == code, value
    assert value["hint"] and value["detail"], value
    return value


def worktree_of(path, wt: Path) -> str:
    return Path(path).relative_to(wt).parts[0]


# --- starting and adding ---------------------------------------------------------------------


def test_it_starts_with_no_worktrees(story):
    status = story["status_at_start"]
    assert status["ready"] is True
    assert status["worktrees"] == [] and status["importing"] is None and status["droppedWorktrees"] == []


def test_the_three_worktrees_import_with_every_module_healthy(story):
    wt = story["worktrees"]
    for name, (status, value, _) in story["adds"].items():
        assert status == 200, value
        assert value["added"] is True and value["importMs"] > 0
        w = value["worktree"]
        assert w["worktree"] == str(wt / name) and w["name"] == name and w["state"] == "imported" and "repair" not in w
        assert {m["module"]: (m["gav"], m["packaging"]) for m in w["modules"]} == MODULES
        assert all(m["state"] == "imported" for m in w["modules"])


def test_list_and_check_return_each_module_with_its_gav(story):
    wt = story["worktrees"]
    status, value, _ = story["list"]
    assert status == 200
    assert [w["name"] for w in value["worktrees"]] == ["main", "wt-a", "wt-b"]
    for (name, module), (status, value, _) in story["checks"].items():
        assert status == 200, value
        assert value["worktree"] == str(wt / name)
        assert value["module"] == {"module": module, "gav": MODULES[module][0], "packaging": MODULES[module][1], "state": "imported"}


def test_adding_a_registered_worktree_again_changes_nothing(story):
    for call in (story["add_again"], story["add_through_a_link"]):
        status, value, _ = call
        assert status == 200 and value["added"] is False, value
    assert story["add_through_a_link"][1]["worktree"]["worktree"] == str(story["worktrees"] / "wt-a")


def test_names_and_resolution_are_run_05s(story):
    """Through the agent's diagnostic log: nine projects named <worktree>.<m2e's name>, and every lookup had a
    requester and chose a POM in the requester's worktree."""
    wt = story["worktrees"]
    log = story["story"].events(0, story["run05_log_end"])
    naming = log.of("naming")
    expected = sorted(f"{w}.{NAMES[m]}" for w in ("main", "wt-a", "wt-b") for m in MODULES)
    assert sorted(e["result"] for e in naming) == expected
    for e in naming:
        assert e["result"] == f"{Path(e['worktree']).name}.{e['m2e']}" and Path(e["pom"]).is_relative_to(e["worktree"])
    lookups = log.of("lookup")
    assert lookups
    assert all(e["requester"] for e in lookups), [e for e in lookups if not e["requester"]]
    away = [e for e in lookups if worktree_of(e["result"], wt) != worktree_of(e["requester"], wt)]
    assert not away, away
    assert any(len(e["candidates"]) > 1 for e in lookups), "no lookup had to choose between worktrees"
    problems = [e for e in log.events if e["event"] in ("hook-not-in-place", "transform-error", "hook-failed", "prefix-collision")]
    assert not problems, problems


def test_no_metadata_files_are_written_into_the_worktrees(story):
    assert story["metadata_files"] == {name: [] for name in ("main", "wt-a", "wt-b", "wt-stray", "wt-d")}


# --- refusals and mapping errors -------------------------------------------------------------


def test_adding_is_refused_for_a_name_in_use_or_a_folder_that_isnt_a_worktree_root(story):
    wt = story["worktrees"]
    r = story["refusals"]
    v = problem(r["a folder name in use"], 409, "worktree-name-in-use")
    assert v["detail"] == f"The registered worktree {wt / 'wt-a'} has the same folder name, wt-a."
    v = problem(r["a module folder, not a worktree's root"], 422, "not-a-worktree")
    assert v["detail"] == f"{wt / 'main' / 'harness-app'} isn't the root of a Git worktree: it has no .git; its nearest .git is in {wt / 'main'}."
    assert v["hint"] == f"Pass the worktree's root, {wt / 'main'}."
    assert problem(r["a relative path"], 422, "not-a-worktree")["detail"] == "wt-a isn't an absolute path."
    assert problem(r["a folder that doesn't exist"], 422, "not-a-worktree")["detail"].endswith("doesn't exist.")
    assert problem(r["an unknown field"], 400, "invalid-request")["errors"] == [
        {"field": "/path", "message": "unknown field; POST /v1/worktrees/add takes worktree, timeoutMs, wait"}]


def test_each_mapping_error_is_a_problem_that_says_what_to_do(story):
    wt = story["worktrees"]
    m = story["mapping"]
    v = problem(m["a worktree that isn't registered"], 404, "worktree-not-registered")
    assert [c["set"]["/worktree"] for c in v["candidates"]] == [str(wt / n) for n in ("main", "wt-a", "wt-b", "wt-c", "wt-stray")]
    v = problem(m["a module path without a pom.xml"], 404, "module-not-found")
    assert [c["set"]["/module"] for c in v["candidates"]] == [".", "harness-app", "harness-contract"]
    v = problem(m["a module that hasn't been imported"], 409, "module-not-imported")
    assert v["detail"] == f"{wt / 'wt-b' / 'harness-cli'} has a pom.xml, but JDT LS has no project for it."
    v = problem(m["a module the agent placed in another worktree"], 409, "module-in-other-worktree")
    stray = wt / "wt-stray" / "harness-app"
    assert v["detail"] == (f"The project for {stray} was placed in the worktree {stray}, because {stray / '.git'} is nearer the module than "
                           f"{wt / 'wt-stray' / '.git'}.")  # fmt: skip
    assert v["hint"] == f"Remove the stray {stray / '.git'}, then remove the worktree and add it again."
    problem(m["a worktree deleted from disk"], 410, "worktree-deleted")
    assert problem(m["a module path with .."], 400, "invalid-request")["errors"][0]["field"] == "/module"


def test_nested_worktrees_are_refused_in_either_direction(story):
    wt = story["worktrees"]
    n = story["nested"]
    v = problem(n["inside"], 409, "worktree-nested")
    assert v["detail"] == f"{wt / 'main' / 'tools'} is inside the registered worktree {wt / 'main'}. Worktrees that share a JDT LS can't be nested."
    assert n["inner"][0] == 200 and n["inner"][1]["worktree"]["modules"] == []
    outer = wt.parent / "outer"
    v = problem(n["containing"], 409, "worktree-nested")
    assert v["detail"] == f"{outer} contains the registered worktree {outer / 'inner'}. Worktrees that share a JDT LS can't be nested."
    assert n["remove inner"][0] == 200


def test_the_stray_git_passes_the_health_check_but_not_the_mapping(story):
    """The stray .git doesn't break the import; it breaks the agent's placement, which the mapping check catches."""
    status, value, _ = story["stray_add"]
    assert status == 200 and value["worktree"]["state"] == "imported", value
    start, end = story["stray_log"]
    naming = {e["result"]: e["worktree"] for e in story["story"].events(start, end).of("naming")}
    assert naming["harness-app.harness-app"] == str(story["worktrees"] / "wt-stray" / "harness-app"), naming
    assert story["remove_stray"][0] == 200


# --- removal ---------------------------------------------------------------------------------


def test_removing_deletes_the_projects_and_keeps_every_file(story):
    wt = story["worktrees"]
    status, value, _ = story["remove_wt_a"]
    assert status == 200 and value == {"worktree": str(wt / "wt-a"), "removed": True, "projectsDeleted": 3, "filesKept": True}
    # 11: .gitignore, 3 POMs, and 7 Java sources (T002.06 added Names, Step, EchoStep, Greeter and PrintStep for navigation).
    assert story["wt_a_files_after"] == story["wt_a_files_before"] and len(story["wt_a_files_before"]) == 11
    after = story["after_remove"]
    assert [w["name"] for w in after["list"][1]["worktrees"]] == ["main", "wt-b", "wt-c"]
    problem(after["check"], 404, "worktree-not-registered")
    problem(after["remove again"], 404, "worktree-not-registered")
    assert not [p for p in story["projects_after_first"] if p.startswith(("wt-a.", "wt-c."))], story["projects_after_first"]


def test_a_later_lookups_candidates_dont_include_the_removed_worktrees_poms(story):
    wt = story["worktrees"]
    start, end = story["wt_d_log"]
    lookups = story["story"].events(start, end).of("lookup")
    assert lookups and any(len(e["candidates"]) > 1 for e in lookups)
    seen = {worktree_of(c, wt) for e in lookups for c in e["candidates"]}
    assert "wt-a" not in seen and "wt-c" not in seen, seen
    assert {"main", "wt-b", "wt-d"} <= seen
    assert all(worktree_of(e["result"], wt) == worktree_of(e["requester"], wt) for e in lookups)


def test_no_worktree_change_waits_for_a_job_that_isnt_its_own(story):
    """T002.04 found removals waiting 300 s for Eclipse's "Periodic workspace save", which ran during them and went
    back to sleep: the join waited for its next run. A change on the fixture takes about a second."""
    slow = [(c["path"], c["body"], c["ms"]) for c in story["first_calls"] + story["restart_calls"]
            if c["path"] in ("/v1/worktrees/add", "/v1/worktrees/remove") and c["ms"] > 60_000]  # fmt: skip
    assert not slow, slow


def test_a_worktree_deleted_from_disk_can_still_be_removed(story):
    wt = story["worktrees"]
    status, value, _ = story["remove_wt_c"]
    assert status == 200 and value == {"worktree": str(wt / "wt-c"), "removed": True, "projectsDeleted": 3, "filesKept": True}


# --- restart ---------------------------------------------------------------------------------


def test_the_registered_worktrees_are_saved_under_data(story):
    wt = story["worktrees"]
    assert story["saved"] == {"version": 1, "worktrees": [{"worktree": str(wt / n)} for n in ("main", "wt-b", "wt-d")]}
    assert story["exit"] == 0


def test_a_restart_serves_the_saved_worktrees_without_creating_a_project(story):
    wt = story["worktrees"]
    status = story["restart_status"]
    assert status["droppedWorktrees"] == [{"worktree": str(wt / "wt-d"), "reason": "its folder is gone"}]
    at_once = story["restart_list_at_once"][1]["worktrees"]
    assert [w["name"] for w in at_once] == ["main", "wt-b"] and {w["state"] for w in at_once} <= {"importing", "imported"}
    worktrees = story["restart_list"][1]["worktrees"]
    assert [(w["name"], w["state"]) for w in worktrees] == [("main", "imported"), ("wt-b", "imported")]
    assert all("repair" not in w for w in worktrees)
    for status_code, value, _ in story["restart_checks"].values():
        assert status_code == 200 and value["module"]["state"] == "imported", value
    start, end = story["restart_log"]
    log = story["story"].events(start, end)
    assert log.of("naming") == [], "a restart creates no projects"
    assert all(e["requester"] and worktree_of(e["result"], wt) == worktree_of(e["requester"], wt) for e in log.of("lookup"))


def test_a_restart_puts_the_git_filter_on_the_saved_worktrees_and_its_first_fixed_point_refreshes(story):
    wt = story["worktrees"]
    log = story["endpoint_log"]
    restart = max(i for i, line in enumerate(log) if "restart: " in line and "saved worktrees go to initialize" in line)
    filtered = [line for line in log[restart:] if ".git filter is on every project of" in line]
    assert [line.split(" of ")[1].split(" (")[0] for line in filtered[:2]] == [str(wt / "main"), str(wt / "wt-b")], filtered
    status, value, _ = story["restart_problems"]
    assert status == 200 and value["sync"]["notSkippedBecause"] == "first" and value["problems"] == [], value
    assert not [f for f in value["sync"]["filesChanged"] if ".git" in Path(f["path"]).parts]
    status, value, _ = story["restart_problems_again"]
    assert status == 200 and value["sync"]["skipped"] is True, value["sync"]


def test_after_a_restart_the_same_worktrees_can_be_added_again(story):
    adds = story["readds"]
    assert adds["main"][0] == 200 and adds["main"][1]["added"] is False
    assert adds["wt-b"][0] == 200 and adds["wt-b"][1]["added"] is False
    status, value, _ = adds["wt-a"]
    assert status == 200 and value["added"] is True and value["worktree"]["state"] == "imported", value
    start, end = story["readd_log"]
    naming = story["story"].events(start, end).of("naming")
    assert sorted(e["result"] for e in naming) == ["wt-a.fractal-harness", "wt-a.harness-app", "wt-a.harness-contract"]
    assert story["restart_exit"] == 0
    assert not [p for p in story["projects_after_restart"] if p.startswith("wt-d.")], "JDT LS deleted the dropped worktree's projects"


def test_no_agent_error_and_the_install_still_verifies(story):
    assert AGENT_ERROR not in story["first_stderr"] and AGENT_ERROR not in story["restart_stderr"]
    assert story["verify"].returncode == 0, story["verify"].stdout + story["verify"].stderr
