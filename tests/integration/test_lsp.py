# SPDX-License-Identifier: Apache-2.0
"""The LSP passthrough, `POST /v1/lsp` (design §11 "The LSP passthrough"), through HTTP only.

One story on the fixture's main checkout and linked worktrees wt-a and wt-b, in one process:

- `textDocument/documentSymbol` on wt-a's `Greeter`, and `workspace/executeCommand` `java.project.getClasspaths` on
  each worktree's harness-app, which reproduces run 05's classpath check: each resolves its own worktree's
  harness-contract, and nothing outside its worktree;
- every method and command the passthrough refuses, each problem kept whole, and the startup log's classification of
  every method JDT LS serves and every command it advertises (none unclassified);
- JDT LS's own error, kept as `lsp-error`; a `file:` URI of wt-b in a request for wt-a, refused, and the request with
  the problem's candidate applied;
- edits returned unapplied (`textDocument/rename`, `java.edit.organizeImports`), with the files unchanged on disk;
- a method that needs an open document, answered without one;
- a request held inside JDT LS (the test-only hold, `TestJobs`) past its `timeoutMs`: `timeout`, and the endpoint's
  `$/cancelRequest` answered by JDT LS while still held.

Every call is kept in runs/lsp/calls.json, and what the tests check in runs/lsp/seen.json.
"""

import json
import sys
import time
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

from endpoint_client import RELEASE, write_settings  # noqa: E402
from scenario import APP, git, make_worktrees  # noqa: E402
from sync_scenario import start  # noqa: E402
from test_navigation import apply  # noqa: E402

GREETER = f"{APP}/Greeter.java"
# The passthrough's refusals (LspPassthrough.REFUSED_METHODS and REFUSED_COMMANDS), each sent once.
REFUSED_METHODS = [
    "initialize", "initialized", "shutdown", "exit", "workspace/didChangeWorkspaceFolders",
    "textDocument/didOpen", "textDocument/didChange", "textDocument/willSave", "textDocument/willSaveWaitUntil", "textDocument/didSave",
    "textDocument/didClose", "notebookDocument/didOpen", "notebookDocument/didChange", "notebookDocument/didSave", "notebookDocument/didClose",
    "java/validateDocument", "workspace/didChangeWatchedFiles", "workspace/didCreateFiles", "workspace/didRenameFiles",
    "workspace/didDeleteFiles", "workspace/willCreateFiles", "workspace/willRenameFiles", "workspace/willDeleteFiles",
    "workspace/didChangeConfiguration", "java/projectConfigurationUpdate", "java/projectConfigurationsUpdate", "java/buildWorkspace",
    "java/buildProjects", "$/cancelRequest", "$/setTrace", "$/progress", "window/workDoneProgress/cancel",
]  # fmt: skip
REFUSED_COMMANDS = [
    "java.project.import", "java.project.changeImportedProjects", "java.clean.workspace", "java.project.addToSourcePath",
    "java.project.removeFromSourcePath", "java.project.updateClassPaths", "java.project.updateSourceAttachment", "java.project.updateSettings",
    "java.project.updateJdk", "java.project.refreshDiagnostics", "java.project.upgradeGradle", "java.protobuf.generateSources",
    "java.project.createModuleInfo", "java.reloadBundles", "java.completion.onDidSelect", "java.edit.handlePasteEvent",
]  # fmt: skip
# What the instead of each kind names (the problem's hint).
INSTEAD = {"shutdown": "POST /v1/shutdown", "exit": "POST /v1/shutdown", "workspace/didChangeWorkspaceFolders": "POST /v1/worktrees/add",
           "textDocument/didOpen": "every request reconciles the worktree with the disk", "java/buildWorkspace": "POST /v1/build",
           "workspace/didChangeConfiguration": "harness", "java.project.import": "POST /v1/worktrees/add",
           "java.project.addToSourcePath": "pom.xml", "java.project.refreshDiagnostics": "POST /v1/problems",
           "java.edit.handlePasteEvent": "java.edit.organizeImports"}  # fmt: skip
# Review R1: a jdt: URI naming a project of wt-a.feature, whose projects' names start with "wt-a." too.
FEATURE_JDT = "jdt://contents/java.base/java.lang/String.class?=wt-a.feature.harness-app/%5C/nothing%3Cjava.lang%28String.class"


def uri(path: Path) -> str:
    return path.as_uri()


