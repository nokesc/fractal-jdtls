**Request changes: the simplification is worthwhile, but use an exact-write digest and a small, fully specified recovery journal under `-data`.**

**1. Is `expectDiffDigest` sufficient? No.**

`Edits` prepares encoded bytes separately from the decoded texts passed to `UnifiedDiff`. Equal printed diffs therefore do not imply equal writes. See [byte preparation and diff construction](/home/nokes/github/nokesc/fractal-jdtls__worktrees/t002-http-endpoint/endpoint/src/main/java/org/fractal/jdtls/endpoint/Edits.java:129).

| Case | What the current code establishes |
|---|---|
| Charset, BOM, byte order | Different bytes can produce identical decoded texts and diffs. For example, the same rename in UTF-8 with and without its BOM prints identically; the resulting bytes differ. |
| Unchanged content outside hunks | Omitted from the diff, although `EditWriter` rewrites the complete file. Changing a distant comment between preview and apply can leave the diff identical while changing the prepared write. |
| Line separators | Preserved: CRLF and LF compare differently and appear differently in the patch. |
| Trailing newline | Preserved by the `No newline at end of file` marker. |
| File modes and other metadata | Not represented. The current writer preserves existing metadata for modifications and moves; it does not bind that metadata to review. |
| Creates, deletes and moves | Ordinary headers distinguish creation/deletion from a move, but do not identify complete contents or metadata. A content-preserving move prints only its two path headers. |
| Binary or invalid bytes | Invalid or non-round-trippable text is refused by `Edits.decode`. There is no general binary-patch representation; this restriction should remain explicit. |

The newline behavior is directly implemented in [UnifiedDiff](/home/nokes/github/nokesc/fractal-jdtls__worktrees/t002-http-endpoint/endpoint/src/main/java/org/fractal/jdtls/endpoint/UnifiedDiff.java:34). Encoding validation is in [Edits.decode](/home/nokes/github/nokesc/fractal-jdtls__worktrees/t002-http-endpoint/endpoint/src/main/java/org/fractal/jdtls/endpoint/Edits.java:223).

The minimal correction is a separate `writeSetDigest`, computed from the exact prepared writes, with `expectWriteSetDigest` on apply. Keep `diffDigest` for verifying the publication. Neither requires saved plans.

**2. Does dropping saved plans lose correctness or stability? No essential guarantee, after that correction.**

Recomputation reruns JDT’s current applicability and safety checks. Changed inputs may cause refusal or a different write digest, which is appropriate. There is no need to preserve the old computation’s entire environment merely to compare its prepared writes with a newly computed result.

The losses are recomputation time and the ability to replay an old result when the engine now refuses it. Neither warrants the saved-plan machinery under Chris’s priorities.

Dropping the outcome store separately removes definitive retry answers after a lost response. That is acceptable if the contract says outcomes can be uncertain and Harness reconciles before retrying. `operation-exists` establishes folder existence, not successful application.

**3. Crash safety: prefer copies under `-data`; neither option is complete as written.**

Git blobs introduce dependencies unrelated to applying an edit:

