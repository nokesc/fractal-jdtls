# SPDX-License-Identifier: Apache-2.0
"""Concurrency's deterministic cases (design §11 "Threading and queuing"), in one process on main, wt-a and wt-b.

- Only fixed points take disk changes in: a POM edited on disk is reported by the next fixed point as
  `disk-changed`, with the POM in `filesChanged` and `pomsUpdated`, and the one after takes the fast path. The
  mapping's checks read m2e's registry only (T002.06 saw them take the POM in, through m2e's `getPom`).
- A fixed point held past its `timeoutMs`: a rename on wt-a is held at the test pause `before-write`, holding wt-a's
  lock and Eclipse's workspace root rule. A `build` of wt-b waits for the rule and gets `timeout` (`phase:
  waiting-for-rule`): Eclipse's job manager saw the progress monitor cancelled at the deadline, and the endpoint log
  says so, and when. A caller that disconnects during such a wait isn't noticed: its work goes on to its deadline,
  and is cancelled then. Meanwhile main's requests keep being answered.
- `busy`: with the rename held, 15 requests on wt-a wait for its lock, so all 16 worker threads are taken
  (`GET /v1/status` lists them); 64 more are queued, and the 3 beyond them are answered `busy` at once, with
  `retryAfter` and `Retry-After`. Once the rename goes on, every held and queued request is answered.
- An LSP request held past its `timeoutMs`: `definition` on wt-a held inside JDT LS (the test hold `lsp-hold`, a job
  of JDT LS's document life-cycle family, which `textDocument/definition` joins). At the deadline the endpoint sends
  `$/cancelRequest`, and JDT LS answers it with -32800. JDT LS's handler doesn't stop waiting, though: its
  `CancellableProgressMonitor` is a `NullProgressMonitor`, which its own progress provider replaces inside
  `JobManager.join`, so a thread dump still shows it until the hold ends (a JDT LS limitation, for Chris's review; a
  handler that checks its monitor itself stops: test_imports.py cancels a references search on the large tree).
  Requests that don't join that family keep being answered.
- After each: the same requests succeed, and nothing is left in flight.

Everything seen is kept in runs/concurrency/seen.json.
"""

import json
import re
import socket
import subprocess
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

from endpoint_client import RELEASE, write_settings  # noqa: E402
from scenario import git, make_worktrees  # noqa: E402
from sync_scenario import start  # noqa: E402

NAMES = "org.fractal.harness.contract.Names"
STEP = "org.fractal.harness.contract.Step"
GREETER = "src/main/java/org/fractal/harness/app/Greeter.java"
ANCHOR = {"file": GREETER, "text": "String clean = Names.normalize(who);", "select": "normalize"}
LANG3 = ("        <dependency>\n            <groupId>org.apache.commons</groupId>\n            <artifactId>commons-lang3</artifactId>\n"
         "            <version>3.17.0</version>\n        </dependency>\n    </dependencies>")  # fmt: skip
WORKERS, QUEUE, BEYOND = 16, 64, 3


def jcmd(pid: int, *command: str) -> str:
    """jcmd beside the endpoint's own java (the PID this test started)."""
    java = Path(f"/proc/{pid}/exe").resolve()
    return subprocess.run([str(java.parent / "jcmd"), str(pid), *command], capture_output=True, text=True, timeout=60).stdout


def threads_in(dump: str, frame: str) -> list[str]:
    """The names of the threads whose stack has the frame."""
    return [block.split('"')[1] for block in dump.split("\n\n") if block.startswith('"') and frame in block]


