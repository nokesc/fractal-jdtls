> **FUTURE PROPOSAL — NOT PART OF THE DESIGN.** Parked by the governor on 2026-11-08 at Chris's request. Kept for a possible later hardening series; the implemented design is §11 "Operation folders" in `.context/design.md`.

# Variant A: records, intent and Git recovery (design as of commit d1ba996)

Chris's operating model with Codex's last review accepted: `intent.json` before an apply's first write, the accepted-failures table, Harness's duties (§10). Superseded for the first implementation by a smaller cut.

## §11 Operation folders


(Chris's idea and operating model, 2026-11-08; reviewed with Codex, whose last review Chris accepted; T002.10. To review.)

JDT LS provides semantic code operations. Fractal Harness manages worktrees and recovery. Agents handle ordinary engineering failures. Git is the recovery mechanism. Chris chose operational simplicity over transactional guarantees: a JDT LS crash should be rare, and if one leaves a refactoring half-applied, that's acceptable as long as the failure can be identified and the worktree restored from Git. So the endpoint only records each refactoring where everyone can see it; it has no transaction engine, backups, journal or recovery states, and it doesn't stop other agents or editors writing the worktree.

- **An operation ID.** Every refactoring request carries `operation`, a lowercase UUID the caller chooses. The endpoint makes the path itself, `<worktree>/temp/fractal-jdtls/<operation>/`, and creates it exclusively: if it exists, `operation-exists` (409). One folder per operation, so records never mix; the caller passes an ID, never a path.
- **What it holds:**
  - every refactoring: `request.json`, written first; `diff.patch`, the full unified diff; and `response.json`, the answer, written last, to a temporary name in the folder and then renamed, so a reader never sees half of it;
  - an apply also writes `intent.json` before its first source write: the request, every file it will change, create, move or delete, and its `writeSetDigest`.
- **What a folder tells a reader:** `response.json` means the request finished with that answer, which may be an error. `intent.json` without `response.json` means the apply didn't finish, or hasn't yet: before restoring its files from Git, Fractal Harness makes sure the request has ended (if the endpoint may still be working on it, it stops the JDT LS process it started, by its PID, and waits for it to exit).
- **Applying what was reviewed: one digest.** A dry run returns `writeSetDigest`: a SHA-256 over a deterministic, length-framed sequence of entries, one per file operation in a fixed order, each `(kind, path, from, result bytes)` with paths relative to the worktree: `from` is a rename's or move's source (absent otherwise), and the result bytes are absent for a delete, so a delete differs from an empty file. The request's `operation` isn't in it, so a dry run and its apply have different folders. An apply may send `expectWriteSetDigest`: the endpoint computes the edit as it does today, and if the digest differs, writes nothing and answers `edit-changed` (409). The comparison is made even when the recomputed edit changes nothing. The digest is of the writes, not of the diff, because the printed diff doesn't fix the written bytes (a byte order mark, or a change outside the diff's hunks, prints the same diff). Equal digests mean the same writes, not that the starting files or JDT's conditions were the same, nor that the result is correct: the build, the tests and the agent's review cover that.
- **The apply itself is unchanged** ("Refactorings and edits"): the per-file checks before the writes, the writes in place, the undo while the process lives, then the refresh, the build and the problems. The checks catch changes made before the writes, not during them; the undo is attempted, and the answer names any file it couldn't restore; the refresh or build can fail after the files were written, and the answer says so.
- **Large diffs.** The answer carries the diff inline up to a fixed limit, cut at a file's or hunk's boundary, with `diffTruncated: true` when it's cut. The full diff is always in `diff.patch`. T002.10 sets the limit.
- **Two small guards:**
  - the folder's real path must be inside the worktree's real root, the rule edits follow (`edit-outside-worktree` otherwise), checked when the folder is created, so a `temp` that links elsewhere can't make the endpoint write outside the worktree;
  - the endpoint's file record skips `temp/fractal-jdtls/`, as it skips `target/`, so the endpoint's own records are never reported to a caller as disk changes. Eclipse's own sync check still sees the new files, so the next fixed point after a refactoring may refresh once more; T002.10 measures it, and filtering the folder out of Eclipse too comes only if the numbers call for it.
- **Failures accepted,** and who handles them:

  | Failure | What happens | Handled by |
  |---|---|---|
  | JDT LS dies mid-apply | Some files edited, some not; a file being written may be truncated | Harness: `intent.json` without `response.json` names the files; it restores them from its checkpoint commit. Uncommitted work in them can be lost, hence the checkpoint |
  | A reference to the renamed element added elsewhere between the computation and the write | That site isn't renamed; the build may or may not fail (the old name can still resolve through inheritance) | The build and tests; the agent repairs |
  | Another agent or editor writes the worktree during an apply | Caught only if it touched the edit's files before the writes (`files-changed`); a write during them can be overwritten | The build and tests; the agent; Git |
  | The undo, the refresh or the build fails after writes | The answer says what was written and what failed | The agent; Git |
  | The answer is lost | The caller doesn't know the result | `response.json`, else as a crash, after making sure the request has ended |
  | `temp/` not git-ignored | Records appear in `git status` | Harness's requirement (§10) |
  | `temp` replaced by a link after the check | A record written elsewhere | Accepted |