@pytest.fixture(scope="module")
def story(scratch, release_dir, maven_repository, launch_env, jvm_home):
    folder = scratch / "runs" / "lsp"
    folder.mkdir(parents=True)
    pauses = folder / "pauses"
    pauses.mkdir()
    wt = make_worktrees(folder / "worktrees")
    git("worktree", "add", "-q", "--detach", str(wt / "wt-a.feature"), cwd=wt / "main")
    settings = write_settings(folder / "settings.json", maven_repository)
    seen = {"worktrees": wt}
    calls = []
    endpoint, c = start(folder, "jdtls", release_dir, jvm_home, launch_env, settings,
                        jvm_args=(f"-Dfractal.jdtls.endpoint.testPauses={pauses}",))  # fmt: skip
    log = folder / f"data/.fractal-jdtls/{RELEASE}/endpoint.log"

    def lsp(name, module, method, params=None, **body):
        request = {"worktree": str(wt / name), "module": module, "method": method, **body}
        if params is not None:
            request["params"] = params
        status, value, ms = c.post("/v1/lsp", request)
        calls.append({"worktree": name, "request": request, "status": status, "ms": ms, "response": value})
        return status, value

    try:
        for name in ("main", "wt-a", "wt-b", "wt-a.feature"):
            assert c.post("/v1/worktrees/add", {"worktree": str(wt / name)})[0] == 200
        before = {p: p.read_bytes() for p in sorted(wt.rglob("*.java"))}

        # --- the two requests --------------------------------------------------------------------
        greeter = {"textDocument": {"uri": uri(wt / "wt-a" / GREETER)}}
        seen["document_symbol"] = lsp("wt-a", "harness-app", "textDocument/documentSymbol", greeter)
        seen["classpaths"] = {}
        for name in ("main", "wt-a", "wt-b"):
            seen["classpaths"][name] = lsp(name, "harness-app", "workspace/executeCommand", {
                "command": "java.project.getClasspaths", "arguments": [uri(wt / name / "harness-app"), json.dumps({"scope": "test"})]})
        seen["get_all"] = lsp("wt-a", ".", "workspace/executeCommand", {"command": "java.project.getAll"})

        # --- refusals ----------------------------------------------------------------------------
        seen["refused_methods"] = {m: lsp("wt-a", "harness-app", m, {}) for m in REFUSED_METHODS}
        seen["refused_commands"] = {
            cmd: lsp("wt-a", "harness-app", "workspace/executeCommand", {"command": cmd, "arguments": []}) for cmd in REFUSED_COMMANDS}
        seen["unknown_method"] = lsp("wt-a", "harness-app", "textDocument/nothingLikeThis", {})
        seen["unknown_command"] = lsp("wt-a", "harness-app", "workspace/executeCommand", {"command": "java.made.up"})
        seen["no_command"] = lsp("wt-a", "harness-app", "workspace/executeCommand", {"arguments": []})
        seen["string_params"] = lsp("wt-a", "harness-app", "textDocument/hover", "not params")

        # --- JDT LS's error, and a URI outside the worktree --------------------------------------
        seen["lsp_error"] = lsp("wt-a", ".", "workspace/executeCommand", {
            "command": "java.project.getClasspaths", "arguments": [uri(wt / "wt-a" / "pom.xml"), json.dumps({"scope": "test"})]})
        seen["lsp_error_params"] = lsp("wt-a", "harness-app", "textDocument/documentSymbol", {"textDocument": {}})
        outside_request = {"worktree": str(wt / "wt-a"), "module": "harness-app", "method": "textDocument/documentSymbol",
                           "params": {"textDocument": {"uri": uri(wt / "wt-b" / GREETER)}}}  # fmt: skip
        seen["outside"] = lsp("wt-a", "harness-app", "textDocument/documentSymbol", outside_request["params"])
        fixed = apply(outside_request, seen["outside"][1]["candidates"][0])
        seen["outside_fixed_request"] = fixed
        seen["outside_fixed"] = lsp(Path(fixed["worktree"]).name, fixed["module"], fixed["method"], fixed["params"])
        seen["jdt_other"] = lsp("wt-a", "harness-app", "java/classFileContents", {"uri": FEATURE_JDT})
        seen["jdt_own"] = lsp("wt-a.feature", "harness-app", "java/classFileContents", {"uri": FEATURE_JDT})

        # --- edits come back unapplied -----------------------------------------------------------
        seen["rename"] = lsp("wt-a", "harness-app", "textDocument/rename",
                             {**greeter, "position": {"line": 7, "character": 18}, "newName": "welcome"})  # fmt: skip
        seen["organize_imports"] = lsp("wt-a", "harness-app", "workspace/executeCommand",
                                       {"command": "java.edit.organizeImports", "arguments": [uri(wt / "wt-a" / GREETER)]})  # fmt: skip
        seen["unchanged"] = [str(p) for p, data in before.items() if p.read_bytes() != data]

        # --- a method that needs an open document --------------------------------------------------
        seen["semantic_tokens"] = lsp("wt-a", "harness-app", "textDocument/semanticTokens/full", greeter)

        # --- a request held in JDT LS past its timeout: the cancel reaches JDT LS ----------------
        (pauses / "lsp-hold.armed").write_text("")
        started = time.monotonic()
        seen["held"] = lsp("wt-a", "harness-app", "textDocument/documentSymbol", greeter, timeoutMs=3000)
        seen["held_ms"] = round((time.monotonic() - started) * 1000)
        seen["held_marker_while_answered"] = (pauses / "lsp-hold").exists()
        cancelled = None
        for _ in range(1000):  # JDT LS's answer to the cancel, while the hold still runs
            lines = log.read_text().splitlines()
            sent = [ln for ln in lines if "sent $/cancelRequest" in ln]
            if sent:
                request_id = sent[-1].split("request ")[1].split(" ")[0]
                answered = [ln for ln in lines if f"JDT LS answered request {request_id}," in ln]
                if answered:
                    cancelled = {"sent": sent[-1], "answered": answered[-1]}
                    break
            time.sleep(0.01)
        seen["cancel"] = cancelled
        seen["held_marker_after_cancel"] = (pauses / "lsp-hold").exists()
        (pauses / "lsp-hold").unlink()
        for _ in range(1000):
            if "test hold: released lsp-hold" in log.read_text():
                break
            time.sleep(0.01)
        seen["after_hold"] = lsp("wt-a", "harness-app", "textDocument/documentSymbol", greeter)

        seen["log"] = log.read_text().splitlines()
        assert endpoint.request("POST", "/v1/shutdown", body={})[0] == 202
        seen["exit"] = endpoint.wait_exit(120)
    finally:
        endpoint.stop()
    seen["calls"] = calls
    (folder / "calls.json").write_text(json.dumps(calls, indent=1) + "\n")
    record = {k: v for k, v in seen.items() if k not in ("worktrees", "log", "calls")}
    (folder / "seen.json").write_text(json.dumps(record, indent=1, default=str) + "\n")
    return seen


