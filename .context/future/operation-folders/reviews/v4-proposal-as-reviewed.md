# Proposal: operation folders, minimal (v4)

Status: for Chris's review. Nothing implemented; design.md not yet changed. Replaces the v3 proposal and §11 "Operation folders" as of d8f7e70.

Principle (Chris, 2026-11-08): JDT LS provides semantic code operations. Fractal Harness manages worktrees and recovery. Agents handle ordinary engineering failures. Git is the recovery mechanism. Operational simplicity over transactional guarantees.

## What the endpoint does

1. **An operation ID.** Every refactoring request carries `operation`, a lowercase UUID the caller chooses. The endpoint makes the path itself, `<worktree>/temp/fractal-jdtls/<operation>/`, and creates it exclusively; if it exists, `operation-exists` (409).
   *Why:* one folder per operation, as Chris proposed, so records never mix; the caller passes an ID, never a path.

2. **The intent, before anything is written.** The endpoint writes `intent.json`: the request, the files the edit will change, create, move or delete, and `writeSetDigest`. For a dry run, `diff.patch` (the full diff) too.
   *Why:* if JDT LS dies mid-apply, a folder with `intent.json` and no `response.json` identifies the incomplete operation and exactly which files to check or restore from Git.

3. **The apply, as T002.07 built it.** Unchanged: the existing per-file baseline check, the in-memory all-or-nothing undo for failures while the process lives, and the build and problems afterwards.
   *Why:* already built and tested; no new machinery.

4. **`response.json`, atomically, when complete:** written to a temporary name in the folder, then renamed. The HTTP answer is the same.
   *Why:* its presence means the operation finished; a reader never sees half of it.

5. **Applying what was reviewed: one digest.** A dry run returns `writeSetDigest`, a SHA-256 over the exact write set (each path, the operation kind, and each file's result bytes). An apply may send `expectWriteSetDigest`; the endpoint recomputes the edit as today, and if the digest differs, writes nothing and answers `edit-changed` (409).
   *Why this digest and not one of the diff:* the printed diff doesn't fix the written bytes (a byte order mark, or a change outside the diff's hunks, prints the same diff; Codex round 1, S1). No other digest is kept.

6. **Large diffs.** The answer carries the diff inline up to a fixed limit, with `diffTruncated: true` when it's cut; the full diff is in `diff.patch`.
   *Why:* the original problem: a 12,000-line rename's diff is 1.4 MB.

7. **Two small guards:**
   - the operation folder's real path must be inside the worktree's real root, the rule edits already follow (`edit-outside-worktree` otherwise). *Why:* a `temp` that links elsewhere would make the endpoint write outside the worktree. One check when the folder is created;
   - the file record skips `temp/fractal-jdtls/`, as it skips `target/`. *Why:* otherwise every refactoring's own records would count as a disk change at the next fixed point, losing its fast path and reporting the endpoint's files to the caller as changes.

## What Fractal Harness does (§10)

- Ignores `temp/` in each worktree (the endpoint doesn't check).
- Commits before a refactoring when the worktree has work in progress.
- After a JDT LS failure: finds operations with `intent.json` and no `response.json`, restores their files from Git if needed, and lets the agent continue; after two failed recovery attempts, stops the worktree and escalates to Chris.
- Cleans up operation folders.

## Failures we accept

| Failure | What happens | Who handles it |
|---|---|---|
| JDT LS dies mid-apply | Some files edited, some not; a file being written may be truncated | Harness: `intent.json` without `response.json` names the files; restore from Git. Work not committed in those files can be lost, hence the commit before refactoring |
| A reference to the renamed element is added in another file between the computation and the write | That site isn't renamed | The build in the apply's answer shows the error; the agent fixes it |
| Another agent or editor writes the worktree during an apply | Caught if it touches the edit's own files (the existing baseline check: `files-changed`); otherwise not | The build and tests; the agent |
| The answer is lost (crash or disconnect after the writes) | The caller doesn't know the result | `response.json` if it exists; otherwise as a crash |
| `temp/` not git-ignored | Records appear in `git status` and could be committed | Harness's requirement |
| `temp` replaced by a link between the check and a write | A record written elsewhere | Accepted: one check, no no-follow machinery |

## Removed from the design (d8f7e70) and from v3

Saved plans and their store, `planDigest`, `diffDigest`, the inputs fingerprint, plan lifetimes, caps, outcome records, `POST /v1/refactor/apply` and `/resolve-interrupted`, the on-disk journal and backups, startup recovery, atomic per-file writes (the writer stays in place, keeping inodes as T002.07 approved), the fresh whole-worktree check inside the write lock, the model-generation counter, the Git subprocess and its ignore check, no-follow directory handles, `operation-folder-unsafe`, `operation-folder-not-ignored`, `git-failed`, `plan-*`, `model-changed`, `apply-interrupted`, and the §10 rule that writers be excluded during applies.

New codes: `operation-exists`, `edit-changed` (2).

## Knock-on changes

- T002.10 shrinks to the above. The read-only mapping checks go back to T002.11 (they were moved only for saved plans).
- T002.11's soak drops plan applies and the same-worktree stale-plan clients; it applies dry runs with `expectWriteSetDigest`.
- T002.08 (the scratch folder) is unaffected.
- One small fix the reviews found stays on the list, outside this feature: `EditWriter.create` registers its undo only after writing, so a create that fails partway isn't undone (T002.10, one test).
