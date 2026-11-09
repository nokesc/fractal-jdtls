# SPDX-License-Identifier: Apache-2.0
"""The T002 refactorings (design §11 "Refactorings and edits"), through HTTP only.

One story on the fixture's main checkout and linked worktrees, in one process, every refactoring in wt-a while wt-b
holds the same code:

- organize imports (an ambiguous `List` first, then with its choice), extract constant, extract local variable,
  extract method (statements, with the requested name in the one edit), inline local variable, and rename of a
  method (asked first from a module that doesn't declare it), a field, a local variable, a type with its file, and a
  package with its folder; each as a dry run that writes nothing, then applied, then a clean `build`;
- JDT's refusals (a name clash, an invalid name, a selection that can't be extracted, a field to inline), each with
  nothing written;
- `files-changed`: another session's change to a file between the edit's computation and its apply, nothing written,
  and the same request sent again succeeding;
- `write-failed`: a file of the edit made unwritable after the apply's baseline check, so the second write fails, and
  the first file is put back to its exact bytes;
- `timeout`: the deadline passing after the apply's check, just before the first write, with nothing written;
- wt-b's and main's files, hashed before and after, never change.

The two races use the endpoint's test-only pauses (`-Dfractal.jdtls.endpoint.testPauses=<folder>`, `TestPauses`).
Every call is kept in runs/refactoring/calls.json.
"""

import hashlib
import json
import os
import stat
import sys
import threading
import time
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

from endpoint_client import RELEASE, write_settings  # noqa: E402
from scenario import APP, CONTRACT, make_worktrees  # noqa: E402
from sync_scenario import start  # noqa: E402

GREETER = "src/main/java/org/fractal/harness/app/Greeter.java"
LISTS = "src/main/java/org/fractal/harness/app/Lists.java"
NAMES = "org.fractal.harness.contract.Names"
NAMES_FILE = "src/main/java/org/fractal/harness/contract/Names.java"
LISTS_SOURCE = ("package org.fractal.harness.app;\n\nimport java.util.ArrayList;\nimport java.util.Map;\n\n"
                "public class Lists {\n\n    public List<String> names() {\n        return new ArrayList<>();\n    }\n}\n")  # fmt: skip


def tree(root: Path) -> dict:
    """Every file under the root but Git's, build output and the endpoint's operation folders, by path, with its SHA-256."""
    hashes = {}
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames if d not in (".git", "target") and not (Path(dirpath) == root and d == "temp"))
        for f in filenames:
            p = Path(dirpath) / f
            if f != ".git":
                hashes[str(p.relative_to(root))] = hashlib.sha256(p.read_bytes()).hexdigest()
    return hashes


