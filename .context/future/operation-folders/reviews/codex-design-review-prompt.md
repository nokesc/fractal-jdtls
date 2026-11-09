You are reviewing a design change, not code. Commit 1cc5295 in this repository (`git show 1cc5295`) changes the design (`.context/design.md` §8 and §11) and the task series (`.context/planning/T002.md`, `T002.08.md` to `T002.12.md`). Read AGENTS.md, then the commit, then whatever design sections and code you need to judge it (especially §6, §11 "Keeping in step with the disk", "Worktrees", "Refactorings and edits", and the endpoint's `DiskSync`, `FileRecords`, `Edits`, `EditPlan`, `Mapping`). Don't modify anything.

## The project, briefly

Fractal JDT LS ships a verified Eclipse JDT LS install plus a Java agent, and (this series, T002) an HTTP endpoint inside JDT LS, so one JDT LS process is shared by many coding-agent sessions, each working in its own Git worktree. Fractal Harness (the orchestrator) launches it, routes requests and exposes them to LLMs over MCP. The endpoint owns all threading, keeps JDT LS in step with the disk at fixed points (no file watcher), and applies refactoring edits itself, all or nothing. The owner, Chris, ranks stability above everything: deterministic mechanisms that fail loudly over faster or heuristic ones.

## The problems this change addresses

1. **Test scratch in RAM.** `/tmp` here is a 7.8 GB tmpfs in a WSL VM. Every gate puts fixture Git worktrees, scratch JDT LS installs, `-data` folders, a generated 10,000-file "large tree" and logs there. It filled up once during this series, it competes for RAM with the JDT LS heaps, Maven and the agents, and a laptop restart wiped the records of earlier runs. The suite refuses any `.git` above its scratch folder (the agent assigns files to the nearest folder with `.git`), and fixture POMs must never be importable by a language server on the checkout, so the scratch can't simply go in the checkout's git-ignored `temp/`.
2. **Large refactoring results.** Every refactoring answer carries a unified diff. A 12,000-line rename's diff is 1.4 MB: too much for an HTTP answer and for an LLM's context.
3. **A dry run's plan is thrown away.** The caller reviews a dry run's diff, then the apply recomputes the edit (a first rename's search takes 0.7–1.7 s) and relies on baseline checks to catch changes. What's written isn't guaranteed to be what was reviewed.

## What Chris wants to achieve

- Intermediate data visible to him on disk, in the repository's git-ignored `temp/`, not hidden in `/tmp`.
- Since the endpoint, the coding agents (Claude Code, Codex) and Fractal Harness can all reach the worktree, use the file system to pass data between them where that's better than HTTP. His idea: Harness chooses a GUID per operation, the endpoint writes that operation's results into a folder named by it, and returns the GUID; a dry run's data could later be applied after review.
- This project has only one linked worktree at a time, so one scratch folder is enough.

## The governor's plan (the commit)

- **T002.08:** every pytest run's base folder moves to `<main checkout>__scratch/` (a sibling of the main checkout, found through Git's common folder), with a lock (one suite at a time), the latest three runs kept, and the `.git`-above check kept. Agents' other intermediate data goes in the checkout's `temp/`.
- **T002.10, "Operation folders" in §11:** each refactoring request carries a caller-chosen UUID; the endpoint creates `<worktree>/temp/fractal-jdtls/<id>/` (git-ignore checked, real path checked, kept out of Eclipse and the fixed point) with the request, the full diff, the answer, and for a dry run a signed `plan.json`. A new `POST /v1/refactor/apply` applies a saved plan after checks: not found, already applied, signature mismatch (HMAC with a key in the endpoint's `-data`), wrong worktree or module, and `plan-stale` if anything in the worktree's file record changed since the dry run. The inline diff is cut at a limit. HTTP stays the only control channel.
- **The diff** stays Myers' linear-space search (already fixed in T002.07's review), rather than being rebuilt from JDT's edits.
- Tasks renumbered so the new ones run before the concurrency soak (T002.11) and the release (T002.12).

## What to review

Judge whether the plan solves the problems and meets Chris's goals, and whether it's sound. In particular:

- Gaps or contradictions with the rest of the design (§6 worktree identity, §11's fixed point, record and fast path, locks, the apply, the contract's conventions, security: the token, localhost, symlinks, path handling).
- Whether `plan-stale` (any change in the worktree's record) is the right staleness rule: too strict to be useful, or not strict enough? Is there anything outside the record that could make a stale plan wrong (other worktrees it depends on, POM or classpath changes, files in other modules, Eclipse state)?
- Whether the signature, the key's storage, and treating `temp/` content as untrusted are sound; whether anything in the folder could be used to make the endpoint write outside the worktree or read secrets.
- Whether keeping the folder out of Eclipse and the fixed point is achievable as stated, given how the endpoint refreshes and records files (read the code).
- Whether the `git check-ignore` call is acceptable inside JDT LS's JVM (a subprocess per operation), and what happens without Git or with unusual worktree layouts.
- Whether the scratch folder plan has holes: the lock, the `.git` check, permissions, worktrees vs the main checkout, CI (`.github/workflows/release.yml` runs `mise run validate`).
- Simpler alternatives that meet the same goals with fewer moving parts.
- Sequencing and acceptance items in the task files: anything missing, untestable, or in the wrong task.

Your final message is the review itself, in Markdown: a one-line verdict, then numbered findings (D1, D2, …), each with severity (blocker, major, minor, suggestion), the file and section, the problem, and a concrete fix. Say which findings you're confident in. Keep exploration focused; finish within 12 minutes.
