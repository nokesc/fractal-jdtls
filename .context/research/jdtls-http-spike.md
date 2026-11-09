# Findings: the HTTP endpoint inside JDT LS

The findings of T002.01's spike ([T002.01](../planning/T002.01.md)), which the [design](../design.md)
cites as HTTP-FINDINGS. The spike's code, an OSGi bundle `org.fractal.jdtls.spike.http` and a Python
driver that ran each question, was in this repository under `.context/spike/` from T002.01 until T002.05
retired it; it remains in Git history (last changed in `e6f8634`). What became of it:

- Its bundle became the endpoint, [endpoint/](../../endpoint/) (design §11): the in-heap pipes, the
  in-JVM LSP client, the HTTP API, the job joins (`Jobs`), and its fixed point and marker reading
  (`DiskSync`, `Markers`), each rebuilt with the decisions taken from these findings.
- Its driver's runs became the integration suite, [tests/integration/](../../tests/integration/):
  its large-tree generator is `large_tree.py`; questions 4 and 5 are `test_http_worktrees.py`,
  `test_imports.py`, `test_http_sync.py` and `test_run05_fresh.py`.

**Where and how it ran.**

- **When:** 2026-11-07/08, on WSL2 (kernel `6.18.33.2-microsoft-standard-WSL2`), with 12 CPUs (Intel
  i7-9750H at 2.60 GHz) and 15.5 GiB of memory.
- **Java:** OpenJDK `25.0.1`, the JVM at `-Xms1G` (the launcher's) and `-Xmx4G` (passed with
  `--jvm-arg`). JDT LS reports 4,096 MB of maximum heap and 12 processors. Every timing below is from
  that machine and that heap.
