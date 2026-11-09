**Request changes: the approach fits Chris’s goals, but the saved-plan guarantees and filesystem trust boundary need tightening before implementation.**

1. **D1 — major — Staleness validation must be part of the write barrier. Confidence: high.**  
   **[design.md §11, “Operation folders”](/home/nokes/github/nokesc/fractal-jdtls__worktrees/t002-http-endpoint/.context/design.md:494)** separates the whole-record comparison from the apply. Current `CodeRequest.edit` releases the first fixed point’s read lock before `DiskSync.apply` obtains the write lock; `Edits.write` then checks only affected files. A new reference added to an unaffected file in that gap can therefore escape the intended `plan-stale` check.

   **Fix:** compare the saved record against a fresh disk scan inside the apply’s write lock and workspace rule, immediately before affected-file validation and writing. Comparing only the last saved `DiskSync` record is insufficient. Add a deterministic pause test that inserts a reference during this gap. T002.11 also needs two clients targeting the **same** worktree; clients editing separate worktrees cannot exercise this race. Explicitly acknowledge that JVM locks do not exclude external filesystem writers.

2. **D2 — major — The file record is not a complete refactoring-input fingerprint. Confidence: high about coverage.**  
   **[design.md §11, saved-plan staleness](/home/nokes/github/nokesc/fractal-jdtls__worktrees/t002-http-endpoint/.context/design.md:501)** treats an unchanged record as sufficient. `FileRecords` covers regular files beneath imported project folders, skips symlinks and Maven output, and does not capture settings and project metadata under `-data`, external libraries, or all effective classpath inputs. Moreover, §6 provides a same-worktree **preference**, not isolation: `Boot.choose` retains m2e’s choice when there is no local candidate, so an actual dependency can belong to another worktree.

   Broad invalidation within the recorded scope is a reasonable conservative starting point; narrowing it to edited files would be unsafe. It is nevertheless incomplete outside that scope.

   **Fix:** define the supported semantic input boundary and persist an environment fingerprint covering effective project/classpath configuration and actual external dependencies, rejecting changes or unsupported inputs. Alternatively, explicitly promise byte replay without renewed refactoring-validity guarantees. Move the known mutating `Mapping.resolve` health-check fix from T002.11 ahead of saved-plan support: model mutation outside fixed points undermines its baseline.

3. **D3 — major — Saving text edits does not preserve the exact prepared writes. Confidence: high.**  
   **[design.md §11, plan contents](/home/nokes/github/nokesc/fractal-jdtls__worktrees/t002-http-endpoint/.context/design.md:493)** lists JDT edits, baseline hashes and records, but omits encoding decisions. Current `Edits` prepares the resulting bytes during the dry run, preserving charset, byte order and BOM. Reconstructing from text edits after a restart can use different Eclipse charset preferences without any worktree-record change.

   **Fix:** persist a versioned representation of the prepared byte writes, moves, deletions, expected absences and protected directories, with baseline and result hashes. Apply that representation without consulting current encoding defaults. Test non-ASCII edits and UTF-16/BOM cases across restart. In **T002.10 acceptance**, instrument refactoring computation to prove it is not invoked; a timing comparison cannot prove that.

4. **D4 — major — A valid signature does not establish which operation or diff was reviewed. Confidence: high.**  
   **[design.md §11, signature and apply checks](/home/nokes/github/nokesc/fractal-jdtls__worktrees/t002-http-endpoint/.context/design.md:498)** checks worktree and module, but not the signed request’s operation UUID. Two dry runs against the same baseline can produce different valid plans; copying one `plan.json` into the other operation’s folder passes the listed checks. Separately, `diff.patch` is unsigned, so changing the displayed diff need not invalidate the plan.

   **Fix:** verify the signed operation UUID and canonical worktree/module identity. Bind the review diff’s hash to the signed payload, return authenticated plan/diff digests in the preview response, and require the expected plan digest when applying. Test plan transplantation and diff replacement.

   A persistent HMAC-SHA-256 key is appropriate if `-data` is trusted. Mode 0600 does not isolate the key from agents running as the same Unix user; the design should state that boundary.

5. **D5 — major — Creation-time realpath checks do not confine later artifact access. Confidence: high.**  
   **[design.md §11, folder creation and atomic files](/home/nokes/github/nokesc/fractal-jdtls__worktrees/t002-http-endpoint/.context/design.md:481)** checks the directory before creation, while computation and later apply leave substantial opportunities to replace it or its ancestors with symlinks. Atomic rename prevents partial publication; it does not secure the path used to open a temporary file. A substituted `plan.json` can also direct reads outside the worktree or to a non-regular file before signature verification.

   **Fix:** specify directory-relative, no-follow access through verified directory handles, rejecting symlinks and non-regular artifact files. Bound plan reads and parsing before authentication, and use exclusive temporary-file creation. Apply equivalent safeguards to key creation/loading. Test ancestor replacement, leaf symlinks and oversized/non-regular plans, asserting that outside sentinel files are neither read nor changed.