def ok(pair):
    status, value = pair
    assert status == 200, value
    return value


def test_the_story_ran_and_stopped_cleanly(story):
    assert story["exit"] == 0
    assert [c for c in story["calls"] if c["status"] == 500] == []


def test_document_symbol_is_jdtls_answer_unchanged(story):
    value = ok(story["document_symbol"])
    assert [(symbol["name"], symbol["kind"]) for symbol in value["result"]] == [("org.fractal.harness.app", 4), ("Greeter", 5)]
    greeter = value["result"][1]  # a package, then a class, in LSP's own terms
    assert [child["name"] for child in greeter["children"]][:1] == ["greet(String)"]
    assert greeter["range"]["start"]["line"] >= 0  # 0-based
    assert value["applyEditsRefused"] == [] and "sync" in value


def test_each_harness_app_resolves_its_own_worktrees_contract(story):
    """Run 05's classpath check, through the passthrough: each worktree's own harness-contract, and nothing outside it."""
    wt = story["worktrees"]
    for name in ("main", "wt-a", "wt-b"):
        result = ok(story["classpaths"][name])["result"]
        inside = sorted(str(Path(e).relative_to(wt)) for e in result["classpaths"] if e.startswith(str(wt) + "/"))
        assert inside == sorted([f"{name}/harness-app/target/classes", f"{name}/harness-app/target/test-classes",
                                 f"{name}/harness-contract/target/classes"]), result  # fmt: skip


def test_results_are_not_filtered_by_worktree(story):
    """java.project.getAll lists every worktree's projects (design §11: results aren't filtered)."""
    projects = ok(story["get_all"])["result"]
    assert {Path(p.removeprefix("file:")).parent.name for p in projects} == {"main", "wt-a", "wt-b", "wt-a.feature"}, projects


@pytest.mark.parametrize("method", REFUSED_METHODS)
def test_each_refused_method_names_what_to_use_instead(story, method):
    status, value = story["refused_methods"][method]
    assert status == 422 and value["code"] == "lsp-method-refused" and value["field"] == "/method", value
    assert value["detail"].startswith(method + " ")
    if method in INSTEAD:
        assert INSTEAD[method] in value["hint"], value


