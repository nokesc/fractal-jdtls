# SPDX-License-Identifier: Apache-2.0
"""Real JDT LS on a main checkout and two linked worktrees of one Maven project (design §8).

JDT LS is installed by install.py from the built release zip and launched through the installed
bin/jdtls, which adds the agent; the control run uses the test-only path without it. The
scenarios reproduce the spike's runs (.context/research/jdtls-worktree-spike.md):

- run 05: import, edits and reimport; each worktree resolves its own modules;
- run 06: a restart on run 05's workspace keeps names and classpaths and creates no projects;
- run 01: without the agent, only one checkout imports (the duplicate-coordinates failure).

Each run's snapshots, LSP traffic, diagnostic log and JDT LS log are kept in pytest's
temporary folder, under runs/<run>/.
"""

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

import pytest

from scenario import FIXTURE, ROOTS, DiagnosticLog, JdtlsRun, make_worktrees, maven_settings, verify

HERE = Path(__file__).resolve().parent
HOOKS = {"getProjectName", "getWorkspaceArtifact", "getProjectFacade", "populateClasspath", "readParentExternally", "initParent"}
PROBLEM_EVENTS = {"hook-not-in-place", "transform-error", "hook-failed", "prefix-collision"}
DUPLICATES = "3 Maven project(s) could not be imported due to duplicate groupId/artifactId"


def fixture_plugins():
    """The plugins the fixture's parent POM pins in its pluginManagement: artifactId to version."""
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    pom = ET.parse(FIXTURE / "pom.xml.fixture").getroot()
    plugins = pom.findall("m:build/m:pluginManagement/m:plugins/m:plugin", ns)
    return {p.findtext("m:artifactId", namespaces=ns): p.findtext("m:version", namespaces=ns) for p in plugins}


def expected_projects():
    """Run 05: every module of every worktree, named <worktree>.<m2e's name>, located in its own worktree."""
    projects = {}
    for wt in ROOTS:
        projects[f"{wt}.fractal-harness"] = wt
        for module in ("harness-contract", "harness-app"):
            projects[f"{wt}.{module}"] = f"{wt}/{module}"
    return projects


def expected_classpaths():
    """Run 05: each harness-app resolves its own worktree's harness-contract, and nothing outside its worktree."""
    classpaths = {}
    for wt in ROOTS:
        classpaths[f"{wt}/harness-contract"] = [f"{wt}/harness-contract/target/classes", f"{wt}/harness-contract/target/test-classes"]
        classpaths[f"{wt}/harness-app"] = sorted(
            [f"{wt}/harness-app/target/classes", f"{wt}/harness-app/target/test-classes", f"{wt}/harness-contract/target/classes"]
        )
    return classpaths


APP = "harness-app/src/main/java/org/fractal/harness/app"
# Run 05 after the edits: WorkType exists only in wt-b's contract, so only wt-a's Dispatch fails
# (3 errors), and wt-b reports only its own type error.
EDIT_ERRORS = {
    f"wt-a/{APP}/Dispatch.java": [
        "3: The import org.fractal.harness.contract.WorkType cannot be resolved",
        "7: WorkType cannot be resolved to a type",
        "8: WorkType cannot be resolved to a variable",
    ],
    f"wt-b/{APP}/Broken.java": ["5: Type mismatch: cannot convert from String to int"],
}


def agent_command(release: Path, log: Path, jvm_home: Path, data: Path):
    """What a client passes: no agent, boot delegation or -configuration; the launcher adds them."""
    return [
        str(release / "bin" / "jdtls"),
        f"--jvm-arg=-Dfractal.jdtls.diagnosticLog={log}",
        f"--jvm-arg=-Duser.home={jvm_home}",
        "-data",
        str(data),
    ]


def worktree_of(path: str, worktrees: Path) -> str:
    return Path(path).relative_to(worktrees).parts[0]


class Run:
    """A finished run: its snapshots, process, diagnostic log (if the agent ran) and folders."""

    def __init__(self, jdtls: JdtlsRun, log: Path | None):
        self.snapshots = jdtls.snapshots
        self.process = jdtls.process
        self.worktrees = jdtls.worktrees
        self.data = jdtls.data
        self.run_dir = jdtls.run_dir
        self.log_path = log
        self.log = DiagnosticLog(log) if log and log.exists() else None