6. **D6 — major — The exclusion promise needs a consistent namespace rule across Eclipse and disk scanning. Confidence: high.**  
   **[design.md §11, “never part of the Java world”](/home/nokes/github/nokesc/fractal-jdtls__worktrees/t002-http-endpoint/.context/design.md:483)** is achievable, but Git ignore does not implement it. Current `FileRecords.scan` skips only `.git` and qualifying `target` directories; Eclipse independently uses recursive refresh, `isSynchronized` and resource-change listeners.

   There is also an allowed counterexample: `temp/fractal-jdtls` can symlink to another directory **inside** the worktree. The realpath check passes, but artifacts then appear under that directory’s ordinary resource/record path.

   **Fix:** reject namespace aliases and reserve one exact subtree. Define matching exclusions for initial/restored records, recursive sync checks, refresh/listener handling and import discovery, installed before artifact creation. Account for creating the `temp/` parent itself; reserving all root-level `temp/` is simpler if acceptable. Extend T002.10’s test to pre-existing folders, cleanup, restart and internal symlink aliases.

7. **D7 — major — Applying source changes and publishing `applied.json` have no defined failure boundary. Confidence: high.**  
   **[design.md §11, receipt and already-applied check](/home/nokes/github/nokesc/fractal-jdtls__worktrees/t002-http-endpoint/.context/design.md:491)** leaves unspecified what happens when source writes succeed but receipt publication fails, the folder disappears, or the process stops before publication. A retry can report `plan-stale` with no authoritative indication that the edit was applied. An untrusted marker’s mere existence is also not reliable proof of application.

   **Fix:** define a persistent operation state and recovery protocol under the same serialization as application. Distinguish failure before source writes from failure to publish results after successful writes; preserve the existing rule that an answer reports what was written. Test simultaneous applies of one UUID, receipt-write failure and restart between source application and receipt publication.

8. **D8 — minor — Git is an acceptable subprocess, but its runtime contract is missing. Confidence: high.**  
   **[design.md §11, git-ignore check](/home/nokes/github/nokesc/fractal-jdtls__worktrees/t002-http-endpoint/.context/design.md:482)** introduces a shipped Git executable dependency where worktree identity currently needs only a `.git` marker. Missing Git, invalid metadata and command failure are different from “not ignored.” An unbounded subprocess would also violate endpoint deadlines.

   **Fix:** document the dependency; invoke Git directly with an explicit worktree directory, controlled repository-selection environment, bounded output and deadline cancellation. Catalogue execution failures separately from ignore refusal. Test missing Git, linked worktrees, separate Git directories and misleading inherited `GIT_DIR`/`GIT_WORK_TREE`. Measure its cost; there is no evident reason to prohibit a bounded subprocess inside the JVM.

9. **D9 — minor — Scratch-root discovery and allocation need an explicit safe fallback. Confidence: medium; ordinary checkout/CI layouts look sound.**  
   **[T002.08, “Changes”](/home/nokes/github/nokesc/fractal-jdtls__worktrees/t002-http-endpoint/.context/planning/T002.08.md:19)** assumes the main checkout can be derived from the common Git directory. Its parent is not necessarily the checkout for separate Git directories or bare-repository worktree arrangements. The design also leaves ownership, symlink handling and lock recovery unspecified.

   **Fix:** identify the main checkout using Git’s worktree metadata, or fail with an actionable override requirement. Validate the resolved scratch root’s ownership, permissions and `.git` ancestry—including the root itself—before allocation or pruning. Use a kernel-held lock outside pruned runs and test recovery after process death. Specify how pytest retention is retained: passing the persistent scratch root as `--basetemp` would delete its existing contents.

10. **D10 — minor — Three-run retention cannot preserve evidence that only cites run folders. Confidence: high.**  
    **[design.md §8, retention](/home/nokes/github/nokesc/fractal-jdtls__worktrees/t002-http-endpoint/.context/design.md:194)** says evidence cites folders instead of copying records out, but those folders are deliberately pruned. A gate also contains separate unit and integration pytest sessions, so three runs are fewer than three complete gates. CI scratch disappears with the runner.

    **Fix:** retain self-contained acceptance output in committed evidence, and explicitly archive or pin diagnostic runs worth preserving. If CI records should survive failures, upload them in the release workflow.

11. **D11 — suggestion — Keep authoritative plans under `-data` and publish review copies into `temp/`. Confidence: architectural recommendation.**  
    **[design.md §11, operation-folder architecture](/home/nokes/github/nokesc/fractal-jdtls__worktrees/t002-http-endpoint/.context/design.md:479)** can meet the visibility and restart goals with fewer trust mechanisms: store immutable prepared writes and authoritative receipts in endpoint-owned storage, addressed by worktree and UUID; publish the request, diff and review copy in the worktree; apply through HTTP using UUID and expected digest.

    This removes the need to authenticate and reimport executable plans from mutable `temp/`, although publication-path safety and cleanup still need definitions. Keeping Myers’ existing bounded, deadline-aware diff implementation is reasonable; this change supplies no compelling reason to replace it.