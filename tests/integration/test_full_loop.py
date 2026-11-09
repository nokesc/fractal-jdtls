# SPDX-License-Identifier: Apache-2.0
"""T002's overall acceptance loop (T002.md, "Overall acceptance"), against the installed release, through HTTP only.

The suite's install, made by install.py from the freshly built release zip, is started through its own bin/jdtls in
HTTP mode: the token in the environment, the port file, a settings file, and HTTP requests, with no LSP client of the
test's own. One process, in order:

1. start in HTTP mode, and wait for the port file and readiness;
2. add two worktrees, the fixture's main checkout and wt-a;
3. write a file with a type error on disk in wt-a, without telling the endpoint, and `build`: exactly that error, and
   none in main; delete it, and `build` is clean again;
4. rename a method by its Java name in wt-a, and see the edit (its files and diff) and a clean build;
5. remove wt-a, whose files stay;
6. shut down, through `POST /v1/shutdown`.

Every file and folder under the install, with its hash, mode and modification time, is the same before and after,
and the install's own `install.py --verify` passes afterwards. The transcript is in runs/full-loop/loop.json.
"""

import hashlib
import json
import os
import sys
import time
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

from endpoint_client import Client, Contract, EndpointProcess, http_command, write_settings  # noqa: E402
from scenario import APP, git, make_worktrees, verify  # noqa: E402
from sync_scenario import BROKEN, errors  # noqa: E402

NAMES = "org.fractal.harness.contract.Names"
BROKEN_FILE = f"{APP}/Broken.java"
BROKEN_ERROR = ("harness-app", "src/main/java/org/fractal/harness/app/Broken.java", 5, "Type mismatch: cannot convert from String to int")
RENAMED = ["harness-app/src/main/java/org/fractal/harness/app/Greeter.java",
           "harness-contract/src/main/java/org/fractal/harness/contract/Names.java"]  # fmt: skip


def install_state(folder: Path) -> dict[str, list]:
    """Every file and folder under the install, not following links: a file's hash, mode and modification time, a
    folder's mode and modification time (which a file made and deleted again would change)."""
    state = {}
    for path in sorted(folder.rglob("*")):
        st = path.lstat()
        key = path.relative_to(folder).as_posix()
        if path.is_symlink():
            state[key] = ["link", os.readlink(path), st.st_mtime_ns]
        elif path.is_file():
            state[key] = [hashlib.sha256(path.read_bytes()).hexdigest(), oct(st.st_mode), st.st_mtime_ns]
        else:
            state[key] = ["dir", oct(st.st_mode), st.st_mtime_ns]
    st = folder.lstat()
    state["."] = ["dir", oct(st.st_mode), st.st_mtime_ns]
    return state


def tree(root: Path) -> dict[str, str]:
    """A worktree's files, but Git's, build output and the endpoint's operation folders, with their hashes."""
    hashes = {}
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames if d not in (".git", "target") and not (Path(dirpath) == root and d == "temp"))
        for f in filenames:
            if f != ".git":
                p = Path(dirpath) / f
                hashes[p.relative_to(root).as_posix()] = hashlib.sha256(p.read_bytes()).hexdigest()
    return hashes


