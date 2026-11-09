// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * What every operation that depends on code does around its answer (design §11 "The contract", "Keeping in step with
 * the disk", "Threading and queuing"): it reads {@code worktree}, {@code module}, {@code changedPaths},
 * {@code timeoutMs} and {@code wait}; waits for a worker, for JDT LS's start and for a worktree change, never past
 * its deadline; maps the module with every check ({@link Mapping}); runs the fixed point for the module's worktree
 * ({@link DiskSync}); and reads its answer under the worktree's read lock, so no fixed point changes the worktree
 * while it's read. The answer's fields go beside {@code sync}. No Eclipse class is named here, so none loads before
 * JDT LS has started.
 */
final class CodeRequest {

    static final int CHANGED_PATHS_MAX = 10_000;

    /** The fields every such operation takes; the operation reads its own between {@link #read} and {@link Fields#done}. */
    record Common(String worktree, String module, List<String> changedPaths, long timeoutMs, boolean waits) {}

    /** Where the answer is read: the worktree, the module (as mapped) and its folder, and the request's deadline. */
    record Scope(Path root, Registry.Module module, Path folder, long deadline) {}

    interface Answer {
        /** The answer, under the worktree's read lock; a {@link Problem} goes to the caller as it is. */
        JsonObject read(Scope scope) throws Exception;
    }

    private CodeRequest() {}

    /** {@code worktree} and {@code module} first; {@code changedPaths}, {@code timeoutMs} and {@code wait} are read at {@link #rest}. */
    static String[] target(Fields f) {
        return new String[] {f.string("worktree", true), f.string("module", true)};
    }

    static Common rest(Fields f, String[] target, long timeoutDefault) {
        List<String> changed = f.strings("changedPaths", CHANGED_PATHS_MAX, null);
        long timeoutMs = f.integer("timeoutMs", 1, HttpApi.TIMEOUT_MAX_MS, timeoutDefault);
        boolean wait = f.bool("wait", true);
        return new Common(target[0], target[1], changed == null ? List.of() : changed, timeoutMs, wait);
    }

    /** The module's and the changed paths' syntax, after {@link Fields#done}: one {@code invalid-request} for all. */
    static void validate(Common c) throws Problem {
        Problem invalid = Problem.of(Code.INVALID_REQUEST);
        int errors = 0;
        if (c.module() != null && Mapping.moduleSyntax(c.module()) != null) {
            invalid.error("/module", Mapping.moduleSyntax(c.module()));
            errors++;
        }
        for (int i = 0; i < c.changedPaths().size(); i++) {
            String syntax = ProblemOps.pathSyntax(c.changedPaths().get(i));
            if (syntax != null) {
                invalid.error("/changedPaths/" + i, syntax);
                errors++;
            }
        }
        if (errors > 0) {
            throw invalid.detail(errors == 1 ? "1 field is invalid." : errors + " fields are invalid.");
        }
    }

    /**
     * Runs the operation. {@code build} never takes the fast path, and with {@code clean} cleans the worktree's
     * projects first; otherwise the fast path is taken when nothing changed and no {@code changedPaths} were named.
     * {@code log} turns the answer into a few words for the endpoint's log.
     */
    static JsonObject run(WorktreeOps ops, HttpApi.Request request, String operation, Common c, boolean build, boolean clean, Answer answer,
            java.util.function.Function<JsonObject, String> log) throws Problem {
        return within(ops, request, operation, c, (scope, files, op, timeout) -> {
            String forced = build ? "build" : files.isEmpty() ? null : "changed-paths";
            DiskSync.Result result = timeout.of(() -> ops.sync().sync(scope.root(), forced, clean, files, scope.deadline(), op::phase, () -> {
                op.phase("running");
                return answer.read(scope);
            }));
            JsonObject o = new JsonObject();
            o.add("sync", result.report().toJson());
            result.answer().entrySet().forEach(e -> o.add(e.getKey(), e.getValue()));
            return new Logged(o, ProblemOps.summary(result.report()) + "; " + log.apply(result.answer()));
        });
    }

    /** A refactoring's edit, computed and checked, not yet written. No Eclipse type is named here. */
    interface Pending {
        /** The edit as {@code dryRun} returns it: {@code files}, {@code diff}, {@code writeSetDigest}, and JDT's {@code warnings}. */
        JsonObject preview();

        /** The full unified diff: the operation folder's {@code diff.patch}. */
        String diff();

        /** The SHA-256 of the exact writes (design §11 "Operation folders"), which {@code expectWriteSetDigest} is compared with. */
        String writeSetDigest();

        /** Whether the edit changes no file: then there's nothing to apply. */
        boolean unchanged();

