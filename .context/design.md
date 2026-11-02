# Fractal JDT LS design

What Fractal JDT LS delivers, and how it is built, verified and released. [README.md](../README.md) says why it exists. The design and the code are authoritative. Task files only order the work.

The decisions below were made by Chris on 2026-11-01, starting from the JDT LS worktree spike. The spike is seeded at [.context/spike/jdtls-worktree-spike/](spike/jdtls-worktree-spike/), and its [FINDINGS.md](spike/jdtls-worktree-spike/FINDINGS.md) is the evidence the agent is built on.

## 1. Scope and boundaries

- **The product:** a verified install of one official Eclipse JDT LS release, and a launcher that is a drop-in for the stock `jdtls` and always adds this project's Java agent. The agent keeps each Git worktree's Maven projects separate inside one JDT LS process.
- **Unopinionated.** These belong to the tool that launches it, not to this project:
  - how many JDT LS processes run, which worktrees share one, and when they start and stop;
  - any broker that lets several clients share one process;
  - compiler preferences, such as errors-only diagnostics or keeping `target/classes`;
  - LSP settings and JVM sizing.

  Fractal Harness is the first consumer and owns all of that for itself.
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
    LICENSE  NOTICE
  ```
- **Installed layout:** one folder per release, containing everything ("option A"):

  ```
  ~/.local/share/fractal-jdtls/1.63.0-1/
    bin/  lib/  pin.json  LICENSE  NOTICE     copied from the zip
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
   - signed by the Eclipse Foundation (`jarsigner -verify`);
   - byte-identical to an artifact on Maven Central (by the artifact's published hash);
   - or **neither**: trusted only through the tarball's checksum.
4. Downloads Byte Buddy from Central, records its hash, and verifies its PGP signature (`.asc`) when `gpg` and the maintainer's key are available.
5. Writes `pin.json` and a pin report. The report goes into the repository as the evidence of how each file was checked.

Hashes we compute ourselves only prove a file hasn't changed since. **What a file should be comes from those outside checks.** The pin is the record of them.

### Verified facts for JDT LS 1.63.0

Checked on 2026-11-01.

- The tarball is `jdt-language-server-1.63.0-202610191322.tar.gz`, SHA-256 `b1f2abe0b3cccb462cf64fb4a5587c7d09df60cf8be7748b152cbd89d018bdc4`. Eclipse publishes `.sha256` only, with no PGP signature.
- **58 of 110 jars** in `plugins/` pass `jarsigner -verify`, signed by `CN=Eclipse Foundation`. **52 are unsigned,** including all of m2e (`m2e.core`, `m2e.jdt`, `m2e.maven.runtime`, `m2e.apt.core`).
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
- Errors say exactly what failed and what to do. It exits non-zero on any failure.

## 5. The launcher: `bin/jdtls`

**A drop-in for Eclipse's stock `bin/jdtls`.** It accepts the same command line: `--java-executable`, repeatable `--jvm-arg=…`, `-data <dir>`, and any other arguments passed through to JDT LS. It builds the same `java` command, with the Equinox launcher, the shared configuration and `--add-opens` (see `bin/jdtls.py` in the Eclipse tarball). It also always adds the agent:

```
-javaagent:<release>/lib/fractal-jdtls-agent.jar
-Dorg.osgi.framework.bootdelegation=<the boot helper's package>
```

- **Java.** It uses `--java-executable`, then `JAVA_HOME`, then `java` on `PATH`, the stock order. It **only checks** that the version meets the pin's minimum, and fails with a clear message otherwise. Choosing a JDK that suits the project is the client's job.
- **Workspace (`-data`).** Whatever the client passes. When none is given, the default is `${XDG_CACHE_HOME:-~/.cache}/fractal-jdtls/ws/<sha1 of the canonical working directory>`, keyed on the full path. The stock launcher keys on the folder name only, which lets two checkouts with the same folder name share one workspace.
- **Configuration area.** It passes a writable `-configuration` outside the install, kept per workspace (next to or inside `-data`), so the install stays read-only and verifiable. Confirm with a real run that nothing is written inside the install.
  - The stock `bin/jdtls.py` already points Equinox at the install's read-only shared configuration (`-Dosgi.sharedConfiguration.area=<install>/config_linux`, `…area.readOnly=true`, `-Dosgi.configuration.cascaded=true`), but passes no `-configuration`. Equinox then puts its writable area at `<install>/configuration`. Keeping the stock properties and adding `-configuration <writable folder>` is enough; an empty folder works because the cascade supplies `config.ini`.
- **Verification at launch.** Before starting JDT LS, it checks the install against `pin.json`. If the cost is more than about half a second, measure it, record it and raise it with Chris.
- **No other options.** Compiler preferences, LSP settings and JVM sizing belong to the client, through `--jvm-arg`, files the client writes into its own `-data`, or LSP.
- It replaces itself with the `java` process, as the stock launcher does on POSIX, so the LSP client's stdin and stdout connect straight to JDT LS.

## 6. The agent

Built from the spike's `Spike.java` and `SpikeAgent.java`, with the behavior of the spike's `fix` mode. FINDINGS "Result" items 1–3 are the specification.

- **Always on.** There are no modes and no off switch in the product. `trace`, `names`, `fix` and `mavenRequester` were switches for the experiment. Anything like them lives only in test code.
- **Naming.** Each Eclipse project is named `<worktree-dir>.<name>`.
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
- **A hook is in place only when its method was advised,** not when its class was transformed. Byte Buddy reports a class as transformed even when no method in it matched, so a changed signature after an upgrade would otherwise pass silently. For each target class, the agent counts the methods its matcher selects, and reports a hook whose matcher selects none, or more than one, as soon as the class loads. A class that fails to transform is reported from Byte Buddy's error listener.
- **When a hook that never loaded is reported.** The target classes load lazily, the Maven ones only when the first Maven project is read, so a missing class can't be detected in `premain`. The checkpoint is the first return from `populateClasspath`: by then a Maven project has been read and configured, and every other target class must have been advised, or it's reported. If `populateClasspath` itself is never advised, the agent reports at JVM shutdown every target still not in place, provided at least one other hook was. A workspace with no Maven projects reaches neither point and reports nothing, which is correct: the hooks don't matter there. T001.04 confirms with real runs, on a fresh import and on a restart, that every target class has loaded by the checkpoint.
- **An optional diagnostic log,** off by default, enabled by a system property that names the file. One JSON line per event, which the integration suite asserts on (§8):
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

- **Folder names must be unique** among the worktrees that share one JDT LS process.
- **The launcher owns the lifecycle:** how many processes, sharing scope, and when they start and stop.
- **One client per process.** A shared process needs one owner of its LSP connection.
- **Adding and removing workspace folders at runtime** (`workspace/didChangeWorkspaceFolders`) wasn't tested by the spike. Any sharing design must test it first.
