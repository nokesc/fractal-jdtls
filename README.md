# Fractal JDT LS

A verified install of the Eclipse JDT Language Server (JDT LS) that works when several Git worktrees of the same Maven project share one server.

## Why it exists

Coding agents, and the developers working alongside them, increasingly work in several Git worktrees of one repository at once. Each worktree is a full checkout of the same project, on its own branch.

JDT LS gives editors and agents Java code intelligence: compile errors as you type, go-to-definition, references and refactoring. It understands Maven projects through m2e, Eclipse's Maven integration, which ships inside it. JDT LS and m2e were built around one checkout of a project per workspace, and they break down when there are several:

- **One server can't hold two checkouts of the same multi-module project.** The modules have the same names and Maven coordinates in every worktree. They collide, and one worktree's code can be resolved against another's.
- **The alternative is one server per worktree,** which costs more than a gigabyte of memory each, plus a long first import.
- **The stock launcher identifies a workspace by its folder name,** so two checkouts with the same folder name share one workspace's state.
- **Installing is manual.** JDT LS is a download you unpack and launch yourself, and part of what it contains can be verified only by the download's checksum.

## What it provides

- **An installer** that downloads an official Eclipse JDT LS release, never a snapshot, from Eclipse, and verifies it before use. This project never repackages or redistributes Eclipse's files.
- **A launcher that drop-in replaces `jdtls`.** It starts the verified release with a small Java agent that keeps each worktree's projects separate inside one server.
- **Releases that pin a tested combination:** one JDT LS version and the agent, tested together.

It's deliberately unopinionated. How many servers to run, which worktrees share one, and when they start and stop are left to the tool that launches it.

## Install

**Prerequisites:** Linux; Python 3.10 or newer as `python3` (the scripts use only its standard library); a JDK at JDT LS's minimum, Java 21 or newer; and network access to `download.eclipse.org` and Maven Central while installing.

Download a release's zip from [Releases](https://github.com/nokesc/fractal-jdtls/releases), unzip it, and run its installer:

```bash
curl -fLO https://github.com/nokesc/fractal-jdtls/releases/download/v1.63.0-1/fractal-jdtls-1.63.0-1.zip
python3 -m zipfile -e fractal-jdtls-1.63.0-1.zip .      # or: unzip fractal-jdtls-1.63.0-1.zip
python3 fractal-jdtls-1.63.0-1/bin/install.py
```

- It installs into `~/.local/share/fractal-jdtls/1.63.0-1/` (`${XDG_DATA_HOME:-~/.local/share}/fractal-jdtls/`). Choose another root with `--root <dir>` or `FRACTAL_JDTLS_ROOT`.
- It downloads JDT LS from Eclipse (about 53 MB) and Byte Buddy from Maven Central, and checks every file against the release's `pin.json` before anything is put in place. An interrupted or failed install leaves nothing behind, and running it again on a good install does nothing.
- The unzipped folder can be deleted afterwards.
- Re-check an install at any time: `~/.local/share/fractal-jdtls/1.63.0-1/bin/install.py --verify`. It reports every missing, extra or changed file.
- To uninstall, delete the release's folder. Each release installs side by side in its own folder.

Each release's notes say what it pins and how each downloaded file is trusted.

## Use

Start JDT LS with `<root>/<release>/bin/jdtls` wherever you'd start Eclipse's `jdtls`. Put it on `PATH`:

```bash
ln -s ~/.local/share/fractal-jdtls/1.63.0-1/bin/jdtls ~/.local/bin/jdtls
```

or point your LSP client's JDT LS command at that path.

- **The same command line as the stock launcher:** `--java-executable <java>`, repeatable `--jvm-arg=<option>`, `-data <workspace>`, and any other arguments, which pass through to JDT LS. `jdtls --help` lists them.
- **Java:** `--java-executable`, else `$JAVA_HOME/bin/java`, else `java` on `PATH`. It must be Java 21 or newer. Choosing the JDK your projects need is up to you.
- **Workspace (`-data`):** whatever your client passes. Without one, it's `${XDG_CACHE_HOME:-~/.cache}/fractal-jdtls/ws/<hash>`, keyed on the full path of the working directory, so two checkouts with the same folder name never share one.
- **On every start it checks the install** (it refuses to start if any file is missing, extra or changed), and always adds the agent. The install itself is never written to.
- **The agent needs no setup.** Each Maven project in a Git worktree is named `<worktree folder>.<artifactId>`, and a module resolves its sibling modules from its own worktree. Projects outside any Git checkout are left as m2e makes them.
- **Use a fresh `-data` for the agent.** The agent must be present on every start of a workspace. A workspace that JDT LS created or opened without it keeps unprefixed names and resolves across worktrees again; to start using Fractal JDT LS with it, give JDT LS a fresh `-data`, and keep using this launcher for it.
- **Folder names must be unique** among the worktrees that share one JDT LS process. The agent reports a collision on stderr and in the JDT LS log.
- Everything else belongs to the client: how many JDT LS processes to run and which worktrees share one, LSP settings, compiler preferences and JVM sizing (`--jvm-arg`).

## Status

Early. Linux only. The aim is for Eclipse to fix these problems upstream, so that this project becomes just a verified installer, or is no longer needed. Contributions are welcome.

### Known gaps

- **Adding and removing workspace folders at runtime** (LSP `workspace/didChangeWorkspaceFolders`) isn't tested yet. The worktrees a server should hold are tested only as the workspace folders it starts with.
- **The agent must be present on every start.** A workspace created without it keeps its projects' unprefixed names, and resolves across worktrees again; to start using the agent, give JDT LS a fresh `-data`.
- **Version-pinned hooks.** The agent hooks private methods of the m2e and Maven bundled in the pinned JDT LS, so each release supports exactly one JDT LS version. A hook that isn't in place is reported on stderr and in the JDT LS log (`<data>/.metadata/.log`).
- Gradle projects, Maven classifiers (such as test-jars) and version ranges aren't tested with several worktrees.

## Report a problem

Open an issue at <https://github.com/nokesc/fractal-jdtls/issues>. Please include:

- the release (the install folder's name) and the output of `<root>/<release>/bin/install.py --verify`;
- the launcher's stderr, and the JDT LS log, `<data>/.metadata/.log`;
- if worktrees resolve each other's modules, the agent's diagnostic log: start JDT LS with `--jvm-arg=-Dfractal.jdtls.diagnosticLog=<file>`, which writes one JSON line per naming decision and module lookup.

## Development

[AGENTS.md](AGENTS.md) describes the repository and its commands, and [.context/design.md](.context/design.md) the design. `mise run validate` is the release gate. Releases are made by pushing a tag `v<release>`, which runs [.github/workflows/release.yml](.github/workflows/release.yml).