@pytest.fixture(scope="module")
def story(scratch, release_dir, maven_repository, launch_env, jvm_home):
    folder = scratch / "runs" / "refactoring"
    folder.mkdir(parents=True)
    pauses = folder / "pauses"
    pauses.mkdir()
    wt = make_worktrees(folder / "worktrees")
    a = wt / "wt-a"
    settings = write_settings(folder / "settings.json", maven_repository)
    seen = {"worktrees": wt}
    calls = []
    for name in ("wt-a", "wt-b"):
        (wt / name / APP / "Lists.java").write_text(LISTS_SOURCE)
    seen["before"] = {name: tree(wt / name) for name in ("main", "wt-a", "wt-b")}

    endpoint, c = start(folder, "jdtls", release_dir, jvm_home, launch_env, settings, jvm_args=(f"-Dfractal.jdtls.endpoint.testPauses={pauses}",))

    def call(op, module, worktree="wt-a", **body):
        request = {"worktree": str(wt / worktree), "module": module, **body}
        status, value, ms = c.post(f"/v1/{op}", request)
        calls.append({"op": op, "worktree": worktree, "request": request, "status": status, "ms": ms, "response": value})
        return status, value

    def step(key, op, module, **body):
        """A refactoring as a dry run, the worktree's files hashed around it, then applied, then a build."""
        before = tree(a)
        seen[key + "_dry"] = call(op, module, dryRun=True, **body)
        seen[key + "_dry_unchanged"] = tree(a) == before
        seen[key] = call(op, module, **body)
        seen[key + "_build"] = call("build", ".", scope="worktree")

    def refused(key, op, module, **body):
        before = tree(a)
        seen[key] = call(op, module, **body)
        seen[key + "_unchanged"] = tree(a) == before

    def race(key, point, op, module, act, **body):
        """The request stopped at the test pause `point` while `act` runs, then let go."""
        (pauses / f"{point}.armed").write_text("")
        result = {}
        thread = threading.Thread(target=lambda: result.update(answer=call(op, module, **body)))
        thread.start()
        marker = pauses / point
        deadline = time.monotonic() + 300
        while not marker.exists():
            assert time.monotonic() < deadline and thread.is_alive(), f"the request never reached {point}"
            time.sleep(0.01)
        act()
        seen[key + "_during"] = tree(a)
        marker.unlink()
        thread.join(600)
        seen[key] = result["answer"]
        seen[key + "_after"] = tree(a)

    try:
        for name in ("main", "wt-a", "wt-b"):
            assert c.post("/v1/worktrees/add", {"worktree": str(wt / name)})[0] == 200
        seen["first_build"] = call("build", ".", scope="worktree")

        # --- organize imports: List is java.util's or java.awt's ---------------------------------
        refused("imports_ambiguous", "refactor/organize-imports", "harness-app", file=LISTS)
        step("imports", "refactor/organize-imports", "harness-app", file=LISTS, choices={"List": "java.util.List"})
        seen["imports_again"] = call("refactor/organize-imports", "harness-app", file=LISTS)

        # --- extract and inline, in Greeter --------------------------------------------------------
        step("constant", "refactor/extract-constant", "harness-app", name="GREETING", target={"file": GREETER, "text": '"Hello, "'})
        step("variable", "refactor/extract-variable", "harness-app", name="padding",
             target={"file": GREETER, "text": '" ".repeat(Math.max(0, width))'})  # fmt: skip
        seen["greeter_after_variable"] = (a / "harness-app" / GREETER).read_text()
        step("method", "refactor/extract-method", "harness-app", name="pad",
             target={"file": GREETER, "text": "int width = 10 - clean.length();\n        String padding = \" \".repeat(Math.max(0, width));"})  # fmt: skip
        step("inline", "refactor/inline-variable", "harness-app", target={"file": GREETER, "text": "String padding = pad(clean);", "select": "padding"})

        # --- renames ---------------------------------------------------------------------------------
        refused("rename_other_module", "refactor/rename", "harness-app", target=f"{NAMES}#normalize(String)", newName="canonical")
        step("rename_method", "refactor/rename", "harness-contract", target=f"{NAMES}#normalize(String)", newName="canonical")
        step("rename_field", "refactor/rename", "harness-contract", target=f"{NAMES}#count", newName="made")
        step("rename_local", "refactor/rename", "harness-app", newName="name",
             target={"file": GREETER, "text": "String clean = Names.canonical(who);", "select": "clean"})  # fmt: skip
        step("rename_type", "refactor/rename", "harness-app", target="org.fractal.harness.app.Greeter", newName="Welcomer")
        step("rename_package", "refactor/rename", "harness-app", target="org.fractal.harness.app", newName="org.fractal.harness.application")
        seen["a_after_renames"] = tree(a)

        # --- refusals --------------------------------------------------------------------------------
        refused("refused_clash", "refactor/rename", "harness-contract", target=f"{NAMES}#pad(String)", newName="describe")
        refused("refused_name", "refactor/rename", "harness-contract", target=f"{NAMES}#pad(String)", newName="1st")
        welcomer = "src/main/java/org/fractal/harness/application/Welcomer.java"
        refused("refused_extract", "refactor/extract-method", "harness-app", name="part", target={"file": welcomer, "text": "GREETING + name"})
        refused("refused_inline_field", "refactor/inline-variable", "harness-contract",
                target={"file": NAMES_FILE, "text": "private final int width;", "select": "width"})  # fmt: skip
        refused("refused_anchor_name", "refactor/extract-variable", "harness-app", name="x", target="org.fractal.harness.application.Welcomer")

        # --- files-changed: another session's change between the computation and the apply -----------
        welcomer_path = a / "harness-app" / welcomer
        shout = {"target": "org.fractal.harness.application.Welcomer#shout(String)", "newName": "yell"}
        race("changed", "before-apply", "refactor/rename", "harness-app",
             lambda: welcomer_path.write_text(welcomer_path.read_text() + "// another session's line\n"), **shout)
        seen["changed_again"] = call("refactor/rename", "harness-app", **shout)
        seen["changed_again_build"] = call("build", ".", scope="worktree")

        # --- write-failed: the second file can't be written; the first is put back --------------------
        names_path = a / "harness-contract" / NAMES_FILE
        mode = names_path.stat().st_mode
        race("unwritable", "before-write", "refactor/rename", "harness-contract",
             lambda: names_path.chmod(stat.S_IRUSR | stat.S_IRGRP | stat.S_IROTH), target=f"{NAMES}#canonical(String)", newName="normal")
        names_path.chmod(mode)
        seen["unwritable_build"] = call("build", ".", scope="worktree")

        # --- timeout: the deadline passes after the apply's check, just before the first write ----------
        # (T002.07's review, R4.) Armed and never let go, the pause at before-write lasts until the deadline.
        (pauses / "before-write.armed").write_text("")
        seen["expired_before"] = tree(a)
        seen["expired"] = call("refactor/rename", "harness-contract", target=f"{NAMES}#canonical(String)", newName="normal", timeoutMs=4000)
        seen["expired_after"] = tree(a)
        seen["expired_build"] = call("build", ".", scope="worktree")

        seen["clean_build"] = call("build", ".", scope="worktree", clean=True)
        seen["after"] = {name: tree(wt / name) for name in ("main", "wt-a", "wt-b")}
        seen["files"] = {
            "Welcomer": welcomer_path.read_text(),
            "PrintStep": (a / "harness-app/src/main/java/org/fractal/harness/application/PrintStep.java").read_text(),
            "Lists": (a / "harness-app/src/main/java/org/fractal/harness/application/Lists.java").read_text(),
            "Names": names_path.read_text(),
        }
        seen["old_package_folder"] = (a / APP).exists()
        seen["log"] = (folder / f"data/.fractal-jdtls/{RELEASE}/endpoint.log").read_text().splitlines()
        assert endpoint.request("POST", "/v1/shutdown", body={})[0] == 202
        seen["exit"] = endpoint.wait_exit(120)
    finally:
        endpoint.stop()
        (folder / "calls.json").write_text(json.dumps(calls, indent=1) + "\n")
    seen["calls"] = calls
    (folder / "hashes.json").write_text(json.dumps({"before": seen["before"], "after": seen.get("after")}, indent=1) + "\n")
    record = {k: v for k, v in seen.items() if k not in ("worktrees", "log", "calls", "before", "after")}
    (folder / "seen.json").write_text(json.dumps(record, indent=1, default=str) + "\n")
    return seen


