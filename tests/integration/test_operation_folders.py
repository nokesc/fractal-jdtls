# SPDX-License-Identifier: Apache-2.0
"""Operation folders (design §11 "Operation folders"), through HTTP and the files only.

One story on the fixture's linked worktree wt-a, in one process, then a second one on the same `-data`:

- a dry run, then its apply with `expectWriteSetDigest`: each folder's `request.json`, `diff.patch` and
  `response.json`, and the answers' `operation` and `operationFolder`; a refactoring's own first fixed point takes the
  fast path (its folder is made after it), the next request's is a full one (Eclipse sees the folder's new files),
  and the one after that is fast again;
- `edit-changed`: a file of the edit changed between the dry run and the apply, once inside the diff's hunks and once
  only outside them (the same diff, another digest), nothing written either time;
- still running: an apply held at the test pause `before-apply`, listed with its `operation` by `GET /v1/status`
  while its folder has no `response.json`, and gone from it once `response.json` is there;
- a refusal (`refactoring-refused`) still has `request.json` and `response.json`;
- a `temp` that links out of the worktree, or inside it: `edit-outside-worktree`, nothing written through it;
- the outcome unknown: JDT LS killed (`kill -9`, the PID this test started) at the test pause `after-first-write`; its
  folder has `request.json` and `diff.patch` and no `response.json`, `git checkout` of the files `diff.patch` names
  restores them, and the next JDT LS on the same `-data` does nothing about it and doesn't list its ID.

Everything seen is kept in runs/operation-folders/seen.json.
"""

import hashlib
import json
import os
import re
import signal
import sys
import threading
import time
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

from endpoint_client import RELEASE, write_settings  # noqa: E402
from scenario import git, make_worktrees  # noqa: E402
from sync_scenario import start  # noqa: E402

NAMES = "org.fractal.harness.contract.Names"
NAMES_FILE = "harness-contract/src/main/java/org/fractal/harness/contract/Names.java"
GREETER_FILE = "harness-app/src/main/java/org/fractal/harness/app/Greeter.java"
ID = re.compile(r"^[0-9]{8}T[0-9]{6}\.[0-9]{3}Z-[0-9a-f]{8}$")


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_tree(root: Path) -> dict:
    """Every file of the worktree but Git's, build output and temp/, with its SHA-256."""
    hashes = {}
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames if d not in (".git", "target") and not (Path(dirpath) == root and d == "temp"))
        for f in filenames:
            if f != ".git":
                p = Path(dirpath) / f
                hashes[str(p.relative_to(root))] = sha(p)
    return hashes


def folder_files(folder: Path) -> dict:
    """An operation folder's files, each with its text."""
    return {p.name: p.read_text() for p in sorted(folder.iterdir())}


def patch_files(diff: str) -> list:
    """The files a unified diff names, from its `--- a/` and `+++ b/` lines."""
    names = []
    for line in diff.splitlines():
        for prefix in ("--- a/", "+++ b/"):
            if line.startswith(prefix) and line[len(prefix):] not in names:
                names.append(line[len(prefix):])
    return names