@pytest.fixture(scope="module")
def loop(scratch, release_dir, maven_repository, launch_env, jvm_home):
    folder = scratch / "runs" / "full-loop"
    folder.mkdir(parents=True)
    wt = make_worktrees(folder / "worktrees")
    main, a = wt / "main", wt / "wt-a"
    settings = write_settings(folder / "settings.json", maven_repository)
    port_file = folder / "port.json"
    seen = {"release": str(release_dir), "install_before": install_state(release_dir), "main_before": tree(main)}
    transcript = []

    def note(step, what, status, ms, result):
        transcript.append({"step": step, "what": what, "status": status, "ms": ms, "result": result})

    endpoint = EndpointProcess(http_command(release_dir, jvm_home, folder / "data", port_file, settings),
                               env=launch_env, run_dir=folder / "jdtls", port_file=port_file)  # fmt: skip
    seen["command"] = endpoint.cmd
    with endpoint:
        # 1. Start in HTTP mode.
        info = endpoint.wait_port_file()
        seen["port_file"] = info
        seen["port_file_mode"] = oct(port_file.stat().st_mode & 0o777)
        note(1, "port file", None, round(endpoint.port_file_after * 1000), {k: info[k] for k in ("release", "api")} | {"after_s": endpoint.port_file_after})
        status, _, description = endpoint.request("GET", "/v1/openapi.json")
        assert status == 200, description
        c = Client(endpoint, Contract(json.loads(description)))
        started = time.monotonic()
        status, _, text = endpoint.request("GET", "/v1/status?wait=ready&timeoutMs=300000")
        seen["ready"] = (status, json.loads(text))
        note(1, "GET /v1/status?wait=ready", status, round((time.monotonic() - started) * 1000), {"ready": json.loads(text).get("ready")})

        def post(step, path, body):
            status, value, ms = c.post(path, body)
            short = {k: v for k, v in value.items() if k not in ("diff", "sync", "syncAfter", "modules", "problems")}
            if "problems" in value:
                short["errors"] = errors(value)
            note(step, f"POST {path} {json.dumps({k: (Path(v).name if k == 'worktree' else v) for k, v in body.items()})}", status, ms, short)
            return status, value

        # 2. Add two worktrees.
        seen["add"] = {name: post(2, "/v1/worktrees/add", {"worktree": str(wt / name)}) for name in ("main", "wt-a")}

        # 3. Edit a file on disk, then build: the expected problems.
        (a / BROKEN_FILE).write_text(BROKEN)
        seen["broken_build"] = post(3, "/v1/build", {"worktree": str(a), "module": "harness-app", "scope": "worktree"})
        seen["main_build"] = post(3, "/v1/build", {"worktree": str(main), "module": "harness-app", "scope": "worktree"})
        (a / BROKEN_FILE).unlink()
        seen["fixed_build"] = post(3, "/v1/build", {"worktree": str(a), "module": "harness-app", "scope": "worktree"})

        # 4. Rename a method by name: the edit, and a clean build.
        a_before = tree(a)
        seen["rename"] = post(4, "/v1/refactor/rename", {"worktree": str(a), "module": "harness-contract",
                                                         "target": f"{NAMES}#normalize(String)", "newName": "canonical"})  # fmt: skip
        a_after = tree(a)
        seen["a_changed"] = sorted(k for k in set(a_before) | set(a_after) if a_before.get(k) != a_after.get(k))
        seen["git_diff"] = git("diff", "--stat", cwd=a)
        seen["rename_build"] = post(4, "/v1/build", {"worktree": str(a), "module": ".", "scope": "worktree"})

        # 5. Remove a worktree.
        seen["remove"] = post(5, "/v1/worktrees/remove", {"worktree": str(a)})
        seen["list"] = post(5, "/v1/worktrees/list", {})
        seen["a_kept"] = (a / RENAMED[1]).is_file()

        # 6. Shut down.
        started = time.monotonic()
        status, _, text = endpoint.request("POST", "/v1/shutdown", body={})
        seen["shutdown"] = (status, json.loads(text))
        seen["exit_code"] = endpoint.wait_exit(120)
        note(6, "POST /v1/shutdown", status, round((time.monotonic() - started) * 1000), {"answer": json.loads(text), "exit": seen["exit_code"]})
    seen["port_file_after"] = port_file.exists()
    seen["main_after"] = tree(main)
    seen["install_after"] = install_state(release_dir)
    checked = verify(release_dir)
    seen["verify"] = (checked.returncode, checked.stdout + checked.stderr)
    note("after", "install.py --verify", checked.returncode, None, (checked.stdout + checked.stderr).strip().splitlines()[-1:])
    seen["install_files"] = len(seen["install_after"])
    seen["transcript"] = transcript
    (folder / "loop.json").write_text(json.dumps({k: v for k, v in seen.items() if not k.startswith(("install_before", "install_after", "main_"))},
                                                 indent=1, default=str) + "\n")  # fmt: skip
    return seen


def test_it_starts_in_http_mode(loop):
    assert loop["port_file"]["release"] == Path(loop["release"]).name
    assert loop["port_file_mode"] == "0o600"
    assert loop["ready"][0] == 200 and loop["ready"][1]["ready"] is True


def test_it_adds_two_worktrees(loop):
    for name, (status, value) in loop["add"].items():
        assert status == 200, (name, value)
    names = sorted(Path(w["worktree"]).name for w in loop["list"][1]["worktrees"])
    assert names == ["main"], names  # after wt-a's removal


def test_an_edit_on_disk_is_built_with_the_expected_problems(loop):
    status, value = loop["broken_build"]
    assert status == 200, value
    assert errors(value) == [BROKEN_ERROR]
    assert errors(loop["main_build"][1]) == [] and loop["main_build"][0] == 200
    assert loop["fixed_build"][0] == 200 and errors(loop["fixed_build"][1]) == []


def test_a_rename_by_name_edits_and_builds_clean(loop):
    status, value = loop["rename"]
    assert status == 200, value
    assert value["applied"] is True
    assert sorted(f["path"] for f in value["files"]) == RENAMED
    assert "-    public static String normalize(String raw) {\n+    public static String canonical(String raw) {" in value["diff"]
    assert value["counts"]["error"] == 0
    assert loop["a_changed"] == RENAMED, "the rename changed exactly its files"
    status, built = loop["rename_build"]
    assert status == 200 and built["counts"]["error"] == 0 and errors(built) == [], built
    assert loop["main_after"] == loop["main_before"], "main's files never changed"


def test_it_removes_a_worktree_and_keeps_its_files(loop):
    status, value = loop["remove"]
    assert status == 200 and value["removed"] is True and value["filesKept"] is True, value
    assert loop["a_kept"]


def test_it_shuts_down(loop):
    assert loop["shutdown"][0] == 202 and loop["shutdown"][1] == {"shuttingDown": True}, loop["shutdown"]
    assert loop["exit_code"] == 0
    assert loop["port_file_after"] is False


def test_nothing_is_written_inside_the_install_and_it_verifies(loop):
    assert loop["install_after"] == loop["install_before"]
    assert loop["install_files"] > 100
    assert loop["verify"][0] == 0, loop["verify"][1]