@pytest.fixture(scope="module")
def story(scratch, release_dir, maven_repository, launch_env, jvm_home):
    folder = scratch / "runs" / "concurrency"
    folder.mkdir(parents=True)
    pauses = folder / "pauses"
    pauses.mkdir()
    wt = make_worktrees(folder / "worktrees")
    settings = write_settings(folder / "settings.json", maven_repository)
    log = folder / f"data/.fractal-jdtls/{RELEASE}/endpoint.log"
    endpoint, c = start(folder, "jdtls", release_dir, jvm_home, launch_env, settings, jvm_args=(f"-Dfractal.jdtls.endpoint.testPauses={pauses}",))
    seen = {}

    def call(op, name, module, **body):
        status, value, ms = c.post(f"/v1/{op}", {"worktree": str(wt / name), "module": module, **body})
        return {"status": status, "ms": ms, "answer": value}

    def log_lines(pattern):
        return [line.split("] ", 1)[1] for line in log.read_text().splitlines() if re.search(pattern, line)]

    def held_at(point, op, name, module, **body):
        (pauses / f"{point}.armed").write_text("")
        result = {}
        thread = threading.Thread(target=lambda: result.update(call(op, name, module, **body)))
        thread.start()
        deadline = time.monotonic() + 120
        while not (pauses / point).exists():
            assert time.monotonic() < deadline and thread.is_alive(), f"the request never reached {point}: {result}"
            time.sleep(0.01)
        return thread, result

    def others():
        """main's requests, each with its time: the other sessions."""
        return {op: call(op, "main", module, **body) for op, module, body in (
            ("problems", "harness-app", {"scope": "worktree"}),
            ("definition", "harness-app", {"target": ANCHOR}),
            ("references", "harness-contract", {"target": f"{NAMES}#normalize(String)"}),
            ("describe", "harness-contract", {"target": NAMES}),
            ("symbols", "harness-app", {"query": "describe", "kinds": ["method"]}),
            ("type-hierarchy", "harness-contract", {"target": STEP, "direction": "subtypes"}),
        )}

    try:
        for name in ("main", "wt-a", "wt-b"):
            assert c.post("/v1/worktrees/add", {"worktree": str(wt / name)})[0] == 200
            assert call("build", name, ".", scope="worktree")["status"] == 200
        seen["idle"] = others()

        # --- only fixed points take disk changes in: a POM edited on disk --------------------------------------
        pom = wt / "wt-a" / "harness-app" / "pom.xml"
        original_pom = pom.read_text()
        seen["pom_before"] = call("problems", "wt-a", "harness-app", scope="worktree")
        pom.write_text(original_pom.replace("    </dependencies>", LANG3, 1))
        seen["pom_changed"] = call("problems", "wt-a", "harness-app", scope="worktree")
        seen["pom_next"] = call("problems", "wt-a", "harness-app", scope="worktree")
        seen["pom_after"] = call("problems", "wt-a", "harness-app", scope="worktree")
        pom.write_text(original_pom)
        seen["pom_restored"] = call("describe", "wt-a", "harness-app", target="org.fractal.harness.app.Greeter", members=False)
        seen["pom_restored_next"] = call("problems", "wt-a", "harness-app", scope="worktree")
        seen["pom_restored_after"] = call("problems", "wt-a", "harness-app", scope="worktree")
        seen["outside_a_fixed_point"] = log_lines("outside a fixed point")

        # --- a fixed point held past its timeoutMs, a disconnect, the other sessions, busy -----------------------
        rename = {"target": f"{NAMES}#normalize(String)", "newName": "canonical", "timeoutMs": 300_000}
        thread, renamed = held_at("before-write", "refactor/rename", "wt-a", "harness-contract", **rename)
        seen["rule_timeout"] = call("build", "wt-b", ".", scope="worktree", timeoutMs=2000)
        seen["others_while_rule_held"] = others()

        # A caller that disconnects: its request is sent in full, then its connection closed.
        body = json.dumps({"worktree": str(wt / "wt-b"), "module": ".", "scope": "worktree", "timeoutMs": 3000}).encode()
        s = socket.create_connection(("127.0.0.1", endpoint.port))
        s.sendall(f"POST /v1/build HTTP/1.1\r\nHost: 127.0.0.1:{endpoint.port}\r\nAuthorization: Bearer {endpoint.token}\r\n"
                  f"X-Fractal-Session: t002.11-disconnect\r\nContent-Type: application/json\r\nContent-Length: {len(body)}\r\n\r\n".encode() + body)
        time.sleep(0.3)
        s.close()
        disconnected_at = time.monotonic()
        seen["status_after_disconnect"] = [(o["path"], o["phase"], o.get("worktree")) for o in c.status()["inFlight"]]
        while not log_lines(r"POST /v1/build -> 504 .*t002\.11-disconnect") and time.monotonic() - disconnected_at < 30:
            time.sleep(0.05)
        seen["disconnected_answered_after_s"] = round(time.monotonic() - disconnected_at, 2)

        # busy: 15 requests on wt-a wait for its lock, beside the rename: every worker is taken.
        with ThreadPoolExecutor(max_workers=WORKERS + QUEUE + BEYOND) as pool:
            waiting = [pool.submit(call, "problems", "wt-a", "harness-app", scope="worktree", timeoutMs=300_000) for _ in range(WORKERS - 1)]
            until = time.monotonic() + 60
            while len(c.status()["inFlight"]) < WORKERS and time.monotonic() < until:
                time.sleep(0.02)
            seen["in_flight_before_queue"] = [(o["path"], o["phase"], Path(o["worktree"]).name) for o in c.status()["inFlight"]]
            queued = [pool.submit(c.post, "/v1/problems", {"worktree": str(wt / "main"), "module": "harness-app"}) for _ in range(QUEUE + BEYOND)]
            until = time.monotonic() + 60
            while sum(f.done() for f in queued) < BEYOND and time.monotonic() < until:
                time.sleep(0.01)
            time.sleep(0.5)  # nothing more is answered while every worker is held
            answered_at_once = [f.result() for f in queued if f.done()]
            seen["busy"] = [(s_, v) for s_, v, _ in answered_at_once]
            raw = endpoint.request("POST", "/v1/problems", body={"worktree": str(wt / "main"), "module": "harness-app"})
            seen["busy_headers"] = {"status": raw[0], "retry-after": raw[1].get("retry-after"), "code": json.loads(raw[2]).get("code")}
            seen["status_while_full"] = c.status()["inFlight"].__len__()
            (pauses / "before-write").unlink()
            thread.join(300)
            seen["renamed"] = renamed
            seen["waiting"] = [f.result()["status"] for f in waiting]
            seen["queued_statuses"] = sorted(f.result()[0] for f in queued)
        seen["rule_recovered"] = call("build", "wt-b", ".", scope="worktree")
        git("checkout", "--", ".", cwd=wt / "wt-a")
        seen["rename_reverted"] = call("problems", "wt-a", ".", scope="worktree")
        seen["in_flight_after_rule"] = c.status()["inFlight"]

        # --- an LSP request held inside JDT LS past its timeoutMs -------------------------------------------------
        (pauses / "lsp-hold.armed").write_text("")
        holder = {}
        greeter_b = {"textDocument": {"uri": (wt / "wt-b" / "harness-app" / GREETER).as_uri()}}
        hold = threading.Thread(target=lambda: holder.update(call("lsp", "wt-b", "harness-app", method="textDocument/documentSymbol",
                                                                  params=greeter_b, timeoutMs=300_000)))  # fmt: skip
        hold.start()
        until = time.monotonic() + 60
        while not (pauses / "lsp-hold").exists() and time.monotonic() < until:
            time.sleep(0.01)
        late = {}
        defining = threading.Thread(target=lambda: late.update(call("definition", "wt-a", "harness-app", target=ANCHOR, timeoutMs=3000)))
        defining.start()
        time.sleep(1.5)
        seen["dump_while_held"] = threads_in(jcmd(endpoint.pid, "Thread.print"), "JDTLanguageServer.lambda$7")
        defining.join(60)
        seen["lsp_timeout"] = late
        time.sleep(1.0)
        seen["dump_after_cancel"] = threads_in(jcmd(endpoint.pid, "Thread.print"), "JDTLanguageServer.lambda$7")
        seen["others_while_lsp_held"] = {op: call(op, "main", module, timeoutMs=20_000, **body) for op, module, body in (
            ("problems", "harness-app", {"scope": "worktree"}),
            ("references", "harness-contract", {"target": f"{NAMES}#normalize(String)"}),
            ("describe", "harness-contract", {"target": NAMES}),
            ("symbols", "harness-app", {"query": "describe", "kinds": ["method"]}),
            ("type-hierarchy", "harness-contract", {"target": STEP, "direction": "subtypes"}),
        )}
        (pauses / "lsp-hold").unlink()
        hold.join(120)
        time.sleep(1.0)
        seen["dump_after_hold"] = threads_in(jcmd(endpoint.pid, "Thread.print"), "JDTLanguageServer.lambda$7")
        seen["held_document_symbol"] = {"status": holder.get("status"), "ms": holder.get("ms")}
        seen["lsp_recovered"] = call("definition", "wt-a", "harness-app", target=ANCHOR)
        seen["in_flight_at_end"] = c.status()["inFlight"]
        seen["log"] = {
            "timeouts": log_lines(r": timeout: stopped "),
            "cancelRequest": log_lines(r"\$/cancelRequest|no longer waits for"),
            "disconnected": log_lines(r"t002\.11-disconnect|couldn't send a response"),
            "testPauses": log_lines(r"test (pause|hold)"),
            "errors": log_lines(r"\] ERROR "),
        }
        seen["shutdown"] = endpoint.request("POST", "/v1/shutdown", body={})[0]
        seen["exit"] = endpoint.wait_exit(120)
    finally:
        endpoint.stop()
        (folder / "seen.json").write_text(json.dumps(seen, indent=1, default=str) + "\n")
    return seen