@pytest.fixture(scope="module")
def story(scratch, release_dir, maven_repository, launch_env, jvm_home):
    folder = scratch / "runs" / "operation-folders"
    folder.mkdir(parents=True)
    pauses = folder / "pauses"
    pauses.mkdir()
    wt = make_worktrees(folder / "worktrees")
    a = wt / "wt-a"
    ops = a / "temp" / "fractal-jdtls"
    settings = write_settings(folder / "settings.json", maven_repository)
    seen = {"worktree": str(a)}
    log = folder / f"data/.fractal-jdtls/{RELEASE}/endpoint.log"

    endpoint, c = start(folder, "jdtls", release_dir, jvm_home, launch_env, settings, jvm_args=(f"-Dfractal.jdtls.endpoint.testPauses={pauses}",))
    second = None

    def call(op, module="harness-contract", **body):
        status, value, ms = c.post(f"/v1/{op}", {"worktree": str(a), "module": module, **body})
        return {"status": status, "ms": ms, "answer": value}

    def newest():
        return max(ops.iterdir(), key=lambda p: p.name) if ops.exists() else None

    def held(point, op, **body):
        """Starts the request in a thread and waits until it's at the test pause `point`; the thread and its result."""
        (pauses / f"{point}.armed").write_text("")
        result = {}

        def run():
            try:
                result["answer"] = call(op, **body)
            except Exception as e:  # noqa: BLE001 - the killed process's connection
                result["error"] = repr(e)

        thread = threading.Thread(target=run)
        thread.start()
        deadline = time.monotonic() + 300
        while not (pauses / point).exists():
            assert time.monotonic() < deadline and thread.is_alive(), f"the request never reached {point}: {result}"
            time.sleep(0.01)
        return thread, result

    try:
        assert c.post("/v1/worktrees/add", {"worktree": str(a)})[0] == 200
        seen["first_build"] = call("build", ".", scope="worktree")
        seen["gitignore"] = (a / ".gitignore").read_text()

        # --- a dry run, then its apply with expectWriteSetDigest -------------------------------------
        rename = {"target": f"{NAMES}#normalize(String)", "newName": "canonical"}
        before = source_tree(a)
        seen["dry"] = call("refactor/rename", dryRun=True, **rename)
        seen["dry_unchanged"] = source_tree(a) == before
        dry = seen["dry"]["answer"]
        seen["dry_folder"] = folder_files(a / dry["operationFolder"])
        seen["applied"] = call("refactor/rename", expectWriteSetDigest=dry["writeSetDigest"], **rename)
        applied = seen["applied"]["answer"]
        seen["applied_folder"] = folder_files(a / applied["operationFolder"])
        seen["problems_after_apply"] = call("problems", scope="worktree")
        seen["problems_again"] = call("problems", scope="worktree")
        seen["listing_after_apply"] = sorted(str(p.relative_to(a)) for p in ops.rglob("*"))
        seen["git_status"] = git("status", "--porcelain", "--ignored=matching", cwd=a)
        seen["token_in_folders"] = any(endpoint.token in p.read_text() for p in ops.rglob("*") if p.is_file())

        # --- edit-changed: a change inside the diff's hunks, then one only outside them ----------------
        names = a / NAMES_FILE
        names_text = names.read_text()
        field = {"target": f"{NAMES}#count", "newName": "made"}
        seen["in_hunks_dry"] = call("refactor/rename", dryRun=True, **field)
        names.write_text(names_text.replace("raw.strip()", "raw.trim()"))
        before = source_tree(a)
        seen["in_hunks"] = call("refactor/rename", expectWriteSetDigest=seen["in_hunks_dry"]["answer"]["writeSetDigest"], **field)
        seen["in_hunks_unchanged"] = source_tree(a) == before
        seen["in_hunks_folder"] = folder_files(newest())
        names.write_text(names_text)

        seen["outside_hunks_dry"] = call("refactor/rename", dryRun=True, **field)
        names.write_text(names_text.replace('return "inner";', 'return "inner!";'))
        before = source_tree(a)
        seen["outside_hunks"] = call("refactor/rename", expectWriteSetDigest=seen["outside_hunks_dry"]["answer"]["writeSetDigest"], **field)
        seen["outside_hunks_unchanged"] = source_tree(a) == before
        seen["outside_hunks_names"] = names.read_text()
        names.write_text(names_text)

        # --- still running: held at before-apply -----------------------------------------------------
        thread, result = held("before-apply", "refactor/rename", **field)
        running = newest()
        seen["running_status"] = c.status()["inFlight"]
        seen["running_folder"] = {"id": running.name, "files": sorted(p.name for p in running.iterdir())}
        (pauses / "before-apply").unlink()
        thread.join(600)
        seen["released"] = result["answer"]
        seen["released_status"] = c.status()["inFlight"]
        seen["released_folder"] = sorted(p.name for p in running.iterdir())
        seen["released_response"] = json.loads((running / "response.json").read_text())

        # --- a refusal ---------------------------------------------------------------------------------
        seen["refused"] = call("refactor/rename", target=f"{NAMES}#pad(String)", newName="describe")
        seen["refused_folder"] = folder_files(newest())

        # --- a temp that links out of the worktree -----------------------------------------------------
        outside = folder / "outside"
        outside.mkdir()
        (a / "temp").rename(a / "temp.real")
        (a / "temp").symlink_to(outside)
        before = source_tree(a)
        seen["linked_dry"] = call("refactor/rename", dryRun=True, target=f"{NAMES}#made", newName="total")
        seen["linked"] = call("refactor/rename", target=f"{NAMES}#made", newName="total")
        seen["linked_outside"] = sorted(str(p.relative_to(outside)) for p in outside.rglob("*"))
        seen["linked_unchanged"] = source_tree(a) == before
        (a / "temp").unlink()
        # ... and one that links inside it (T002.10's review, R2): the records must be where the record skips them.
        inside = a / "scratch"
        inside.mkdir()
        (a / "temp").symlink_to(inside)
        before = source_tree(a)
        seen["linked_inside"] = call("refactor/rename", target=f"{NAMES}#made", newName="total")
        seen["linked_inside_scratch"] = sorted(str(p.relative_to(inside)) for p in inside.rglob("*"))
        seen["linked_inside_unchanged"] = source_tree(a) == before
        (a / "temp").unlink()
        inside.rmdir()
        (a / "temp.real").rename(a / "temp")

        # --- the outcome unknown: JDT LS killed after the first write ----------------------------------
        git("add", "-A", cwd=a)
        git("-c", "user.name=fractal-jdtls tests", "-c", "user.email=tests@localhost", "commit", "-q", "-m", "checkpoint", cwd=a)
        files = [GREETER_FILE, NAMES_FILE]
        seen["killed_before"] = {f: sha(a / f) for f in files}
        thread, result = held("after-first-write", "refactor/rename", target=f"{NAMES}#canonical(String)", newName="normal")
        killed = newest()
        seen["killed_marker_pid"] = int((pauses / "after-first-write").read_text())
        seen["killed_pid"] = endpoint.pid
        seen["killed_status"] = c.status()["inFlight"]
        seen["killed_at_pause"] = {f: sha(a / f) for f in files}
        os.kill(endpoint.pid, signal.SIGKILL)
        seen["killed_exit"] = endpoint.wait_exit(60)
        thread.join(60)
        (pauses / "after-first-write").unlink()
        seen["killed_result"] = result
        seen["killed_id"] = killed.name
        seen["killed_folder"] = folder_files(killed)
        seen["killed_after"] = {f: sha(a / f) for f in files}
        named = patch_files(seen["killed_folder"]["diff.patch"])
        seen["killed_patch_files"] = named
        seen["killed_git_status"] = git("status", "--porcelain", cwd=a)
        git("checkout", "--", *named, cwd=a)
        seen["killed_restored"] = {f: sha(a / f) for f in files}
        seen["killed_git_status_restored"] = git("status", "--porcelain", cwd=a)

        log_length = len(log.read_text())
        second, c = start(folder, "jdtls-2", release_dir, jvm_home, launch_env, settings)
        seen["restarted_status"] = c.status()
        seen["restarted_folder"] = folder_files(killed)
        seen["restarted_problems"] = call("problems", scope="worktree")
        seen["restarted_log"] = log.read_text()[log_length:].splitlines()
        assert second.request("POST", "/v1/shutdown", body={})[0] == 202
        seen["exit"] = second.wait_exit(120)
    finally:
        endpoint.stop()
        if second is not None:
            second.stop()
        (folder / "seen.json").write_text(json.dumps(seen, indent=1, default=str) + "\n")
    return seen


