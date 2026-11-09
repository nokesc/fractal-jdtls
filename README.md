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
- **An opt-in HTTP mode** for tools that share one JDT LS between several sessions, each in its own worktree. The launcher also loads this project's endpoint, which becomes JDT LS's only client and serves a token-gated JSON API on localhost: worktrees added and removed at runtime, builds and problems, navigation and refactorings by Java name, and an LSP passthrough.
- **Releases that pin a tested combination:** one JDT LS version, the agent and the endpoint, tested together.

It's deliberately unopinionated. How many servers to run, which worktrees share one, and when they start and stop are left to the tool that launches it.

## Install

**Prerequisites:** Linux; Python 3.10 or newer as `python3` (the scripts use only its standard library); a JDK at JDT LS's minimum, Java 21 or newer; and network access to `download.eclipse.org` and Maven Central while installing.

Download a release's zip from [Releases](https://github.com/nokesc/fractal-jdtls/releases), unzip it, and run its installer:

```bash
curl -fLO https://github.com/nokesc/fractal-jdtls/releases/download/v1.63.0-2/fractal-jdtls-1.63.0-2.zip
python3 -m zipfile -e fractal-jdtls-1.63.0-2.zip .      # or: unzip fractal-jdtls-1.63.0-2.zip
python3 fractal-jdtls-1.63.0-2/bin/install.py
```

- It installs into `~/.local/share/fractal-jdtls/1.63.0-2/` (`${XDG_DATA_HOME:-~/.local/share}/fractal-jdtls/`). Choose another root with `--root <dir>` or `FRACTAL_JDTLS_ROOT`.
- It downloads JDT LS from Eclipse (about 53 MB) and Byte Buddy from Maven Central, and checks every file against the release's `pin.json` before anything is put in place. An interrupted or failed install leaves nothing behind, and running it again on a good install does nothing.
- The unzipped folder can be deleted afterwards.
- Re-check an install at any time: `~/.local/share/fractal-jdtls/1.63.0-2/bin/install.py --verify`. It reports every missing, extra or changed file.
- To uninstall, delete the release's folder. Each release installs side by side in its own folder.

Each release's notes say what it pins and how each downloaded file is trusted.

## Use

Start JDT LS with `<root>/<release>/bin/jdtls` wherever you'd start Eclipse's `jdtls`. Put it on `PATH`:

```bash
ln -s ~/.local/share/fractal-jdtls/1.63.0-2/bin/jdtls ~/.local/bin/jdtls
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

### HTTP mode

With `--http-port-file <file>`, the launcher also loads this project's endpoint. It becomes JDT LS's only client, inside the process, and serves a JSON API on `127.0.0.1`, so several sessions, each in its own worktree, can share one JDT LS. Without the flag, the launcher behaves exactly as above.

```bash
export FRACTAL_JDTLS_HTTP_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
port_file="$(mktemp -d "${XDG_RUNTIME_DIR:-/tmp}/jdtls.XXXXXX")/port.json"  # a fresh path per launch: never a stale file
jdtls -data ~/.cache/my-tool/jdtls-workspace --http-port-file "$port_file" --http-settings settings.json &
pid=$!  # the launcher becomes JDT LS's java, so this is the process to stop
deadline=$((SECONDS + 120))
until [ -e "$port_file" ]; do
  kill -0 "$pid" 2>/dev/null || { echo "jdtls (PID $pid) exited before listening" >&2; exit 1; }
  [ "$SECONDS" -lt "$deadline" ] || { echo "no port file after 120 s: stopping PID $pid" >&2; kill "$pid"; exit 1; }
  sleep 0.2
done
port=$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))["port"])' "$port_file")