- **The install:** every run used a scratch install of the `1.63.0-1` release zip (JDT LS `1.63.0`, m2e
  core `2.8.0.20260815-0704`, T001's agent). It was launched through the install's own `bin/jdtls`, with
  a scratch `-data` and a scratch `user.home`, and with the integration suite's Maven local repository
  (design §8).
- **The worktrees:** made from `tests/integration/fixture/` in scratch folders: a main checkout and the
  linked worktrees `wt-a`, `wt-b` and `wt-c`, all at one commit.
- **The large tree:** generated in scratch by the driver's generator (now `tests/integration/large_tree.py`). It has 50 modules of 200
  classes each (10,001 Java files with `m00`'s `Base`). Every module depends on `m00` and on the module
  before it.

## Result

**The design's in-process endpoint works on JDT LS 1.63.0, with no product change and no agent hook.**
Questions 1 to 4 pass, and so do 5 to 8.

1. **Loading.** In HTTP mode, the launcher adds the bundle to Equinox's start list with a system
   property, `-Dosgi.bundles=<the install's config.ini list>,reference:file:<jar>@4:start`. Equinox
   starts it before JDT LS's application runs. Nothing is written inside the install.
2. **The connection.** The bundle's activator sets `System.in` and `System.out` to two pipes that live
   only on the Java heap. JDT LS's application reads both once, when it starts
   (`LanguageServerApplication.prepareStreams`), so the bundle is its only LSP client.
   - Nothing outside the JVM can reach the connection: it has no file descriptor.
   - `-DwatchParentProcess=false` keeps JDT LS up when its parent ends. JDT LS's real stdin isn't read
     at all.
3. **HTTP.** `Import-Package: com.sun.net.httpserver` resolves from the system bundle as it is.
4. **Worktrees at runtime.** `workspace/didChangeWorkspaceFolders` adds and removes worktrees with the
   agent's naming and resolution intact. Removing one deletes its projects, and leaves nothing of them
   in m2e's registry.
   - On a restart, the registered worktrees must be passed in `initialize`. Otherwise JDT LS deletes
     every project at startup, and the next add creates them again.
5. **Disk sync.** These steps at a fixed point gave run 05's errors 20 times out of 20, in one process
   and in 20 fresh ones: `refreshLocal`, m2e's project update for changed POMs, a build, a join of the
   resulting jobs, then the markers.
   - **Autobuild off, with an explicit incremental build, is the recommendation.** It gives the same
     results, nothing builds between fixed points, and each fixed point is 7 to 10 times faster.
   - **Eclipse's lightweight auto-refresh must be turned off** for the files found changed to be
     reported exactly. The markers were right either way.
6. **Threading.** Plain LSP reads stay fast while other worktrees import or build, and none of the LSP
   requests the spike sent waited for the workspace rule.
   - **But a complete endpoint operation waits.** Its fixed point refreshes and builds, and both need the
     workspace root rule. So an operation on a changed worktree waits for another worktree's import or
     build: up to 27 s here (review R3, `q6-complete`).
   - **A rule-free fast path** keeps an unchanged worktree apart: about 12 ms an operation during the
     other worktree's import.
   - `$/cancelRequest` and a cancelled monitor both stop long work within milliseconds.
7. **Refactoring.** `textDocument/rename` and extract method (`java/getRefactorEdit`) produce
   `WorkspaceEdit`s that the endpoint's own writer applies. Each was checked with a refresh and a clean
   build.
8. **Names.** `SearchEngine`, scoped to one worktree's Java projects, never returns another worktree's
   copy of a type. Members are best matched on the `IType` model, with the endpoint's own parameter
   comparison.

**One open problem, for Chris:** in 5 of 31 imports of the large tree, JDT LS's import silently left one
module unknown to m2e, and every module after it failed to build. All 5 had endpoint requests running
during the import; none of the 9 imports without any did. The cause wasn't found, and a restart repaired
it. So the recommendation is that imports run alone (see "An import that silently broke").

## Runs

Each run was `python3 -I runs.py <run>` in the spike's `driver/` folder (in Git history). Its evidence was in
`$FRACTAL_SPIKE_SCRATCH/runs/<run>/`:

- `result.json`;
- the LSP traffic, `spike/lsp-traffic.jsonl`;
- the spike's own log;
- the agent's diagnostic log, `agent.jsonl`;
- JDT LS's log.

The driver's `summary.py` printed the key outputs, and T002.01's evidence file has them.

| Run | Questions | Outcome |
| --- | --- | --- |
| `q1q4` | 1, 2, 4, 6 | Loaded through `-Dosgi.bundles`, under a parent shell that exits after 15 s, with stdin `/dev/null`. Only the HTTP port listens. Folders added and removed at runtime, one of them already deleted from disk. Then two restarts: with the folders in `initialize`, and with none followed by an add. |
| `q1-config-ini` | 1 | Loaded through `osgi.bundles` in the writable configuration area's `config.ini`. Works too. |
| `q1-mode-switch` | 1 | HTTP mode, then plain stdio mode, then HTTP mode again, on one `-data`. Plain mode never starts the bundle. |
| `q2-parent-watch-control` | 2 | The control: without `watchParentProcess=false`, JDT LS exits 35 s after its parent does. |
| `q2-baseline-sockets` | 2 | The same install as plain stdio JDT LS: it holds the same unnamed unix socket as the endpoint's JVM, so that socket is stock. |
| `q5` | 5 | 20 reset-and-edit cycles with autobuild on (joined), and 20 with autobuild off (an explicit build). Also a `git checkout` of another commit, a POM that adds a dependency, a change that keeps the file's mtime, and refresh costs on the fixture. |
| `q5-fresh` | 5 | Run 05's edits on 20 fresh copies, each in a fresh JDT LS with a fresh `-data`. |
| `q5-absorb` | 5 | Autobuild off, 4 × 20 cycles: a workspace or worktree build, with lightweight auto-refresh on or off. Counts the fixed points that found fewer changed files than were written. |
| `q5-git-internals` | 5 | What a main checkout's refresh finds after Git runs in its worktrees. |
| `q6` | 5, 6 | Reads while `wt-b` imports, while the large tree imports, and during a full build. Requests sent while the workspace rule is held. Cancellation. Refresh and sync costs on the large tree. |
| `q6-import-repeat` | 6 | The large tree imported into a fresh `-data` 10 times, alternately with and without reads. A second set of 6 did the same with `q6`'s steps (`wt-b` added first, a freshly generated tree each time). |
| `q6-broken-repair` | 6 | On a copy of a broken large-tree import: a restart, then a remove and add. |
| `q6-complete` | 6 | Review R3: complete endpoint operations on `main` (its fixed point, then a read), while the large tree imports and while it builds. Four variants: the fixed point as spiked, a join of only what the fixed point scheduled, and a rule-free fast path with `main` unchanged and changed. |
| `q6-import-repeat-fastpath-ops` | 6 | 6 more imports of the large tree into a fresh `-data`, with `q6-complete`'s fast-path operations on `main` running during each one. |
| `r2-cleanup` | — | Review R2: the driver kills a JVM that never writes its port file, and any JVM still running when the driver fails. |
| `q78` | 7, 8 | Names resolved in one worktree; rename and extract method applied and built; JDT LS's requests to the client. |
| `q7-apply-edit-off` | 7 | With `workspace.applyEdit: false`, organize imports returns its edit instead of pushing it. |

## Answers

### 1. Loading

- **Equinox's start list, through the system property `osgi.bundles`, works.** The launcher's
  `--jvm-arg` passes `-Dosgi.bundles=` with two parts:
  - the `osgi.bundles` value from the install's `eclipse/config_linux/config.ini`, with Java properties
    escaping removed;
  - then `,reference:file:<jar>@4:start`.

  The system property wins over `config.ini`, and the stock list is kept because it's copied.
  - The activator runs in Equinox's start-level thread, before the application, and takes 135–205 ms.
  - JDT LS first reads the endpoint's pipe 291–494 ms after the activator starts. The endpoint writes
    `initialize` at 91–148 ms, and the pipe buffers it until then.
  - JDT LS answers at 1.6–2.1 s, and the port file appears 4.2–4.6 s after launch.
  - Equinox records the bundle in the writable configuration area,
    `<data>/.fractal-jdtls/<release>/configuration`, as `initial@reference:file:…`.
  - The install's `--verify` passed after every run: "all 133 files are present, with nothing extra."
- **`osgi.bundles` in the writable configuration area's `config.ini` works too.** That's
  `<data>/.fractal-jdtls/1.63.0-1/configuration/config.ini`, holding only `osgi.bundles=<stock
  list>,reference\:file\:<jar>@4\:start`. Equinox's cascade supplies the rest from the install's
  read-only `config.ini`. The port file appeared after 4.3 s.
- **Switching modes on one `-data`.**
  - A plain stdio start after an HTTP-mode start doesn't start the bundle: no port file, no spike log,
    and JDT LS answers `initialize` on stdout.
  - HTTP mode started again afterwards reads the endpoint's pipe as before.
- **Recommendation: the system property.**
  - It writes nothing.
  - The stock list comes from the verified install at every launch, so no copy can go stale.
  - It can't leak into a plain-mode start. A `config.ini` written into `-data` would be read again by
    a later plain-mode start on the same `-data`, and would start the bundle there, unless the launcher
    deleted it. That wasn't run; it follows from how Equinox reads the configuration area.

### 2. The in-JVM client

- **The connection.** `LanguageServerApplication.prepareStreams` captures `System.in` and `System.out`
  when the application starts. The activator has already replaced both with `HeapPipe`s. JDT LS's stdio
  mode, `ConnectionStreamFactory.StdIOStreamProvider`, then uses them through `application.getIn()` and
  `getOut()`.
  - After `initialize`, the endpoint checks the identity: `JavaLanguageServerPlugin.getLanguageServer()`
    returns its pipe from `getIn()` and its print stream from `getOut()`. Both were `true` in every run.
    A future JDT LS that captured stdio earlier would fail this check loudly.
  - `java.io.PipedInputStream` isn't usable: it fails with "Write end dead" once the last thread that
    wrote to it ends, and lsp4j and JDT LS write from pooled threads. `HeapPipe` is a small synchronized
    queue of byte chunks, with no file descriptor.
  - The other §11 candidates weren't needed:
    - an agent hook on `ConnectionStreamFactory`, which wasn't built;
    - JDT LS's client-socket mode (`CLIENT_PORT`), whose TCP port on `127.0.0.1` is reachable by any
      local process;
    - its `--pipe=` mode, a Unix-domain socket that any process of the same user can connect to.
- **What's listening** (`ss -ltnpx`, filtered to the JVM's PID): one TCP socket only,
  `LISTEN [::ffff:127.0.0.1]:<port>`, which is the HTTP API.
  - **The JVM's other sockets:**
    - one unnamed, connected unix stream socket (`u_str ESTAB`, peer `0`, no path);
    - one socket that is in neither `/proc/net/unix` nor `ss`'s TCP list.
  - **The unix socket is stock.** Plain stdio JDT LS, started without the bundle
    (`q2-baseline-sockets`), holds the same unnamed unix socket. Nothing can connect to it.
  - **The other socket doesn't listen.** Stock JDT LS also holds a non-listening socket: an outgoing
    HTTPS connection (`ESTAB` to `104.16.72.101:443`). The endpoint's third socket wasn't identified
    further.
  - **No pipes.** The JVM's two `anon_inode`s are the HTTP server's epoll and eventfd.
- **`initialize`** has `workspaceFolders: []`, `rootUri: null` and `processId: null`.
  `initializationOptions.settings` comes from the settings file, `java.configuration.maven.userSettings`
  included.
  - With no folders, JDT LS logs "No workspace folders or root uri was defined. Falling back on
    <-data>".
  - It imports nothing from there: no projects, and an empty m2e registry.
- **Lifetime.**
  - Started under a shell that exits after 15 s, with stdin `/dev/null`, JDT LS was still alive at the
    end of `q1q4`, minutes later.
  - The control run, without `watchParentProcess=false`, logged "Parent process stopped running, forcing
    server exit" and exited 35 s after the parent did. `ParentProcessWatcher` polls every 10 s, and acts
    after 30 s without activity.
  - `processId: null` in `initialize` stops JDT LS from watching a client PID as well.
  - The spike's activator sets the property itself, before the application starts. The launcher could
    pass it instead.
- **JDT LS's requests back to the client** are answered from the endpoint's own state, on its own
  thread:
  - `window/workDoneProgress/create`: `null`;
  - `workspace/configuration` (a code action asks for `java.format.tabSize` and
    `java.format.insertSpaces`): from the settings file, `null` where it has none;
  - `workspace/applyEdit` (pushed by `java.edit.organizeImports`): `{"applied": false, "failureReason":
    "the endpoint applies edits itself"}`;
  - `client/registerCapability`, `window/showMessageRequest` and `window/showDocument`: `null`;
  - anything else: error `-32601`.

  With `workspace.applyEdit: false` in the client capabilities (`q7-apply-edit-off`), organize
  imports returns the `WorkspaceEdit` as its result. JDT LS sends no `workspace/applyEdit`, and the
  endpoint applied the edit itself.
- **Framing.** The spike has its own Content-Length framing over Gson's JSON tree, from the pinned
  JDT LS's `com.google.gson` bundle (`LspClient`, about 300 lines with the answers).
  - Why: the passthrough (§11) forwards params unchanged, the traffic log records every message as
    sent, and each answer to JDT LS is a few lines. lsp4j's typed endpoint would add adapters to build
    and maintain, for no gain.
  - `$/cancelRequest` is one notification.
- **The token.**
  - A wrong token gets `401`, and a `Host` of `localhost:<port>` gets `403`.
  - The token came from `FRACTAL_JDTLS_HTTP_TOKEN`, and it stays in `/proc/<pid>/environ` for the
    JVM's whole life. That file is mode `0400`, readable only by the same user. The token isn't in the
    command line.
  - Java can't remove a variable from its own environment, and `exec` fixes the environment. So §11's
    "removes it from the environment JDT LS sees once the endpoint has read it" can't be done as
    written. See the proposals.

### 3. HTTP in OSGi

- **`Import-Package: com.sun.net.httpserver` resolves from the system bundle, with nothing added.**
  - The stock command line has `--add-modules=ALL-SYSTEM`, so `jdk.httpserver` is in the boot layer,
    and Equinox exports the boot layer's packages from the system bundle.
  - `HttpServer`'s class loader is `jdk.internal.loader.ClassLoaders$PlatformClassLoader`.
  - No boot delegation and no `org.osgi.framework.system.packages.extra` is needed.
- **The server** listens on `127.0.0.1`, port 0, with a fixed pool of 8 threads. It writes the port file
  with mode `0600`, through an atomic rename, from inside the activator's 135–205 ms.

### 4. Worktrees at runtime

- **Adding at runtime.** Started with no folders, one `didChangeWorkspaceFolders` added `main` and
  `wt-a`, in 3.6 s (8.8 s the first time the scratch Maven repository was used).
  - **Names.** Six projects, each `<worktree>.<m2e's name>`, each located in its own worktree: six
    `naming` events.
  - **Lookups.** All 10 had a requester, and none had a final choice outside the requester's worktree.
    6 decided between several candidates.
  - **Classpaths.** Each `harness-app` has its own worktree's `harness-contract/target/classes`, as in
    run 05.
- **Knowing the add was handled, without timing.** lsp4j reads messages in order on one thread, and
  calls a notification's handler before it reads the next message. `didChangeWorkspaceFolders`
  schedules JDT LS's "Updating workspace folders" job (`ProjectsManager$2`, rule `R/`) inside that
  handler.
  - So the endpoint follows the notification with a request JDT LS can't answer, `fractal/barrier`.
  - Its error, `-32601 Unsupported request method`, comes back after 2–18 ms. By then the job is
    scheduled, and the endpoint can join it.
- **wt-b added while wt-a is busy.**
  - wt-a was busy with m2e's update of its 3 changed POMs, under the workspace rule. It took 1.1 s.
  - At the barrier, wt-b's folder job was waiting for the workspace rule. It ran from 11.73 to 12.26 s,
    alongside wt-a's update.
  - The add took 1.1 s in all, with three more `naming` events (`wt-b.*`). All 26 lookups had a
    requester and a final choice at home.
- **Removing wt-a** took 1.5 s.
  - Its three projects are deleted with `project.delete(false, true, …)`, so their files aren't deleted.
  - m2e's registry lists only `main`'s and `wt-b`'s POMs.
  - wt-a's 6 files are untouched, apart from the 3 POMs the spike itself had touched.
  - A later lookup came from a change to `wt-b/harness-app/pom.xml`, pushed through m2e. Its 13 lookups
    had candidates from `main` and `wt-b` only.
  - JDT LS's removal also runs `cleanInvalidProjects`, which deletes every project outside the remaining
    root paths. Here that's correct.
- **Metadata left behind.** At import, JDT LS and m2e write 15 files into the worktree: `.project`,
  `.classpath` and `.settings/*`, in every module. Removing the worktree leaves them.
  - With `-Djava.import.generatesMetadataFilesAtProjectRoot=false` (`q1-mode-switch`), none are written.
- **A worktree deleted from disk first.** `wt-c` was added, deleted with `git worktree remove --force`,
  then removed from JDT LS, in 1.4 s. Its projects are gone, m2e's registry has no `wt-c` POM, and
  nothing failed.
- **Restart, with the remaining folders in `initialize`.** Same `-data`, with `workspaceFolders: [main,
  wt-b]`:
  - the same six names, and **no `naming` events**, so no project was created;
  - all 28 lookups at home;
  - classpaths identical to before the shutdown;
  - exit code 0.
- **Restart with no folders, then an add**, on a copy of the same `-data`:
  - JDT LS's startup deleted all six projects (`cleanInvalidProjects`, with only `-data` as a root);
  - the add, 2.6 s, **created them again**: six `naming` events;
  - names and classpaths came out the same.

  A first try at this run, before the join was fixed, exited with code 1. JDT LS's
  `MavenProjectImporter` had scheduled an "Update Maven project configuration" job with no family, and
  `shutdown` cancelled it. Hence "Joining the work" below.

### 5. Deterministic disk sync

The fixed point, `POST /spike/sync` for one worktree:

1. **Refresh.** `refreshLocal(DEPTH_INFINITE)` on the worktree's projects, inside
   `IWorkspace.run(…, root rule)`. A `POST_CHANGE` listener collects the files found changed, build
   output excluded.
2. **POMs.** m2e's update for the projects whose `pom.xml` changed.
3. **Build.** With autobuild on, the join (below). With autobuild off,
   `IWorkspace.build(INCREMENTAL_BUILD)`, then the join.
4. **Markers.** `IMarker.PROBLEM` markers of severity error, from every project under the worktree.

**Run 05's errors, 20 times in one process** (`q5`). Each cycle:

1. reset the three worktrees to the fixture commit (`git checkout -- .`, `git clean -fd`);
2. sync each worktree, and check there are no errors;
3. write run 05's edits to disk without telling JDT LS;
4. sync each worktree, and read the markers.

| Autobuild | Cycles | Errors equal to run 05's | Distinct outcomes | Sync per worktree after the edits, ms (min / median / max) |
| --- | --- | --- | --- | --- |
| on, joined | 20 | 20 | 1 | 994 / 1,046 / 1,111 |
| off, explicit incremental build | 20 | 20 | 1 | 105 / 114 / 163 |

- **The errors, every time:** `wt-a/…/Dispatch.java` lines 3, 7 and 8 (`WorkType` can't be resolved),
  and `wt-b/…/Broken.java` line 5 (the type mismatch). Every reset came back clean.
- **20 fresh copies** (`q5-fresh`): a fresh fixture, a fresh JDT LS and a fresh `-data` each time; the
  import, the edits, a sync, then the markers. All 20 equal run 05's errors, with 1 distinct outcome.
  Adding the three worktrees took 4.1–5.2 s, and a sync after the edits 0.98–1.16 s, with autobuild on.
- **The control.** With autobuild off and no build, the markers after the edits are empty. Markers
  come from the build, so a fixed point must build.
- **A `git checkout` of another commit**, in `wt-a`:
  - the refresh found `added …/Dispatch.java` and `added …/WorkType.java`;
  - the markers showed that commit's one error, `TESTER cannot be resolved or is not a field`;
  - checking the first commit out again found both `removed`, and left no errors.
- **A POM that adds a dependency**, in `wt-b`:
  - with only the source that uses `StringUtils`, there are 2 errors ("The import org.apache cannot be
    resolved");
  - after `commons-lang3` was added to `wt-b/harness-app/pom.xml`, the refresh found the POM changed;
  - m2e updated `wt-b.harness-app` (Status OK, 355 ms, then 656 ms of build), and the errors were gone;
  - the classpath has `commons-lang3-3.17.0.jar`, from the scratch Maven repository.
- **A change that keeps the file's mtime is missed.** `Launcher.java` was rewritten at the same size,
  with its old mtime restored (`os.utime`), as `cp -p`, `rsync -t` and `tar x` do.
  - The refresh found nothing changed, and no error appeared. After a plain `touch`, it found the
    change and the error.
  - Eclipse compares only the file's millisecond mtime (`RefreshLocalVisitor`:
    `info.getLocalSyncInfo() == node.getLastModified()`).
- **The files found changed include `.git` internals.** The main checkout's `.git/` folder is inside
  its root project. After Git runs in the worktrees, `main`'s refresh finds `.git/index`,
  `.git/worktrees/wt-a/index` and `.git/worktrees/wt-b/index` changed (`q5-git-internals`).
  - JDT LS's default resource filter (`java.project.resourceFilters`: `node_modules`, `\.git`) is
    applied only when that preference changes (`ProjectsManager.configureFilters`). Projects imported
    later don't get it.
- **Lightweight auto-refresh takes changes in behind the endpoint's back** (`q5-absorb`). Its default,
  `refresh.lightweight.enabled: true`, refreshes a file that something reads while it's out of sync.
  - With it on, some fixed points found fewer changed files than were written: in `wt-b`, 0 files
    instead of 3. Something had already refreshed them during the fixed point of another worktree.
  - The markers were right in all 80 cycles. Only the report of what changed, and a POM change found
    only through the delta, are affected.

  | Autobuild off, with | Lightweight refresh | Cycles | Errors equal run 05's | Fixed points that found fewer changes |
  | --- | --- | --- | --- | --- |
  | a workspace build | on | 20 | 20 | 4 |
  | a build of the worktree's projects | on | 20 | 20 | 10 |
  | a workspace build | off | 20 | 20 | 0 |
  | a build of the worktree's projects | off | 20 | 20 | 0 |

- **Autobuild: recommend off, with an explicit incremental build at each fixed point.**
  - Both give the same result every time.
  - Joining autobuild costs about 1 s per fixed point, almost all of it waiting. `AutoBuildJob` delays
    a build by up to `MAX_BUILD_DELAY` (1 s) after a recent build.
  - The explicit build costs 100–150 ms on the fixture, and nothing builds between fixed points.
  - Set it with JDT LS's own setting, `java.autobuild.enabled: false`, fixed by the endpoint, so
    JDT LS's preference agrees. The spike set the workspace description directly.
- **What `refreshLocal` costs**, with nothing changed, 10 times each (ms, min / median / max):

  | Scope | Fixture | Large tree |
  | --- | --- | --- |
  | one module | 0.8 / 1.0 / 1.6 (`main/harness-app`) | 3.6 / 3.9 / 4.3 (`m25`, 200 files) |
  | one worktree | 5.5 / 6.7 / 7.4 (3 projects) | 316 / 330 / 371 (51 projects, 10,001 files) |
  | all worktrees | 10.6 / 11.9 / 16.4 (9 projects) | — |

  A whole fixed point on the large tree, after one file in `m25` is edited (5 times each):

  | Scope, build | Refresh | Build and join | Total |
  | --- | --- | --- | --- |
  | worktree, autobuild joined | 324–350 ms | 208–792 ms | 0.54–1.13 s |
  | module, autobuild joined | 5.5–8.3 ms | 1,037–1,106 ms | 1.04–1.11 s |
  | worktree, explicit build | 321–342 ms | 101–677 ms | 0.44–1.00 s |
  | module, explicit build | 5–13 ms | 100–153 ms | 107–157 ms |

  The first explicit build after autobuild was turned off was the slow one (677 ms). After it, the
  worktree's fixed points took 442–494 ms.

### 6. Threading

- **Reads on `main` while other worktrees import or build** (`q6`). The spike looped
  `textDocument/definition` (`Names.normalize` in `Greeter.java`) and `workspace/symbol` (`Greeter`).
  Times are in milliseconds.

  | While | Definition (n, median, max) | workspace/symbol (n, median, max) | Failed |
  | --- | --- | --- | --- |
  | idle, at the start | 20, 12.4, 138.7 | 20, 4.2, 5,415 | 0 |
  | `wt-b` imports (0.7 s) | 48, 8.6, 19.8 | 48, 4.6, 20.6 | 0 |
  | the large tree imports (18.7 s, 51 projects) | 1,064, 5.4, 80.1 | 1,064, 6.5, 3,900 | 0 |
  | a full build of 57 projects (7.7 s) | 653, 4.8, 9.2 | 653, 6.6, 24.4 | 0 |
  | idle, at the end | 20, 4.7, 5.4 | 20, 6.3, 15.1 | 0 |

  The slow `workspace/symbol` answers (5.4 s and 3.9 s) came while JDT's indexer was busy: on the
  first query, and while the large tree was being indexed. Searches wait for the indexes they need.
  Definition never waited.
- **Which requests wait for the workspace rule.** The spike held the workspace root rule for 5 s
  (`IWorkspace.run` with a sleep), and sent ten requests 0.5 s into the hold. **None waited for it.**

  | Request | ms after sending, rule held | ms when idle |
  | --- | --- | --- |
  | `textDocument/definition` | 16.1 | 7.5 |
  | `textDocument/documentSymbol` | 19.9 | 2.7 |
  | `workspace/symbol` | 23.5 | 9.9 |
  | `textDocument/references` | 127.4 | 38.3 |
  | `textDocument/completion` | 152.1 | 9.2 |
  | `workspace/executeCommand java.project.getAll` | 185.1 | 2.9 |
  | `textDocument/hover` | 188.3 | 6.7 |
  | `textDocument/prepareRename` | 195.6 | 14.2 |
  | `textDocument/codeAction` | 507.9 | 63.2 |
  | `textDocument/rename` (computing the edit) | 1,210 | 623 |

- **The jobs that ran, with their rules,** from a job-change listener:
  - **with the workspace root rule `R/`:** `AutoBuildJob` ("Building", and `AutoBuildOffJob` when
    autobuild is off), JDT LS's "Updating workspace folders" (`ProjectsManager$2`),
    `ValidateProjectEncoding`, `CharsetDeltaJob`, `ContentDescriptionManager$FlushJob` and
    `DelayedSnapshotJob` ("Periodic workspace save");
  - **with no rule:** "Register Watchers" (`StandardProjectsManager$1`), m2e's
    `ProjectRegistryRefreshJob` ("Updating Maven Dependencies"), JDT's indexer,
    `NotificationManager$NotifyJob` and "Updating index names".

  From the source: JDT LS's "Update project" job and `MavenProjectImporter`'s "Update Maven project
  configuration" are `WorkspaceJob`s, and m2e's `updateProjectConfiguration` takes rules inside. The
  endpoint's own refresh and writes run under the root rule.
- **Cancellation.**
  - **`$/cancelRequest`:**
    - `textDocument/references` on `Base.touch` in the large tree took 9.1 s to find its 10,000
      references.
    - Cancelled at 200 ms, it was answered 1.6 ms later with `-32800 "The request … has been
      cancelled"`. Three thread dumps straight afterwards found no thread still in
      `ReferencesHandler`.
    - `workspace/symbol`, cancelled at 50 ms, was answered 0.6 ms later.
  - **A cancelled progress monitor:** a full build of 57 projects took 6.3 s. With its monitor
    cancelled at 1 s, `IWorkspace.build` threw `OperationCanceledException` 31 ms later. The next
    incremental build, 3.2 s, left 0 errors.
- **Plain LSP reads don't wait for another worktree's import.** No read failed, and none slowed beyond
  the indexing outliers above. A worktree that is importing has no projects yet, so its own requests must
  wait or get `not-ready`, as §11 says.

#### Complete operations: the fixed point, then the read (review R3)

The reads above went straight to `/spike/lsp` and skipped §11's fixed point. `q6-complete` measured
complete operations on `main`, with the recommended settings (autobuild off, lightweight auto-refresh
off). Each operation is a fixed point on `main` (refresh, build, join), then `textDocument/definition`.
One operation looped while the large tree (51 projects) was imported, and again during its full build.
Where `main` changed, each operation first appended a line to `Greeter.java`.

| Variant | Idle (median) | While the large tree imports | During its full build |
| --- | --- | --- | --- |
| The fixed point as spiked (join every job), `main` changed | 182 ms | 1 operation in the 27.4 s import, taking **27.4 s**: refresh 17 ms, then build and join 27.3 s | 1 in 6.0 s, taking **6.0 s**: refresh 5.9 s, waiting for the rule |
| Join only what the fixed point scheduled, `main` changed | 127 ms | 1 in 6.9 s, taking **6.9 s**: the build waited for the rule | 1 in 4.5 s, taking **4.5 s**: refresh 4.4 s |
| Fast path, `main` unchanged | 11 ms | **562 operations**, 8.6 / 11.8 / 64.6 ms (min / median / max) | **245 operations**, 8.8 / 9.9 / 25.0 ms |
| Fast path, `main` changed | 134 ms | 1 in 12.6 s, taking **12.6 s**: refresh 5.4 s, build and join 2.3 s | 1 in 2.5 s, taking **2.5 s**: refresh 2.4 s |

All reads answered correctly.

- **Complete operations on a changed worktree wait for any other worktree's import or build.**
  - `refreshLocal` on a project needs the workspace root rule: Eclipse's `refreshRule` is the parent,
    and a project's parent is the root.
  - Every build needs the root rule too (`buildRule()` returns the root).
  - JDT LS's folder job holds the root rule while it imports a worktree, and a build holds it while it
    builds. Every rule conflicts with the root rule, so nothing narrower than the root avoids this.
- **The fixed point as spiked also waits for everything else.** Its join waits for every job in the
  workspace, including the other worktree's whole import (27.3 s here).
  - A join of only what the fixed point scheduled (a mark taken when it starts, from the job listener's
    `scheduled` events) still waits for the rule, but no longer for the rest of the other import:
    6.9 s here.
- **The fast path keeps an unchanged worktree apart.** `IResource.isSynchronized(DEPTH_INFINITE)` needs
  no rule, and finds files changed, added or deleted on disk (`IsSynchronizedVisitor`). It is the same
  timestamp comparison `refreshLocal` makes.
  - When every project of the worktree is in sync, and none has changed inside Eclipse since its last
    full fixed point (a resource-change listener clears a "clean" mark), the refresh, build and join
    are skipped.
  - The check costs 3.8 ms on `main` (3 projects) and 246–251 ms on the large tree (10,001 files).
  - A worktree that did change still waits, as in the last row.
  - In the spike, a change that lands during a full fixed point can be lost from the clean mark, because
    the mark is set when the fixed point ends. The product must take it under the rule, before the
    refresh.

### 7. A refactoring end to end

- **Rename.** `textDocument/rename` renamed `Names#normalize` in `wt-a` to `canonical`. It's declared in
  `harness-contract`, and used twice in `harness-app/Greeter.java`.
  - It took 1.4 s, and returned a `WorkspaceEdit` with both `changes` and `documentChanges`. It touched
    only `wt-a`'s two files.
  - **The baseline is taken before the edit is computed** (review R1). That's a sync, so Eclipse's view
    is the disk's, and then a hash of every file in the worktree.
  - **Under the workspace rule, the writer refuses the whole edit** if any file it changes differs from
    that baseline ("files-changed: …"), or is out of sync with Eclipse's view (`IFile.isSynchronized`,
    "out-of-sync: …"). Eclipse's view is what JDT computed the edit from.
  - **A race, simulated:** another session inserted a line at the top of `Greeter.java` after the edit
    was computed. Both checks refused the edit, and neither file was written.
  - **Computed again from a fresh baseline,** the edit applied 1 edit to each file, in 2.9 ms. `git diff
    --stat` shows 2 files changed, with 3 insertions and 3 deletions.
  - The sync found both files changed, and the build left 0 errors in all three worktrees. `git status`
    in `main` and `wt-b` was unchanged.
- **Extract method, with no prompts.** `java/getRefactorEdit` takes `command: "extractMethod"`, the
  selection as a `CodeActionParams` range, the formatting options, and no `commandArguments`.
  - In 396 ms, it returned the edit and a `java.action.rename` command (offset 289, length 9).
  - JDT LS names the new method `extracted`, and there's no argument for the name. So the endpoint
    applied the edit, then renamed `extracted` to `pad` with `textDocument/rename`, and applied that
    too. Each step had its own baseline, taken before it was computed, and both checks.
  - The result is `String padded = pad(clean, width);` and `private String pad(String clean, int
    width)`. After a sync, there are 0 errors.
  - The one-step alternative, LTK's `ExtractMethodRefactoring` with `setMethodName` called directly,
    wasn't tried.

### 8. Names

`POST /spike/search` resolves `<type>#<member>(<params>)` in a worktree.

- The type comes from a `SearchEngine` type-declaration search, scoped to that worktree's Java projects
  (`SearchEngine.createJavaSearchScope(projects, SOURCES)`).
- The member is matched on the `IType` model. Its parameter types are erased, and resolved in the
  declaring type with `IType.resolveType`.

| Name, asked in `wt-a` | Matches |
| --- | --- |
| `org.fractal.harness.contract.Names` | the type in `wt-a.harness-contract` only. A workspace-wide scope finds all three worktrees' copies |
| `…Names#describe` | all 4 overloads, so it's ambiguous. Each is listed in the fully qualified form |
| `…Names#describe(int)` | `describe(int)` |
| `…Names#describe(String)` and `…(java.lang.String)` | `describe(java.lang.String)` |
| `…Names#describe(String...)` | `describe(java.lang.String[])`. `SearchEngine`'s own method pattern finds nothing for `String...` |
| `…Names#describe(ExecutionId)` | `describe(org.fractal.harness.contract.ExecutionId)` |
| `…Names#describe(long)` | nothing; the candidates are the 4 overloads |
| `…Names#Names(int)` | the constructor. `SearchEngine`'s method pattern finds nothing |
| `…Names#count` | the field. A method pattern doesn't find fields |
| `…Names.Inner#label` and `…Names$Inner#label()` | `Names.Inner#label()` |
| `org.fractal.harness.contract.WorkType` | nothing: it exists only in `wt-b`, and is found when asked in `wt-b` |

The first search of the session took 3.6 s, while the indexes were built; the rest took 27–231 ms.

## An import that silently broke

- **What happened.** In 2 of 4 runs of `q6`, the large tree's import left `big.m17` as a project that
  m2e never registered, and nothing was logged. Later imports broke the same way, with `m30` and `m13`.
  - The project's `.location` names its own folder, `big/m17`. But its `.project` and `.settings/` were
    written to `<data>/big.m17`, the workspace's default location, and it had only m2e's nature, not
    Java's.
  - The agent's diagnostic log shows all 247 lookups of `org.gen:m17` with no candidate. m2e resolved
    it from the Maven repository, where it doesn't exist.
  - So the 32 modules after it (`m18` to `m49`) failed with "Missing artifact org.gen:m17:jar:1.0-SNAPSHOT"
    and "The project cannot be built until build path errors are resolved": 96 error markers.
- **How often.** 5 of 31 large-tree imports broke, one module each.

  | Imports | Requests during the import | Broken |
  | --- | --- | --- |
  | `q6` | plain LSP reads (`definition`, `workspace/symbol`) on `main` | 2 of 4 (`m17`, `m17`) |
  | `q6-import-repeat` and `q6-import-repeat-like-q6`, with reads | plain LSP reads | 0 of 8 |
  | `q6-complete` | complete operations on `main` (1 to 562 of them) | 2 of 4 (`m30`, `m30`) |
  | `q6-import-repeat-fastpath-ops` | fast-path operations on `main`, about 1,400 per import | 1 of 6 (`m13`) |
  | `q6-import-repeat` and `q6-import-repeat-like-q6`, without reads; the re-add in `q6-broken-repair` | none | 0 of 9 |

  - Every broken import had requests running during it: 5 of 22 such imports broke, against 0 of 9
    imports without any.
  - That points at concurrent requests, but doesn't prove it.
  - In `q6-complete`, the later trees reused the earlier trees' project names (each tree was removed
    before the next), which is a second difference. `q6-import-repeat-fastpath-ops` used a fresh `-data`
    every time, and broke too.
  - What triggers it wasn't found.