def test_a_pom_edited_on_disk_is_taken_in_only_by_the_next_fixed_point(story):
    assert story["pom_before"]["answer"]["sync"]["skipped"] is True
    sync = story["pom_changed"]["answer"]["sync"]
    assert sync["notSkippedBecause"] == "disk-changed", sync
    assert sync["filesChanged"] == [{"path": "harness-app/pom.xml", "change": "changed"}] and sync["pomsUpdated"] == ["harness-app"], sync
    # The fixed point after a POM's update is a full one too, with nothing to report (for Chris's review), then the fast path.
    assert story["pom_next"]["answer"]["sync"].get("filesChanged") == [] and story["pom_next"]["answer"]["sync"]["pomsUpdated"] == []
    assert story["pom_after"]["answer"]["sync"]["skipped"] is True, story["pom_after"]["answer"]["sync"]
    sync = story["pom_restored"]["answer"]["sync"]
    assert sync["notSkippedBecause"] == "disk-changed" and sync["pomsUpdated"] == ["harness-app"], sync
    assert story["pom_restored_next"]["answer"]["sync"]["filesChanged"] == []
    assert story["pom_restored_after"]["answer"]["sync"]["skipped"] is True
    assert not [line for line in story["outside_a_fixed_point"] if "pom.xml" in line], story["outside_a_fixed_point"]


