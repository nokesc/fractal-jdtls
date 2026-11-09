// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The worktree operations (design §11 "Worktrees"): {@code worktrees/add}, {@code /remove} and {@code /list}, and
 * {@code modules/check}, which maps a {@code worktree} and {@code module} to its project with every check of "The
 * contract" and nothing else.
 *
 * <p>Worktree changes run one at a time on their own thread, alone ({@link Alone}): each sends
 * {@code workspace/didChangeWorkspaceFolders}, then the barrier, then joins the jobs it scheduled ({@link Jobs}).
 * Every add, and every restart, ends with the import health check of each module, and at most one repair: removing
 * and adding the worktree again. A change, once started, is never cancelled: a folder change cut short would leave
 * JDT LS's projects half made. A request's timeout bounds only its own wait, and it then gets {@code not-ready}.
 */
final class WorktreeOps {

    static final long CHANGE_TIMEOUT_DEFAULT_MS = 900_000;
    static final long CHECK_TIMEOUT_DEFAULT_MS = 120_000;
    static final long BARRIER_GUARD_MINUTES = 5;
    static final int METHOD_NOT_FOUND = -32601;

    private final LspClient lsp;
    private final Registry registry;
    private final Supplier<CompletableFuture<Void>> ready;
    private final LongSupplier uptimeMs;
    private final Alone alone = new Alone();
    private final InFlight inFlight = new InFlight();
    private final ExecutorService changes = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fractal-endpoint-worktrees");
        t.setDaemon(true);
        return t;
    });
    private volatile Jobs jobs;
    private volatile DiskSync sync;

    WorktreeOps(LspClient lsp, Registry registry, Supplier<CompletableFuture<Void>> ready, LongSupplier uptimeMs) {
        this.lsp = lsp;
        this.registry = registry;
        this.ready = ready;
        this.uptimeMs = uptimeMs;
    }

    void register(HttpApi http) {
        http.route("POST", "/v1/worktrees/add", CHANGE_TIMEOUT_DEFAULT_MS, r -> done(add(r)));
        http.route("POST", "/v1/worktrees/remove", CHANGE_TIMEOUT_DEFAULT_MS, r -> done(remove(r)));
        http.route("POST", "/v1/worktrees/list", r -> done(list(r)));
        http.route("POST", "/v1/modules/check", CHECK_TIMEOUT_DEFAULT_MS, r -> done(check(r)));
        new ProblemOps(this).register(http);
        new NavOps(this).register(http);
        new EditOps(this).register(http);
        new LspPassthrough(this).register(http);
    }

    Alone alone() {
        return alone;
    }

    InFlight inFlight() {
        return inFlight;
    }

    Registry registry() {
        return registry;
    }

    LspClient lsp() {
        return lsp;
    }

    DiskSync sync() {
        return sync;
    }

    /**
     * Called once JDT LS has answered {@code initialize}: from then on, every scheduled job is numbered, and every
     * resource change is seen. Lightweight auto-refresh must be off (design §11 "Taking in changes exactly"): the
     * launcher sets its default through {@code -Declipse.pluginCustomization}, and a workspace that turns it on
     * again stops startup, rather than letting it take changes in behind the fixed points.
     */
    void initialized() throws StartupException {
        if (DiskSync.lightweightRefresh()) {
            throw new StartupException("Eclipse's lightweight auto-refresh (org.eclipse.core.resources/" + DiskSync.LIGHTWEIGHT_REFRESH
                    + ") is on, though HTTP mode's preference defaults turn it off. The workspace's own preferences, in"
                    + " <data>/.metadata/.plugins/org.eclipse.core.runtime/.settings/org.eclipse.core.resources.prefs, may set it: remove it there.");
        }
        jobs = new Jobs();
        sync = new DiskSync(jobs, registry.folder().resolve("records"));
        Log.info("lightweight auto-refresh is off; the fixed points' resource listener is on");
    }

    private static CompletableFuture<HttpApi.Response> done(JsonObject body) {
        return CompletableFuture.completedFuture(HttpApi.Response.json(200, body));
    }

    // --- status ----------------------------------------------------------------------------------

    void status(JsonObject o) {
        JsonArray worktrees = new JsonArray();
        registry.all().forEach(w -> worktrees.add(w.summary()));
        o.add("worktrees", worktrees);
        Alone.Change change = alone.current();
        if (change != null && change.worktree != null) {
            JsonObject importing = new JsonObject();
            importing.addProperty("worktree", change.worktree.toString());
            importing.addProperty("sinceMs", (System.nanoTime() - change.startedAt) / 1_000_000);
            o.add("importing", importing);
        } else {
            o.add("importing", JsonNull.INSTANCE);
        }
        JsonArray dropped = new JsonArray();
        for (Registry.Dropped d : registry.dropped()) {
            JsonObject e = new JsonObject();
            e.addProperty("worktree", d.worktree());
            e.addProperty("reason", d.reason());
            dropped.add(e);
        }
        o.add("droppedWorktrees", dropped);
        o.add("inFlight", inFlight.toJson());
    }

    // --- the routes ------------------------------------------------------------------------------

    private JsonObject add(HttpApi.Request request) throws Problem {
        Fields f = new Fields(HttpApi.object(request), "POST /v1/worktrees/add");
        String given = f.string("worktree", true);
        long timeoutMs = f.integer("timeoutMs", 1, HttpApi.TIMEOUT_MAX_MS, CHANGE_TIMEOUT_DEFAULT_MS);
        boolean wait = f.bool("wait", true);
        f.done();
        long deadline = request.received() + timeoutMs * 1_000_000;
        try (InFlight.Op op = inFlight.begin(request.path(), given, null)) {
            refuseIfExpired(request, deadline, timeoutMs);
            awaitReady(deadline, wait, op);
            refuseIfExpired(request, deadline, timeoutMs);
            Path root = Mapping.worktreeRoot(given);
            Registry.Worktree w;
            boolean added = false;
            synchronized (registry) {
                w = registry.get(root);
                if (w == null) {
                    Registry.Worktree overlap = Mapping.overlapping(registry, root);
                    if (overlap != null) {
                        throw Mapping.nested(root, overlap);
                    }
                    Registry.Worktree other = registry.named(root.getFileName().toString());
                    if (other != null) {
                        throw Problem.of(Code.WORKTREE_NAME_IN_USE)
                                .detail("The registered worktree " + other.root + " has the same folder name, " + other.name + ".")
                                .with("field", "/worktree");
                    }
                    w = registry.add(root);
                    try {
                        registry.save();
                    } catch (IOException e) {
                        registry.remove(w);
                        throw new IllegalStateException("can't save the registered worktrees", e);
                    }
                    Alone.Change change = new Alone.Change(Alone.Kind.ADD, root);
                    w.change = change;
                    alone.announce(change);
                    Registry.Worktree adding = w;
                    changes.execute(() -> runAdd(adding, change));
                    added = true;
                }
            }
            if (w.state == Registry.State.REMOVING) {
                throw Alone.notReady(w.change);
            }
            if (!w.settled.isDone()) {
                if (!wait) {
                    throw Alone.notReady(w.change);
                }
                op.phase("waiting-for-import");
                awaitSettled(w, deadline);
            }
            if (w.state == Registry.State.IMPORT_FAILED) {
                throw Mapping.importFailed(w);
            }
            JsonObject o = new JsonObject();
            o.addProperty("added", added);
            if (added) {
                o.addProperty("importMs", w.importMs);
            }
            o.add("worktree", w.toJson());
            return o;
        }
    }

    private JsonObject remove(HttpApi.Request request) throws Problem {
        Fields f = new Fields(HttpApi.object(request), "POST /v1/worktrees/remove");
        String given = f.string("worktree", true);
        long timeoutMs = f.integer("timeoutMs", 1, HttpApi.TIMEOUT_MAX_MS, CHANGE_TIMEOUT_DEFAULT_MS);
        boolean wait = f.bool("wait", true);
        f.done();
        long deadline = request.received() + timeoutMs * 1_000_000;
        try (InFlight.Op op = inFlight.begin(request.path(), given, null)) {
            refuseIfExpired(request, deadline, timeoutMs);
            awaitReady(deadline, wait, op);
            refuseIfExpired(request, deadline, timeoutMs);
            Registry.Worktree w;
            CompletableFuture<Integer> removal = new CompletableFuture<>();
            Alone.Change change;
            synchronized (registry) {
                w = Mapping.registered(registry, given);
                if (w.settled.isDone()) {
                    w.state = Registry.State.REMOVING; // else when its import, queued first, is done
                }
                change = new Alone.Change(Alone.Kind.REMOVE, w.root);
                w.change = change;
                alone.announce(change);
                Registry.Worktree removing = w;
                changes.execute(() -> runRemove(removing, change, removal));
            }
            if (!wait) {
                throw Alone.notReady(change);
            }
            op.phase("waiting-for-import");
            int deleted;
            try {
                deleted = removal.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                throw Alone.notReady(change);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("removing " + w.root + " failed", e.getCause());
            }
            JsonObject o = new JsonObject();
            o.addProperty("worktree", w.root.toString());
            o.addProperty("removed", true);
            o.addProperty("projectsDeleted", deleted);
            o.addProperty("filesKept", true);
            return o;
        }
    }

    private JsonObject list(HttpApi.Request request) throws Problem {
        Fields f = new Fields(HttpApi.object(request), "POST /v1/worktrees/list");
        String given = f.string("worktree", false);
        f.done();
        List<Registry.Worktree> worktrees = given == null ? registry.all() : List.of(Mapping.registered(registry, given));
        JsonArray list = new JsonArray();
        worktrees.forEach(w -> list.add(w.toJson()));
        JsonObject o = new JsonObject();
        o.add("worktrees", list);
        return o;
    }

    private JsonObject check(HttpApi.Request request) throws Problem {
        Fields f = new Fields(HttpApi.object(request), "POST /v1/modules/check");
        String worktree = f.string("worktree", true);
        String module = f.string("module", true);
        long timeoutMs = f.integer("timeoutMs", 1, HttpApi.TIMEOUT_MAX_MS, CHECK_TIMEOUT_DEFAULT_MS);
        boolean wait = f.bool("wait", true);
        f.done();
        String syntax = Mapping.moduleSyntax(module);
        if (syntax != null) {
            throw Problem.of(Code.INVALID_REQUEST).detail("1 field is invalid.").error("/module", syntax);
        }
        long start = request.received(); // waiting for a worker counts too
        long deadline = start + timeoutMs * 1_000_000;
        try (InFlight.Op op = inFlight.begin(request.path(), worktree, module)) {
            refuseIfExpired(request, deadline, timeoutMs);
            awaitReady(deadline, wait, op);
            refuseIfExpired(request, deadline, timeoutMs);
            try (Alone.Pass pass = alone.enter(deadline, wait, () -> op.phase("waiting-for-import"))) {
                long waited = (System.nanoTime() - start) / 1_000_000;
                op.phase("running");
                Registry.Module m = Mapping.resolve(registry, worktree, module);
                JsonObject o = new JsonObject();
                o.addProperty("worktree", Mapping.registered(registry, worktree).root.toString());
                o.add("module", m.toJson());
                o.addProperty("waitedMs", waited);
                if (pass.waitedMs > 0) {
                    Log.info(request.instance() + " waited " + pass.waitedMs + " ms for a worktree change");
                }
                return o;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    // --- waiting ---------------------------------------------------------------------------------

    /**
     * A request whose time is up never runs (T002.04's review, R3). Its deadline counts from its arrival, so the time
     * it waited for a worker thread counts too: once it's past, the answer is {@code not-ready} if a worktree change
     * or JDT LS's start is what it waited for, else {@code busy}. Checked wherever a request may have waited: when a
     * worker picks it up, after JDT LS's start, and after a worktree change ({@link Alone#enter}).
     */
    void refuseIfExpired(HttpApi.Request request, long deadline, long timeoutMs) throws Problem {
        if (System.nanoTime() < deadline) {
            return;
        }
        Alone.Change blocking = alone.blocking();
        if (blocking != null) {
            throw Alone.notReady(blocking);
        }
        CompletableFuture<Void> r = ready.get();
        if (!r.isDone() || r.isCompletedExceptionally()) {
            throw starting();
        }
        long waited = (System.nanoTime() - request.received()) / 1_000_000;
        throw HttpApi.waitedForAWorker(waited, timeoutMs);
    }

    private Problem starting() {
        return Problem.of(Code.NOT_READY)
                .detail("JDT LS is still starting (" + String.format(Locale.ROOT, "%.1f", uptimeMs.getAsLong() / 1000.0) + " s since launch).")
                .hint("Wait `retryAfter` seconds and send the same request again, or send it with `wait: true`.")
                .with("reason", "starting").retryAfter(2);
    }

    void awaitReady(long deadline, boolean wait, InFlight.Op op) throws Problem {
        CompletableFuture<Void> r = ready.get();
        if (r.isDone() && !r.isCompletedExceptionally()) {
            return;
        }
        if (wait) {
            op.phase("waiting-for-ready");
            try {
                r.copy().get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                op.phase("running");
                return;
            } catch (TimeoutException e) {
                // below
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("JDT LS failed to start", e.getCause());
            }
        }
        throw starting();
    }

    private static void awaitSettled(Registry.Worktree w, long deadline) throws Problem {
        try {
            w.settled.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            throw Alone.notReady(w.change);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    // --- the changes, on the worktree thread ----------------------------------------------------

    private void runAdd(Registry.Worktree w, Alone.Change change) {
        try {
            alone.begin(change);
            long start = System.nanoTime();
            Log.info("adding " + w.root);
            sync.adding(w.root); // the record, before the import
            Jobs.Joined joined = folders(List.of(w.root), List.of());
            Log.info("added " + w.root + " to JDT LS's folders; joined " + joined.jobs().size() + " jobs in " + joined.rounds()
                    + " rounds, " + joined.ms() + " ms: " + joined.jobs());
            checkHealth(w);
            filterGit(w);
            w.importMs = (System.nanoTime() - start) / 1_000_000;
            Log.info("import of " + w.root + ": " + w.state.id + ", " + w.modules.size() + " modules, in " + w.importMs + " ms");
        } catch (Throwable t) {
            failed(w, "the import", t);
        } finally {
            alone.end(change);
            w.settled.complete(null);
        }
    }

    private void runRemove(Registry.Worktree w, Alone.Change change, CompletableFuture<Integer> result) {
        try {
            alone.begin(change);
            long start = System.nanoTime();
            if (!w.settled.isDone()) {
                throw new IllegalStateException("the import of " + w.root + " isn't done, though it ran first on this thread");
            }
            refuseIfNested(w, "removing it");
            w.state = Registry.State.REMOVING;
            int before = Workspace.projectsUnder(w.root).size();
            Jobs.Joined joined = folders(List.of(), List.of(w.root));
            List<String> left = Workspace.projectsUnder(w.root);
            List<String> poms = Workspace.registeredPomsUnder(w.root);
            registry.remove(w);
            registry.save();
            sync.forget(w.root);
            if (!left.isEmpty() || !poms.isEmpty()) {
                throw new IllegalStateException("after removing " + w.root + ", JDT LS still has its projects " + left
                        + " and m2e still knows its POMs " + poms);
            }
            Log.info("removed " + w.root + ": " + before + " projects deleted, files kept; joined " + joined.jobs().size()
                    + " jobs in " + joined.rounds() + " rounds, " + (System.nanoTime() - start) / 1_000_000 + " ms: " + joined.jobs());
            result.complete(before);
        } catch (Throwable t) {
            Log.error("removing " + w.root + " failed", t);
            result.completeExceptionally(t);
        } finally {
            alone.end(change);
        }
    }

    /**
     * A restart (design §11 "Worktrees"): JDT LS imported or updated the saved worktrees, passed in {@code initialize},
     * while it started. Once it's ready, join every job left (nothing else may run yet), then check each worktree.
     */
    void restore(List<Registry.Worktree> restored) {
        if (restored.isEmpty()) {
            return;
        }
        Alone.Change change = new Alone.Change(Alone.Kind.RESTART, restored.get(0).root);
        restored.forEach(w -> w.change = change);
        alone.announce(change);
        changes.execute(() -> {
            try {
                ready.get().get();
                alone.begin(change);
                long start = System.nanoTime();
                Jobs.Joined joined = jobs.join(null);
                Log.info("restart: joined " + joined.jobs().size() + " jobs in " + joined.rounds() + " rounds, " + joined.ms() + " ms: " + joined.jobs());
                for (Registry.Worktree w : restored) {
                    long t = System.nanoTime();
                    try {
                        sync.restored(w.root);
                        checkHealth(w);
                        filterGit(w);
                        w.importMs = (System.nanoTime() - t) / 1_000_000;
                        Log.info("restart: " + w.root + " " + w.state.id + ", " + w.modules.size() + " modules");
                    } catch (Throwable e) {
                        failed(w, "the restart's check", e);
                    } finally {
                        w.settled.complete(null);
                    }
                }
                Log.info("restart: checked " + restored.size() + " worktrees in " + (System.nanoTime() - start) / 1_000_000 + " ms");
            } catch (Throwable t) {
                Log.error("the restart's check failed", t);
                restored.forEach(w -> {
                    if (!w.settled.isDone()) {
                        failed(w, "the restart's check", t);
                        w.settled.complete(null);
                    }
                });
            } finally {
                alone.end(change);
            }
        });
    }

    private void failed(Registry.Worktree w, String what, Throwable t) {
        Log.error(what + " of " + w.root + " failed", t);
        w.state = Registry.State.IMPORT_FAILED;
        List<Registry.Module> modules = new ArrayList<>();
        for (Registry.Module m : w.modules) {
            modules.add(m.state() == Registry.State.IMPORT_FAILED ? m
                    : new Registry.Module(m.module(), m.gav(), m.packaging(), Registry.State.IMPORT_FAILED, what + " failed in the endpoint: " + t));
        }
        if (modules.isEmpty()) {
            modules.add(new Registry.Module(".", "unknown", null, Registry.State.IMPORT_FAILED, what + " failed in the endpoint: " + t));
        }
        w.modules = modules;
    }

    /** The endpoint's {@code .git} resource filter on the worktree's projects (design §11 "Taking in changes exactly"). */
    private static void filterGit(Registry.Worktree w) throws Exception {
        long start = System.nanoTime();
        DiskSync.filterGit(w.root);
        Log.info("the .git filter is on every project of " + w.root + " (" + (System.nanoTime() - start) / 1_000_000 + " ms)");
    }

    /** Health check, then one repair if a module fails it (design §11 "Worktrees"). */
    private void checkHealth(Registry.Worktree w) throws Exception {
        List<Registry.Module> modules = health(w.root);
        List<Registry.Module> failing = modules.stream().filter(m -> m.state() == Registry.State.IMPORT_FAILED).toList();
        if (!failing.isEmpty()) {
            String reason = String.join("; ", failing.stream().map(m -> m.module() + ": " + m.failure()).toList());
            Log.info("REPAIR: " + w.root + " failed the import health check (" + reason + "); removing and adding it again");
            refuseIfNested(w, "its repair");
            long start = System.nanoTime();
            folders(List.of(), List.of(w.root));
            List<String> left = Workspace.projectsUnder(w.root);
            if (!left.isEmpty()) {
                Log.info("REPAIR: after the removal, JDT LS still has " + left);
            }
            folders(List.of(w.root), List.of());
            modules = health(w.root);
            boolean repaired = modules.stream().noneMatch(m -> m.state() == Registry.State.IMPORT_FAILED);
            w.repair = new Registry.Repair(reason, repaired ? "repaired" : "failed", (System.nanoTime() - start) / 1_000_000);
            Log.info("REPAIR: " + w.root + " " + w.repair.result() + " in " + w.repair.ms() + " ms"
                    + (repaired ? "" : "; still failing: " + modules.stream().filter(m -> m.failure() != null).map(m -> m.module() + ": " + m.failure()).toList()));
        }
        w.modules = modules;
        w.state = modules.stream().anyMatch(m -> m.state() == Registry.State.IMPORT_FAILED) ? Registry.State.IMPORT_FAILED : Registry.State.IMPORTED;
    }

    /**
     * Never a folder removal that reaches another registered worktree: JDT LS deletes every project under the removed
     * root. Adding and the restart refuse nested worktrees, so this only guards against a way in that was missed.
     */
    private void refuseIfNested(Registry.Worktree w, String what) {
        Registry.Worktree other = Mapping.overlapping(registry, w.root);
        if (other != null) {
            throw new IllegalStateException(what + " would delete the projects of " + other.root + ", which overlaps " + w.root
                    + "; nested worktrees are refused, so this shouldn't happen");
        }
    }

    private static List<Registry.Module> health(Path root) throws Exception {
        List<Registry.Module> modules = new ArrayList<>();
        for (Workspace.Found m : Workspace.modules(root)) {
            modules.add(Workspace.health(m));
        }
        return modules;
    }

    /** One folder change: the notification, the barrier, and the join of what it scheduled. */
    private Jobs.Joined folders(List<Path> added, List<Path> removed) throws Exception {
        long mark = jobs.mark();
        JsonObject event = new JsonObject();
        event.add("added", folderList(added));
        event.add("removed", folderList(removed));
        JsonObject params = new JsonObject();
        params.add("event", event);
        lsp.notify("workspace/didChangeWorkspaceFolders", params);
        barrier();
        return jobs.join(mark);
    }

    static JsonArray folderList(List<Path> folders) {
        JsonArray list = new JsonArray();
        for (Path p : folders) {
            list.add(folder(p));
        }
        return list;
    }

    static JsonObject folder(Path p) {
        JsonObject folder = new JsonObject();
        folder.addProperty("uri", uri(p));
        folder.addProperty("name", p.getFileName().toString());
        return folder;
    }

    /** A folder's {@code file:} URI, the same whether or not it still exists (no trailing slash). */
    static String uri(Path p) {
        String u = p.toUri().toString();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    /**
     * A request JDT LS can't answer: lsp4j handles messages in order, a notification's handler before the next
     * message, so its error proves the notifications sent before it were handled, and their jobs scheduled.
     */
    private void barrier() throws Exception {
        JsonObject response = lsp.request("fractal/barrier", null).get(BARRIER_GUARD_MINUTES, TimeUnit.MINUTES);
        JsonObject error = response.getAsJsonObject("error");
        if (error == null || error.get("code").getAsInt() != METHOD_NOT_FOUND) {
            throw new IllegalStateException("JDT LS answered the barrier request with " + response + ", not error " + METHOD_NOT_FOUND);
        }
    }

    /** The registered worktrees as JDT LS's {@code workspace/workspaceFolders} answer. */
    JsonArray workspaceFolders() {
        JsonArray list = new JsonArray();
        for (Registry.Worktree w : registry.all()) {
            if (w.state != Registry.State.REMOVING) {
                list.add(folder(w.root));
            }
        }
        return list;
    }

    static boolean isWorktreeRoot(Path p) {
        return Files.isDirectory(p) && Files.exists(p.resolve(".git"));
    }
}