- **What repaired it.** On a copy of a broken `-data`, a restart with the folders in `initialize`
  repaired it: 51 projects with facades and no errors. JDT LS's startup updates projects whose POM
  changed since the last save. Removing and adding the worktree also left it clean.
- **What the endpoint needs.** After every add, the endpoint must check each module:
  - the project is at the module's own folder;
  - it has an m2e facade and the Java nature.

  If any check fails, it must say so loudly, and not report the worktree as imported. The spike's
  `import_health` check catches this case. §11's per-request check ("its project is imported") must
  mean the same thing.
- **And, until the cause is known, imports run alone.** While a worktree is added, the endpoint holds
  every other request that reaches JDT LS (reads included), and releases them when the import and its
  health check are done. This costs waits as long as an import (7–27 s on the large tree). It buys the
  only setting in which no import broke.

## Recommendations for what §11 leaves open

- **How the bundle loads:** in HTTP mode, the launcher passes `-Dosgi.bundles=<the install's
  config_linux/config.ini list>,reference:file:<release>/lib/fractal-jdtls-endpoint.jar@4:start`. It
  reads the list from the verified install at every launch (question 1).
- **How the in-JVM connection is made:**
  - the activator swaps `System.in` and `System.out` for in-heap pipes before the application starts;
  - after `initialize`, it checks that JDT LS's application holds those pipes, and fails loudly
    otherwise;
  - `watchParentProcess=false` keeps JDT LS up when its parent ends, and `processId` is `null` in
    `initialize`.

  No agent hook, socket or pipe is involved (question 2).