def ok(pair):
    status, value = pair
    assert status == 200, value
    return value


def test_the_story_ran_and_stopped_cleanly(story):
    assert story["exit"] == 0
    expired = [c for c in story["calls"] if c["request"].get("timeoutMs") == 4000]
    assert [c for c in story["calls"] if c["status"] >= 500] == expired, "no 5xx but the deliberate timeout"


def applied(story, key):
    """A refactoring's dry run and its apply: the same edit, the dry run writing nothing, the apply then building clean."""
    dry = ok(story[key + "_dry"])
    done = ok(story[key])
    assert dry["applied"] is False and "syncAfter" not in dry and "problems" not in dry
    assert story[key + "_dry_unchanged"], "the dry run wrote nothing"
    assert done["applied"] is True
    assert (done["files"], done["diff"]) == (dry["files"], dry["diff"])
    assert done["syncAfter"]["notSkippedBecause"] == "edit-applied"
    assert {f["path"] for f in done["syncAfter"]["filesChanged"]} >= {f["path"] for f in done["files"]}, "the apply's fixed point took the files in"
    assert done["counts"]["error"] == 0 and done["problems"] == []
    assert ok(story[key + "_build"])["counts"]["error"] == 0, "a clean build after it"
    return done


def changes(value):
    return [(f["change"], f["path"].rsplit("/", 1)[1], f.get("from", "").rsplit("/", 1)[-1]) for f in value["files"]]


