# SPDX-License-Identifier: Apache-2.0
"""Keeping in step with the disk, builds and problems (design §11), through HTTP only, with every file changed on disk
and never reported to the endpoint.

One story on the fixture's main checkout and linked worktrees, in one process:

- run 05's edits, and `build` in each worktree returns run 05's errors; `problems` then takes the fast path;
- a `git checkout` of another commit, in main, and back;
- a file deleted on disk, in wt-b;
- a POM change that adds a dependency, in wt-b;
- changes that keep the file's modification time, in main: a file replaced with `cp -p`, one restored with
  `touch -r`, and a second write within the same millisecond as the first; each in the next `build`, with the file's
  bytes and times unchanged by the endpoint;
- `changedPaths`, and the request errors of both operations.

Every `sync` report is kept, for the costs in the evidence, in runs/http-sync/syncs.json.
"""

import json
import os
import shutil
import subprocess
import sys
import threading
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

from endpoint_client import RELEASE, write_settings  # noqa: E402
from scenario import APP, CONTRACT, git, make_worktrees  # noqa: E402
from sync_scenario import EXPECTED, errors, file_state, start, write_run05_edits  # noqa: E402

LAUNCHER = f"{APP}/Launcher.java"
TEXT = f"{APP}/Text.java"
USES_LANG3 = ("package org.fractal.harness.app;\n\nimport org.apache.commons.lang3.StringUtils;\n\n"
              "public class Text {\n\n    public String shout(String s) {\n        return StringUtils.upperCase(s);\n    }\n}\n")  # fmt: skip
LANG3 = ("        <dependency>\n            <groupId>org.apache.commons</groupId>\n            <artifactId>commons-lang3</artifactId>\n"
         "            <version>3.17.0</version>\n        </dependency>\n    </dependencies>")  # fmt: skip
TESTER = ("package org.fractal.harness.app;\n\nimport org.fractal.harness.contract.WorkType;\n\n"
          "public class Dispatch {\n\n    public WorkType type() {\n        return WorkType.TESTER;\n    }\n}\n")  # fmt: skip
WORKTYPE_TWO = "package org.fractal.harness.contract;\n\npublic enum WorkType { CODER, REVIEWER }\n"
CYCLIC = f"{APP}/Cyclic.java"
CYCLIC_SOURCE = ("package org.fractal.harness.app;\n\npublic class Cyclic {\n\n    <T extends T> void f(T value) {\n        Missing x;\n    }\n\n"
                 "    <A extends B, B extends A> void g(A a) {\n        Missing y;\n    }\n}\n")  # fmt: skip


def broken_launcher(original: str, n: int) -> str:
    """Launcher.java with one type error, at the same size or not, each version different."""
    return original.replace("return id.value();", f"return {n} + id;").replace("public String launch", "public int launch")


