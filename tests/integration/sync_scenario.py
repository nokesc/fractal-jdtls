# SPDX-License-Identifier: Apache-2.0
"""Run 05 over HTTP (design §11 "Keeping in step with the disk", "Builds and problems"): its edits written to disk
and never reported, the problems each worktree's `build` must return, and a fresh process to run it in.

Run 05's edits (FINDINGS run 05): `WorkType` exists only in wt-b's harness-contract, wt-a's and wt-b's harness-app
both use it, and wt-b's harness-app also gets a type error. So wt-a reports the three `WorkType cannot be resolved`
errors, wt-b only its own `Broken.java` error, and main none.
"""

import hashlib
import json
import os
from pathlib import Path

from endpoint_client import Client, Contract, EndpointProcess, http_command
from scenario import APP, CONTRACT

USES_WORKTYPE = (
    "package org.fractal.harness.app;\n\nimport org.fractal.harness.contract.WorkType;\n\n"
    "public class Dispatch {\n\n    public WorkType type() {\n        return WorkType.CODER;\n    }\n}\n"
)
WORKTYPE = "package org.fractal.harness.contract;\n\npublic enum WorkType { CODER }\n"
BROKEN = 'package org.fractal.harness.app;\n\npublic class Broken {\n\n    int value = "not an int";\n}\n'

DISPATCH = f"{APP.removeprefix('harness-app/')}/Dispatch.java"
EXPECTED = {
    "main": [],
    "wt-a": [
        ("harness-app", DISPATCH, 3, "The import org.fractal.harness.contract.WorkType cannot be resolved"),
        ("harness-app", DISPATCH, 7, "WorkType cannot be resolved to a type"),
        ("harness-app", DISPATCH, 8, "WorkType cannot be resolved to a variable"),
    ],
    "wt-b": [("harness-app", f"{APP.removeprefix('harness-app/')}/Broken.java", 5, "Type mismatch: cannot convert from String to int")],
}


def write_run05_edits(worktrees: Path):
    """Run 05's edits, written to disk with ordinary writes. Nothing tells JDT LS."""
    a, b = worktrees / "wt-a", worktrees / "wt-b"
    (b / CONTRACT / "WorkType.java").write_text(WORKTYPE)
    (b / APP / "Dispatch.java").write_text(USES_WORKTYPE)
    (a / APP / "Dispatch.java").write_text(USES_WORKTYPE)
    (b / APP / "Broken.java").write_text(BROKEN)


def errors(value: dict) -> list[tuple]:
    """A problems answer's errors as (module, file, line, message)."""
    return [(p["module"], p.get("file"), p.get("line"), p["message"]) for p in value["problems"] if p["severity"] == "error"]


def file_state(path: Path) -> dict:
    """A file's bytes and times, to show the endpoint never changes them."""
    st = os.stat(path)
    return {"sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "size": st.st_size, "mtimeNs": st.st_mtime_ns,
            "ctimeNs": st.st_ctime_ns, "inode": st.st_ino}  # fmt: skip


def start(folder: Path, name: str, release: Path, jvm_home: Path, launch_env: dict, settings: Path, data: Path | None = None, jvm_args=()):
    """A fresh JDT LS in HTTP mode, ready, with a client that checks every answer against the served description."""
    port_file = folder / f"{name}-port.json"
    endpoint = EndpointProcess(http_command(release, jvm_home, data or folder / "data", port_file, settings, *jvm_args), env=launch_env,
                               run_dir=folder / name, port_file=port_file).start()  # fmt: skip
    endpoint.wait_port_file()
    status, _, description = endpoint.request("GET", "/v1/openapi.json")
    assert status == 200, description
    status, _, text = endpoint.request("GET", "/v1/status?wait=ready&timeoutMs=300000")
    assert status == 200, text
    return endpoint, Client(endpoint, Contract(json.loads(description)))