def problem(pair, status, code):
    got, value = pair
    assert (got, value["code"]) == (status, code), value
    assert value["hint"]
    return value


def test_organize_imports_asks_for_an_ambiguous_name_then_takes_its_choice(story):
    first_errors = ok(story["first_build"])["problems"]
    assert [p["message"] for p in first_errors] == ["List cannot be resolved to a type"]
    ambiguous = problem(story["imports_ambiguous"], 422, "ambiguous-import")
    assert story["imports_ambiguous_unchanged"]
    assert ambiguous["field"] == "/choices/List"
    chosen = {c["set"]["/choices/List"] for c in ambiguous["candidates"]}
    assert {"java.util.List", "java.awt.List"} <= chosen
    done = applied(story, "imports")
    assert changes(done) == [("modified", "Lists.java", "")]
    assert "-import java.util.Map;\n+import java.util.List;\n" in done["diff"]
    again = ok(story["imports_again"])
    assert (again["applied"], again["files"], again["diff"]) == (False, [], ""), "nothing left to organize: nothing applied"


def test_extract_constant_variable_and_method_and_inline(story):
    constant = applied(story, "constant")
    assert '+    private static final String GREETING = "Hello, ";\n' in constant["diff"]
    assert '+        return GREETING + clean + " ".repeat(Math.max(0, width));\n' in constant["diff"]
    variable = applied(story, "variable")
    assert '+        String padding = " ".repeat(Math.max(0, width));\n+        return GREETING + clean + padding;\n' in variable["diff"]
    method = applied(story, "method")
    # The requested name arrives with the extraction, in the one edit: never JDT LS's `extracted`.
    assert "+        String padding = pad(clean);\n" in method["diff"]
    assert "+    private String pad(String clean) {\n" in method["diff"]
    assert "extracted" not in method["diff"]
    inline = applied(story, "inline")
    assert "-        String padding = pad(clean);\n-        return GREETING + clean + padding;\n+        return GREETING + clean + pad(clean);\n" in inline["diff"]
    for key in ("constant", "variable", "method", "inline"):
        assert changes(ok(story[key])) == [("modified", "Greeter.java", "")]


def test_renames_of_a_method_field_local_type_and_package(story):
    other = problem(story["rename_other_module"], 422, "declared-in-other-module")
    assert story["rename_other_module_unchanged"]
    assert other["candidates"][0]["set"] == {"/module": "harness-contract"}
    method = applied(story, "rename_method")
    assert changes(method) == [("modified", "Greeter.java", ""), ("modified", "Names.java", "")]
    assert method["diff"].count("+        String clean = Names.canonical(who);\n") == 1
    assert "{@link #canonical(String)}" in method["diff"], "the Javadoc link too"
    field = applied(story, "rename_field")
    assert "+    public static int made;\n" in field["diff"] and "+        made++;\n" in field["diff"]
    local = applied(story, "rename_local")
    assert "+        String name = Names.canonical(who);\n+        return GREETING + name + pad(name);\n" in local["diff"]
    assert "+    private String pad(" not in local["diff"], "another method's parameter of the same name is left alone"
    renamed = applied(story, "rename_type")
    assert changes(renamed) == [("modified", "PrintStep.java", ""), ("renamed", "Welcomer.java", "Greeter.java")]
    assert "--- a/harness-app/src/main/java/org/fractal/harness/app/Greeter.java\n+++ b/harness-app/src/main/java/org/fractal/harness/app/Welcomer.java\n" in renamed["diff"]
    package = applied(story, "rename_package")
    assert changes(package) == [("renamed", n, n) for n in ("Launcher.java", "Lists.java", "PrintStep.java", "Welcomer.java")]
    assert all(f["path"].startswith("harness-app/src/main/java/org/fractal/harness/application/") for f in package["files"])
    assert package["diff"].count("+package org.fractal.harness.application;\n") == 4
    assert story["old_package_folder"] is False, "the emptied package folder went with its files"
    assert "public class Welcomer {" in story["files"]["Welcomer"]
    assert "private final Welcomer greeter = new Welcomer();" in story["files"]["PrintStep"]


