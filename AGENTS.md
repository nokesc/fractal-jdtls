# Fractal JDT LS: agent guide

This file is for every coding agent working in this repository. It follows the Fractal conventions (`CONVENTIONS.md` in the umbrella repository, `~/github/nokesc/fractal/`), and wins where it's more specific.

## What the project is

Fractal JDT LS is a verified install of one official Eclipse JDT Language Server release, plus a launcher that is a drop-in for the stock `jdtls` and always adds a small Java agent. The agent keeps each Git worktree's Maven projects separate when several worktrees of one project share a JDT LS process. [README.md](README.md) says why it exists; [.context/design.md](.context/design.md) says what it delivers and how, and is authoritative together with the code.

## Repository structure

- `bin/`: the shipped Python scripts, `install.py` and the `jdtls` launcher (design §4, §5).
- `agent/`: the Maven modules for the agent jar (`agent/agent/`) and the boot jar (`agent/boot/`), built from the root `pom.xml` (design §6), with their JUnit tests. The root POM's `byte-buddy.version` property is the only place the Byte Buddy version is chosen.
  - The agent's diagnostic log is enabled with `-Dfractal.jdtls.diagnosticLog=<file>` (one JSON line per event; see `DiagnosticLog`).
  - The build also makes a test-only `agent/agent/target/fractal-jdtls-agent-broken-hook.jar` (`BrokenHookAgent`: one hook's matcher names a missing method). It is never in the release zip; put it in a copy of an install's `lib/` to check that a broken hook is reported.
- `pin.json`: the pin of the one JDT LS release and Byte Buddy version this release installs (design §3). It's written by `tools/pin.py`, never by hand, and its evidence, the pin report, is in `.context/pins/<jdtls-version>.md`.
- `tools/`: maintainer tooling that isn't shipped: `tools/pin.py` (design §3), `tools/release_zip.py` (design §2) and `tools/release_tag.py`, the release workflow's tag check (design §9).
- `.github/`: the release workflow, `workflows/release.yml` (design §9), and each release's notes, `release-notes/v<release>.md`, which the workflow uses as the release's body.
- `tests/`: pytest suites.
  - `tests/unit/` runs in `mise run test`, each test with a scratch `HOME` and XDG folders (`tests/unit/conftest.py`).
  - `tests/integration/` runs in `mise run test:integration` (design §8): real JDT LS, installed by `install.py` from a freshly built release zip into a pytest scratch root, launched through the installed `bin/jdtls` on a scratch main checkout and two linked worktrees made from `tests/integration/fixture/` (its POMs stay `pom.xml.fixture`). It reproduces the spike's runs 05 and 06, and run 01 without the agent through the test-only `launch_without_agent.py`. Each run's snapshots, LSP traffic, diagnostic log and JDT LS log stay in pytest's temporary folder under `runs/<run>/` (`/tmp/pytest-of-$USER/pytest-current/` for the latest).
- `.context/`: the design (`design.md`), task series (`planning/`), each task's evidence (`planning/evidence/`), the pin reports (`pins/`) and research evidence (`research/`), such as the JDT LS worktree spike's findings, cited by the design as FINDINGS.
- `scripts/bootstrap.sh`: sets up a fresh clone. `.agents/skills/` holds the repository's skills; `.claude/skills` is a committed link to it.

## Implementation rules

- **Scope (design §1).** Ship a verified install of one official JDT LS release and a launcher that always adds the agent. Leave process counts, sharing, lifecycle, brokers, compiler preferences, LSP settings and JVM sizing to the client.
- **Never repackage or redistribute Eclipse's files or Byte Buddy.** The installer downloads them, from Eclipse and Maven Central, and verifies them against `pin.json`.
- **Official releases only:** `https://download.eclipse.org/jdtls/milestones/<version>/`. Never `snapshots/` or any other pre-release build.
- **Linux only.** Write Python portably where it costs nothing; support, test and document only Linux.
- **Shipped scripts use the Python standard library only** (Python 3.10 or newer). pytest and other dependencies are for development.
- **No IDE-importable fixture POMs.** Test fixture POMs are committed as `pom.xml.fixture` and materialized only into scratch folders, so no IDE or language server on this checkout imports them. The root `pom.xml` and `agent/*/pom.xml` are the only real POMs.
- **Tests never touch the real `~/.local/share`, `~/.cache` or `~/.m2`.** They use a scratch home or pytest temp folders, and integration runs use their own Maven local repository.
- **Licensing (design §7).** Apache-2.0; every source file starts with an `SPDX-License-Identifier: Apache-2.0` line, which a unit test checks.
- **Processes.** Never stop a process you didn't start: another checkout, or an editor, may be running its own JDT LS. Stop only processes whose PIDs you recorded when you started them, never by port or executable name.
- **Agents never push, merge, tag or publish.** Chris tags releases: pushing a tag `v<release>` runs `.github/workflows/release.yml`, which checks the tag, runs `mise run validate` with the repository's mise toolchain (all of `mise.toml`'s pins except `workmux`, a developer tool the release doesn't need, which the job skips with `MISE_DISABLE_TOOLS: workmux`), creates a draft release with the notes, attaches the zip and publishes. A new release needs its notes in `.github/release-notes/` before the tag; the actionlint check is `mise exec actionlint@1.7.12 shellcheck@0.11.0 -- actionlint`.

## Commands and validation

Commands are mise tasks; `mise tasks ls` lists them.

- `./scripts/bootstrap.sh`: trusts and installs the mise toolchain and runs `mise run setup`, for a fresh clone.
- `mise run setup`: installs the locked Python dependencies into this checkout's own `.venv`.
- `mise run test`: the unit tests. It runs `build` first, which runs the agent's JUnit tests, then pytest, which checks the built jars.
- `mise run build`: builds `agent/agent/target/fractal-jdtls-agent.jar` and `agent/boot/target/fractal-jdtls-boot.jar`, running the agent's JUnit tests.
- `mise run release:zip`: builds the jars, then the release zip, `build/fractal-jdtls-<release>.zip` (design §2). Install from it with `python3 fractal-jdtls-<release>/bin/install.py --root <scratch root>`; check an install with `<root>/<release>/bin/install.py --verify`. Start JDT LS from an install with `<root>/<release>/bin/jdtls` (`--help` lists its options); it verifies the install first, and refuses to start if it fails. Inside this checkout, the mise `python3` shim that its `#!` line finds sets `JAVA_HOME` to mise's Java, so pass `--java-executable` (or run it with `/usr/bin/python3`) to choose another Java.
- `mise run test:integration`: the integration suite alone (after `build`). It needs network access: each session downloads the JDT LS tarball (about 53 MB) and Byte Buddy, and the first one fills the suite's Maven local repository from Maven Central. That repository persists in `$FRACTAL_JDTLS_TEST_CACHE`, else `.cache/integration/` in this checkout; JDT LS gets it through `java.configuration.maven.userSettings`, and a scratch `user.home`, so it never touches `~/.m2`. It fails, naming the folder, if a stray `.git` sits above pytest's temporary folder (an empty `/tmp/.git` has appeared in agent sessions; remove it).
- `mise run validate`: the full release gate: the build with the agent's JUnit tests, the unit tests, the release zip and the integration suite. It must pass before any task is complete.
- `mise run release:check-tag -- <tag>`: the release workflow's first step: the tag must be `v` plus `pin.json`'s `release`, and `.github/release-notes/<tag>.md` must exist.
- `mise run pin -- <jdtls-version> [--release <n>]`: rewrites `pin.json` and the pin report (maintainers only). It downloads the tarball (about 53 MB), runs `jarsigner` on every jar and queries Maven Central, which takes several minutes; pass `--cache-dir <dir>` to keep the tarball between runs. Byte Buddy's PGP signature is checked only when its signer's key is in your GnuPG keyring.

Each task records the real output of every acceptance item in `.context/planning/evidence/<task>.md`, committed with the work in one commit whose subject starts `<task>: `.