- **New codes:** `operation-exists`, `edit-changed`.


## §10 as it stood


These belong to whoever launches JDT LS. For Fractal Harness, they're recorded here until Harness tracks them:

- **Folder names must be unique** among the worktrees that share one JDT LS process. In HTTP mode, the endpoint refuses to add a worktree whose folder name is already in use (§11).
- **The launcher owns the lifecycle:** how many processes, sharing scope, and when they start and stop.
- **In HTTP mode, a start has failed if the port file doesn't appear** within the harness's own timeout. The harness then stops the process it started, by its PID (§11).
- **Refactorings and recovery are the client's** (Chris, 2026-11-08; §11, "Operation folders"). Fractal Harness:
  - makes sure each worktree ignores `temp/` (the endpoint doesn't check);
  - makes a checkpoint commit before a refactoring; the endpoint never commits or checks for one;
  - treats a failed build or failed tests after a refactoring like any other: the agent repairs it, and after two unsuccessful attempts the worktree is stopped and Chris is told;
  - after a JDT LS failure, finds operations with `intent.json` and no `response.json`, makes sure the request has ended (stopping the process it started, by its PID), restores their files from Git, and continues or escalates after two unsuccessful attempts;
  - cleans up operation folders.
- **One client per process.** A shared process needs one owner of its LSP connection. In HTTP mode, that owner is the endpoint (§11), and sessions share the process through its API.
- **Adding and removing workspace folders at runtime** (`workspace/didChangeWorkspaceFolders`) wasn't tested by the worktree spike. T002.01 tested it: it works with the agent's naming and resolution intact (HTTP-FINDINGS question 4), and the endpoint's worktree calls depend on it (§11).


## Its T002.10

# T002.10 — A folder per operation

Step 10 of [T002](T002.md). Inserted by the governor on 2026-11-08, at Chris's request.

## Why now

A refactoring's diff can be too large for an HTTP answer or an LLM's context, nothing records a refactoring where Fractal Harness, the agents and Chris can see it, and nothing ties an apply to the dry run that was reviewed. Chris's operating model (design §11, "Operation folders"): JDT LS records and performs semantic operations; Harness manages worktrees, checkpoints and recovery with Git; agents repair ordinary failures. This comes before T002.11, so the soak covers it.

## Depends on

T002.07 (refactorings and the apply), T002.08 (the scratch folder).

## Design

[design.md](../design.md) §11, "Operation folders" and "Refactorings and edits"; §10, "Refactorings and recovery are the client's".

## Changes

- **`operation` on every refactoring request,** required, a lowercase UUID; the folder `<worktree>/temp/fractal-jdtls/<operation>/` created exclusively (`operation-exists`), its real path inside the worktree's (`edit-outside-worktree`).
- **The records:** `request.json` first; `diff.patch`; for an apply, `intent.json` before the first source write; `response.json` last, by temporary name and rename.
- **`writeSetDigest`** in every dry run's and apply's answer, as §11 defines it; `expectWriteSetDigest` on an apply, compared before anything is written, also when the recomputed edit changes nothing (today `CodeRequest` returns early there); `edit-changed` when it differs.
- **The inline diff limit,** with `diffTruncated`. Set the limit and record why.
- **The file record skips `temp/fractal-jdtls/`.**
- **`EditWriter.create`** records its undo before writing, so a create that fails partway is undone (found in review).
- **The contract:** `operation`, `writeSetDigest`, `expectWriteSetDigest`, `diffTruncated`, `operationFolder`, and the codes `operation-exists` and `edit-changed`, each with its example and `hint`. Replace the refactoring examples with the real ones.
- **The fixture's `.gitignore`** ignores `temp/`.
- **AGENTS.md** mentions the folders.

## Acceptance

Record real output for each item.

- **A dry run, then its apply with `expectWriteSetDigest`:** both folders' files listed, `diff.patch` passing `git apply --check` before the apply, and the written files matching it.
- **`edit-changed`:** a file in the edit changed between the dry run and the apply, so the recomputed write set differs; nothing written. And once more with the change made only outside the diff's hunks.
- **`operation-exists`;** a `temp` that links outside the worktree refused; each with the worktree's hashes unchanged.
- **An interrupted apply leaves a readable record:** JDT LS killed (`kill -9`) at a `TestPauses` point after the first source write; the folder has `intent.json` naming every file and no `response.json`, and `git checkout` of those files restores the worktree (shown with hashes). The endpoint does nothing at its next start.
- **A large diff:** a rename that changes more than the inline limit, with `diffTruncated: true` and the full `diff.patch`.
- **The extra refresh:** the next fixed point's time after a refactoring, with and without the folder's files, so Chris can see whether Eclipse's sync check costs anything.
- **The create fix:** a create that fails partway, undone.
- `mise run validate` passes.

## Out of scope

- Operation folders for navigation, problems and the passthrough (a later series).
- Checkpoint commits, recovery and cleanup: Fractal Harness's (§10).