def test_refusals_quote_jdt_and_write_nothing(story):
    clash = problem(story["refused_clash"], 422, "refactoring-refused")
    assert "describe" in clash["detail"] and "same parameter type" in clash["detail"] and "Nothing was written." in clash["detail"]
    name = problem(story["refused_name"], 422, "refactoring-refused")
    assert "'1st' is not a valid Java identifier" in name["detail"]
    extract = problem(story["refused_extract"], 422, "not-applicable")
    assert "Can only extract a single expression or a set of statements" in extract["detail"]
    inline = problem(story["refused_inline_field"], 422, "not-applicable")
    assert "is a field" in inline["detail"]
    anchor = problem(story["refused_anchor_name"], 400, "invalid-request")
    assert anchor["errors"][0]["field"] == "/target"
    for key in ("refused_clash", "refused_name", "refused_extract", "refused_inline_field", "refused_anchor_name"):
        assert story[key + "_unchanged"], key


def test_a_file_changed_between_computing_and_applying_is_files_changed(story):
    value = problem(story["changed"], 409, "files-changed")
    assert value["retryable"] is True
    assert value["files"] == ["harness-app/src/main/java/org/fractal/harness/application/Welcomer.java"]
    assert story["changed_after"] == story["changed_during"], "nothing written: every file as the other session left it"
    assert story["files"]["Welcomer"].endswith("}\n// another session's line\n"), "the other session's line is kept"
    again = ok(story["changed_again"])
    assert again["applied"] is True and "+    public String yell(String who) {\n" in again["diff"]
    assert ok(story["changed_again_build"])["counts"]["error"] == 0


def test_a_write_that_fails_puts_every_written_file_back(story):
    value = problem(story["unwritable"], 409, "write-failed")
    assert value["files"] == ["harness-contract/src/main/java/org/fractal/harness/contract/Names.java"]
    assert "AccessDeniedException" in value["detail"] and "restored to its earlier bytes" in value["detail"]
    # Welcomer.java (harness-app) sorts first and was written, then put back when Names.java failed.
    assert story["unwritable_after"] == story["unwritable_during"]
    assert "Names.canonical(who)" in story["files"]["Welcomer"] and "public static String canonical(String raw)" in story["files"]["Names"]
    assert ok(story["unwritable_build"])["counts"]["error"] == 0
    assert any("test pause: waiting at before-write" in line for line in story["log"])


def test_a_deadline_passing_just_before_the_writes_is_timeout_with_nothing_written(story):
    value = problem(story["expired"], 504, "timeout")
    assert value["phase"] == "applying", value
    assert story["expired_after"] == story["expired_before"], "nothing written"
    assert "public static String canonical(String raw)" in story["files"]["Names"]
    assert ok(story["expired_build"])["counts"]["error"] == 0


def test_wt_b_and_main_are_untouched_and_wt_a_builds_clean(story):
    for name in ("main", "wt-b"):
        assert story["after"][name] == story["before"][name], name
    assert story["after"]["wt-a"] != story["before"]["wt-a"]
    clean = ok(story["clean_build"])
    assert clean["sync"]["cleanBuild"] == "requested" and clean["counts"]["error"] == 0