- **Autobuild: off.** The endpoint fixes `java.autobuild.enabled: false`. Each fixed point runs an
  explicit incremental build, then the join. That gave the same results every time, and was 7 to 10
  times faster than joining autobuild.
  - Turn off lightweight auto-refresh in HTTP mode as well, so that only the fixed points take in disk
    changes (question 5).
  - A build of the worktree's own projects was no more exact than a workspace build, so the simpler
    workspace build will do.
- **The refresh scope for queries: the requesting module's whole worktree,** as for builds and
  refactorings.
  - A module-only refresh is about 85 times cheaper on the large tree, 3.9 ms against 330 ms.
  - But it misses changes in the modules a query depends on, or in the modules that depend on it. A
    definition, references search or rename that reaches another module of the same worktree would then
    answer from stale state.
  - Stability comes first, so refresh the worktree, and report the cost in `sync`.
  - If the cost matters on the real reactor (T002.05 re-measures it), there is a narrowing that stays
    deterministic: the requesting module plus its in-worktree dependencies and dependents, from m2e's
    registry.
- **Whether reads must wait for imports: yes, for now** (corrected after review R3).
  - **Measured:** a complete operation on a changed worktree waits for another worktree's import or
    build anyway, because its refresh and build need the workspace root rule.
  - **Open:** an operation on an unchanged worktree, taking the fast path, needn't wait, and stays fast.
    But concurrent requests during an import go with the silent broken imports (5 of 22, against 0
    of 9).
  - **So, stability first:** imports run alone, and other requests wait for them, or get `not-ready`
    with `retryAfter` if the caller asked not to wait. Requests are allowed during an import again only
    once the broken imports are explained.
  - **Builds are another matter.** Requests on an unchanged worktree, taking the fast path, needn't wait
    for another worktree's build: no build broke anything.
