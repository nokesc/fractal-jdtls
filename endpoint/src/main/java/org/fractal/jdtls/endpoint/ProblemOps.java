// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import com.google.gson.JsonObject;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * {@code POST /v1/build} and {@code POST /v1/problems} (design §11 "Builds and problems"): the fixed point for the
 * module's worktree ({@link DiskSync}), then the problems from Eclipse's markers ({@link Markers}). {@code problems}
 * takes the fast path when nothing changed; {@code build} never does, and with {@code clean: true} cleans the
 * worktree's projects first. Each waits as {@code modules/check} does: for a worker, for JDT LS's start and for a
 * worktree change, never past its deadline. No Eclipse class is named here, so none loads before JDT LS has started.
 */
final class ProblemOps {

    static final long PROBLEMS_TIMEOUT_DEFAULT_MS = 120_000;
    static final long BUILD_TIMEOUT_DEFAULT_MS = 300_000;
    static final int LIMIT_DEFAULT = 200;
    static final int LIMIT_MAX = 5000;
    static final List<String> SEVERITIES = List.of("error", "warning", "info");

    private final WorktreeOps ops;

    ProblemOps(WorktreeOps ops) {
        this.ops = ops;
    }

    void register(HttpApi http) {
        http.route("POST", "/v1/build", BUILD_TIMEOUT_DEFAULT_MS, r -> CompletableFuture.completedFuture(HttpApi.Response.json(200, run(r, true))));
        http.route("POST", "/v1/problems", PROBLEMS_TIMEOUT_DEFAULT_MS, r -> CompletableFuture.completedFuture(HttpApi.Response.json(200, run(r, false))));
    }

    private JsonObject run(HttpApi.Request request, boolean build) throws Problem {
        String operation = build ? "build" : "problems";
        Fields f = new Fields(HttpApi.object(request), "POST /v1/" + operation);
        String[] target = CodeRequest.target(f);
        String scope = f.oneOf("scope", List.of("module", "worktree"), "module");
        List<String> severities = f.strings("severities", SEVERITIES.size(), SEVERITIES);
        long limit = f.integer("limit", 1, LIMIT_MAX, LIMIT_DEFAULT);
        boolean clean = build && f.bool("clean", false);
        CodeRequest.Common c = CodeRequest.rest(f, target, build ? BUILD_TIMEOUT_DEFAULT_MS : PROBLEMS_TIMEOUT_DEFAULT_MS);
        f.done();
        CodeRequest.validate(c);
        java.util.Set<String> wanted = new LinkedHashSet<>(severities == null ? List.of("error") : severities);
        return CodeRequest.run(ops, request, operation, c, build, clean,
                s -> Markers.read(s.root(), scope.equals("module") ? s.folder() : null, wanted, (int) limit), o -> String.valueOf(o.get("counts")));
    }

    static String summary(DiskSync.Report r) {
        if (r.skipped) {
            return String.format(Locale.ROOT, "fast path (check %.1f ms: record of %d files %.1f, isSynchronized %.1f; total %.1f ms)", r.checkMs,
                    r.recorded, r.scanMs, r.synchronizedMs, r.totalMs);
        }
        String files = r.files.isEmpty() ? "" : " " + r.files.entrySet().stream().limit(5).map(e -> e.getValue() + " " + e.getKey()).toList()
                + (r.files.size() > 5 ? " and " + (r.files.size() - 5) + " more" : "");
        return String.format(Locale.ROOT, "%s: %d files changed%s, POMs updated %s; check %.1f (isSynchronized %.1f), rule %.1f, refresh %.1f"
                + " (record of %d files %.1f, refreshLocal %.1f, %d taken in %.1f), POMs %.1f, build %.1f%s, join %.1f, record saved %.1f, total %.1f ms%s",
                r.notSkippedBecause, r.files.size(), files, r.poms, r.checkMs, r.synchronizedMs, r.ruleWaitMs, r.refreshMs, r.recorded, r.scanMs,
                r.refreshLocalMs, r.touched, r.touchMs, r.pomsMs, r.buildMs, r.cleanBuild == null ? "" : " (cleaned first: " + r.cleanBuild + ")",
                r.joinMs, r.saveMs, r.totalMs,
                r.joined == null || r.joined.jobs().isEmpty() ? "" : "; joined " + r.joined.jobs());
    }

    /** A {@code changedPaths} entry's syntax: a file relative to the worktree, inside it. Null when it's valid. */
    static String pathSyntax(String path) {
        if (path.startsWith("/") || path.contains("\\") || path.contains("\0")) {
            return "must be relative to the worktree's root, with / separators, such as harness-app/src/main/java/App.java";
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return "must name a file inside the worktree: no empty, . or .. segments";
            }
        }
        return null;
    }
}