def test_a_fixed_point_waiting_for_the_rule_past_its_timeout_is_cancelled(story):
    t = story["rule_timeout"]
    assert (t["status"], t["answer"]["code"], t["answer"]["phase"]) == (504, "timeout", "waiting-for-rule"), t
    assert 2000 <= t["ms"] < 4000, t["ms"]
    stopped = [line for line in story["log"]["timeouts"] if "build" in line and "waiting for the workspace rule" in line]
    assert len(stopped) == 2, story["log"]["timeouts"]  # this one, and the disconnected caller's
    assert story["rule_recovered"]["status"] == 200 and story["rule_recovered"]["answer"]["counts"]["error"] == 0
    assert story["in_flight_after_rule"] == []


def test_other_sessions_keep_being_answered_while_a_fixed_point_holds_the_rule(story):
    for op, r in story["others_while_rule_held"].items():
        assert r["status"] == 200, (op, r)
        assert r["ms"] < 5000, (op, r["ms"])
    assert story["renamed"]["status"] == 200 and story["renamed"]["answer"]["applied"] is True
    assert story["rename_reverted"]["status"] == 200 and story["rename_reverted"]["answer"]["counts"]["error"] == 0


def test_a_caller_that_disconnects_is_cancelled_at_its_deadline(story):
    # The JDK's server gives no sign of a disconnect: the work goes on until its deadline, then is cancelled.
    assert any(p == "/v1/build" and phase == "waiting-for-rule" for p, phase, _ in story["status_after_disconnect"]), story["status_after_disconnect"]
    assert 2.0 <= story["disconnected_answered_after_s"] < 6.0, story["disconnected_answered_after_s"]
    assert any("-> 504" in line for line in story["log"]["disconnected"]), story["log"]["disconnected"]


def test_busy_beyond_the_queue_with_retry_after(story):
    held = story["in_flight_before_queue"]
    assert len(held) == WORKERS and sum(1 for p, _, w in held if p == "/v1/problems" and w == "wt-a") == WORKERS - 1, held
    assert len(story["busy"]) == BEYOND, story["busy"]
    for status, value in story["busy"]:
        assert status == 503 and value["code"] == "busy" and value["retryAfter"] == 2, value
    assert story["busy_headers"] == {"status": 503, "retry-after": "2", "code": "busy"}
    assert story["status_while_full"] == WORKERS
    assert story["waiting"] == [200] * (WORKERS - 1)
    assert story["queued_statuses"] == [200] * QUEUE + [503] * BEYOND


def test_an_lsp_request_held_past_its_timeout_is_cancelled_in_jdt_ls(story):
    t = story["lsp_timeout"]
    assert (t["status"], t["answer"]["code"], t["answer"]["phase"]) == (504, "timeout", "running"), t
    assert len(story["dump_while_held"]) == 1, "JDT LS's definition handler waits for the held job"
    # JDT LS's limitation: the cancel doesn't reach its wait for the life-cycle jobs, so the handler goes on waiting,
    # abandoned, until the hold ends.
    assert story["dump_after_cancel"] == story["dump_while_held"], "JDT LS's handler still waits: its monitor was replaced"
    assert story["dump_after_hold"] == [], "and is gone once the hold ends"
    cancels = story["log"]["cancelRequest"]
    assert any("$/cancelRequest" in line and "textDocument/definition" in line for line in cancels), cancels
    assert any("-32800" in line for line in cancels), cancels
    for op, r in story["others_while_lsp_held"].items():
        assert r["status"] == 200, (op, r)
    assert story["held_document_symbol"]["status"] == 200
    assert story["lsp_recovered"]["status"] == 200
    assert story["in_flight_at_end"] == []


def test_no_internal_problems_and_a_clean_exit(story):
    assert story["log"]["errors"] == []
    assert story["shutdown"] == 202 and story["exit"] == 0