@pytest.mark.parametrize("command", REFUSED_COMMANDS)
def test_each_refused_command_names_what_to_use_instead(story, command):
    status, value = story["refused_commands"][command]
    assert status == 422 and value["code"] == "lsp-method-refused" and value["field"] == "/params/command", value
    assert value["detail"].startswith(f"The command {command} ")
    if command in INSTEAD:
        assert INSTEAD[command] in value["hint"], value


def test_unknown_methods_and_commands_and_malformed_params_are_refused(story):
    status, value = story["unknown_method"]
    assert (status, value["code"]) == (422, "lsp-method-refused") and "isn't a method JDT LS serves" in value["detail"], value
    status, value = story["unknown_command"]
    assert (status, value["code"]) == (422, "lsp-method-refused") and "doesn't advertise it" in value["detail"], value
    for key, field in (("no_command", "/params/command"), ("string_params", "/params")):
        status, value = story[key]
        assert (status, value["code"], value["errors"][0]["field"]) == (400, "invalid-request", field), value


def test_every_method_and_command_jdtls_has_is_classified(story):
    (line,) = [ln for ln in story["log"] if "passthrough: JDT LS serves" in ln]
    assert "0 unclassified; it advertises" in line and line.endswith("0 unclassified"), line
    assert "serves 102 methods (80 requests, 22 notifications)" in line and "advertises 33 commands" in line, line


def test_a_jdtls_error_is_kept_as_lsp_error(story):
    status, value = story["lsp_error"]
    assert status == 502 and value["code"] == "lsp-error", value
    assert value["lsp"]["code"] == -32001 and "non-existing project" in value["lsp"]["message"], value
    assert value["lsp"]["data"] == {"message": value["lsp"]["message"]}, "JDT LS's data, kept"
    assert value["detail"] == "JDT LS answered workspace/executeCommand with error -32001.", value
    # Params JDT LS can't use aren't always an error: documentSymbol without a URI answers [].
    assert ok(story["lsp_error_params"])["result"] == []


def test_a_uri_of_another_worktree_is_refused_with_the_worktree_to_use(story):
    status, value = story["outside"]
    wt = story["worktrees"]
    assert status == 422 and value["code"] == "uri-outside-worktree" and value["field"] == "/params/textDocument/uri", value
    assert value["candidates"][0]["set"] == {"/worktree": str(wt / "wt-b"), "/module": "harness-app"}
    assert ok(story["outside_fixed"])["result"][1]["name"] == "Greeter"


def test_review_r1_a_jdt_uris_project_is_placed_by_its_location_not_its_name(story):
    """wt-a.feature's project wt-a.feature.harness-app starts with "wt-a.", but it's located in wt-a.feature."""
    wt = story["worktrees"]
    status, value = story["jdt_other"]
    assert status == 422 and value["code"] == "uri-outside-worktree" and value["field"] == "/params/uri", value
    assert value["candidates"][0]["set"] == {"/worktree": str(wt / "wt-a.feature"), "/module": "harness-app"}, value
    assert f"located at {wt / 'wt-a.feature' / 'harness-app'}" in value["detail"], value
    status, value = story["jdt_own"]
    assert status in (200, 502) and value.get("code") != "uri-outside-worktree", value  # passed on to JDT LS


def test_edits_come_back_unapplied_and_no_file_changes(story):
    rename = ok(story["rename"])
    edits = rename["result"].get("documentChanges") or rename["result"].get("changes")
    assert edits, rename
    assert rename["applyEditsRefused"] == []
    organize = ok(story["organize_imports"])
    assert organize["applyEditsRefused"] == [] and isinstance(organize["result"], dict), organize
    assert story["unchanged"] == []


def test_a_method_for_open_documents_answers_without_one(story):
    """The endpoint never opens documents: JDT LS's semantic tokens come from the file on disk."""
    value = ok(story["semantic_tokens"])
    assert len(value["result"]["data"]) > 0 and len(value["result"]["data"]) % 5 == 0, value


def test_a_held_request_times_out_and_its_cancel_reaches_jdtls(story):
    status, value = story["held"]
    assert status == 504 and value["code"] == "timeout" and value["phase"] == "running", value
    assert 3000 <= story["held_ms"] < 10_000
    assert story["held_marker_while_answered"] and story["held_marker_after_cancel"], "the hold still ran"
    assert story["cancel"] is not None, "JDT LS never answered the cancelled request"
    assert "error -32800" in story["cancel"]["answered"], story["cancel"]
    assert ok(story["after_hold"])["result"][1]["name"] == "Greeter"