def run_scenario(cmd, *, launch_env, worktrees, data, settings, run_dir, log, steps):
    with JdtlsRun(cmd, env=launch_env, worktrees=worktrees, data=data, settings=settings, run_dir=run_dir) as jdtls:
        jdtls.start()
        for step in steps:
            getattr(jdtls, step)()
    return Run(jdtls, log)


@pytest.fixture(scope="module")
def fresh(scratch, release_dir, maven_repository, launch_env, jvm_home):
    """Run 05: import, edits, reimport on a fresh workspace."""
    folder = scratch / "runs" / "05-import-edits-reimport"
    worktrees = make_worktrees(folder / "worktrees")
    log = folder / "agent.jsonl"
    return run_scenario(
        agent_command(release_dir, log, jvm_home, folder / "data"),
        launch_env=launch_env, worktrees=worktrees, data=folder / "data",
        settings=maven_settings(folder / "settings.xml", maven_repository),
        run_dir=folder, log=log, steps=["edits", "reimport"],
    )


@pytest.fixture(scope="module")
def restart(scratch, fresh, release_dir, maven_repository, launch_env, jvm_home):
    """Run 06: a new process on run 05's workspace and worktrees."""
    folder = scratch / "runs" / "06-restart"
    folder.mkdir(parents=True)
    log = folder / "agent.jsonl"
    return run_scenario(
        agent_command(release_dir, log, jvm_home, fresh.data),
        launch_env=launch_env, worktrees=fresh.worktrees, data=fresh.data,
        settings=maven_settings(folder / "settings.xml", maven_repository),
        run_dir=folder, log=log, steps=[],
    )


@pytest.fixture(scope="module")
def control(scratch, release_dir, maven_repository, launch_env, jvm_home):
    """Run 01: the same launcher command without the agent, through the test-only launch path."""
    folder = scratch / "runs" / "01-control-without-agent"
    worktrees = make_worktrees(folder / "worktrees")
    log = folder / "agent.jsonl"  # passed, to show that nothing writes it without the agent
    cmd = [sys.executable, "-I", "-B", str(HERE / "launch_without_agent.py"), str(release_dir)]
    cmd += agent_command(release_dir, log, jvm_home, folder / "data")[1:]
    return run_scenario(
        cmd, launch_env=launch_env, worktrees=worktrees, data=folder / "data",
        settings=maven_settings(folder / "settings.xml", maven_repository),
        run_dir=folder, log=log, steps=[],
    )


# --- shared checks ----------------------------------------------------------------------------


def assert_agent_launched(run: Run, release: Path):
    argv = run.process["argv"]
    assert f"-javaagent:{release / 'lib' / 'fractal-jdtls-agent.jar'}" in argv, argv
    assert "-Dorg.osgi.framework.bootdelegation=org.fractal.jdtls.boot" in argv, argv
    assert argv[argv.index("-data") + 1] == str(run.data), argv


def assert_hooks_in_place(log: DiagnosticLog):
    (start,) = log.of("agent-start")
    assert set(start["hooks"]) == HOOKS
    assert {e["hook"] for e in log.of("hook-in-place")} == HOOKS
    checkpoints = log.of("checkpoint")
    assert len(checkpoints) == 1, checkpoints
    assert checkpoints[0]["hooks"] == {hook: "in-place" for hook in HOOKS}
    problems = [e for e in log.events if e["event"] in PROBLEM_EVENTS]
    assert not problems, problems


def assert_lookups_stay_home(run: Run):
    """Every GAV lookup had a requester, and its final choice is in the requester's worktree."""
    lookups = run.log.of("lookup")
    assert lookups, "the diagnostic log has no lookups"
    no_requester = [e for e in lookups if not e["requester"]]
    assert not no_requester, no_requester
    away = [e for e in lookups if worktree_of(e["result"], run.worktrees) != worktree_of(e["requester"], run.worktrees)]
    assert not away, away
    assert all(e["result"] in e["candidates"] for e in lookups)


def assert_own_worktree_only(snapshot):
    """No classpath entry of any project points into another worktree."""
    for project, entries in snapshot["classpaths"].items():
        wt = project.split("/")[0]
        assert isinstance(entries, list), (project, entries)
        assert all(e.split("/")[0] == wt for e in entries), (project, entries)