@pytest.fixture(scope="module")
def story(scratch, release_dir, maven_repository, launch_env, jvm_home):
    folder = scratch / "runs" / "http-sync"
    folder.mkdir(parents=True)
    wt = make_worktrees(folder / "worktrees")
    main, wt_a, wt_b = wt / "main", wt / "wt-a", wt / "wt-b"
    settings = write_settings(folder / "settings.json", maven_repository)
    seen = {"worktrees": wt}
    syncs = []

    endpoint, c = start(folder, "jdtls", release_dir, jvm_home, launch_env, settings)

    def call(op, name, module="harness-app", **body):
        status, value, ms = c.post(f"/v1/{op}", {"worktree": str(wt / name), "module": module, **body})
        if status == 200:
            syncs.append({"op": op, "worktree": name, "step": step, "ms": ms, "sync": value["sync"]})
        return status, value

    try:
        step = "add"
        seen["cmdline"] = [a.decode() for a in Path(f"/proc/{endpoint.pid}/cmdline").read_bytes().split(b"\0")]
        for name in ("main", "wt-a", "wt-b"):
            assert c.post("/v1/worktrees/add", {"worktree": str(wt / name)})[0] == 200
        step = "first"
        seen["first"] = {name: call("build", name, scope="worktree") for name in ("main", "wt-a", "wt-b")}
        seen["first_problems"] = {name: call("problems", name, scope="worktree") for name in ("main", "wt-a", "wt-b")}

        # Run 05.
        step = "run05"
        write_run05_edits(wt)
        seen["run05"] = {name: call("build", name, scope="worktree") for name in ("main", "wt-a", "wt-b")}
        step = "run05-again"
        seen["run05_problems"] = {name: call("problems", name, scope="worktree") for name in ("main", "wt-a", "wt-b")}
        seen["run05_module_scope"] = call("problems", "wt-a", module="harness-contract")
        seen["run05_warnings"] = call("problems", "wt-b", scope="worktree", severities=["error", "warning", "info"], limit=1)

        # T002.05's review, R2: problems asked while clean builds of the same worktree run, which delete its markers
        # before they make them again. Every answer must be run 05's: none is read during a fixed point.
        step = "concurrent-clean"
        cleans, answers = [], []
        cleaning = threading.Event()

        def clean_builds():
            try:
                for _ in range(5):
                    cleans.append(call("build", "wt-a", clean=True, scope="worktree"))
            finally:
                cleaning.set()

        def ask():
            while not cleaning.is_set():
                answers.append(call("problems", "wt-a", scope="worktree"))

        threads = [threading.Thread(target=clean_builds)] + [threading.Thread(target=ask) for _ in range(4)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        seen["concurrent_clean"] = {"cleans": cleans, "answers": answers}

        # A git checkout of another commit, in main: WorkType, and a Dispatch that uses a constant it lacks.
        step = "checkout"
        git("worktree", "add", "-q", "-b", "feature", str(folder / "feature"), cwd=main)
        (folder / "feature" / CONTRACT / "WorkType.java").write_text(WORKTYPE_TWO)
        (folder / "feature" / APP / "Dispatch.java").write_text(TESTER)
        git("add", "-A", cwd=folder / "feature")
        git("-c", "user.name=t", "-c", "user.email=t@localhost", "commit", "-q", "-m", "feature: WorkType, and a Dispatch that uses TESTER",
            cwd=folder / "feature")  # fmt: skip
        git("worktree", "remove", str(folder / "feature"), cwd=main)
        git("checkout", "-q", "--detach", "feature", cwd=main)
        seen["checkout"] = call("build", "main", scope="worktree")
        git("checkout", "-q", "main", cwd=main)
        seen["checkout_back"] = call("build", "main", scope="worktree")

        # A file deleted on disk, in wt-b.
        step = "delete"
        (wt_b / APP / "Broken.java").unlink()
        seen["delete"] = call("build", "wt-b", scope="worktree")

        # A POM change that adds a dependency, in wt-b.
        step = "pom"
        (wt_b / TEXT).write_text(USES_LANG3)
        seen["pom_before"] = call("build", "wt-b")
        pom = wt_b / "harness-app" / "pom.xml"
        pom.write_text(pom.read_text().replace("    </dependencies>", LANG3, 1))
        seen["pom_after"] = call("build", "wt-b")

        # T002.05's review, R4: type variables whose bounds form a cycle, in broken source.
        step = "cyclic-bounds"
        (wt_b / CYCLIC).write_text(CYCLIC_SOURCE)
        seen["cyclic"] = call("build", "wt-b")
        (wt_b / CYCLIC).unlink()
        seen["cyclic_gone"] = call("build", "wt-b")

        # Changes that keep the modification time, in main. Each starts from a build that saw the file as it is.
        step = "kept-mtime"
        launcher = main / LAUNCHER
        original = launcher.read_text()
        kept = {}

        def kept_mtime(label, change, op="build"):
            change()
            before = file_state(launcher)
            status, value = call(op, "main")
            kept[label] = {"status": status, "response": value, "before": before, "after": file_state(launcher)}
            launcher.write_text(original)  # an ordinary write: the next build takes it in
            kept[label]["restored"] = call("build", "main")

        assert call("build", "main")[0] == 200
        scratch_copy = folder / "kept"
        scratch_copy.mkdir()

        def cp_p():
            # A copy with the file's own mtime, then cp -p over the file: new bytes, the old mtime.
            (scratch_copy / "Launcher.java").write_text(broken_launcher(original, 1))
            subprocess.run(["touch", "-r", str(launcher), str(scratch_copy / "Launcher.java")], check=True)
            subprocess.run(["cp", "-p", str(scratch_copy / "Launcher.java"), str(launcher)], check=True)

        def touch_r():
            # Kept aside with cp -p, edited, then its old mtime restored with touch -r.
            subprocess.run(["cp", "-p", str(launcher), str(scratch_copy / "Launcher.orig")], check=True)
            launcher.write_text(broken_launcher(original, 22))
            subprocess.run(["touch", "-r", str(scratch_copy / "Launcher.orig"), str(launcher)], check=True)

        def same_millisecond():
            # A second write within the millisecond of the first: Eclipse stores the mtime in milliseconds, so
            # a write that lands in the same one looks unchanged. Written, then its mtime set inside that millisecond.
            first = os.stat(launcher).st_mtime_ns
            second = first + 1 if first % 1_000_000 != 999_999 else first - 1
            launcher.write_text(broken_launcher(original, 333))
            os.utime(launcher, ns=(os.stat(launcher).st_atime_ns, second))
            kept["same_millisecond_mtimes"] = (first, second)

        kept_mtime("cp -p", cp_p)
        kept_mtime("touch -r", touch_r)
        kept_mtime("two writes within one millisecond", same_millisecond)
        kept_mtime("cp -p, then problems", cp_p, op="problems")
        seen["kept"] = kept

        # changedPaths: a named file is taken in, and the fast path isn't taken.
        step = "changed-paths"
        seen["unchanged"] = call("problems", "main")
        seen["changed_paths"] = call("problems", "main", changedPaths=[LAUNCHER])
        seen["changed_paths_after"] = call("problems", "main")

        # Request errors.
        step = "errors"
        seen["errors"] = {
            "a module that isn't there": c.post("/v1/build", {"worktree": str(main), "module": "harness-cli"}),
            "a worktree that isn't registered": c.post("/v1/problems", {"worktree": str(wt / "wt-z"), "module": "."}),
            "a changed path outside the worktree": c.post("/v1/problems", {"worktree": str(main), "module": ".", "changedPaths": ["../wt-a/pom.xml"]}),
            "clean on problems": c.post("/v1/problems", {"worktree": str(main), "module": ".", "clean": True}),
            "an unknown severity": c.post("/v1/problems", {"worktree": str(main), "module": ".", "severities": ["fatal"]}),
            "a timeout of 1 ms": c.post("/v1/build", {"worktree": str(main), "module": ".", "timeoutMs": 1}),
        }
        step = "clean"
        seen["clean"] = call("build", "wt-a", clean=True, scope="worktree")
        seen["status"] = c.status()
        seen["git_status"] = {name: git("status", "--porcelain", "--ignored", cwd=wt / name) for name in ("main", "wt-a", "wt-b")}
        assert endpoint.request("POST", "/v1/shutdown", body={})[0] == 202
        seen["exit"] = endpoint.wait_exit(120)
        records = folder / "data/.fractal-jdtls/records"
        seen["records"] = sorted(json.loads(f.read_text())["worktree"] for f in records.glob("*.json"))

        # T002.05's review, R1: a change that keeps its modification time, made while JDT LS is stopped.
        launcher = main / LAUNCHER
        original = launcher.read_text()

        def cp_p_while_stopped(n):
            copy = folder / f"stopped-{n}.java"
            copy.write_text(broken_launcher(original, n))
            subprocess.run(["touch", "-r", str(launcher), str(copy)], check=True)
            subprocess.run(["cp", "-p", str(copy), str(launcher)], check=True)
            copy.unlink()
            return file_state(launcher)

        def restarted(name, label):
            nonlocal endpoint, c
            endpoint, c = start(folder, name, release_dir, jvm_home, launch_env, settings)
            status, value = call("problems", "main")
            restart = {"status": status, "response": value, "after": file_state(launcher)}
            launcher.write_text(original)  # an ordinary write
            restart["restored"] = call("build", "main")
            restart["others"] = {n: call("problems", n, scope="worktree") for n in ("wt-a", "wt-b")}
            assert endpoint.request("POST", "/v1/shutdown", body={})[0] == 202
            restart["exit"] = endpoint.wait_exit(120)
            seen[label] = restart

        step = "restart-with-record"
        before = cp_p_while_stopped(4444)
        restarted("restart-1", "restart_with_record")
        seen["restart_with_record"]["before"] = before

        step = "restart-without-record"
        before = cp_p_while_stopped(55555)
        (main_record,) = [f for f in records.glob("*.json") if json.loads(f.read_text())["worktree"] == str(main)]
        main_record.unlink()
        restarted("restart-2", "restart_without_record")
        seen["restart_without_record"]["before"] = before
    finally:
        endpoint.stop()
    seen["log"] = (folder / f"data/.fractal-jdtls/{RELEASE}/endpoint.log").read_text().splitlines()
    seen["syncs"] = syncs
    (folder / "syncs.json").write_text(json.dumps(syncs, indent=1) + "\n")
    record = {k: v for k, v in seen.items() if k not in ("worktrees", "log", "cmdline")}
    (folder / "seen.json").write_text(json.dumps(record, indent=1, default=str) + "\n")
    shutil.rmtree(folder / "kept", ignore_errors=True)
    return seen


def test_run05_through_build(story):
    for name, expected in EXPECTED.items():
        status, value = story["run05"][name]
        assert status == 200, value
        assert errors(value) == expected, (name, value["problems"])
        assert value["sync"]["skipped"] is False and value["sync"]["notSkippedBecause"] == "build"
    changed = {name: {(f["path"], f["change"]) for f in story["run05"][name][1]["sync"]["filesChanged"]} for name in EXPECTED}
    assert changed == {
        "main": set(),
        "wt-a": {(f"{APP}/Dispatch.java", "added")},
        "wt-b": {(f"{CONTRACT}/WorkType.java", "added"), (f"{APP}/Dispatch.java", "added"), (f"{APP}/Broken.java", "added")},
    }


def test_problems_have_positions_the_source_line_and_the_enclosing_declaration(story):
    problems = story["run05"]["wt-a"][1]["problems"]
    assert [(p["line"], p["column"], p["endLine"], p["endColumn"], p["source"], p["element"], p["kind"]) for p in problems] == [
        (3, 8, 3, 45, "import org.fractal.harness.contract.WorkType;", "org.fractal.harness.app.Dispatch", "java"),
        (7, 12, 7, 20, "    public WorkType type() {", "org.fractal.harness.app.Dispatch#type()", "java"),
        (8, 16, 8, 24, "        return WorkType.CODER;", "org.fractal.harness.app.Dispatch#type()", "java"),
    ]
    (broken,) = story["run05"]["wt-b"][1]["problems"]
    assert (broken["column"], broken["source"], broken["element"]) == (17, '    int value = "not an int";', "org.fractal.harness.app.Broken#value")
    assert story["run05"]["wt-a"][1]["counts"]["error"] == 3 and story["run05"]["wt-a"][1]["truncated"] is False


def test_problems_take_the_fast_path_when_nothing_changed_and_give_the_same_answer(story):
    for name in EXPECTED:
        status, value = story["run05_problems"][name]
        assert status == 200 and value["sync"]["skipped"] is True, value["sync"]
        assert value["problems"] == story["run05"][name][1]["problems"]
        assert value["sync"]["checkMs"] > 0 and value["sync"]["refreshMs"] == 0 and value["sync"]["buildMs"] == 0
        assert story["first_problems"][name][1]["sync"]["skipped"] is True
        assert errors(story["first"][name][1]) == []


def test_the_module_scope_severities_and_limit(story):
    status, value = story["run05_module_scope"]
    assert status == 200 and value["problems"] == [] and value["counts"]["error"] == 0, "harness-contract of wt-a has no problems"
    status, value = story["run05_warnings"]
    assert status == 200 and len(value["problems"]) == 1
    counts = value["counts"]
    assert value["truncated"] is (sum(counts.values()) > 1)


def test_a_git_checkout_is_detected_without_being_reported(story):
    status, value = story["checkout"]
    assert status == 200
    assert {(f["path"], f["change"]) for f in value["sync"]["filesChanged"]} == {(f"{CONTRACT}/WorkType.java", "added"), (f"{APP}/Dispatch.java", "added")}
    assert [p["message"] for p in value["problems"]] == ["TESTER cannot be resolved or is not a field"]
    status, value = story["checkout_back"]
    assert {(f["path"], f["change"]) for f in value["sync"]["filesChanged"]} == {(f"{CONTRACT}/WorkType.java", "removed"), (f"{APP}/Dispatch.java", "removed")}
    assert value["problems"] == []


def test_a_pom_change_that_adds_a_dependency_is_detected(story):
    status, value = story["pom_before"]
    assert status == 200 and {p["file"] for p in value["problems"]} == {"src/main/java/org/fractal/harness/app/Text.java"}, value["problems"]
    assert any("org.apache" in p["message"] for p in value["problems"])
    status, value = story["pom_after"]
    assert status == 200
    assert value["sync"]["pomsUpdated"] == ["harness-app"]
    assert ("harness-app/pom.xml", "changed") in {(f["path"], f["change"]) for f in value["sync"]["filesChanged"]}
    assert value["problems"] == [], "commons-lang3 is on the classpath now"
    assert value["sync"]["pomsMs"] > 0


def test_a_file_deleted_on_disk_is_detected(story):
    status, value = story["delete"]
    assert status == 200
    assert (f"{APP}/Broken.java", "removed") in {(f["path"], f["change"]) for f in value["sync"]["filesChanged"]}
    assert errors(value) == []


@pytest.mark.parametrize("label", ["cp -p", "touch -r", "two writes within one millisecond", "cp -p, then problems"])
def test_a_change_that_kept_its_modification_time_is_detected_and_the_file_isnt_touched(story, label):
    kept = story["kept"][label]
    assert kept["status"] == 200
    value = kept["response"]
    assert [p["file"] for p in value["problems"]] == ["src/main/java/org/fractal/harness/app/Launcher.java"], value
    assert (LAUNCHER, "changed") in {(f["path"], f["change"]) for f in value["sync"]["filesChanged"]}
    assert kept["after"] == kept["before"], "the endpoint changed neither the file's bytes nor its times"
    if label == "cp -p, then problems":
        assert value["sync"]["notSkippedBecause"] == "status-changed"
    assert kept["restored"][1]["problems"] == []


def test_changed_paths_are_taken_in_and_skip_the_fast_path(story):
    assert story["unchanged"][1]["sync"]["skipped"] is True
    status, value = story["changed_paths"]
    assert status == 200 and value["sync"]["skipped"] is False and value["sync"]["notSkippedBecause"] == "changed-paths"
    assert value["sync"]["filesChanged"] == [{"path": LAUNCHER, "change": "changed"}]
    assert story["changed_paths_after"][1]["sync"]["skipped"] is True


def test_request_errors(story):
    e = story["errors"]
    assert e["a module that isn't there"][0] == 404 and e["a module that isn't there"][1]["code"] == "module-not-found"
    assert e["a worktree that isn't registered"][1]["code"] == "worktree-not-registered"
    for label, pointer in (("a changed path outside the worktree", "/changedPaths/0"), ("clean on problems", "/clean"),
                           ("an unknown severity", "/severities/0")):  # fmt: skip
        status, value, _ = e[label]
        assert status == 400 and value["code"] == "invalid-request" and pointer in {x["field"] for x in value["errors"]}, (label, value)
    status, value, _ = e["a timeout of 1 ms"]
    assert status in (503, 504) and value["code"] in ("busy", "timeout"), value


def test_clean_builds_from_scratch(story):
    status, value = story["clean"]
    assert status == 200 and errors(value) == EXPECTED["wt-a"]


def test_every_sync_report_leaves_out_git_and_build_output(story):
    for s in story["syncs"]:
        for f in s["sync"]["filesChanged"]:
            parts = Path(f["path"]).parts
            assert ".git" not in parts and "target" not in parts, s
    assert "Git's internals never show up, though Git ran in main and its linked worktrees"


def test_lightweight_refresh_is_off_and_the_worktrees_stay_clean(story):
    assert any(a.startswith("-Declipse.pluginCustomization=") and a.endswith("/lib/http-mode-preferences.ini") for a in story["cmdline"])
    assert any("lightweight auto-refresh is off" in line for line in story["log"])
    # 3 adds, then each of the 2 restarts' 3 saved worktrees
    assert sum(".git filter is on every project" in line for line in story["log"]) == 9
    for name, status in story["git_status"].items():
        assert ".project" not in status and ".classpath" not in status and ".settings" not in status, (name, status)
    assert story["exit"] == 0


def test_no_answer_is_read_while_a_clean_build_of_the_worktree_runs(story):
    """T002.05's review, R2."""
    run = story["concurrent_clean"]
    assert len(run["cleans"]) == 5 and all(status == 200 and errors(v) == EXPECTED["wt-a"] for status, v in run["cleans"])
    assert len(run["answers"]) >= 4, run["answers"]
    wrong = [(status, v.get("counts"), v.get("sync")) for status, v in run["answers"] if status != 200 or errors(v) != EXPECTED["wt-a"]]
    assert wrong == [], f"{len(wrong)} of {len(run['answers'])} answers weren't run 05's"


def test_cyclic_type_variable_bounds_dont_stop_the_problems(story):
    """T002.05's review, R4: naming the enclosing method doesn't recurse forever."""
    status, value = story["cyclic"]
    assert status == 200, value
    mine = [p for p in value["problems"] if p.get("file", "").endswith("Cyclic.java")]
    missing = {p["line"]: p.get("element") for p in mine if p["message"] == "Missing cannot be resolved to a type"}
    assert missing == {6: "org.fractal.harness.app.Cyclic#f(java.lang.Object)", 10: "org.fractal.harness.app.Cyclic#g(java.lang.Object)"}, mine
    assert any("Cycle" in p["message"] for p in mine), mine
    assert story["cyclic_gone"][1]["problems"] == []


def test_the_record_is_saved_for_each_worktree(story):
    wt = story["worktrees"]
    assert story["records"] == [str(wt / n) for n in ("main", "wt-a", "wt-b")]


@pytest.mark.parametrize("label, clean", [("restart_with_record", None), ("restart_without_record", "no-saved-record")])
def test_a_change_that_kept_its_mtime_while_jdtls_was_stopped_is_found_after_the_restart(story, label, clean):
    """T002.05's review, R1: with the saved record, the first fixed point compares against it; without one, it cleans."""
    r = story[label]
    assert r["status"] == 200 and r["exit"] == 0
    value = r["response"]
    assert value["sync"]["notSkippedBecause"] == "first" and value["sync"].get("cleanBuild") == clean, value["sync"]
    assert [p["file"] for p in value["problems"]] == ["src/main/java/org/fractal/harness/app/Launcher.java"], value["problems"]
    if clean is None:
        assert (LAUNCHER, "changed") in {(f["path"], f["change"]) for f in value["sync"]["filesChanged"]}
    assert r["after"] == r["before"], "the endpoint changed neither the file's bytes nor its times"
    assert r["restored"][1]["problems"] == []
    for name, (status, other) in r["others"].items():
        assert status == 200 and other["sync"].get("cleanBuild") is None, (name, other["sync"])
