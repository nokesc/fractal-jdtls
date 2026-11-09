> **FUTURE PROPOSAL — NOT PART OF THE DESIGN.** Parked by the governor on 2026-11-08 at Chris's request. Kept for a possible later hardening series; the implemented design is §11 "Operation folders" in `.context/design.md`.

# Variant C: saved plans, journal and startup recovery (design as of commit d8f7e70)

The largest variant, after two Codex design reviews and Chris's restart question. Rejected by Chris as over-built ("an elaborate crash-recovery and transaction system for something that should be an exceptional failure").

## §11 Operation folders


(Chris's idea, 2026-11-08; designed by the governor, revised after two Codex design reviews, D1–D11 and N1 in the run's `design-review.md` and `design-review2.md`, and Chris's question about restarts; T002.10. To review.)

The endpoint, the coding agents (Claude Code, Codex) and Fractal Harness can all reach the worktree, so each refactoring publishes its record and its full diff as files there, where the agent reads them with its own tools and Chris can see them. HTTP stays the only control channel: requests, answers, locks, timeouts and errors never move to files, because coordinating through files means polling and partial reads, the timing the endpoint avoids everywhere else.

- **What it solves:**
  - a refactoring's diff can be far larger than an HTTP answer or an LLM's context should carry (a 12,000-line rename's diff is 1.4 MB); the agent reads the file, as much as it needs;
  - the folder is inside the session's own worktree, so the agent reads it without the permission prompts a folder elsewhere would need;
  - a dry run's plan can be applied exactly as the caller reviewed it, byte for byte, without computing it again (a first rename's search takes 0.7–1.7 s).
- **Two places, one authority.** The plan that gets applied lives only in the endpoint's own storage, `<data>/.fractal-jdtls/plans/`, written and read by the endpoint alone. The worktree's operation folder is a publication: the endpoint writes it and never reads it back, so nothing an agent changes in `temp/` can change what's applied. (Codex D11: no signature or key needed, and no untrusted input to parse.) Like the rest of `-data`, the plan store is trusted: an agent running as the same Unix user could change it, which is outside what the endpoint defends against, as for `-data` today.
- **The operation ID.** Every refactoring request carries `operation`, a UUID in its canonical lowercase form, chosen by the caller (Fractal Harness keeps the bookkeeping). Anything else is `invalid-request`. The caller sends an ID, never a path: the endpoint makes every path itself. An ID is used once per worktree, for good: if its folder already exists, whichever process made it, the request is `operation-exists` (409), nothing computed. The folder's exclusive creation is the check, so the endpoint keeps no list of used IDs.
- **The plan** (a dry run's), kept under the worktree and the ID:
  - the exact bytes each file will get, as prepared at the dry run (charset, byte order, byte order mark, line separators already applied), and every move, delete, expected absence and protected folder, with each file's baseline hash and result hash (Codex D3: a plan of text edits could be encoded differently later);
  - the worktree's record at the dry run's fixed point, and the inputs fingerprint (below);
  - `diffDigest`, the SHA-256 of `diff.patch`'s exact bytes, and `planDigest`, a SHA-256 over all of the above including `diffDigest`. The dry run's HTTP answer returns both, and the apply must send `planDigest` back (Codex D4): an apply can only write the plan whose diff the caller was shown. A reader of `diff.patch` checks it against `diffDigest` from the answer (`sha256sum`), so a changed copy is detected; the inline diff in the answer is always authoritative.
- **What a plan depends on, and when it's stale.** A plan was computed from more than the edit's own files: a call to the renamed method added anywhere else after the dry run would be missed, and the build would break. So the plan is stale, `plan-stale` (409, not retryable, `hint`: run the dry run again with a new `operation`), when any of these differs from the dry run:
  - the worktree's record, in any file it covers;
  - the inputs fingerprint: for each project the edit's modules are in, or that is on their classpath, its resolved classpath (each entry; for a jar or an external class folder's files, the same record the worktree's files get: status-change time, size, inode and modification time, so a replacement that keeps the size and modification time is still seen), its project and JDT options (compliance, encoding) and, for a workspace project in another worktree (§6 prefers but doesn't force the same worktree), that worktree's record; and the workspace's set of projects and their locations (Codex D2). An input the fingerprint can't cover (a linked source folder outside every worktree, a classpath entry that isn't a jar or folder) means no plan is saved: the dry run still answers with its diff, `planDigest` absent and `planNotSavedBecause` naming the input, and the caller applies with `dryRun: false`.

  These are checked inside the apply's write lock and workspace rule, against a fresh scan taken there, immediately before each file's own check and the writes, never against a record taken before the lock (Codex D1). Changes made by processes outside the endpoint at the moment of the write aren't excluded by the endpoint's locks; the per-file checks catch any that reach the edit's own files, as for every apply. Before T002.10, the mapping's checks become read-only (they once took a POM in outside a fixed point), so nothing changes the model between fixed points.