        /** The files the apply writes, created and deleted ones included. */
        List<Path> written();

        /** Under the worktree's write lock and the workspace rule: the check against the baseline, then the writes. */
        void write() throws Exception;

        /** After the apply's fixed point, under the read lock: the problems. */
        JsonObject after() throws Exception;

        /** For the endpoint's log: the files, and what the check and the writes cost. */
        String log();
    }

    interface Compute {
        /** Under the worktree's read lock, after the fixed point: the edit, and its baseline. */
        Pending compute(Scope scope) throws Exception;
    }

    /**
     * A refactoring (design §11 "Refactorings and edits", "Operation folders"). The fixed point, and the edit computed
     * under the read lock (its baseline the record that fixed point left). Then its operation folder, made after that
     * fixed point so the folder's files don't make it a full one: the ID on the in-flight entry first, then the folder,
     * {@code request.json}, and the edit's {@code diff.patch}, or the problem the computation answered (a JDT
     * refusal, an addressing error), which is then the answer. A fixed point that fails (a timeout) leaves no folder. With {@code expectWriteSetDigest}, the edit's digest must be it, else {@code edit-changed},
     * nothing written; with {@code dryRun}, its diff. Otherwise the apply, one more fixed point: under the write lock
     * and the workspace rule, the baseline check, the writes, the refresh, the build and the join; then the problems,
     * read under the read lock. {@code sync} is the first fixed point and {@code syncAfter} the apply's. Last, the
     * answer, success or problem, as the caller gets it, in {@code response.json}; the in-flight entry closes after it.
     */
    static JsonObject edit(WorktreeOps ops, HttpApi http, HttpApi.Request request, JsonObject body, String operation, Common c, boolean dryRun,
            String expectWriteSetDigest, Compute compute, java.util.function.Function<JsonObject, String> log) throws Problem {
        return within(ops, request, operation, c, (scope, files, op, timeout) -> {
            Instant received = Instant.now().minusNanos(System.nanoTime() - request.received());
            OperationFolder[] folder = new OperationFolder[1]; // made by refactor(), after the first fixed point
            Opening opening = () -> {
                folder[0] = OperationFolder.create(scope.root(), received, op::operation);
                folder[0].request(received, operation, body);
                return folder[0];
            };
            try {
                Logged done = refactor(ops, scope, files, op, timeout, opening, dryRun, expectWriteSetDigest, compute, log);
                record(folder[0], done.answer());
                return done;
            } catch (Problem p) {
                record(folder[0], http.problemJson(p, request.instance()));
                throw p;
            } catch (Exception e) {
                // As within() turns it into the caller's internal problem.
                record(folder[0], http.problemJson(new IllegalStateException(e), request.instance()));
                throw e;
            } catch (Error e) {
                record(folder[0], http.problemJson(e, request.instance()));
                throw e;
            }
        });
    }

    /** Makes the operation folder and its {@code request.json}. */
    private interface Opening {
        OperationFolder open() throws Problem, IOException;
    }

    private static Logged refactor(WorktreeOps ops, Scope scope, List<Path> files, InFlight.Op op, Timeout timeout, Opening opening, boolean dryRun,
            String expectWriteSetDigest, Compute compute, java.util.function.Function<JsonObject, String> log) throws Exception {
        Pending[] pending = new Pending[1];
        Problem[] refused = new Problem[1];
        DiskSync.Result first = timeout.of(() -> ops.sync().sync(scope.root(), files.isEmpty() ? null : "changed-paths", false, files,
                scope.deadline(), op::phase, () -> {
                    op.phase("running");
                    try {
                        pending[0] = compute.compute(scope);
                    } catch (Problem p) {
                        refused[0] = p; // answered once the folder holds the request
                        return new JsonObject();
                    }
                    return pending[0].preview();
                }));
        OperationFolder folder = opening.open();
        if (refused[0] != null) {
            throw refused[0];
        }
        folder.diff(pending[0].diff());
        if (expectWriteSetDigest != null && !expectWriteSetDigest.equals(pending[0].writeSetDigest())) {
            // Compared even when the edit now changes nothing.
            throw Problem.of(Code.EDIT_CHANGED)
                    .detail("The edit computed now isn't the one expected: its writeSetDigest is " + pending[0].writeSetDigest() + ", not "
                            + expectWriteSetDigest + ", the expectWriteSetDigest sent. Nothing was written.")
                    .with("writeSetDigest", pending[0].writeSetDigest())
                    .with("diff", pending[0].diff());
        }
        JsonObject o = new JsonObject();
        o.add("sync", first.report().toJson());
        o.addProperty("operation", folder.id);
        o.addProperty("operationFolder", folder.relative);
        boolean apply = !dryRun && !pending[0].unchanged();
        o.addProperty("applied", apply);
        first.answer().entrySet().forEach(e -> o.add(e.getKey(), e.getValue()));
        String summary = "operation " + folder.id + "; " + ProblemOps.summary(first.report()) + "; " + (dryRun ? "dry run, " : "") + log.apply(o);
        if (!apply) {
            return new Logged(o, summary + (dryRun ? "" : "; nothing to change"));
        }
        TestPauses.pause("before-apply", scope.deadline());
        DiskSync.Result after = timeout.of(() -> ops.sync().apply(scope.root(), pending[0].written(), scope.deadline(), op::phase, pending[0]::write, () -> {
            op.phase("running");
            return pending[0].after();
        }));
        o.add("syncAfter", after.report().toJson());
        after.answer().entrySet().forEach(e -> o.add(e.getKey(), e.getValue()));
        return new Logged(o, summary + "; applied in " + after.report().applyMs + " ms (" + pending[0].log() + "), then " + ProblemOps.summary(after.report()) + "; "
                + o.get("counts"));
    }

