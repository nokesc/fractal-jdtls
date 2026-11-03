# Findings: worktree-aware Maven projects in one JDT LS runtime

The findings of the JDT LS worktree spike
([Spike — Worktree-aware Eclipse project naming in one JDT LS runtime](https://app.notion.com/p/40baa8717e448102bf02f43a300a90cd)),
which the [design](../design.md) cites as FINDINGS. The spike's code was seeded into this repository
as `.context/spike` and retired in T001.06; it remains in Git history. What became of it:

- Its `Spike.java` and `SpikeAgent.java` became the agent, [agent/](../../agent/) (design §6), with
  the `fix` behavior always on.
- Its fixture, driver and `run-all.sh` became the integration suite,
  [tests/integration/](../../tests/integration/) (design §8, `mise run test:integration`), which
  reproduces runs 01, 05 and 06 against the released agent.

The spike's agent had modes (`-Dspike.mode`), used in the runs below: `trace` only logged; `names`
also prefixed project names with the worktree's folder name; `fix` also preferred the requester's
worktree when several workspace POMs shared a GAV. The fixture is a minimal reactor with the planned
Harness coordinates: parent `org.fractal:fractal-harness:0.1.0-SNAPSHOT` and modules
`harness-contract` and `harness-app`, where the app depends on the contract.

Run 2026-11-01/02 on WSL2 against JDT LS `1.63.0.202610191322` (source `v1.63.0`), m2e core
`2.8.0.20260815-0704` (source `f82726e0`), m2e jdt `2.5.300.20260813-1359` (source `61ff4470`),
embedded Maven `3.9.16`, OpenJDK `25.0.1`, Byte Buddy `1.18.14`. One JDT LS process and one `-data`
workspace per run; three roots `main` (main checkout), `wt-a` and `wt-b` (linked worktrees), at one
fixture commit per run (recorded as `roots` in every snapshot), with identical coordinates. The spike's
`scripts/run-all.sh` reproduced all runs in about 4 minutes. Their evidence was kept outside the
repository, one folder per run, under `~/.local/state/fractal-harness/evidence/jdtls-worktree-spike`.

## Result

**Recommendation:** a JDT LS `-javaagent` with one naming hook and one resolution rule. It works for
import, incremental builds, reimport and restart, and it needs no changes to POMs, coordinates or
sources. Built-in configuration cannot do it.

1. **Naming.** Exit advice on `ProjectConfigurationManager.getProjectName(ProjectImportConfiguration, Model)`
   returns `<worktree-dir>.<name>`. The worktree is derived from `model.getPomFile()`.
2. **Resolution.** When several workspace POMs share a GAV, prefer the one under the requester's
   worktree, and otherwise keep m2e's choice. Two lookups need it:
   - `EclipseWorkspaceArtifactRepository.getWorkspaceArtifact(g, a, v)` (Maven artifact and POM resolution);
   - `BasicProjectRegistry.getProjectFacade(g, a, v)` (JDT classpath project references).

   The requester is `context.pom` when it is set. Otherwise it comes from a nested-safe `ThreadLocal`
   set around:
   - `DefaultClasspathManagerDelegate.populateClasspath(…, facade, …)`, using `facade.getPom()`;
   - Maven `DefaultModelBuilder.readParentExternally(childModel, …)`, using `childModel.getPomFile()`;
   - Maven `DefaultProjectBuilder.initParent(childProject, …)`, using `childProject.getFile()`.
3. **The agent must be present on every start**, not only at first import (run 07).

## Runs

| Run | Agent | Outcome |
| --- | --- | --- |
| 01-baseline | none | Only `main` imports. `wt-a` and `wt-b` fail: "3 Maven project(s) could not be imported due to duplicate groupId/artifactId" (twice). |
| 02-trace | trace | Identical to the baseline. All six target classes transform under Equinox and every `getProjectName` call runs inside `create()` on one import thread. The other worktrees' `create()` calls return null. |
| 03-names | names | All nine projects import with distinct names. **Every** `harness-app` (main, wt-a, wt-b) resolves `harness-contract` to `wt-b/harness-contract/target/classes`. wt-a's `Dispatch.java` uses `WorkType`, which exists only in wt-b's contract, and gets **no error** (false negative). The trace shows 32 of 50 GAV lookups crossing worktrees and 11 project references degraded to library entries. |
| 04-names-no-maven-requester | names, Maven requester hooks off | Exactly 6 lookups have no requester: 3 from `DefaultModelBuilder.readParentExternally:1035` and 3 from `DefaultProjectBuilder.initParent:911`, both under `IMavenToolbox.readMavenProjects`. |
| 05-fix | fix | Each `harness-app` resolves its own worktree's contract. After the edits, wt-a reports `WorkType cannot be resolved` (3 errors) and wt-b reports only its own `Broken.java` type error. `java/projectConfigurationUpdate` on wt-b's POMs changes nothing. All 50 GAV lookups had a requester and resolved within their own worktree. 0 library entries point into a worktree, and 0 transform errors. |
| 06-restart-fix | fix | Restart on run 05's workspace: same nine names, **0** `create()` calls and **0** name computations, the same per-worktree classpaths and the same diagnostics. |
| 07-restart-no-agent | none | Restart on a copy of run 05's workspace without the agent. The names persist, because projects are found by location, but `main` and `wt-a` `harness-app` silently resolve wt-b's contract again. wt-a still shows its 3 `WorkType` errors although its classpath now has wt-b's `WorkType`, so the diagnostics are stale as well as wrong. |

## Hypotheses

- **H1, confirmed (03).** Unique names alone make every worktree import, but cross-module resolution
  ignores the worktree.
- **H2, confirmed (03).** The two lookups disagree. The trace shows `classpath-library` entries such
  as `wt-a/harness-app` → `main/harness-contract/target/classes` and → `wt-b/…/target/classes`. A
  project reference degraded to a plain output-folder library because `getWorkspaceArtifact` and
  `getProjectFacade` chose different POMs.
- **H3, confirmed (03, 05).** Every `getProjectFacade(g, a, v)` call arrives from
  `DefaultClasspathManagerDelegate.addClasspathEntries:117` on the same thread as the enclosing
  `populateClasspath`. A `ThreadLocal` carries the requester. `populateClasspath` runs on several job
  threads, so the value must be per thread and restored on exit.
- **H4, refined (04).** Only two GAV lookup sites ran, the two above. However,
  `getWorkspaceArtifact` is also reached with `context.pom == null` when Maven resolves a **parent
  POM** (`fractal-harness:pom`) during `IMavenToolbox.readMavenProjects`, in both import and
  `ProjectRegistryManager.refresh`. That gap is not visible from m2e source alone. The two Maven
  requester hooks close it (04 without them: 6 lookups lack a requester; 05 with them: 0).
- Default resolution is not even stable. In run 03 the same requester got main's, wt-a's and then
  wt-b's contract as projects registered. The final winner depends on registration and hash order.

## Constraints and risks

- **Version-pinned.** The hooks target private and internal methods (`getWorkspaceArtifact`,
  `readParentExternally`, `initParent`, `BasicProjectRegistry` fields). A production agent must check
  that every target was transformed (the agent logs `transformed` and `transform-error`) and fail
  loudly otherwise. It must be revalidated on every JDT LS, m2e or Maven runtime upgrade.
- **IDE side effect.** A fixture `pom.xml` inside this repository was imported by the developer's
  IDE language server, and its parent shares the repository's own GAV. That is the same collision, on
  a real workspace. The fixture POMs are therefore stored as `pom.xml.fixture`.
- **Classloading.** The advice is inlined into bundle-loaded classes. The helper must be on the boot
  class path (`Boot-Class-Path`) and visible through
  `-Dorg.osgi.framework.bootdelegation=org.fractal.spike.jdtls.boot`, with JDK-only signatures.
- **Worktree token.** The token is the worktree directory name. Two worktrees with the same directory
  name under different parents would collide. Harness/Workmux naming must keep them unique, or the
  token needs a path hash.
- Projects created without the agent keep their unprefixed names. Introducing the agent into an
  existing workspace needs a fresh `-data` directory.

## Not tested

- Adding or removing workspace folders at runtime (`workspace/didChangeWorkspaceFolders`), and
  cleanup after a worktree is deleted.
- The real Harness reactor, which is still single-module on `main`. The fixture uses its planned
  coordinates.
- Classifiers (test-jars), version ranges, Gradle, and the m2e UI wizards.
- Performance. Import-to-ready was about 18–24 s with and without the agent on this tiny fixture,
  which is not a benchmark.