def test_a_dry_run_and_its_apply_each_have_a_folder_with_their_files(story):
    dry, applied = story["dry"]["answer"], story["applied"]["answer"]
    assert story["dry"]["status"] == 200 and story["applied"]["status"] == 200, (story["dry"], story["applied"])
    for answer in (dry, applied):
        assert ID.match(answer["operation"]), answer["operation"]
        assert answer["operationFolder"] == f"temp/fractal-jdtls/{answer['operation']}"
    assert dry["operation"] < applied["operation"], "IDs sort by time"
    assert (dry["applied"], applied["applied"]) == (False, True)
    assert story["dry_unchanged"], "the dry run wrote no source file"
    assert dry["writeSetDigest"] == applied["writeSetDigest"] and dry["diff"] == applied["diff"]
    for key, answer in (("dry_folder", dry), ("applied_folder", applied)):
        files = story[key]
        assert sorted(files) == ["diff.patch", "request.json", "response.json"], files
        assert files["diff.patch"] == answer["diff"]
        assert json.loads(files["response.json"]) == answer, "response.json is the answer as sent"
        request = json.loads(files["request.json"])
        assert (request["id"], request["operation"]) == (answer["operation"], "refactor/rename")
        assert request["request"]["target"] == f"{NAMES}#normalize(String)" and request["request"]["newName"] == "canonical"
        assert re.match(r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$", request["received"]), request["received"]
    assert json.loads(story["applied_folder"]["request.json"])["request"]["expectWriteSetDigest"] == dry["writeSetDigest"]
    assert not story["token_in_folders"], "the token is never in a folder"


def test_a_refactorings_own_fixed_point_is_fast_and_the_next_one_is_the_full_one(story):
    """The records' cost (§11): the folder is made after the refactoring's first fixed point, which stays on the fast
    path when nothing else changed; Eclipse's sync check sees the new files, so the next request's fixed point is the
    one full one (accepted); the record skips them, so none is reported, and the one after that is fast again."""
    for key in ("dry", "in_hunks_dry"):
        own = story[key]["answer"]["sync"]
        assert own["skipped"] is True, (key, own)
    applied = story["applied"]["answer"]
    for sync in (applied["sync"], story["problems_after_apply"]["answer"]["sync"]):
        assert (sync["skipped"], sync["notSkippedBecause"], sync["filesChanged"]) == (False, "disk-changed", []), sync
    assert not any("temp/" in f["path"] for f in applied["syncAfter"]["filesChanged"]), applied["syncAfter"]
    again = story["problems_again"]["answer"]["sync"]
    assert again["skipped"] is True, again


def test_temp_is_ignored_by_the_fixtures_git(story):
    assert "temp/\n" in story["gitignore"]
    assert "!! temp/\n" in story["git_status"], story["git_status"]


def test_edit_changed_inside_the_hunks(story):
    dry, changed = story["in_hunks_dry"]["answer"], story["in_hunks"]
    assert changed["status"] == 409 and changed["answer"]["code"] == "edit-changed", changed
    assert changed["answer"]["writeSetDigest"] != dry["writeSetDigest"]
    assert changed["answer"]["diff"] != dry["diff"] and "raw.trim()" in changed["answer"]["diff"]
    assert story["in_hunks_unchanged"], "nothing written"
    files = story["in_hunks_folder"]
    assert sorted(files) == ["diff.patch", "request.json", "response.json"]
    assert files["diff.patch"] == changed["answer"]["diff"]
    assert json.loads(files["response.json"]) == changed["answer"]


def test_edit_changed_only_outside_the_hunks_has_the_same_diff(story):
    dry, changed = story["outside_hunks_dry"]["answer"], story["outside_hunks"]
    assert changed["status"] == 409 and changed["answer"]["code"] == "edit-changed", changed
    assert changed["answer"]["diff"] == dry["diff"], "the change is outside every hunk"
    assert changed["answer"]["writeSetDigest"] != dry["writeSetDigest"], "but the writes differ"
    assert story["outside_hunks_unchanged"], "nothing written"
    assert 'return "inner!";' in story["outside_hunks_names"] and "public static int count;" in story["outside_hunks_names"]


def test_a_running_refactoring_is_listed_until_its_response_is_written(story):
    folder = story["running_folder"]
    listed = [o for o in story["running_status"] if o.get("operation") == folder["id"]]
    assert len(listed) == 1 and listed[0]["path"] == "/v1/refactor/rename", story["running_status"]
    assert folder["files"] == ["diff.patch", "request.json"], "no response.json while it runs"
    assert story["released"]["status"] == 200 and story["released"]["answer"]["operation"] == folder["id"]
    assert not [o for o in story["released_status"] if o.get("operation") == folder["id"]], story["released_status"]
    assert story["released_folder"] == ["diff.patch", "request.json", "response.json"]
    assert story["released_response"] == story["released"]["answer"]


def test_a_refused_refactoring_has_its_request_and_response(story):
    refused = story["refused"]
    assert refused["status"] == 422 and refused["answer"]["code"] == "refactoring-refused", refused
    files = story["refused_folder"]
    assert sorted(files) == ["request.json", "response.json"], "JDT refused it before there was a diff"
    assert json.loads(files["response.json"]) == refused["answer"]


def test_a_temp_that_links_out_of_the_worktree_is_refused(story):
    for key in ("linked_dry", "linked"):
        answer = story[key]
        assert answer["status"] == 422 and answer["answer"]["code"] == "edit-outside-worktree", answer
        assert answer["answer"]["files"][0].startswith("temp/fractal-jdtls/")
    assert story["linked_outside"] == [], "nothing written outside"
    assert story["linked_unchanged"], "nothing written in the worktree"


def test_a_temp_that_links_inside_the_worktree_is_refused_too(story):
    answer = story["linked_inside"]
    assert answer["status"] == 422 and answer["answer"]["code"] == "edit-outside-worktree", answer
    assert "/scratch/fractal-jdtls/" in answer["answer"]["detail"], answer["answer"]["detail"]
    assert story["linked_inside_scratch"] == [], "nothing written through the link"
    assert story["linked_inside_unchanged"], "nothing written in the worktree"


def test_the_outcome_unknown_after_a_kill(story):
    assert story["killed_marker_pid"] == story["killed_pid"], "the PID the test started is JDT LS's"
    assert story["killed_exit"] == -signal.SIGKILL
    assert "error" in story["killed_result"], story["killed_result"]
    assert [o["operation"] for o in story["killed_status"] if "operation" in o] == [story["killed_id"]]
    files = story["killed_folder"]
    assert sorted(files) == ["diff.patch", "request.json"], "no response.json: the outcome is unknown"
    before, after = story["killed_before"], story["killed_after"]
    assert after[GREETER_FILE] != before[GREETER_FILE] and after[NAMES_FILE] == before[NAMES_FILE], "killed after the first write"
    assert story["killed_patch_files"] == [GREETER_FILE, NAMES_FILE]
    assert story["killed_restored"] == before, "git checkout of the files diff.patch names restores them"
    assert story["killed_git_status_restored"] == ""


def test_the_next_start_does_nothing_about_it(story):
    status = story["restarted_status"]
    assert status["ready"] is True and status["inFlight"] == [], status["inFlight"]
    assert story["restarted_folder"] == story["killed_folder"], "the folder is left as it was"
    assert not [line for line in story["restarted_log"] if story["killed_id"] in line]
    assert story["restarted_problems"]["status"] == 200
    assert story["exit"] == 0
