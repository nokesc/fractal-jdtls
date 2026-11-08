# Fractal JDT LS design

What Fractal JDT LS delivers, and how it is built, verified and released. [README.md](../README.md) says why it exists. The design and the code are authoritative. Task files only order the work.

The decisions below were made by Chris on 2026-11-01, starting from the JDT LS worktree spike. Its findings, [research/jdtls-worktree-spike.md](research/jdtls-worktree-spike.md) (cited below as FINDINGS), are the evidence the agent is built on.

## 1. Scope and boundaries

- **The product:** a verified install of one official Eclipse JDT LS release, and a launcher that is a drop-in for the stock `jdtls` and always adds this project's Java agent. The agent keeps each Git worktree's Maven projects separate inside one JDT LS process.
- **The HTTP endpoint (§11), opt-in.** In HTTP mode, the launcher also loads this project's endpoint bundle, which becomes JDT LS's only client and serves a token-gated REST API on localhost. Several sessions can then share one process through it. Without HTTP mode, the launcher is still the plain drop-in. (Decided by Chris on 2026-11-07.)
- **Unopinionated.** These belong to the tool that launches it, not to this project:
  - how many JDT LS processes run, which worktrees share one, and when they start and stop. In HTTP mode, the endpoint carries out the client's choices: it adds and removes worktrees when told to, and never decides which worktrees share a process;
  - which sessions use a process, and how their requests are routed to it;
  - compiler preferences, such as errors-only diagnostics or keeping `target/classes`;
  - LSP settings and JVM sizing.

  Fractal Harness is the first consumer and owns all of that for itself. It talks to the endpoint over HTTP, and does its own MCP delegation.
- **Never repackage or redistribute Eclipse's files.** The installer downloads them from Eclipse. The same goes for Byte Buddy, which comes from Maven Central (§3).
- **Official JDT LS releases only.** JDT LS publishes its releases under `https://download.eclipse.org/jdtls/milestones/<version>/`. Never use `snapshots/`, or any other pre-release build.
- **Linux only.** Write the Python portably where it costs nothing, but support, test and document only Linux.
- **Interim.** The aim is for m2e and JDT LS to fix these problems upstream. Then the agent goes away, and this project becomes only a verified installer or is retired.

## 2. Releases and the installed layout

- **A release** is a tag `v<jdtls-version>-<n>`, for example `v1.63.0-1`. It pins one JDT LS version, one Byte Buddy version, and the agent, all tested together. A fix that doesn't change the pin is the next `<n>`.
- **The release ID** (`1.63.0-1`) has one source: the `release` field of `pin.json` (§3). The zip's name and top folder, the install folder and the tag the workflow accepts (§9) all come from it, so a local build before any tag has the same identity as the published one.
- **The release asset** is one zip. It holds this project's files only:

  ```
  fractal-jdtls-1.63.0-1/
    bin/install.py     installs this release (§4)
    bin/jdtls          the launcher (§5); called run.py in the discussion that led to this design
    lib/               the agent jar and the boot jar (§6)
    pin.json           the pin (§3)
    release-files.json the SHA-256 of every other file in the zip (§4)
    LICENSE  NOTICE
  ```
- **Installed layout:** one folder per release, containing everything ("option A"):

  ```
  ~/.local/share/fractal-jdtls/1.63.0-1/
    bin/  lib/  pin.json  release-files.json  LICENSE  NOTICE     copied from the zip
    lib/byte-buddy-<version>.jar              from Maven Central, verified
    eclipse/                                  JDT LS from Eclipse, verified
  ```

  - The default root is `${XDG_DATA_HOME:-~/.local/share}/fractal-jdtls/`, overridable with a flag and an environment variable.
  - To uninstall, delete the folder.
  - Each release downloads Eclipse again (about 53 MB). That's accepted to keep the model simple.
- **The install stays read-only after it's installed.** Nothing at runtime writes inside it (§5, "Configuration area").

## 3. The pin

**`pin.json` is hard-coded in each release** and read by both `install.py` and `bin/jdtls`. It holds:

- the release ID, `<jdtls-version>-<n>` (§2);
- the JDT LS version, its download URL and the tarball's SHA-256;
- the SHA-256 of **every file** in the unpacked tarball;
- the minimum Java version that release of JDT LS needs to run;
- the Byte Buddy version, its Maven Central URL and its SHA-256.

**The Byte Buddy version is chosen once,** as the `byte-buddy.version` property of the root `pom.xml`, because the agent build compiles against it and writes it into the agent jar's `Class-Path` (§6). `tools/pin.py` reads it from there. A unit test fails unless the POM property, `pin.json` and the built agent jar's `Class-Path` all name the same version; a mismatch would otherwise surface only as the agent failing to load at launch.