    /** {@code response.json}: a failure to write it is logged, and the caller still gets the answer it records. */
    private static void record(OperationFolder folder, JsonObject answer) {
        if (folder == null) {
            return; // refused before the folder was made
        }
        try {
            folder.response(answer);
        } catch (IOException | RuntimeException e) {
            Log.error("can't write response.json in the operation folder " + folder.relative + "; the caller still gets its answer", e);
        }
    }

    /** An answer, and what the log says of it. */
    private record Logged(JsonObject answer, String log) {}

    /** Turns a fixed point that its deadline stopped into {@code timeout}. */
    private interface Timeout {
        DiskSync.Result of(Step step) throws Exception;
    }

    private interface Step {
        DiskSync.Result run() throws Exception;
    }

    private interface Body {
        Logged run(Scope scope, List<Path> changedPaths, InFlight.Op op, Timeout timeout) throws Exception;
    }

    /** The waits, the mapping and the problems every operation on code shares, around {@code body}. */
    private static JsonObject within(WorktreeOps ops, HttpApi.Request request, String operation, Common c, Body body) throws Problem {
        long deadline = request.received() + c.timeoutMs() * 1_000_000;
        try (InFlight.Op op = ops.inFlight().begin(request.path(), c.worktree(), c.module())) {
            ops.refuseIfExpired(request, deadline, c.timeoutMs());
            ops.awaitReady(deadline, c.waits(), op);
            ops.refuseIfExpired(request, deadline, c.timeoutMs());
            try (Alone.Pass pass = ops.alone().enter(deadline, c.waits(), () -> op.phase("waiting-for-import"))) {
                op.phase("running");
                Registry.Module m = Mapping.resolve(ops.registry(), c.worktree(), c.module());
                Registry.Worktree w = Mapping.registered(ops.registry(), c.worktree());
                List<Path> files = new ArrayList<>(new LinkedHashSet<>(c.changedPaths().stream().map(p -> w.root.resolve(p).normalize()).toList()));
                Path folder = m.module().equals(".") ? w.root : w.root.resolve(m.module());
                Scope scope = new Scope(w.root, m, folder, deadline);
                Timeout timeout = step -> {
                    try {
                        return step.run();
                    } catch (DiskSync.Cancelled cancelled) {
                        // What stopped, and when: the work itself saw the cancel (a monitor, JDT LS's -32800), not just the answer.
                        Log.info(String.format(Locale.ROOT, "%s %s: timeout: stopped %s %.1f ms after the deadline (phase %s)", request.instance(), operation,
                                cancelled.getMessage(), (System.nanoTime() - deadline) / 1e6, cancelled.phase));
                        throw Problem.of(Code.TIMEOUT)
                                .detail(String.format(Locale.ROOT, "%s didn't finish within its timeoutMs of %,d ms, so the endpoint stopped %s.", operation,
                                        c.timeoutMs(), cancelled.getMessage()))
                                .with("phase", cancelled.phase);
                    }
                };
                Logged result;
                try {
                    result = body.run(scope, files, op, timeout);
                } catch (Problem p) {
                    throw p;
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(operation + " on " + w.root + " " + m.module() + " failed", e);
                }
                Log.info(request.instance() + " " + operation + " " + w.root + " " + m.module() + ": " + result.log()
                        + (pass.waitedMs > 0 ? "; waited " + pass.waitedMs + " ms for a worktree change" : ""));
                return result.answer();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}