- **A plan's lifetime.** A plan can only be applied while nothing it depends on has changed, so it's dropped, deterministically, when it's applied, when a fixed point of its worktree sees any change, when its worktree is removed, and when the endpoint starts. A plan computed before a restart is never written after one: the client's settings, or JDT LS itself, may have changed. Re-running the dry run costs one computation.
- **Outcomes outlive plans.** For each operation the store keeps a small outcome record (applied, with what was written; stale; dropped and why; interrupted, with the files), kept across restarts, so a retry always gets a definite answer:

  | When it happens | The next apply of that operation answers |
  |---|---|
  | a restart before the apply | `plan-not-found`, `hint`: the endpoint restarted, run the dry run again with a new `operation` |
  | a change in the worktree before the apply | `plan-stale` |
  | the plan dropped by the cap | `plan-not-found`, saying so |
  | a restart during the writes | `apply-interrupted` (409), with what recovery did (below) |
  | a restart after the writes, before the answer | `plan-applied`, with the recorded answer |
  | an endpoint with no record of it (another `-data`, another process, or the outcome evicted) | `plan-not-found`: this endpoint has no record of the operation |

- **Bounded** (Codex N1): each worktree keeps at most a fixed number of live plans and a fixed number of outcomes; beyond either, the oldest goes, and its next apply is `plan-not-found` saying it was evicted. T002.10 sets both from measured sizes, and T002.11 shows the store doesn't grow past them over many operations.
- **Every apply is journaled on disk, saved plan or not** (Chris's question and Codex D7). Today "all or nothing" holds only while the JVM lives: its undo journal is in memory, so a process killed between the first and the last write (Harness stopping a slow JDT LS, say) leaves some files edited and some not, silently. So:
  1. before the first write, the endpoint writes an intent to `<data>/.fractal-jdtls/applies/` (each file's path, original bytes and hash, and new bytes' hash; each move, create and delete), and syncs it to disk; if it can't, the apply is `write-failed` with nothing written;
  2. after the last write, and before the refresh and build, it records the source writes as done; if the refresh, build or join then fails, the answer and the outcome still report what was written, with the failure;
  3. the outcome is recorded before the answer is sent; if recording it fails, the answer still reports what was written and says the outcome wasn't recorded;
  4. **at startup, before anything else** (and before plans are dropped), each intent left without "done" is recovered: if every file holds its original bytes, nothing was applied; if every file holds either its original or its new bytes, the files with new bytes are put back to their originals (all or nothing, from the journal); if any file holds neither, someone changed it since, so the endpoint changes nothing, and the outcome lists every file and its state for the caller to resolve. It never replays an edit. Each recovery is `apply-interrupted` for that operation, shown in `/v1/status` and the endpoint's log, and needs no saved plan.
  An intent recorded as done but without an outcome is recorded as applied, from the journal.
- **`POST /v1/refactor/apply`** takes `worktree`, `module`, `operation`, `planDigest` and `timeoutMs`:
  1. a fixed point;
  2. under the worktree's write lock and the workspace rule, the checks, each refusing with nothing written:
     - no plan for the ID in that worktree: `plan-not-found` (404), saying why when the store knows (the table above);
     - recovered after an interrupted apply: `apply-interrupted` (409), with the recovery's result;
     - already applied: `plan-applied` (409); another apply of the same ID is serialized behind the same lock;
     - `planDigest` isn't the plan's: `plan-mismatch` (409);
     - the request's module isn't the plan's: `invalid-request`, whose candidates set the plan's;
     - stale, as above: `plan-stale`;
  3. the writes, all or nothing, from the plan's prepared bytes, with the same per-file checks as every apply; then the refresh, the build and the join. JDT isn't asked to compute anything again.

- **The operation folder:** `<worktree>/temp/fractal-jdtls/<operation>/`, at the worktree's root.
  - **Written safely** (Codex D5): every component from the worktree's real root down (`temp`, `fractal-jdtls`, the operation) must be a real directory, not a link; the endpoint opens each one without following links, keeps the handle, and creates every file relative to it, exclusively, without following links (a temporary name, then renamed, so a reader never sees a partial file). A component that's a link or not a directory is `operation-folder-unsafe` (422), nothing computed.
  - **It must be git-ignored.** Before creating it, the endpoint runs Git directly (`git -C <worktree> check-ignore -q temp/fractal-jdtls/`, with `GIT_DIR`, `GIT_WORK_TREE` and the other repository-selection variables removed from its environment, bounded output, under the request's deadline). Not ignored is `operation-folder-not-ignored` (422), whose `hint` says to add `temp/` to the worktree's `.gitignore`; Git missing or failing is `git-failed` (500, with Git's output). The endpoint never edits `.gitignore` or Git's own files. Git becomes a runtime requirement of HTTP mode (§10); T002.10 measures the call. (Codex D8.)
  - **It's never part of the Java world** (Codex D6). The endpoint reserves exactly `temp/fractal-jdtls/` in every worktree: it's left out of the file record (when it's taken, restored and compared), of every refresh and sync check, of resource-change handling and of import discovery, from before the first folder is made. So writing it never makes a fixed point see a change or a plan go stale. The rest of `temp/` is the project's own, and is recorded like any other file.
  - **What it holds:** `request.json` (the request as received; the token is never in it), `diff.patch` (the full unified diff, which `git apply --check` accepts), `response.json` (the answer as sent), and after an apply of the plan, `applied.json` (when, and the apply's answer). These are copies for reading. Failing to write one after the source files were written doesn't fail the apply: the answer still reports what was written, and says which copy is missing (Codex D7).
  - **The endpoint never deletes an operation folder,** nor does removing a worktree. Fractal Harness (or Chris) cleans them up.
- **The answer** gains `operation`, `operationFolder` (the folder's path relative to the worktree), `diffDigest`, `planDigest` or `planNotSavedBecause` (for a dry run), and the diff inline up to a fixed limit, cut at a file's or hunk's boundary, with `diffTruncated: true` when it's cut. The full diff is always in `diff.patch`. T002.10 sets the limit and records why.
- **Later series:** large navigation answers (references, problems) through the same folders.


## Its T002.10

# T002.10 — A folder per operation

Step 10 of [T002](T002.md). Inserted by the governor on 2026-11-08, at Chris's request.

## Why now

A refactoring's diff can be too large for an HTTP answer or an LLM's context, and today a dry run's plan is thrown away and computed again at the apply. Everything that reaches the worktree (the endpoint, the coding agents, Fractal Harness) can share files there. This comes before T002.11, so the soak covers applying saved plans.

## Depends on

T002.07 (refactorings and the apply), T002.08 (the scratch folder).

## Design

[design.md](../design.md) §11, "Operation folders", and "Refactorings and edits".

## Changes

- **First, the mapping's checks become read-only** (moved here from T002.11): a POM was once taken in outside a fixed point, and saved plans depend on nothing changing the model between fixed points.
- **`operation` on every refactoring request,** required, a canonical lowercase UUID, used once per worktree for good (an existing folder is `operation-exists`).
- **Every apply journaled on disk** (saved plan or direct): the intent synced before the first write, "done" after the last, the outcome before the answer, and recovery at startup as §11 says (put back, or change nothing and list, never replay).
- **The plan store** in `<data>/.fractal-jdtls/plans/`: prepared bytes and file operations with their hashes, the record, the inputs fingerprint (jars and external class folders by file record), `diffDigest` and `planDigest`; dropped when applied, when a fixed point sees a change, when the worktree is removed, and at start; outcomes kept across restarts; per-worktree caps on plans and outcomes, set from their measured sizes. No plan for inputs the fingerprint can't cover (`planNotSavedBecause`).
- **`POST /v1/refactor/apply`,** with each check in §11's order, inside the write lock against a fresh scan, writing the plan's bytes without computing again.
- **The operation folder:** the reserved `temp/fractal-jdtls/` left out of the record, refreshes, sync checks, resource-change handling and import discovery; each component opened without following links and files created relative to it; the git-ignore check through a bounded Git call; `request.json`, `diff.patch`, `response.json`, `applied.json`.
- **The answer:** `operation`, `operationFolder`, `diffDigest`, `planDigest` or `planNotSavedBecause`, the inline diff up to a limit with `diffTruncated`. Set the limit and record why.
- **The contract:** the new operation and fields, and the codes `operation-exists`, `operation-folder-unsafe`, `operation-folder-not-ignored`, `git-failed`, `plan-not-found`, `plan-applied`, `plan-mismatch`, `plan-stale` and `apply-interrupted`, each with its example and `hint`. Replace the refactoring examples with the real ones.
- **§10 and AGENTS.md:** Git on the `PATH` in HTTP mode; `temp/fractal-jdtls/` ignored.
- **The fixture's `.gitignore`** ignores `temp/`; one test worktree doesn't, for `operation-folder-not-ignored`.

## Acceptance

Record real output for each item. Show the worktree's hashes unchanged after every refusal.

- **A dry run, then an apply of its plan:** the operation folder's files listed, `diff.patch` passing `git apply --check` before the apply, and the written files byte-equal to the plan's prepared bytes, including a non-ASCII edit and a UTF-16 file with a byte order mark.
- **The apply doesn't compute again,** shown by instrumentation (a diagnostic-log event or counter for each refactoring computation), not by timing; the apply's time against a direct `dryRun: false` refactoring also recorded.
- **`plan-stale`:**
  - a new call to the method being renamed, added in a file outside the edit after the dry run; the dry run repeated, then a clean build;
  - the same change made inside the race window, between the apply's fixed point and its write lock, placed deterministically with `TestPauses`;
  - a change in the inputs fingerprint (a dependency's POM changed, so the classpath changes);
  - a jar on the classpath replaced by different bytes with the same size and modification time.
- **`planNotSavedBecause`** for a module with an input the fingerprint can't cover, and its `dryRun: false` apply working.
- **`diffDigest`** equal to `sha256sum diff.patch`, and differing after `diff.patch` is edited.
- **`plan-mismatch`** for a wrong `planDigest`; **`plan-applied`** for a second apply, and for two simultaneous applies of one ID (one applies, one refuses); **`plan-not-found`** after a restart, with its `hint`, and for a plan dropped by the cap; **`operation-exists`**; **`operation-folder-not-ignored`**; **`git-failed`** with Git missing from the `PATH`.
- **Changing the folder never changes what's applied:** `diff.patch` and `request.json` edited after a dry run, then the apply writes the plan's bytes.
- **`operation-folder-unsafe`:** `temp`, `fractal-jdtls`, or the operation's folder replaced by a link (to a folder outside the worktree, and to one inside it), each refused, with a sentinel file outside the worktree neither read nor changed.
- **The folder isn't part of the Java world:** with the worktree's root a module's project, writing operation folders leaves the next fixed point on its fast path, no plan goes stale, and no Eclipse resource exists for them, also after a restart.
- **Restarts and interrupted applies** (each with the endpoint killed with `kill -9` at a `TestPauses` point, then started again on the same `-data`):
  - between the dry run and the apply: `plan-not-found` with the restart `hint`;
  - after some files are written: at startup, those files put back to their original bytes, `apply-interrupted` for the operation, and the recovery in `/v1/status` and the log;
  - the same, with one written file then changed by hand before the restart: nothing changed by recovery, every file listed with its state;
  - after the writes, before the answer: `plan-applied` with the recorded answer;
  - the journal's intent unwritable (its folder made read-only): `write-failed`, nothing written.
- **Bounded:** many dry runs and applies past both caps; the store's size stays under them, and an evicted operation answers `plan-not-found` saying so.
- **A copy that can't be written:** `response.json` made unwritable after the source files are written (with `TestPauses`): the apply succeeds and its answer says the copy is missing.
- **A large diff:** a rename that changes more than the inline limit, with `diffTruncated: true` and the full `diff.patch`.
- **The Git call's cost,** measured.
- `mise run validate` passes.

## Out of scope

- Operation folders for navigation, problems and the passthrough (a later series).
- Cleaning up folders: Fractal Harness's job.
