**Verdict: v4 fits Chris’s requirements; keep the architecture and tighten three small contract/documentation points.**

1. **Unnecessary or redundant?** No substantial overbuilding remains. Dry runs could omit `intent.json`: they need the diff and response, while intent-before-mutation is needed only for applies. That is an optional simplification. Keep the existing in-memory undo and the already-listed create-undo fix; neither justifies expanding recovery machinery.

2. **Anything necessary missing?** I found no realistic failure requiring recovery beyond Git, agent investigation, and escalation after two failed attempts. Failed rollback, failed reconciliation after successful writes, and failed response publication are identifiable engineering failures that this workflow can reasonably handle. A missing response means an uncertain outcome; after a disconnect, Harness must establish that the request has ended before restoring files. Stopping its recorded JDT LS PID and waiting for exit is sufficient—no acknowledgement protocol is needed.

3. **Is `writeSetDigest` right?** Yes, for comparing the **prepared filesystem actions and resulting bytes**. Its definition needs a little precision: include rename source and destination, deterministic ordering, unambiguous field boundaries, and an explicit distinction between deletion and an empty file. Compare even when recomputation produces no changes, before [CodeRequest’s early return](/home/nokes/github/nokesc/fractal-jdtls__worktrees/t002-http-endpoint/endpoint/src/main/java/org/fractal/jdtls/endpoint/CodeRequest.java:184).

   Under that output-comparison guarantee, additional input, metadata, model-generation, or diff digests are unnecessary. Equal write digests do not certify identical starting contents, identical semantic conditions, or correctness; compilation, tests, and agent review retain those responsibilities.

4. **Is the failure table accurate?** Mostly, with these qualifications:

   - **Mid-apply crash:** accurate. The writer moves files and then performs in-place writes, so partial edits and truncated files are possible.
   - **New reference:** the reference can be missed, but compilation does **not always** expose it—for example, the old name might still resolve through inheritance. Tests and investigation remain necessary.
   - **Concurrent editor writes:** overstated. [Edits checks before writing](/home/nokes/github/nokesc/fractal-jdtls__worktrees/t002-http-endpoint/endpoint/src/main/java/org/fractal/jdtls/endpoint/Edits.java:443); a later write can escape detection and be overwritten.
   - **Lost answer:** the proposed response file helps, but its presence means a terminal response exists, potentially an error. Its absence does not prove that a disconnected request has stopped.
   - **Undo:** §3’s “all-or-nothing” wording is stronger than today’s implementation. [Rollback can report unrestored files](/home/nokes/github/nokesc/fractal-jdtls__worktrees/t002-http-endpoint/endpoint/src/main/java/org/fractal/jdtls/endpoint/Edits.java:467). Also, [DiskSync can fail after successful source writes](/home/nokes/github/nokesc/fractal-jdtls__worktrees/t002-http-endpoint/endpoint/src/main/java/org/fractal/jdtls/endpoint/DiskSync.java:669). These need ordinary failure handling even when `response.json` exists.
   - The Git-ignore and substituted-link rows are reasonable accepted limitations. Operation folders themselves are proposed behavior, not implemented today.

**F1 — Minor — ADDS precision to the existing digest definition.**  
**Scenario:** a recomputed rename changes its source path while retaining the destination and result bytes. Hashing only the destination-shaped write entry would miss that difference; [the current writer represents `path` and `from` separately](/home/nokes/github/nokesc/fractal-jdtls__worktrees/t002-http-endpoint/endpoint/src/main/java/org/fractal/jdtls/endpoint/EditWriter.java:45). This is uncommon, but directly concerns the promised comparison.  
**Smallest fix:** specify a deterministic, length-framed sequence of worktree-relative `(kind, path, from-or-null, result-bytes-or-null)` entries. Exclude the operation UUID. Compare the empty write set too. Keep one digest.

**F2 — Minor — REMOVES unconditional detection and rollback claims.**  
**Scenario:** an editor saves after baseline validation, rollback encounters an I/O error, or reconciliation fails after writes. These are occasional engineering failures; the current code does not guarantee detection before mutation or complete restoration.  
**Smallest fix:** qualify the table and §3: checks detect changes observed before writing; undo is attempted and reports failures; builds can reveal missed references. Say that response presence establishes a completed response, not success, and that Harness establishes request termination before Git recovery. Add no endpoint recovery states or writer exclusion.

**F3 — Minor — REMOVES an unsupported fast-path claim.**  
**Scenario:** a dry run creates its records after synchronization, or an apply publishes its response after reconciliation. Skipping those files in `FileRecords` does not stop Eclipse’s independent [whole-project synchronization check](/home/nokes/github/nokesc/fractal-jdtls__worktrees/t002-http-endpoint/endpoint/src/main/java/org/fractal/jdtls/endpoint/DiskSync.java:729) from noticing them. This can happen routinely, forcing another refresh.  
**Smallest fix:** qualify §7’s rationale and accept that extra synchronization. If avoiding it matters in measurements, extend the existing Eclipse filtering approach to the exact operation subtree; Git-ignore alone does not provide that exclusion.

No files were modified.