**A maintainer tool, `tools/pin.py`, writes it.** It's development tooling, not shipped in the zip. Given a JDT LS version and a release number `<n>` (default 1), it:

1. Downloads the tarball and checks it against Eclipse's published `<tarball>.sha256`.
2. Records the hash of every file.
3. Classifies each jar:
   - signed by the Eclipse Foundation (`jarsigner -verify`, with the signer's chain valid, not self-signed and allowed to sign code; a warning about the timestamp authority alone doesn't disqualify a jar);
   - byte-identical to an artifact on Maven Central (by the artifact's published hash);
   - or **neither**: trusted only through the tarball's checksum.
4. Downloads Byte Buddy from Central, records its hash, and verifies its PGP signature (`.asc`) when `gpg` and the maintainer's key are available.
5. Writes `pin.json` and a pin report. The report goes into the repository as the evidence of how each file was checked.

Hashes we compute ourselves only prove a file hasn't changed since. **What a file should be comes from those outside checks.** The pin is the record of them.

### Verified facts for JDT LS 1.63.0

Checked on 2026-11-01.

- The tarball is `jdt-language-server-1.63.0-202610191322.tar.gz`, SHA-256 `b1f2abe0b3cccb462cf64fb4a5587c7d09df60cf8be7748b152cbd89d018bdc4`. Eclipse publishes `.sha256` only, with no PGP signature.
- **58 of 110 jars** in `plugins/` pass `jarsigner -verify`, signed by the Eclipse Foundation. **52 are unsigned,** including all of m2e (`m2e.core`, `m2e.jdt`, `m2e.maven.runtime`, `m2e.apt.core`).
  - The signer certificates use three names for the Foundation: `Eclipse Foundation`, `Eclipse Foundation, Inc.` and `Eclipse.org Foundation, Inc.`. `tools/pin.py` accepts all three, and rejects a signer whose chain is invalid or self-signed, whatever its name.
  - `org.eclipse.m2e.workspace.cli_0.4.0.jar` counts as signed: plain `jarsigner -verify` passes it. Only its timestamp authority's chain is invalid and the timestamp expired, so `jarsigner -verify -strict` would reject it and the count would be 57. It is also byte-identical to Maven Central, and the pin report records the warning.
- Gson, Guava, ASM, commons-lang3 and jsoup are byte-identical to Maven Central, which has `.asc` signatures for them.
- The bundled m2e build, `2.8.0.20260815-0704`, isn't on m2e's release site (`2.7.800`) or its snapshot site (`3.0.0`), so it can't be checked against m2e's PGP-signed update sites.
- **A second source for m2e:** Red Hat's VS Code Java extension `1.59.2026103108` (Marketplace, universal package) bundles JDT LS 1.64.0 with byte-identical m2e jars. This is optional, used when an extension version bundles the pinned JDT LS version.
- JDT LS **writes a `configuration/` folder into its install folder** at runtime when launched by the stock script (seen in the hand-made HS-26 install). The cause and the fix are in §5, "Configuration area".

## 4. Installing: `bin/install.py`

- **Python standard library only,** Python 3.10 or newer. It runs without a virtual environment.
- **Installs the release it ships in** into `<root>/<release>/`. The unzipped download can be deleted afterwards.
- **Idempotent.** If the target exists and verifies, it does nothing.
- **Atomic.** It downloads and unpacks into a temporary folder beside the target, verifies everything against `pin.json`, then renames it into place. A failed or interrupted install never leaves a partial target. The tarball's checksum is checked before unpacking, and every unpacked file's checksum after.
- **Byte Buddy** is downloaded from Maven Central into `lib/` and verified against the pin.
- **`--verify`** re-checks an existing install and reports every missing, extra or changed file.
  - **The release's own files are verified too,** through `release-files.json`. `pin.json` can't hold their hashes: it is one of them, and the agent jar is built after the pin. So `release:zip` writes `release-files.json` with the release ID and the SHA-256 of every other file in the zip (`bin/`, the agent and boot jars, `pin.json`, `LICENSE`, `NOTICE`). The installer checks the unzipped release against it before installing, copies it into the install, and `--verify` and the launch check (§5) check those files' contents against it, wherever they run from. Its release ID must equal `pin.json`'s. (Decided by the governor on 2026-11-02 during T001.03, under Chris's delegation; to review.)
  - **Root and concurrency.** The root is `--root`, else `FRACTAL_JDTLS_ROOT`, else, for an installed `install.py`, the root it's installed in, else the XDG default (§2). Installs hold a lock file, `<root>/.<release>.lock`, so a later install can safely remove the partial folder a killed one left beside the target.
  - **A target that exists but fails verification is left alone,** with an instruction to delete it and run again. The installer never repairs or overwrites an install.
- Errors say exactly what failed and what to do. It exits non-zero on any failure.

## 5. The launcher: `bin/jdtls`

**A drop-in for Eclipse's stock `bin/jdtls`.** It accepts the same command line: `--java-executable`, repeatable `--jvm-arg=…`, `-data <dir>`, and any other arguments passed through to JDT LS. It builds the same `java` command, with the Equinox launcher, the shared configuration and `--add-opens` (see `bin/jdtls.py` in the Eclipse tarball). It also always adds the agent:

```
-javaagent:<release>/lib/fractal-jdtls-agent.jar
-Dorg.osgi.framework.bootdelegation=<the boot helper's package>
```

- **Java.** It uses `--java-executable`, then `JAVA_HOME`, then `java` on `PATH`, the stock order. It **only checks** that the version meets the pin's minimum, and fails with a clear message otherwise. Choosing a JDK that suits the project is the client's job.
- **Workspace (`-data`).** Whatever the client passes. When none is given, the default is `${XDG_CACHE_HOME:-~/.cache}/fractal-jdtls/ws/<sha1 of the canonical working directory>`, keyed on the full path. The stock launcher keys on the folder name only, which lets two checkouts with the same folder name share one workspace.
  - A client's `-data` and `-configuration` pass to Equinox unchanged, as with the stock launcher. The launcher resolves them as the pinned Equinox does, and refuses, before starting Java, any that resolves inside the install, and the forms whose folder is ambiguous: `file:` URLs with `?` or `#`, upper-case `FILE:`, other URL schemes and `@` locations. (Decided by the governor on 2026-11-02 during T001.05; to review.)
- **Configuration area.** It passes a writable `-configuration` outside the install, at `<data>/.fractal-jdtls/<release>/configuration`, so the install stays read-only and verifiable. Confirm with a real run that nothing is written inside the install.
  - The stock `bin/jdtls.py` already points Equinox at the install's read-only shared configuration (`-Dosgi.sharedConfiguration.area=<install>/config_linux`, `…area.readOnly=true`, `-Dosgi.configuration.cascaded=true`), but passes no `-configuration`. Equinox then puts its writable area at `<install>/configuration`. Keeping the stock properties and adding `-configuration <writable folder>` is enough; an empty folder works because the cascade supplies `config.ini`.
- **Verification at launch.** Before starting JDT LS, it checks the install against `pin.json` and `release-files.json` (§4). If the cost is more than about half a second, measure it, record it and raise it with Chris.
- **No other options,** except the opt-in HTTP mode's (§11): `--http-port-file` and `--http-settings`. Compiler preferences, LSP settings and JVM sizing belong to the client, through `--jvm-arg`, files the client writes into its own `-data`, or LSP (in HTTP mode, the settings file).
- **In HTTP mode, stdin and stdout don't carry LSP.** The endpoint is the client (§11), and the "stdout belongs to LSP" rule below applies only without HTTP mode.
- It replaces itself with the `java` process, as the stock launcher does on POSIX, so the LSP client's stdin and stdout connect straight to JDT LS.
- **Boot delegation is merged, never replaced.** If the client passes its own `-Dorg.osgi.framework.bootdelegation=…` through `--jvm-arg`, the launcher passes one property: the client's packages plus the boot helper's. The last `-D` would otherwise win, and losing the boot helper's package breaks m2e itself, which the checkpoint (§6) can't catch.
- **stdout belongs to LSP.** The launcher and its checks write only to stderr; anything on stdout before `exec` corrupts the LSP stream.
- **Nothing is written into the install,** including Python bytecode: the launcher doesn't write `__pycache__` for itself or for any module it imports from the install, and it never runs the stock `eclipse/bin/jdtls`, which would.

## 6. The agent

Built from the spike's `Spike.java` and `SpikeAgent.java`, with the behavior of the spike's `fix` mode. FINDINGS "Result" items 1–3 are the specification.

- **Always on.** There are no modes and no off switch in the product. `trace`, `names`, `fix` and `mavenRequester` were switches for the experiment. Anything like them lives only in test code.
- **Naming.** Each Eclipse project is named `<worktree-dir>.<name>`, where `<name>` is m2e's name, always prefixed: m2e computes that name afresh from the POM on each creation, so it never already carries the prefix, and skipping names that look prefixed would make `harness-app` and `wt-a.harness-app` collide.
- **Worktree identity.** The worktree is the nearest ancestor of the project's `pom.xml` that contains `.git`, whether that's a file (a linked worktree) or a folder (a main checkout). Nothing is passed in by the launcher or client: folders added at runtime work the same way. The spike's `spike.worktrees` property is dropped.
- **Outside any worktree** (no `.git` ancestor), the agent changes nothing: the project keeps m2e's name, and lookups it requests keep m2e's choice. Same for a requester that can't be determined.
- **Resolution.** When several workspace POMs share a groupId, artifactId and version, prefer the one in the requester's worktree, using the lookups and requester hooks listed in FINDINGS.
- **The hooks.** These six methods, and no others, are the product's hook targets (FINDINGS "Result"):

  | Class | Method | Role |
  | --- | --- | --- |
  | `org.eclipse.m2e.core.internal.project.ProjectConfigurationManager` | static `getProjectName(ProjectImportConfiguration, Model)` | naming |
  | `org.eclipse.m2e.core.internal.project.registry.EclipseWorkspaceArtifactRepository` | `getWorkspaceArtifact(String, String, String)` | resolution |
  | `org.eclipse.m2e.core.internal.project.registry.BasicProjectRegistry` | `getProjectFacade(String, String, String)` | resolution |
  | `org.eclipse.m2e.jdt.internal.DefaultClasspathManagerDelegate` | `populateClasspath` (4 arguments) | requester |
  | `org.apache.maven.model.building.DefaultModelBuilder` | `readParentExternally` (3 arguments) | requester |
  | `org.apache.maven.project.DefaultProjectBuilder` | `initParent` (5 arguments) | requester |

  The spike's `create` and `ClasspathDescriptor` hooks only logged, and are dropped. `getProjectName` is called only from `create()` (FINDINGS run 02), so its diagnostic event also marks a project creation.
- **Two jars.**
  - **Agent jar:** `premain` and advice, with a manifest that names Byte Buddy as `Class-Path: byte-buddy-<version>.jar`, resolved next to it in `lib/`, with the version from the root POM (§3). Byte Buddy is **not bundled**. The spike always bundled it, so confirm loading through `Class-Path` with a real run first.
  - **Boot jar:** the helper the advice calls. The agent jar's manifest lists it with `Boot-Class-Path`, and its package appears in `org.osgi.framework.bootdelegation`. It uses JDK types only in its signatures.
- **Packages:** `org.fractal.jdtls.agent` and `org.fractal.jdtls.boot`. Compiled with `--release 21`, the minimum Java for JDT LS 1.63.
- **Fail loudly.** It reports clearly, on stderr and in the JDT LS log:
  - any hook that isn't in place (FINDINGS "Constraints and risks"), as defined below;
  - **two worktrees in one process with the same prefix,** once per collision. Making folder names unique within one process is the client's rule. The agent only reports a collision, at no ongoing cost.
  - **a hook that fails while running,** for example because an m2e field it reads was renamed by an update. It's reported once per hook, and that call keeps m2e's own result. Reports go to the process's original stderr, captured in `premain`, because JDT LS replaces `System.err` after it starts.
- **A hook is in place only when its method was advised,** not when its class was transformed. Byte Buddy reports a class as transformed even when no method in it matched, so a changed signature after an upgrade would otherwise pass silently. For each target class, the agent counts the methods its matcher selects, and reports a hook whose matcher selects none, or more than one, as soon as the class loads. A class that fails to transform is reported from Byte Buddy's error listener.
- **When a hook that never loaded is reported.** The target classes load lazily, the Maven ones only when the first Maven project is read, so a missing class can't be detected in `premain`. The checkpoint is the first return from `populateClasspath`: by then a Maven project has been read and configured, and every other target class must have been advised, or it's reported. If `populateClasspath` itself is never advised, the agent reports at JVM shutdown every target still not in place, provided at least one other hook was. A workspace with no Maven projects reaches neither point and reports nothing, which is correct: the hooks don't matter there. T001.04 confirms with real runs, on a fresh import and on a restart, that every target class has loaded by the checkpoint.
- **An optional diagnostic log,** off by default, enabled by the system property `fractal.jdtls.diagnosticLog`, which names the file. One JSON line per event, which the integration suite asserts on (§8):
  - each hook in place, and each hook not in place;
  - each naming decision: the POM, m2e's name and the result;
  - each GAV lookup at either resolution site: the site, the GAV, the requester (or none), the candidate POMs, m2e's choice and the final choice;
  - each prefix collision.
- **Must be present on every start.** A workspace created without the agent needs a fresh `-data` to use it (FINDINGS runs 06 and 07). Document this for users.

## 7. Licensing

- This project's code is **Apache-2.0**: a `LICENSE` file with the standard text, a `NOTICE` with Chris's copyright line, and an `SPDX-License-Identifier: Apache-2.0` line in each source file.
- No third-party code is redistributed. Eclipse's files and Byte Buddy are downloaded by the installer under their own licenses, so the zip needs no third-party notices.

## 8. Testing and the release gate

- **Unit tests** (pytest) cover `install.py`, `bin/jdtls` and `tools/pin.py` against a scratch home and a local fake download server:
  - checksum mismatches;
  - interrupted installs;
  - idempotency;
  - `--verify` reports;
  - Java detection and the minimum version;
  - the exact `java` command line;
  - default `-data` keying.

  These tests never touch the real `~/.local/share`, `~/.cache` or `~/.m2`.
- **Integration tests** port the spike's driver and fixture:
  - they run real JDT LS, installed by `install.py` from a built release zip into a scratch root, and launched through `bin/jdtls`;
  - scenarios: import, edits, reimport and restart across a main checkout and two linked worktrees;
  - the results must match FINDINGS run 05 (each worktree resolves its own modules, with the right diagnostics) and run 06 (restart keeps names and classpaths);
  - they observe JDT LS through LSP (`java.project.getAll`, `java.project.getClasspaths`, diagnostics) and through the agent's diagnostic log (§6), which the tests enable. The log is what shows that every lookup had a requester and stayed in its worktree, and that a restart created no projects (no naming events);
  - a **control run** without the agent, through a test-only launch path, must reproduce the baseline failure (run 01). That proves the fixture still shows the problem.
- **Integration tests use their own Maven local repository,** never `~/.m2`. Each run writes a Maven `settings.xml` whose `localRepository` is a scratch cache that persists between runs, and passes it to JDT LS as the LSP setting `java.configuration.maven.userSettings`. The first run downloads the fixture's plugins from Maven Central, so the suite needs network access, in CI too. Confirm that the scratch repository fills with the fixture's plugins.
- **Keep fixture POMs as `pom.xml.fixture`,** materialized only into scratch folders, so no IDE or language server on this checkout imports them (FINDINGS "IDE side effect").
- A downloaded tarball may be cached for tests in a scratch cache, keyed by its SHA-256.
- The agent's own Maven build is ordinary development tooling and uses `~/.m2` as usual.
- **`mise run validate`** is the full gate: unit tests, the agent build, building the release zip, and the integration suite.

## 9. Releasing

- **A GitHub Actions workflow, triggered by a pushed tag:**
  1. checks that the tag is `v<release>`, with `release` from `pin.json` (§2), and fails otherwise; then builds the release;
  2. runs `mise run validate`;
  3. creates a **draft** release for the tag;
  4. attaches the zip;
  5. publishes.
- **Turn on immutable releases** for the repository. That's a setting Chris changes.
- **Agents never tag, push or publish.** The release task prepares and dry-runs everything locally. Chris tags.

## 10. Requirements on consumers

These belong to whoever launches JDT LS. For Fractal Harness, they're recorded here until Harness tracks them:

- **Folder names must be unique** among the worktrees that share one JDT LS process. In HTTP mode, the endpoint refuses to add a worktree whose folder name is already in use (§11).
- **The launcher owns the lifecycle:** how many processes, sharing scope, and when they start and stop.
- **One client per process.** A shared process needs one owner of its LSP connection. In HTTP mode, that owner is the endpoint (§11), and sessions share the process through its API.
- **Adding and removing workspace folders at runtime** (`workspace/didChangeWorkspaceFolders`) wasn't tested by the spike. Any sharing design must test it first. T002.01 tests it, and the endpoint's worktree calls depend on it (§11).

## 11. The HTTP endpoint

The endpoint lets one JDT LS process serve many LLM sessions. Fractal Harness decides which worktrees share a process, routes each session's requests to it, and turns the API into MCP tools. The endpoint, inside JDT LS, does everything that needs JDT LS: it tracks how worktrees and modules map to Eclipse projects, keeps JDT LS in step with the disk, handles all threading and queuing, and gives errors an LLM can act on. The harness only makes blocking calls.

The decisions below were made by Chris on 2026-11-07, who approved the planner's proposals the same day and left the Java name syntax to the planner. T002.01's spike may change the mechanisms in "Inside JDT LS". Its findings go to `.context/research/jdtls-http-spike.md`, cited below as HTTP-FINDINGS.

### Why it's in-process

- **No extra processes,** even lightweight ones. Everything runs inside JDT LS, where the endpoint has full control.
- **Better integrations than LSP alone.** Inside the process, the endpoint can:
  - join Eclipse's build jobs instead of guessing when work is done;
  - read problem markers instead of keeping a cache of pushed diagnostics;
  - resolve Java names with JDT search;
  - create and delete Eclipse projects with the workspace API.
- **Why not stdio or an existing wrapper:**
  - stdio has one ordered stream and one owner, and JDT LS stops when the stream closes;
  - generic LSP-to-MCP bridges are position-based, have one session per server, don't understand worktrees or the agent's naming, and usually start a JDT LS per project. That's the cost this project exists to avoid (README).
- **The cost:** more coupling to JDT LS internals, pinned per release like the agent (§6); and a defect in the endpoint affects every session that shares the process. Timeouts and loud failures (below) contain it.

### Inside JDT LS

- **An OSGi bundle,** `lib/fractal-jdtls-endpoint.jar`, our own Apache-2.0 code, shipped in the release zip and listed in `release-files.json` (§4). Package `org.fractal.jdtls.endpoint`. It's named for what it is, the endpoint; "tools" would collide with Fractal Tools, Fractal Agent Tools and the MCP tools Harness builds from it.
  - **No new third-party code.** HTTP comes from the JDK's `com.sun.net.httpserver` (module `jdk.httpserver`), and JSON from the Gson bundle in the pinned JDT LS. lsp4j, LTK, `jdt.core.manipulation`, m2e and Eclipse resources also come from the pinned JDT LS, imported by the bundle.
  - **It compiles against the pinned tarball's jars,** downloaded and verified against `pin.json` into a build cache. Many of them, including `org.eclipse.jdt.ls.core`, aren't on Maven Central. They're never committed or redistributed (§1). There's no real alternative: `org.eclipse.jdt.ls.core` isn't published anywhere else, and compiling against the exact runtime jars is what proves the bundle's imports match the pinned release. Reflection alone would trade compile errors for runtime ones.
- **The endpoint is JDT LS's only LSP client, connected inside the JVM.** The LSP connection never leaves the process: no TCP socket, no named pipe that another process could open. JDT LS's own request handling and threading run unchanged; the endpoint sends it LSP requests and answers its requests back to the client (`workspace/configuration`, `workspace/applyEdit`, `client/registerCapability`, `window/*`).
  - **How the bundle loads before any client exists, and how the connection is made, is the spike's first question** (HTTP-FINDINGS). The candidates:
    - adding the bundle to Equinox's start list, through the writable configuration area or `osgi.bundles`, with the stock list kept;
    - an agent hook on JDT LS's connection stream factory that hands it in-JVM pipes;
    - JDT LS's own client-socket or pipe modes, only if the other end provably can't be reached from outside the JVM.
  - **Process lifetime:** in HTTP mode, JDT LS must not exit when stdin closes or when a parent process ID ends. It stops on `POST /v1/shutdown`, or when the harness kills the process it started.
- **Direct Eclipse API, for what LSP does badly:** refreshing resources, joining build jobs, reading markers, resolving names, creating and deleting projects, and writing refactoring edits under the workspace lock.

### Launching

- **One opt-in flag on the existing launcher:** `bin/jdtls --http-port-file <file>`, with the other options as in §5. Without the flag, the launcher behaves exactly as §5 says.
- **The token** comes from the environment variable `FRACTAL_JDTLS_HTTP_TOKEN`, never the command line, which any user can read through `/proc`. The launcher refuses to start in HTTP mode without a token of at least 32 characters, and removes it from the environment JDT LS sees once the endpoint has read it.
- **Listening:** only on `127.0.0.1`, on port 0 (the kernel picks a free port, so there's no race between choosing and binding). Once it's listening, the endpoint writes `{"port", "pid", "release", "api"}` to the port file, atomically (write and rename) and with mode 0600. The harness waits for the file. Every startup failure is reported on stderr, and the process exits non-zero.
- **The LSP settings belong to the harness (§1).** The harness passes them as a JSON file, `--http-settings <file>`. The endpoint sends them in `initialize` and answers `workspace/configuration` from them. The endpoint fixes only the settings its contract depends on, and the API documents those; the harness can't override them.
- **The agent is always added,** as in §5. The endpoint relies on the agent's naming and resolution.

### Security

- **Every request needs `Authorization: Bearer <token>`,** compared in constant time. A missing or wrong token gets `401` with no detail. The token is never logged or echoed back.
- **`Host` must be `127.0.0.1:<port>`.** Requests with an `Origin` header are refused, so a browser page can't reach the endpoint through DNS rebinding.
- **HTTP only, no TLS:** the traffic never leaves the machine.

### The contract

- **JSON over HTTP/1.1, versioned under `/v1/`.** Operations are `POST` with a JSON body. The `GET` calls take no session context: `/v1/status` and `/v1/openapi.json`.
- **The OpenAPI 3.1 description is the contract.** It's committed with the bundle and served at `/v1/openapi.json`. Fractal Harness generates its MCP tools from it, and a test fails if any route or error code is missing from it.
- **Every operation names its worktree and module, in the body:**
  - `worktree`: the absolute path of a registered worktree's root;
  - `module`: the folder holding the requesting Maven module's `pom.xml`, relative to that root (`harness-app`, or `.` for the root POM).

  Callers never see Eclipse project names. The endpoint maps the pair to the Eclipse project, and checks on every request that:
  - the worktree is registered;
  - the module's `pom.xml` exists;
  - its project is imported;
  - the agent placed the project in that worktree.

  Any failed check is an error that says what to do. The last check catches a wrong nearest `.git`, such as a stray `/tmp/.git`, as a loud error instead of a silent mismatch.
- **The module is where a request starts. Results never cross worktrees.** A search or rename covers the requesting module and the modules of the same worktree that depend on it. Results from other worktrees are never returned, and a refactoring never edits another worktree's files.
- **Every response that depends on code** carries `sync`, as described in "Keeping in step with the disk".
- **An optional `X-Fractal-Session` header** is recorded in the endpoint's log and nowhere else. The endpoint keeps no state per session.

### Errors

- **Every error is an RFC 9457 problem** (`application/problem+json`), written for an LLM. Its fields:
  - a stable `code` (for example `worktree-not-registered`, `module-not-found`, `ambiguous-member`, `text-not-found`, `text-not-unique`, `files-changed`, `not-ready`, `timeout`);
  - `title` and `detail`: what went wrong, in the caller's terms (worktree, module, Java names, files), never Eclipse's;
  - `hint`: what to do next;
  - `candidates`, when there's a choice to make, written so a candidate can be passed straight back. For example, every overload of an ambiguous method, with its parameter types;
  - `retryable`, and `retryAfter` in seconds.
- **The error catalogue** is part of the OpenAPI description. A test fails if a code that the endpoint can return isn't in it.
- **Unexpected failures** get `500` with code `internal`. The endpoint logs the stack trace and keeps serving; it never returns a stack trace.

### Addressing code

- **Declarations are addressed by Java name:**
  - a type: `com.acme.Foo`, nested types `com.acme.Foo.Bar`;
  - a member: `com.acme.Foo#bar`, or with parameter types `com.acme.Foo#bar(int,java.lang.String)` when it's overloaded;
  - a field: `com.acme.Foo#count`;
  - a package: `com.acme`.

  The rules:
  - **Nested types use `.`,** and `$` is accepted too (`com.acme.Foo$Bar`).
  - **Constructors use the type's simple name:** `com.acme.Foo#Foo(int)`.
  - **Parameter types are optional.** Without them, a name that matches several overloads is ambiguous. With them, the list is comma-separated, and whitespace is ignored. Each type may be simple (`String`) or fully qualified (`java.lang.String`).
  - **Type arguments are ignored** (`List<String>` matches `List`). Java can't overload on type arguments alone, so matching on the erasure loses nothing.
  - **Arrays are written `int[]`,** and varargs `String...`, which is the same as `String[]`.
  - **A name that matches both a field and a method,** or a simple parameter type that matches two types (`Date`), is ambiguous.
  - **Anonymous classes, lambdas and local variables have no name:** they're addressed by text anchors (below).

  An ambiguous name is an error that lists every candidate in the fully qualified form, which always resolves. (Syntax decided by the planner on 2026-11-07, as Chris delegated; to review.)
- **Code without a name** (a local variable, an expression, statements to extract) is addressed by a file, relative to the module, and `text`: the exact source text, which must occur exactly once in that file, or once within a named member. It's the same idea as an editor's `old_string`. Text found nowhere, or more than once, is an error that shows where it was found.
- **Positions appear only in the LSP passthrough.**

### Keeping in step with the disk

Stability comes before speed. Sessions edit files with their own tools and with shell commands (`git checkout`, `sed`, generated sources), so the endpoint can't be told about every change, and a missed change is silent staleness: FINDINGS run 07 showed diagnostics that were stale and wrong at once. T001.06's review finding R1 showed that waiting for the server to go quiet is a race. So:

- **No file watcher and no timing heuristics.** inotify has watch limits, can overflow and drop events, and doesn't work on WSL2's `/mnt` paths.
- **The endpoint reconciles with the disk at fixed points.** Before every operation that depends on code:
  1. refresh the scope from disk with Eclipse's `refreshLocal`: the requesting module for queries, the whole worktree for builds and refactorings;
  2. send changed POMs through m2e's project update;
  3. join the resulting build jobs;
  4. answer.

  The harness may also name the paths it changed (`changedPaths`), which narrows the refresh. They never replace it. The refresh scope for queries is the spike's to measure (HTTP-FINDINGS), and comes back for review in T002.02.
- **Problems come from Eclipse's markers,** read after the build has been joined. Diagnostics that JDT LS pushed earlier are never used.
- **Every such response reports `sync`:** files found changed, POMs updated, and milliseconds spent refreshing and building. That makes the cost and any drift visible.
- **If refreshing proves too slow** on a real-sized tree (T002.01 and T002.05 measure it), the numbers go to Chris. The endpoint doesn't quietly switch to a watcher.
- **The autobuild setting** is chosen by the spike: autobuild on with the jobs joined, or off with an explicit incremental build at each fixed point, whichever gives the same result every time (HTTP-FINDINGS).

### Worktrees

- **Worktrees are added and removed at runtime,** as sessions need them (`POST /v1/worktrees/add`, `/remove`, `/list`). The endpoint sends `workspace/didChangeWorkspaceFolders` and waits until the import is done, or returns `not-ready` with `retryAfter` if the caller asked not to wait.
- **Adding is refused** when the folder isn't a Git worktree root, or when its folder name is already used by a registered worktree (§10).
- **Removing deletes the worktree's Eclipse projects without deleting their files,** and leaves nothing of them in m2e's registry. A worktree already deleted from disk can still be removed.
- **Listing** returns each worktree with its modules (path, GAV, import state), so the harness can check what the endpoint sees.

### Builds and problems

- **`build`** reconciles the worktree with the disk, runs an incremental build (or joins autobuild), and returns the requesting module's problems, and optionally the worktree's. Problems can be filtered by severity. Each problem has: file, 1-based line and column, severity, message, and the source line.
- **`problems`** returns the same without forcing a build, after reconciling with the disk.

### Refactorings and edits

- **JDT LS computes a refactoring as a `WorkspaceEdit`** (text edits per file, plus file creates, renames and moves). The LSP client applies it, and here the endpoint is the client. The harness never applies edits.
- **Applying edits:**
  1. reconcile the worktree with the disk;
  2. compute the edit;
  3. under the workspace lock, check that every affected file still has the content the edit was computed from;
  4. write the files;
  5. refresh, build, and return a unified diff and the resulting problems.

  If a file changed in between, it's `files-changed`, and nothing is written. `dryRun: true` returns the diff without writing. Git is the undo.
- **T002's refactorings:**
  - rename of a type (with its file), method, field, local variable or package;
  - organize imports;
  - extract local variable, extract constant, extract method;
  - inline local variable.

  Choices that JDT LS asks the user for interactively become request fields.
- **Later series:** move, change signature, the rest of JDT LS's code actions, quick fixes and source generation, then the refactorings Eclipse has but JDT LS doesn't expose (pull up, push down, encapsulate field), through LTK.

### Navigation and search

- Definition, references, implementations, type hierarchy, call hierarchy, and symbols in a file or a worktree, plus Javadoc and the signature of a named element.
- Answers are in the caller's terms: Java names, files relative to their module, and 1-based lines with the source line.

### The LSP passthrough

- **`POST /v1/lsp`** takes `worktree`, `module`, an LSP `method` and its `params`. It reconciles with the disk first and sends the request to JDT LS unchanged.
- **It's an expert escape hatch.** It uses LSP's own URIs and 0-based positions, its results aren't filtered by worktree, and its errors are JDT LS's raw errors, wrapped in a problem with code `lsp-error`.
- Requests that would break the endpoint's ownership of the connection are refused: lifecycle methods, folder changes, and document synchronization.

### Threading and queuing

The endpoint handles all of it. The harness makes one blocking call per operation.

- **A fixed pool of HTTP worker threads,** separate from JDT LS's threads. A request beyond the pool's queue gets `503` (`busy`) with `retryAfter`. T002.09 sets the pool and queue sizes from measurements, and brings them to Chris for review.
- **Reads run concurrently.** Operations that change the workspace run one at a time, under Eclipse's workspace scheduling rule: refactorings that apply, worktree changes, and the refresh-and-build at each fixed point. Whether reads of one worktree must wait while another worktree imports is measured in T002.01 and T002.09.
- **Readiness:** until JDT LS has finished starting, and while a worktree is importing, requests for it wait, up to their timeout, or get `not-ready` with `retryAfter`, as the caller asks.
- **Every operation has a timeout,** with a default and an optional `timeoutMs` in the request. When the timeout passes or the caller disconnects, the endpoint cancels the LSP request (`$/cancelRequest`) or the Eclipse job's progress monitor, and returns `timeout`.
- **JDT LS never waits on HTTP I/O.** The endpoint answers JDT LS's requests back to the client from its own state, without calling out.

### Status and shutdown

- **`GET /v1/status`:** the release, the API version, whether JDT LS is ready, the registered worktrees with their import state, and the operations in flight. `?wait=ready&timeoutMs=` blocks until JDT LS is ready.
- **`POST /v1/shutdown`:** answers first, then shuts JDT LS down through LSP `shutdown` and `exit`, so it saves the workspace state. The process then exits.

### Testing

- **JUnit, in the bundle's module:** name parsing, text anchors, token and `Host` checks, mapping errors to problems, and the OpenAPI description's completeness.
- **Integration (pytest), through HTTP only:**
  - against real JDT LS, installed from a built release zip as in §8, on a fixture of a main checkout and linked worktrees;
  - worktrees added and removed at runtime;
  - run 05's per-worktree resolution and diagnostics reproduced through `build`;
  - edits made on disk, and with `git checkout`, detected without being reported;
  - each T002 refactoring applied and built;
  - concurrent sessions in different worktrees.

  The fixture grows Java sources for navigation and refactoring (overloads, cross-module references). Its POMs stay `pom.xml.fixture` (§8).