api() {  # api <path> [<JSON body>]: a GET without a body, a POST with one; the token reaches curl through a pipe
  curl -sS -H @<(printf 'Authorization: Bearer %s\n' "$FRACTAL_JDTLS_HTTP_TOKEN") \
       -H 'Content-Type: application/json' ${2+--data "$2"} "http://127.0.0.1:$port$1"
}
api '/v1/status?wait=ready&timeoutMs=120000'
api /v1/worktrees/add '{"worktree": "/path/to/worktree"}'
api /v1/build '{"worktree": "/path/to/worktree", "module": "my-module"}'
api /v1/shutdown '{}'
```

- **The token** comes from `FRACTAL_JDTLS_HTTP_TOKEN`: 32 to 4,096 visible ASCII characters, no spaces. The launcher refuses to start without it, and hands it to the endpoint through a pipe, so it's never on a command line, and neither JDT LS nor anything it starts has it in its environment. Every request needs `Authorization: Bearer <token>`, `Host: 127.0.0.1:<port>` (as curl sends it), and no `Origin` header.
- **The port file** appears once the endpoint is listening, on `127.0.0.1` at a port the kernel chose: `{"port", "pid", "release", "api"}`, written atomically with mode 0600. A port file left from an earlier run is removed first, and a clean shutdown removes it. If it doesn't appear within your own timeout, or the process ends first, the start failed: stop the process you started, by its PID, as the example does. Startup failures go to stderr.
- **The settings file** (`--http-settings <file>`, optional) holds the LSP settings as one JSON object. The endpoint sends them to JDT LS in `initialize`, and answers JDT LS's `workspace/configuration` from them. It fixes the few its contract depends on, such as `java.autobuild.enabled: false`, and refuses to start if the file sets one to another value.
- **The API is described by `GET /v1/openapi.json`,** an OpenAPI 3.1 description (API version 1.0.0) written to be read by an LLM. Every operation is a blocking `POST` that names its worktree and Maven module. Edit files however you like: before every operation on code, the endpoint reconciles JDT LS with the disk, and reports what it found in `sync`.
- **Stop it** with `POST /v1/shutdown`, or by killing the PID you started.
- **What it writes:** its log, `<data>/.fractal-jdtls/<release>/endpoint.log`, and its registered worktrees, `<data>/.fractal-jdtls/worktrees.json`, which a restart on the same `-data` brings back. Each refactoring leaves a record in its worktree, `temp/fractal-jdtls/<id>/` (the request, the diff, and the answer): ignore `temp/` in Git, and delete the records when you're done with them.
- **It needs a full JDK,** with the `jdk.httpserver` module; the launcher checks.

## Status

Early. Linux only. The aim is for Eclipse to fix these problems upstream, so that this project becomes just a verified installer, or is no longer needed. Contributions are welcome.

### Known gaps

- **The agent must be present on every start.** A workspace created without it keeps its projects' unprefixed names, and resolves across worktrees again; to start using the agent, give JDT LS a fresh `-data`.
- **Version-pinned hooks.** The agent hooks private methods of the m2e and Maven bundled in the pinned JDT LS, so each release supports exactly one JDT LS version. A hook that isn't in place is reported on stderr and in the JDT LS log (`<data>/.metadata/.log`).
- Gradle projects, Maven classifiers (such as test-jars) and version ranges aren't tested with several worktrees.
- **Adding and removing workspace folders at runtime** (LSP `workspace/didChangeWorkspaceFolders`) is tested through HTTP mode, whose `worktrees/add` and `/remove` send it: each worktree keeps its own names and resolution.
- **HTTP mode's refactorings** are rename (of a type, method, field, local variable or package), organize imports, extract local variable, constant and method, and inline local variable. Move, change signature, quick fixes, source generation, and Eclipse's refactorings that JDT LS doesn't expose are still to come.
- **HTTP mode's LSP passthrough** (`POST /v1/lsp`) is an expert escape hatch. It sends only requests it knows are safe, refuses those that would change projects or settings behind the endpoint's back, and refuses a request whose `file:` or `jdt:` URIs point outside the named worktree. It doesn't filter results: they're JDT LS's own, with its URIs and 0-based positions.
- **In HTTP mode, a worktree change runs alone.** While a worktree is added or removed, other requests that reach JDT LS wait for it, or get `not-ready` with `retryAfter`. Adding a generated tree of 51 Maven projects took about 8 seconds.
- **A caller that disconnects isn't noticed.** Its work goes on until its `timeoutMs`, and is cancelled then. Send a `timeoutMs` no longer than you'll wait.
- **A refactoring isn't a transaction.** If JDT LS is killed while it writes, some files can be left edited; Git is the undo. The record in `temp/fractal-jdtls/<id>/` names the files, and a missing `response.json` means the outcome is unknown.
- **Measured only on this machine's kind of disk:** a test fixture and a generated tree of 51 Maven projects and 10,001 Java files, on WSL2's ext4. Before every operation on code, the whole worktree is checked against the disk, and refreshed and built if anything changed: about half a second either way on that tree, and milliseconds on the fixture. WSL2's `/mnt` paths and larger reactors aren't measured.

## Report a problem

Open an issue at <https://github.com/nokesc/fractal-jdtls/issues>. Please include:

- the release (the install folder's name) and the output of `<root>/<release>/bin/install.py --verify`;
- the launcher's stderr, and the JDT LS log, `<data>/.metadata/.log`;
- in HTTP mode, the endpoint's log, `<data>/.fractal-jdtls/<release>/endpoint.log`, and the request and its answer;
- if worktrees resolve each other's modules, the agent's diagnostic log: start JDT LS with `--jvm-arg=-Dfractal.jdtls.diagnosticLog=<file>`, which writes one JSON line per naming decision and module lookup.

## Development

[AGENTS.md](AGENTS.md) describes the repository and its commands, and [.context/design.md](.context/design.md) the design. `mise run validate` is the release gate. Releases are made by pushing a tag `v<release>`, which runs [.github/workflows/release.yml](.github/workflows/release.yml).