# --- run 05 -------------------------------------------------------------------------------------


def test_import_edits_reimport_keep_each_worktree_separate(fresh, release_dir):
    assert_agent_launched(fresh, release_dir)
    for step in ("import", "edits", "reimport"):
        snap = fresh.snapshots[step]
        assert snap["eclipseProjects"] == expected_projects(), step
        assert snap["classpaths"] == expected_classpaths(), step
        assert_own_worktree_only(snap)
        assert snap["serverErrorMessages"] == [], step
        assert len(set(snap["roots"].values())) == 1, snap["roots"]
    assert fresh.snapshots["import"]["errorDiagnostics"] == {}
    assert fresh.snapshots["edits"]["errorDiagnostics"] == EDIT_ERRORS
    assert fresh.snapshots["reimport"]["errorDiagnostics"] == EDIT_ERRORS


def test_import_diagnostic_log(fresh):
    log = fresh.log
    assert log is not None, f"no diagnostic log at {fresh.log_path}"
    assert_hooks_in_place(log)
    assert_lookups_stay_home(fresh)
    # The lookups the agent decided: several workspace POMs shared the GAV.
    assert any(len(e["candidates"]) > 1 for e in log.of("lookup"))
    # One naming decision per project created: nine, each <worktree>.<m2e's name>.
    naming = log.of("naming")
    assert sorted(e["result"] for e in naming) == sorted(expected_projects())
    for e in naming:
        assert e["result"] == f"{Path(e['worktree']).name}.{e['m2e']}"
        assert Path(e["pom"]).is_relative_to(e["worktree"])


# --- run 06 -------------------------------------------------------------------------------------


def test_restart_keeps_names_classpaths_and_diagnostics(restart, fresh, release_dir):
    assert_agent_launched(restart, release_dir)
    before, after = fresh.snapshots["reimport"], restart.snapshots["import"]
    assert after["eclipseProjects"] == before["eclipseProjects"] == expected_projects()
    assert after["classpaths"] == before["classpaths"] == expected_classpaths()
    assert after["errorDiagnostics"] == before["errorDiagnostics"] == EDIT_ERRORS
    assert after["serverErrorMessages"] == []


def test_restart_creates_no_projects_and_hooks_are_in_place(restart):
    log = restart.log
    assert log is not None, f"no diagnostic log at {restart.log_path}"
    assert log.of("naming") == [], "a restart re-created projects"
    assert_hooks_in_place(log)
    assert_lookups_stay_home(restart)


# --- run 01 -------------------------------------------------------------------------------------


def test_control_without_agent_shows_the_duplicate_coordinates_failure(control, release_dir):
    argv = control.process["argv"]
    assert not any(a.startswith("-javaagent:") for a in argv), argv
    assert not any("org.fractal.jdtls.boot" in a for a in argv), argv
    snap = control.snapshots["import"]
    errors = snap["serverErrorMessages"]
    assert errors and all(DUPLICATES in e for e in errors), errors
    # Only the main checkout imports, under m2e's own names.
    assert snap["eclipseProjects"] == {
        "fractal-harness": "main",
        "harness-contract": "main/harness-contract",
        "harness-app": "main/harness-app",
    }
    assert control.log is None, "the agent wrote a diagnostic log in the control run"


# --- Maven and the install ----------------------------------------------------------------------


def test_maven_uses_only_the_suites_repository(fresh, restart, control, maven_repository, jvm_home):
    plugins = fixture_plugins()
    assert len(plugins) == 4, plugins
    for plugin, version in plugins.items():
        jar = maven_repository / "org/apache/maven/plugins" / plugin / version / f"{plugin}-{version}.jar"
        assert jar.is_file(), f"{jar} is missing: JDT LS didn't use the suite's Maven repository"
    # JDT LS's user.home is a scratch folder; Maven's default repository would appear here.
    assert not (jvm_home / ".m2").exists(), sorted(p.name for p in (jvm_home / ".m2").rglob("*"))


def test_install_unchanged_after_the_runs(fresh, restart, control, release_dir):
    checked = verify(release_dir)
    assert checked.returncode == 0, checked.stdout + checked.stderr
    assert not list(release_dir.rglob("__pycache__"))
