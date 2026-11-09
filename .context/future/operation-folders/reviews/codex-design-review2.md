**Request changes: the revised architecture addresses most findings, but D2, D4 and D7 remain partly resolved.**

1. **D1 — resolved.** Fresh validation now occurs inside the write lock and workspace rule. Deterministic race testing, same-worktree clients and the limitation concerning external writers are covered.

2. **D2 — partly resolved: external-input changes can still escape the fingerprint.** **Major; high confidence.** [§11, inputs fingerprint](/home/nokes/github/nokesc/fractal-jdtls__worktrees/t002-http-endpoint/.context/design.md:489) fingerprints JARs using path, size and modification time. Replacing a dependency with different bytes while preserving size and mtime therefore leaves a plan apparently valid—the same failure that the worktree’s ctime/inode record was designed to catch. Directory classpath entries and generated or linked sources outside the record also lack a defined content check. **Fix:** fingerprint actual external source/binary inputs using content hashes or suitable file records, and reject unsupported inputs. Add preserved-mtime JAR replacement and directory-classpath tests.

3. **D3 — resolved.** Prepared bytes, file operations, baseline/result hashes and encoding tests now preserve the intended writes. Instrumentation replaces timing as proof that application does not recompute.

4. **D4 — partly resolved: the published diff cannot be independently verified.** **Major; high confidence.** [§11, `planDigest`](/home/nokes/github/nokesc/fractal-jdtls__worktrees/t002-http-endpoint/.context/design.md:486) binds the authoritative plan and diff, but the caller cannot verify `diff.patch` against that digest without the private plan. Replacing the publication can still cause review of different changes while the original digest remains accepted. **Fix:** return an authenticated `diffDigest` alongside `planDigest`, define their binding, and require consumers to verify the exact bytes they review. Test that publication tampering fails this verification.

5. **D5 — resolved.** Directory-relative, no-follow publication and exclusive temporary files address the access boundary. Eliminating plan reads from the publication removes the unauthenticated parsing risk.

6. **D6 — resolved.** The exact reserved subtree, matching exclusions across disk and Eclipse handling, and rejection of internal symlink aliases address the design concern.

7. **D7 — partly resolved: authoritative outcome failure and recovery remain undefined.** **Major; high confidence.** [§11, outcome recording](/home/nokes/github/nokesc/fractal-jdtls__worktrees/t002-http-endpoint/.context/design.md:508) handles publication-copy failures, but says only that the authoritative outcome is recorded before answering. It does not define what happens if that recording fails, or the process stops after source writes. Post-write reconciliation can also fail: current [DiskSync](/home/nokes/github/nokesc/fractal-jdtls__worktrees/t002-http-endpoint/endpoint/src/main/java/org/fractal/jdtls/endpoint/DiskSync.java:663) can throw after writing. Dropping plans at restart does not recover the outcome. **Fix:** persist an apply intent before writing, record successful source application before reconciliation, and recover incomplete intents before startup cleanup. Ambiguous or partial results must fail loudly without replay. Test authoritative-store failure and termination between source application and outcome recording.

8. **D8 — resolved.** Git’s runtime requirement, controlled environment, bounded execution/output, deadline and distinct execution failure are specified.

9. **D9 — resolved.** Git-based discovery, an explicit override, scratch-root checks, kernel locking and retention without `--basetemp` address the design finding.

10. **D10 — resolved.** Evidence remains self-contained, retention accounts for both pytest sessions, and failed CI gates archive scratch diagnostics.

11. **D11 — resolved.** The authoritative plan resides under `-data`; the worktree contains publications only. Restart survival has deliberately been replaced with restart invalidation.

- **N1 — minor — Operation history remains unbounded. Confidence: high.** [§11, operation IDs and lifetime](/home/nokes/github/nokesc/fractal-jdtls__worktrees/t002-http-endpoint/.context/design.md:482) requires remembering every used ID since startup and retaining applied/stale outcomes, while the cap covers live plans. A long-running shared endpoint can therefore accumulate unlimited authoritative metadata despite plan eviction. **Fix:** bound retained operation history as well as live plans, with explicit expiry semantics or an actionable quota refusal. Extend T002.11 to check store growth across many completed and invalidated operations.