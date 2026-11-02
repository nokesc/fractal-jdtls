# Spike: worktree-aware Maven projects in one JDT LS runtime

One-off executable spike for the Notion page
[Spike — Worktree-aware Eclipse project naming in one JDT LS runtime](https://app.notion.com/p/40baa8717e448102bf02f43a300a90cd).
Results: [FINDINGS.md](FINDINGS.md).

**Not production code and not part of any build.** Nothing here is referenced by the root `pom.xml`,
`mise.toml`, gates or CI. It runs only through the manual commands below.

## What it contains

| Path | Purpose |
| --- | --- |
| `fixture/` | POMs stored as `pom.xml.fixture` so IDEs on this checkout do not import them. A minimal reactor with the planned Harness coordinates: parent `org.fractal:fractal-harness:0.1.0-SNAPSHOT`, modules `harness-contract` and `harness-app` (app depends on contract). No Spring, so it imports offline. |
| `agent/src/boot/.../Spike.java` | Bootstrap-loaded helper: logging, worktree identity, naming and same-worktree resolution. Talks to m2e and Maven only via reflection. |
| `agent/src/agent/.../SpikeAgent.java` | Byte Buddy `premain`; inlines advice into m2e and Maven classes that only calls `Spike`. |
| `scripts/build-agent.sh` | Builds `spike-agent.jar` (with Byte Buddy) and `spike-boot.jar` into `$SPIKE_HOME/agent`. |
| `scripts/make-fixture.sh` | (Re)creates `$SPIKE_HOME/worktrees/{main,wt-a,wt-b}`: a Git repo from `fixture/` plus two linked worktrees at one commit. |
| `scripts/run-all.sh` | Reproduces every run in FINDINGS.md from scratch. |
| `scripts/lsp_driver.py` | Starts one JDT LS (`~/.local/bin/jdtls`) with all roots as workspace folders, drives scenario steps and writes snapshots. |

Agent modes (`-Dspike.mode`): `trace` logs only; `names` also prefixes project names with the
worktree directory name; `fix` also prefers the requester's worktree when several workspace POMs
share a GAV.

## Prerequisites

The HS-26 user-scoped install: JDT LS 1.63.0 in `~/.local/share/jdtls`, launcher `~/.local/bin/jdtls`,
Temurin/OpenJDK 25.0.1 at `~/.local/share/mise/installs/java/25.0.1`, and Byte Buddy 1.18.14 in
`~/.m2`. Fixture plugins (compiler 3.13.0, resources 3.3.1, jar 3.3.0, surefire 3.5.6) must be in `~/.m2`.

State lives outside the checkout: `$SPIKE_HOME` (default `~/.local/state/fractal-harness/jdtls-worktree-spike`)
holds the agent jars, worktrees and JDT LS workspaces; `$EVIDENCE` (default
`~/.local/state/fractal-harness/evidence/jdtls-worktree-spike`) holds per-run snapshots and logs.

## Manual run

```bash
cd experiments/jdtls-worktree-spike
scripts/run-all.sh      # rebuilds the agent and reproduces runs 01–07 from scratch (about 4 minutes)
```

Or run one scenario on its own, for example:

```bash
scripts/build-agent.sh && scripts/make-fixture.sh
python3 scripts/lsp_driver.py --run 05-fix --mode fix --steps import edits reimport
python3 scripts/lsp_driver.py --run 06-restart-fix --mode fix \
  --data ~/.local/state/fractal-harness/jdtls-worktree-spike/data/05-fix
```

Each run writes `$EVIDENCE/<run>/{import,edits,reimport}.json` (Eclipse project names and locations,
Java projects, worktree classpath entries, error diagnostics, server error messages, root commits and
the `-data` path), `agent.jsonl` (agent trace), `lsp.jsonl` (LSP notifications) and `jdtls.log`.

Use a fresh `--run` name or delete `$SPIKE_HOME/data/<run>` first; reusing a workspace is the restart test.