- A successful `hash-object -w` is not, by itself, a durability guarantee. Git’s documented default commonly excludes loose-object hardening. [Git configuration](https://git-scm.com/docs/git-config#Documentation/git-config.txt-corefsync)
- The restore record is not a Git reachability root. Default expiry can eventually remove its backups, and configured expiry or `gc --prune=now` can remove them sooner. [Git garbage collection](https://git-scm.com/docs/git-gc)
- Alternates add another object store’s availability and retention to recovery. Linked worktrees also commonly have a `.git` **file** and share the common repository’s objects. Use Git discovery and commands; never assume `<worktree>/.git/objects`. [Repository layout](https://git-scm.com/docs/gitrepository-layout)
- SHA-256 repositories are supported Git layouts, not an error case. Record the object format and full returned object IDs; do not assume 40 hexadecimal characters. [Object-format discovery](https://git-scm.com/docs/git-rev-parse#Documentation/git-rev-parse.txt---show-object-formatstorageinputoutputcompat)
- Blobs contain bytes, not paths, permissions, ownership or ACLs. Those still need journaling. Object-store permissions can also expose an ignored file’s contents more broadly than its original permissions did.

`hash-object --stdin` does capture raw bytes, including untracked and ignored files; filters are implicitly disabled without `--path`. That part is sound. Ordinary Git history/checkout does **not** protect those files or uncheckpointed edits. [Git hash-object](https://git-scm.com/docs/git-hash-object)

Private copies under `-data` avoid those dependencies and are equally recoverable by hand if their format is documented. “Only the endpoint can recover” is unnecessary.

Both options share two larger problems:

- A kill after [truncate-before-write](/home/nokes/github/nokesc/fractal-jdtls__worktrees/t002-http-endpoint/endpoint/src/main/java/org/fractal/jdtls/endpoint/EditWriter.java:122) can leave bytes matching neither original nor result. The proposed recovery then refuses the very failure it was introduced to repair.
- A rename followed by a content edit has an intermediate state: source absent, destination holding the **original source bytes**. That state must be explicitly recoverable.

Write-temp-then-rename would substantially simplify per-file crash behavior: fully prepare and sync a sibling temporary file, then publish it atomically. Atomic replacement does not make a multi-file refactoring atomic, so a small transaction journal remains necessary. Directory changes also require durability handling; syncing a file alone does not durably publish its directory entry. [Linux rename](https://man7.org/linux/man-pages/man2/rename.2.html), [Linux fsync](https://man7.org/linux/man-pages/man2/fsync.2.html)

Replacement changes the inode and hard-link behavior. It therefore requires an explicit revision to the current inode-preservation promise, metadata preservation or refusal, and preservation of the current read-only-file refusal. Creates and move destinations must still never overwrite an unexpected file.

**4. Whole-record checking inside the write lock: correct, necessary, but only sufficient within its scope.**

It closes the identified gap for a new reference in an otherwise unaffected, recorded file. Capture an immutable copy of the computation’s baseline and compare it with a **fresh scan**, under both locks, before writing. Do not compare against whichever cached record happens to be current.

Three limits remain:

- `FileRecords` scans imported project locations, skips symlinks and Maven output, and does not cover all external dependencies or Eclipse configuration.
- Its current scanner silently ignores traversal/stat failures. An incomplete scan cannot establish that inputs are unchanged. See [FileRecords](/home/nokes/github/nokesc/fractal-jdtls__worktrees/t002-http-endpoint/endpoint/src/main/java/org/fractal/jdtls/endpoint/FileRecords.java:67).
- Filesystem equality does not establish that the relevant Eclipse model stayed unchanged during the read-lock-to-write-lock gap. A settings/classpath change can leave source records equal.

Keep the model stable across computation and application, or detect relevant model-generation changes and recompute/refuse. This needs no persistent saved-plan fingerprint.

Finally, neither JVM lock excludes editors, Git or agents. Even affected-file byte checks leave a check-to-write race. A stronger guarantee requires Harness to serialize external writes to that worktree during apply; the endpoint cannot obtain it through another scan.

**5. What should remain, and what is still overbuilt?**

Keep publication-only folders, no-follow access, namespace exclusions, bounded responses and existing per-file validation. Keep transient crash-recovery state and explicit reporting of successful writes when later reconciliation or publication fails.

Remove saved-plan storage, lifetime management and persistent refactoring history. Harness and Git can own checkpoints, history and deliberate undo. The bounded ignore check is reasonable as a boundary assertion; delegating it to Harness is also reasonable. It is not a security boundary.

The Git-blob recovery backend adds more lifecycle machinery than private backup copies. The simplification should choose copies and retain only unfinished transactions and the completion information needed to resolve interrupted requests.

**S1 — Major — The review token identifies presentation, not the writes.**

Proposal point 2’s “equal digests mean equal writes” claim is false without any hash collision.

**Fix:** hash a versioned, unambiguous canonical representation containing worktree/module identity, operation kinds, source/destination paths, expected existence, full original/result byte hashes, and metadata effects the writer promises. Exclude the request UUID so preview and apply can use different operation folders. Compare before every apply, including a recomputed no-op. Return `diffDigest` alongside it and require readers to verify published patch bytes against the HTTP value.

**S2 — Major — Unreferenced Git objects are not dependable recovery storage.**

Proposal point 4(a) relies on object retention, durability, repository topology and permissions that the endpoint does not control.

**Fix:** choose private, versioned backup files under `-data`, with restrictive permissions and verified content hashes. Retain every unresolved backup until recovery or explicit resolution; never evict one to satisfy a storage cap.

**S3 — Major — The crash protocol does not cover its own intermediate states or durable completion.**

Both proposed backends leave truncated files ambiguous, omit a complete rename-state model, and do not specify durable source writes before durable “done.” Recovery can itself crash.

There is also a current live-process rollback hole: [EditWriter.create](/home/nokes/github/nokesc/fractal-jdtls__worktrees/t002-http-endpoint/endpoint/src/main/java/org/fractal/jdtls/endpoint/EditWriter.java:130) registers undo **after** `Files.write`; a partial write failure can leave a created file without an undo entry.

**Fix:** use staged atomic file publication and a restart-safe, idempotent rollback protocol. Durably publish originals, metadata and intent before mutation; sync changed files and affected directories before marking completion; retain the journal through durable rollback. Recover before imports or requests, revalidate path confinement without following substituted links, and block mutation of an unresolved worktree. Add interruption tests during creation, rename, commit and rollback.

**S4 — Major — Removing outcomes must not remove the post-write failure boundary.**

The proposal drops explicit handling previously present for failed response publication and reconciliation. Current [DiskSync](/home/nokes/github/nokesc/fractal-jdtls__worktrees/t002-http-endpoint/endpoint/src/main/java/org/fractal/jdtls/endpoint/DiskSync.java:663) can fail after source writes.

**Fix:** record durable source completion before refresh/build. Return that the edit was applied when later reconciliation or publication fails, identifying the failure separately. If completion recording fails after mutation, retain an uncertain transaction for recovery. No historical outcome table is required.

**S5 — Major — Record equality needs fail-closed scanning and a model-stability boundary.**

Proposal point 30 correctly strengthens affected-file validation, but it overstates what the record proves. Silently omitted inputs and intervening model changes remain possible.

**Fix:** compare the immutable computation baseline with a fresh, error-reporting scan; refuse unreadable relevant inputs. Keep relevant model state stable or invalidate the computation when it changes. State the external-writer limitation explicitly and put stronger worktree serialization in Harness.