- **The fixed point's shape:**
  - the rule-free fast path when the worktree is unchanged;
  - otherwise the full fixed point, with a join of only what it scheduled (question 6, `q6-complete`).

## Proposed design changes, for T002.02

This task doesn't edit the design. These are for T002.02 to bring into §11, or for Chris where marked.
Items 14 to 18 came from the review (R1, R3).

1. **§11 "Inside JDT LS": loading and connection,** as recommended above. The candidates (start list,
   agent hook, JDT LS's socket or pipe modes) become the start list through `-Dosgi.bundles`, with stdio
   swapped for in-heap pipes. Neither of the other two is needed.
2. **Process lifetime.** The launcher passes `-DwatchParentProcess=false` in HTTP mode. JDT LS's real
   stdin no longer matters, because nothing reads it.
3. **The token can't be removed from the JVM's environment** (question 2). Two options, for Chris:
   - **(a) An inherited file descriptor.** The launcher removes `FRACTAL_JDTLS_HTTP_TOKEN` from the
     environment it `exec`s, and hands the token over an inherited file descriptor. For example, a pipe
     on fd 3 that the endpoint reads once, named by `-Dfractal.jdtls.http.tokenFd=3`.
   - **(b) Keep the environment variable.** It's readable only by the same user, through
     `/proc/<pid>/environ` (mode 0400). §11 then drops "removes it from the environment".

   (a) keeps §11's intent. (b) is simpler, and the same user can already attach a debugger.
4. **"Join the resulting build jobs" is defined as in "Joining the work":** a barrier request after the
   notifications, then rounds of joins, skipping implicit jobs and the indexer's progress job.
5. **What changed on disk:**
   - **Lightweight auto-refresh off in HTTP mode** (`refresh.lightweight.enabled: false`). Then the
     endpoint's refresh is the only way disk changes come in, and its report of changed files is exact
     (`q5-absorb`).
   - **Detect POM changes by content as well.** Keep a hash of each registered worktree's POMs, and
     compare at each fixed point. JDT LS's own jobs also refresh POMs (`MavenProjectImporter`, "Update
     project"), so a change found only through the delta can still be missed.
   - **A resource filter for `.git`** on each worktree's projects, set by the endpoint when it adds the
     worktree. Then a main checkout's refresh neither walks nor reports Git's internals.
   - **Send changed POMs through m2e's update as JDT LS does,** with the internal multi-project
     `updateProjectConfiguration(request, true, true, monitor)`.
6. **Restart.** The endpoint keeps its registered worktrees in its own state under `-data`, and passes
   them in `initialize` as `workspaceFolders`. A restart then creates no projects, as in run 06.
   Starting with no folders and adding them later deletes and re-creates every project.
7. **Import health.** After every add, check every module as in "An import that silently broke", and
   report a failed import loudly. Include the per-module import state in `/v1/worktrees/list`. For
   Chris: whether the endpoint should repair a broken import itself (remove and add, or an m2e update of
   the worktree's projects), and whether to open a task to find the cause.
8. **Settings and capabilities the endpoint fixes** (§11 "Launching"), because its contract depends on
   them and the harness can't override them:
   - `java.autobuild.enabled: false`;
   - `java.configuration.updateBuildConfiguration: disabled`, since the endpoint updates POMs itself;
   - the client capability `workspace.applyEdit: false`, so commands such as organize imports return
     their edits instead of pushing them;
   - `didChangeWatchedFiles.dynamicRegistration: false`, so JDT LS registers no watchers.
9. **Metadata files in worktrees.** In HTTP mode, the launcher could pass
   `-Djava.import.generatesMetadataFilesAtProjectRoot=false`. Then JDT LS writes no `.project`,
   `.classpath` or `.settings/` into the worktrees, and removing one leaves nothing behind. It's a JVM
   option, which §1 leaves to the client. For Chris: fix it in HTTP mode, or document it for the
   harness.
10. **The mtime limit** (risks) belongs in the API's description of `sync`. Paths that a caller copied
    with their times preserved aren't detected, even when named in `changedPaths`. Detecting them would
    mean hashing file contents, which the spike didn't measure.
11. **Names (§11 "Addressing code").** Types are resolved with `SearchEngine`, scoped to the worktree.
    Members are matched on the `IType` model, with the endpoint's own erasure comparison, because
    `SearchEngine`'s method patterns miss varargs, constructors written `Type#Type(…)`, and fields.
12. **Extract method.** JDT LS always names the new method `extracted`. So a request's method name is
    applied as a second step, a rename, unless the endpoint calls LTK's `ExtractMethodRefactoring`
    directly.
13. **A wording fix in §11 "Why not stdio".** Plain stdio JDT LS 1.63.0 was still running 60 s after its
    stdin closed, while its parent was alive (`q1-mode-switch`). It stops when the parent process ends,
    not when the stream closes.
14. **§11 "Threading and queuing": complete operations wait for the workspace root rule.** Refreshing a
    project and building both take it, as do JDT LS's imports and builds. §11 should say that operations
    on a changed worktree run one at a time with every other worktree's imports and builds, and report
    the wait in `sync`.
15. **The fixed point's fast path.** Before a fixed point, the endpoint checks every project of the
    worktree with `isSynchronized(DEPTH_INFINITE)`, which takes no rule.
    - If all are in sync, and the endpoint has seen no change to them inside Eclipse since their last
      full fixed point, it skips the refresh, the build and the join, and reports `sync.skipped`.
    - "No change seen" is a mark that a resource-change listener clears, and that is set under the rule
      before the refresh.
    - It cost 3.8 ms on the fixture's worktree and about 250 ms on the 10,001-file tree. It kept an
      unchanged worktree at about 12 ms an operation during another worktree's import.
16. **Join only what the fixed point scheduled.** Take a mark when the fixed point starts, and join only
    jobs scheduled after it, round after round. This replaces "every running or waiting job", which waited
    27 s for another worktree's import in `q6-complete`.
17. **Imports run alone, for now.** While a worktree is added, every other request that reaches JDT LS
    waits, or gets `not-ready` with `retryAfter`, until the import and its health check are done (item 7).
    This is for Chris to confirm: it trades waits as long as an import for the only setting in which no
    large import broke.
18. **The refactoring baseline** (review R1). §11 "Applying edits" checks "that every affected file still
    has the content the edit was computed from". That content is Eclipse's view at the fixed point, so
    the endpoint must take the baseline before computing the edit.
    - In the spike: a sync, then a hash of the worktree's files.
    - The product could keep the hashes of the files the fixed point read.
    - Under the workspace rule, before writing, it checks both the hashes and `IFile.isSynchronized`.

## Joining the work

"Join the resulting build jobs" needs care. These are the spike's three findings.

- **Families aren't enough.**
  - JDT LS schedules some jobs with no family. On a restart, `MavenProjectImporter` schedules "Update
    Maven project configuration" as an anonymous `WorkspaceJob`.
  - A join of the known families returned before that job ran. Those were JDT LS's folder,
    project-update and initialization families, `FAMILY_AUTO_BUILD` and `FAMILY_MANUAL_BUILD`.
  - `shutdown` then cancelled the job, JDT LS logged "Error updating …", and the process exited with
    code 1.
  - The rule that works joins every job that is running or waiting to run, and every `AutoBuildJob` or
    m2e background-queue job (`IBackgroundProcessingQueue`) that is sleeping (scheduled with a delay).
    It does so in rounds, until a round finds none.
  - The perpetual sleeping system jobs ("Periodic workspace save", "Compacting resource model") aren't
    joined while they sleep.
- **Never join an implicit job.** Eclipse represents a thread that holds a scheduling rule outside a job
  (`IWorkspace.run`, `beginRule`) as a `ThreadJob`, and a join on one never returns.
  - When wt-b's add and wt-a's sync ran at once, wt-b's join hung on wt-a's `ThreadJob`, until the
    spike's 600 s guard timeout ended it.
  - Skipping `ThreadJob` fixed it. That thread's own work isn't a job to wait for. It holds the rule,
    and the jobs it schedules are joined once it lets go.
  - The design already runs workspace changes one at a time, and this shows that's needed.
- **The indexer's progress job isn't joined.** JDT search waits for the indexes it needs, and joining
  "Java indexing…" cost up to 5 s for nothing.

No step here depends on time. The barrier proves that JDT LS has handled the notifications, and each
round joins jobs that are already scheduled.

## Constraints and risks

- **Version-pinned internals,** like the agent's hooks (design §6). The endpoint relies on:
  - `LanguageServerApplication.prepareStreams` reading `System.in` and `System.out` at application
    start, which the identity check catches;
  - the `watchParentProcess` property;
  - JDT LS's job classes and families;
  - m2e's internal `ProjectConfigurationManager.updateProjectConfiguration(request, true, true,
    monitor)`. The public method throws `IllegalArgumentException` for more than one POM, and JDT LS
    itself calls the internal one;
  - `IBackgroundProcessingQueue` and `ThreadJob`, by class.

  Each must be re-checked on every JDT LS upgrade.
- **A large import can break silently** ("An import that silently broke"): 5 of 31 times, every time
  with requests running during it. Cause not found.
- **Changes that keep the mtime are invisible to `refreshLocal`.** Copies that preserve times (`cp -p`,
  `rsync -a`, `tar x`, `touch -r`) are missed, and so are two writes within one millisecond. Git
  checkouts and ordinary writes are seen. Nothing in the spike detects the missed ones, and a missed
  change is silent staleness.
- **Refresh cost grows with the worktree:** 330 ms for 10,001 files on WSL2's ext4, at every fixed point
  that refreshes the whole worktree. It wasn't measured on `/mnt` (9p) paths, which are far slower on
  WSL2.
- **The bundle lives in JDT LS's process.** A hang in it, like the implicit-job join, blocks the
  operation that hit it. Every join has a long guard timeout that reports loudly.
- **JDT LS writes `.project`, `.classpath` and `.settings/` into every module of a worktree** unless the
  JVM has `-Djava.import.generatesMetadataFilesAtProjectRoot=false`. Removing a worktree leaves them.

## Not tested

- An agent hook on JDT LS's connection stream factory, and JDT LS's client-socket and `--pipe=` modes.
  None was needed, and the last two are reachable from outside the JVM.
- A plain-mode start after a `config.ini` in the configuration area had loaded the bundle: reasoned, not
  run.
- `jdk.httpserver` without the stock `--add-modules=ALL-SYSTEM`.
- What stock JDT LS's outgoing HTTPS connection is for.
- What makes a large import break `m17`.
- The real Harness reactor, Gradle, nested multi-level reactors, and a root POM change that must reach
  every child.
- Worktrees on WSL2's `/mnt` paths, and memory use with many worktrees.
- Many concurrent HTTP sessions, callers disconnecting, and the endpoint's request timeouts (T002.11).
- Refreshing below the project level, under project rules instead of the root rule. Not tried: while
  another worktree's import holds the root rule, every rule conflicts with it.
- The fast path's clean mark taken under the rule before the refresh, as the product should take it.
  The spike sets it when the fixed point ends.
- Whether imports that run alone never break. The evidence so far is that none of the 9 imports without
  concurrent requests broke.
- LTK refactorings called directly, and the rest of T002's refactorings (T002.07).
- Hashing file contents to catch changes that keep their mtime.
