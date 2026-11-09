// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import org.eclipse.core.resources.FileInfoMatcherDescription;
import org.eclipse.core.resources.IBuildConfiguration;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceFilterDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.internal.project.ProjectConfigurationManager;
import org.eclipse.m2e.core.project.MavenUpdateRequest;

/**
 * Keeping in step with the disk (design §11): the fixed point every operation that depends on code runs first, for
 * the requesting module's whole worktree. No file watcher and no timing:
 *
 * <ol>
 *   <li>the fast path: if every project of the worktree is in sync ({@code isSynchronized}), the endpoint's own
 *       record of the files ({@link FileRecords}) is unchanged, and no change to the worktree was seen inside Eclipse
 *       since the last full fixed point, answer at once;
 *   <li>otherwise, holding the workspace root rule: refresh the worktree's projects, make Eclipse take in the files
 *       whose record changed but that the refresh didn't take in (changes that kept their modification time, and the
 *       request's {@code changedPaths}), send changed POMs (by the refresh's delta, and by content hash) through
 *       m2e's project update, and run an incremental workspace build;
 *   <li>then, the rule released, join the jobs scheduled since the fixed point took the rule.
 * </ol>
 *
 * <p>The files are never touched: a change Eclipse would miss is taken in with {@link IResource#touch}, which changes
 * only Eclipse's own record of the file (its content id and modification stamp), and fires a content change, so the
 * build compiles what's on disk.
 *
 * <p>"No change seen" is a mark per worktree. A resource-change listener clears it on any change to the worktree's
 * files but markers, build output and {@code .git}. The fixed point sets it once it holds the rule, before its
 * refresh: while it holds the rule nothing else can change a resource, and Eclipse delivers an operation's changes
 * before it lets go of the rule, so every change delivered while the fixed point holds it is the fixed point's own
 * (or was made before it, and its build covers it). Those are its report, and don't clear its mark; any other clears
 * it. A worktree with a fixed point still running, its join included, never takes the fast path.
 */
final class DiskSync {

    /** The endpoint's {@code .git} filter: JDT LS's own filter is applied only when its preference changes. */
    static final String GIT_FILTER = "^(\\.git|__CREATED_BY_FRACTAL_JDTLS_ENDPOINT__)$";
    static final String REGEX_MATCHER = "org.eclipse.core.resources.regexFilterMatcher";
    static final int GIT_FILTER_TYPE = IResourceFilterDescription.EXCLUDE_ALL | IResourceFilterDescription.FILES
            | IResourceFilterDescription.FOLDERS | IResourceFilterDescription.INHERITABLE;
    static final int MAX_FILES_REPORTED = 200;
    static final String LIGHTWEIGHT_REFRESH = "refresh.lightweight.enabled";

    /** What a fixed point found and cost (the contract's {@code Sync}). */
    static final class Report {
        boolean skipped;
        String notSkippedBecause;
        String cleanBuild;
        final Map<String, String> files = new TreeMap<>();
        final List<String> poms = new ArrayList<>();
        double checkMs, ruleWaitMs, refreshMs, pomsMs, buildMs, joinMs, totalMs;
        /** For the log, not the contract: how the check and the refresh split, and the files recorded. */
        double synchronizedMs, scanMs, refreshLocalMs, touchMs, saveMs, applyMs;
        int recorded, touched;
        Jobs.Joined joined;

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("skipped", skipped);
            if (notSkippedBecause != null) {
                o.addProperty("notSkippedBecause", notSkippedBecause);
            }
            if (cleanBuild != null) {
                o.addProperty("cleanBuild", cleanBuild);
            }
            JsonArray list = new JsonArray();
            files.entrySet().stream().limit(MAX_FILES_REPORTED).forEach(e -> {
                JsonObject f = new JsonObject();
                f.addProperty("path", e.getKey());
                f.addProperty("change", e.getValue());
                list.add(f);
            });
            o.add("filesChanged", list);
            o.addProperty("filesChangedCount", files.size());
            JsonArray p = new JsonArray();
            poms.forEach(p::add);
            o.add("pomsUpdated", p);
            o.addProperty("checkMs", checkMs);
            o.addProperty("ruleWaitMs", ruleWaitMs);
            o.addProperty("refreshMs", refreshMs);
            o.addProperty("pomsMs", pomsMs);
            o.addProperty("buildMs", buildMs);
            o.addProperty("joinMs", joinMs);
            o.addProperty("totalMs", totalMs);
            return o;
        }
    }

    /** A fixed point that its deadline stopped, and where. */
    static final class Cancelled extends Exception {
        private static final long serialVersionUID = 1L;
        final String phase;

        Cancelled(String phase, String what) {
            super(what, null, false, false);
            this.phase = phase;
        }
    }

    /**
     * A progress monitor that's cancelled once the request's deadline passes: Eclipse's operations check it. Not a
     * {@code NullProgressMonitor}: JDT LS's progress provider ({@code ProgressReporterManager.monitorFor}) replaces
     * one, subclasses included, with its own when {@code JobManager.beginRule} is called, so a fixed point then waited
     * for the workspace rule past its deadline, until the rule was free (T002.11; {@code DeadlineTest}).
     */
    static final class Deadline implements IProgressMonitor {
        final long deadline;
        private volatile boolean canceled;

        Deadline(long deadline) {
            this.deadline = deadline;
        }

        @Override
        public boolean isCanceled() {
            return canceled || System.nanoTime() - deadline >= 0;
        }

        @Override
        public void setCanceled(boolean value) {
            canceled = value;
        }

        @Override
        public void beginTask(String name, int totalWork) {}

        @Override
        public void done() {}

        @Override
        public void internalWorked(double work) {}

        @Override
        public void setTaskName(String name) {}

        @Override
        public void subTask(String name) {}

        @Override
        public void worked(int work) {}
    }

    /** What the endpoint keeps of a worktree between fixed points. */
    private static final class Tracked {
        final Path root;
        volatile boolean unchanged;
        /**
         * Fixed points take it to write, the fast path and every marker read to read (T002.05's review, R2): so no
         * answer is read while a fixed point, a clean one included, changes the markers, and a fixed point that starts
         * after a fast path's check waits for that request's answer. Fair, so a waiting fixed point isn't starved.
         */
        final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
        // Guarded by this:
        boolean first = true; // until a full fixed point has run, since the add or the restart
        long generation; // full fixed points done
        Map<Path, FileRecords.State> files; // null with no record: the first fixed point then cleans
        Map<Path, String> poms;

        Tracked(Path root) {
            this.root = root;
        }
    }

    /** The fixed point holding the root rule, and the changes delivered meanwhile (path to kind). */
    private record Window(Tracked owner, Map<Path, String> changed) {}

    /** What reads the answer, under the worktree's read lock, once the fixed point is done. */
    interface Reader {
        JsonObject read() throws Exception;
    }

    /** A fixed point's report, and what was read after it. */
    record Result(Report report, JsonObject answer) {}

    private final Jobs jobs;
    private final Path records;
    private final Map<Path, Tracked> tracked = new ConcurrentHashMap<>();
    private volatile Window window;

    /** {@code records} is the folder, under {@code -data}, where each worktree's record is saved. */
    DiskSync(Jobs jobs, Path records) {
        this.jobs = jobs;
        this.records = records;
        ws().addResourceChangeListener(this::changed, IResourceChangeEvent.POST_CHANGE);
    }

    private static IWorkspace ws() {
        return ResourcesPlugin.getWorkspace();
    }

    private static double ms(long since) {
        return Math.round((System.nanoTime() - since) / 1e5) / 10.0;
    }

    /** The resource preference HTTP mode turns off, through the launcher's preference defaults: its value now. */
    static boolean lightweightRefresh() {
        return Platform.getPreferencesService().getBoolean(ResourcesPlugin.PI_RESOURCES, LIGHTWEIGHT_REFRESH, true, null);
    }

    // --- the record, kept and saved --------------------------------------------------------------

    /**
     * A worktree is being added: its record is taken now, before JDT LS imports it, and saved, so a change made from
     * now on, during the import or before the first fixed point, differs from it, even one that keeps its
     * modification time. Changes before it, the import builds.
     */
    void adding(Path root) {
        Tracked t = new Tracked(root);
        try {
            Map<Path, FileRecords.State> files = FileRecords.scan(root, List.of(root));
            Map<Path, String> poms = pomHashes(files);
            synchronized (t) {
                t.files = files;
                t.poms = poms;
            }
            save(t);
        } catch (IOException e) {
            Log.error("can't take the record of " + root + " before its import; its first fixed point cleans its projects", e);
            synchronized (t) {
                t.files = null;
            }
        }
        tracked.put(root, t);
    }

    /**
     * A saved worktree after a restart: its saved record, so its first fixed point finds what changed while JDT LS was
     * stopped. Without one (none was saved, or it's damaged), the first fixed point cleans the worktree's projects.
     */
    void restored(Path root) {
        Tracked t = new Tracked(root);
        Path file = recordFile(root);
        try {
            if (Files.exists(file)) {
                load(t, file);
                Log.info("restart: the record of " + root + " has " + t.files.size() + " files, from " + file);
            } else {
                Log.info("restart: no saved record of " + root + "; its first fixed point cleans its projects");
            }
        } catch (IOException | RuntimeException e) {
            Log.error("restart: the saved record of " + root + " in " + file + " can't be read; its first fixed point cleans its projects", e);
            synchronized (t) {
                t.files = null;
                t.poms = null;
            }
        }
        tracked.put(root, t);
    }

    /** Forgets the worktree, and deletes its saved record: it was removed. */
    void forget(Path root) {
        tracked.remove(root);
        try {
            Files.deleteIfExists(recordFile(root));
        } catch (IOException e) {
            Log.error("can't delete the saved record of " + root, e);
        }
    }

    /** {@code <records>/<sha-256 of the worktree's path, 16 digits>.json}; the file names the worktree too. */
    Path recordFile(Path root) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return records.resolve(HexFormat.of().formatHex(h).substring(0, 16) + ".json");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Writes the worktree's record, atomically: {@code {"version": 1, "worktree": <path>, "files": [[<relative path>,
     * <mtime ns>, <ctime ns>, <size>, <inode>], ...], "poms": {<relative path>: <sha-256>}}}.
     */
    private void save(Tracked t) throws IOException {
        Map<Path, FileRecords.State> files;
        Map<Path, String> poms;
        synchronized (t) {
            files = t.files;
            poms = t.poms;
        }
        if (files == null) {
            return;
        }
        Files.createDirectories(records);
        Path file = recordFile(t.root);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        synchronized (t) { // one writer at a time for the worktree
            try (com.google.gson.stream.JsonWriter w = new com.google.gson.stream.JsonWriter(
                    Files.newBufferedWriter(tmp, java.nio.charset.StandardCharsets.UTF_8))) {
                w.beginObject().name("version").value(1).name("worktree").value(t.root.toString()).name("files").beginArray();
                for (Map.Entry<Path, FileRecords.State> e : new TreeMap<>(files).entrySet()) {
                    FileRecords.State s = e.getValue();
                    w.beginArray().value(t.root.relativize(e.getKey()).toString()).value(s.mtimeNs()).value(s.ctimeNs()).value(s.size())
                            .value(s.inode() == null ? null : s.inode().toString()).endArray();
                }
                w.endArray().name("poms").beginObject();
                for (Map.Entry<Path, String> e : poms == null ? Map.<Path, String>of().entrySet() : poms.entrySet()) {
                    w.name(t.root.relativize(e.getKey()).toString()).value(e.getValue());
                }
                w.endObject().endObject();
            }
            Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void load(Tracked t, Path file) throws IOException {
        JsonObject o = Json.parseStrict(Files.readString(file, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        if (o.get("version").getAsInt() != 1 || !t.root.toString().equals(o.get("worktree").getAsString())) {
            throw new IOException(file + " isn't version 1 of " + t.root + "'s record");
        }
        Map<Path, FileRecords.State> files = new java.util.HashMap<>();
        for (com.google.gson.JsonElement e : o.getAsJsonArray("files")) {
            JsonArray a = e.getAsJsonArray();
            files.put(t.root.resolve(a.get(0).getAsString()), new FileRecords.State(a.get(1).getAsLong(), a.get(2).getAsLong(), a.get(3).getAsLong(),
                    a.get(4).isJsonNull() ? null : Long.valueOf(a.get(4).getAsString())));
        }
        Map<Path, String> poms = new TreeMap<>();
        o.getAsJsonObject("poms").entrySet().forEach(e -> poms.put(t.root.resolve(e.getKey()), e.getValue().getAsString()));
        synchronized (t) {
            t.files = files;
            t.poms = poms;
        }
    }

    // --- the listener ----------------------------------------------------------------------------


    private void changed(IResourceChangeEvent event) {
        IResourceDelta delta = event.getDelta();
        if (delta == null || tracked.isEmpty()) {
            return;
        }
        Window w = window;
        try {
            delta.accept(d -> {
                IResource r = d.getResource();
                if (r.getType() == IResource.ROOT || r.getType() == IResource.PROJECT) {
                    return true;
                }
                if (r.getLocation() == null) {
                    return false;
                }
                Path path = Path.of(r.getLocation().toOSString());
                if (r.getType() == IResource.FOLDER) {
                    Tracked owner = owner(path);
                    return !FileRecords.skipped(path, owner == null ? null : owner.root) && !r.isDerived();
                }
                String kind = kind(d);
                if (kind == null || path.getFileName().toString().equals(".git") || r.isDerived(IResource.CHECK_ANCESTORS)) {
                    return false;
                }
                Tracked t = owner(path);
                if (t != null) {
                    if (w != null && w.owner() == t) {
                        synchronized (w.changed()) {
                            w.changed().putIfAbsent(path, kind);
                        }
                    } else {
                        if (t.unchanged) {
                            Log.info("the next fixed point of " + t.root + " refreshes: Eclipse saw " + kind + " " + t.root.relativize(path)
                                    + " outside a fixed point (" + Thread.currentThread().getName() + ")");
                        }
                        t.unchanged = false;
                    }
                }
                return false;
            });
        } catch (CoreException e) {
            Log.error("reading a resource delta failed; every worktree's next fixed point refreshes", e);
            tracked.values().forEach(t -> t.unchanged = false);
        }
    }

    private static String kind(IResourceDelta d) {
        return switch (d.getKind()) {
            case IResourceDelta.ADDED -> "added";
            case IResourceDelta.REMOVED -> "removed";
            case IResourceDelta.CHANGED -> (d.getFlags() & (IResourceDelta.CONTENT | IResourceDelta.REPLACED)) != 0 ? "changed" : null;
            default -> null;
        };
    }

    private Tracked owner(Path path) {
        for (Tracked t : tracked.values()) {
            if (path.startsWith(t.root)) {
                return t;
            }
        }
        return null;
    }

    // --- the fixed point -------------------------------------------------------------------------

    /**
     * The fixed point for the worktree, then {@code reader} under the worktree's read lock. {@code forced} is why the
     * fast path mustn't be taken ({@code build}, {@code changed-paths}), or null; {@code changedPaths} are absolute
     * files inside the worktree, taken in even if their record didn't change; {@code clean} cleans the worktree's
     * projects before the build. Eclipse's operations are cancelled, the join stops, and the answer is refused, at
     * {@code deadline} ({@link System#nanoTime()}): then {@link Cancelled}.
     */
    Result sync(Path root, String forced, boolean clean, List<Path> changedPaths, long deadline, Consumer<String> phase, Reader reader)
            throws Exception {
        long start = System.nanoTime();
        Report report = new Report();
        Tracked t = tracked.computeIfAbsent(root, Tracked::new); // neither added nor restored: no record, so it cleans
        Map<Path, FileRecords.State> now = null;
        long checkedAt = -1;
        String reason = forced;
        boolean first;
        synchronized (t) {
            first = t.first;
        }
        if (reason == null && first) {
            reason = "first";
        }
        if (reason == null) {
            lock(t.lock.readLock(), deadline, "waiting for another request's fixed point of " + root);
            try {
                long check = System.nanoTime();
                synchronized (t) {
                    checkedAt = t.generation;
                }
                if (!t.unchanged) {
                    reason = "eclipse-changed";
                } else {
                    // The endpoint's own record first: it's the cheaper check, and the refresh reuses it.
                    now = FileRecords.scan(root, locations(projects(root)));
                    report.scanMs = ms(check);
                    report.recorded = now.size();
                    FileRecords.Diff diff;
                    synchronized (t) {
                        diff = FileRecords.diff(t.files, now);
                    }
                    if (!diff.isEmpty()) {
                        reason = diff.onlyKeptMtime() ? "status-changed" : "disk-changed";
                    } else {
                        long sync = System.nanoTime();
                        if (!synchronizedWithDisk(projects(root))) {
                            reason = "disk-changed";
                        }
                        report.synchronizedMs = ms(sync);
                        if (reason == null && !t.unchanged) {
                            reason = "eclipse-changed"; // a change seen during the check
                        }
                    }
                }
                report.checkMs = ms(check);
                if (reason == null) {
                    report.skipped = true;
                    expired(deadline, "syncing", "checking " + root + " against the disk");
                    JsonObject answer = reader.read();
                    expired(deadline, "running", "reading the answer for " + root);
                    report.totalMs = ms(start);
                    return new Result(report, answer);
                }
            } finally {
                t.lock.readLock().unlock();
            }
        }
        report.notSkippedBecause = reason;
        return full(t, report, root, clean, changedPaths, now, checkedAt, start, deadline, phase, null, reader);
    }

    /**
     * A refactoring's apply, as one fixed point (design §11 "Refactorings and edits"): under the worktree's write
     * lock and the workspace root rule, {@code writer} checks the edit's files against its baseline and writes them,
     * then the refresh, the build and the join take them in, before {@code reader} reads the answer. No other request
     * of the worktree sees the files half written, or reads an answer before the build. {@code written} are the files
     * the edit writes, taken in even if the refresh missed them. The deadline is checked just before {@code writer}
     * runs; once it has written, nothing is cancelled, so the answer always reports what was written.
     */
    Result apply(Path root, List<Path> written, long deadline, Consumer<String> phase, Writer writer, Reader reader) throws Exception {
        long start = System.nanoTime();
        Tracked t = tracked.computeIfAbsent(root, Tracked::new);
        Report report = new Report();
        report.notSkippedBecause = "edit-applied";
        return full(t, report, root, false, written, null, -1, start, deadline, phase, writer, reader);
    }

    /** What an apply does under the worktree's write lock and the workspace rule: the check and the writes. */
    interface Writer {
        void write() throws Exception;
    }

    /**
     * A file's state in the worktree's record as the last fixed point left it, or null if it had none. The record
     * changes only under the worktree's write lock, so it's the baseline of an answer read under the read lock.
     */
    FileRecords.State recorded(Path root, Path file) {
        Tracked t = tracked.get(root);
        if (t == null) {
            return null;
        }
        synchronized (t) {
            return t.files == null ? null : t.files.get(file);
        }
    }

    /** No deadline: what an apply does once it has written. */
    private static long never() {
        return System.nanoTime() + Long.MAX_VALUE / 2;
    }

    private Result full(Tracked t, Report report, Path root, boolean clean, List<Path> changedPaths, Map<Path, FileRecords.State> now, long checkedAt,
            long start, long deadline, Consumer<String> phase, Writer writer, Reader reader) throws Exception {
        Deadline monitor = new Deadline(deadline);
        boolean cleanAll = clean;
        boolean done = false;
        boolean reading = false;
        boolean written = false;
        String where = "waiting-for-rule";
        phase.accept(where);
        lock(t.lock.writeLock(), deadline, "waiting for another request's fixed point of " + root);
        try {
            synchronized (t) {
                if (t.generation != checkedAt) {
                    now = null; // another fixed point ran since the check: record the files again
                }
                if (t.files == null && !clean) {
                    cleanAll = true; // no record to compare with: what Eclipse built can't be trusted
                    report.cleanBuild = "no-saved-record";
                }
            }
            if (clean) {
                report.cleanBuild = "requested";
            }
            IWorkspaceRoot rule = ws().getRoot();
            long waiting = System.nanoTime();
            long mark;
            Map<Path, String> hashes;
            try {
                try {
                    Job.getJobManager().beginRule(rule, monitor);
                } catch (OperationCanceledException e) {
                    throw new Cancelled(where, "waiting for the workspace rule");
                }
                report.ruleWaitMs = ms(waiting);
                where = writer != null ? "applying" : "syncing";
                phase.accept(where);
                if (writer != null) {
                    expired(deadline, where, "applying the edit to " + root); // the last moment nothing is written yet
                }
                if (ws().isAutoBuilding()) {
                    throw new IllegalStateException("Eclipse's autobuild is on, though HTTP mode fixes java.autobuild.enabled: false");
                }
                mark = jobs.mark();
                Window w = new Window(t, new TreeMap<>());
                window = w;
                try {
                    t.unchanged = true; // before the refresh: a change from now on, not the fixed point's own, clears it
                    if (writer != null) {
                        long writing = System.nanoTime();
                        writer.write();
                        written = true;
                        report.applyMs = ms(writing);
                        monitor = new Deadline(never()); // written: what follows takes the files in, whatever the deadline
                        where = "syncing";
                        phase.accept(where);
                    }
                    long refresh = System.nanoTime();
                    List<IProject> projects = projects(root); // as they are, now that nothing else can change them
                    filterGit(projects, monitor);
                    if (now == null) {
                        long scan = System.nanoTime();
                        now = FileRecords.scan(root, locations(projects)); // before the refresh, so a later change differs from it
                        report.scanMs = ms(scan);
                        report.recorded = now.size();
                    }
                    long refreshLocal = System.nanoTime();
                    ws().run(m -> {
                        for (IProject p : projects) {
                            p.refreshLocal(IResource.DEPTH_INFINITE, m);
                        }
                    }, rule, 0, monitor);
                    report.refreshLocalMs = ms(refreshLocal);
                    Set<Path> missed = new LinkedHashSet<>(changedPaths);
                    Map<Path, FileRecords.State> before;
                    synchronized (t) {
                        before = t.files;
                    }
                    if (before != null) {
                        missed.addAll(FileRecords.diff(before, now).candidates());
                    }
                    synchronized (w.changed()) {
                        missed.removeAll(w.changed().keySet());
                    }
                    long touching = System.nanoTime();
                    touch(missed, rule, monitor);
                    report.touchMs = ms(touching);
                    report.touched = missed.size();
                    report.refreshMs = ms(refresh);

                    long pomsStart = System.nanoTime();
                    hashes = pomHashes(now);
                    Set<Path> poms = new java.util.TreeSet<>();
                    synchronized (w.changed()) {
                        w.changed().keySet().stream().filter(p -> p.getFileName().toString().equals(Workspace.POM)).forEach(poms::add);
                    }
                    Map<Path, String> oldHashes;
                    synchronized (t) {
                        oldHashes = t.poms;
                    }
                    if (oldHashes != null) {
                        Set<Path> all = new java.util.TreeSet<>(hashes.keySet());
                        all.addAll(oldHashes.keySet());
                        for (Path p : all) {
                            if (!java.util.Objects.equals(hashes.get(p), oldHashes.get(p))) {
                                poms.add(p);
                            }
                        }
                    }
                    updatePoms(root, poms, report, monitor);
                    report.pomsMs = ms(pomsStart);

                    long build = System.nanoTime();
                    if (cleanAll) {
                        List<IBuildConfiguration> configs = new ArrayList<>();
                        for (IProject p : projects(root)) {
                            if (p.isOpen()) {
                                configs.add(p.getActiveBuildConfig());
                            }
                        }
                        ws().build(configs.toArray(IBuildConfiguration[]::new), IncrementalProjectBuilder.CLEAN_BUILD, true, monitor);
                    }
                    ws().build(IncrementalProjectBuilder.INCREMENTAL_BUILD, monitor);
                    report.buildMs = ms(build);
                } finally {
                    window = null;
                    Set<Path> locations = new java.util.HashSet<>(locations(projects(root)));
                    synchronized (w.changed()) {
                        w.changed().forEach((p, kind) -> {
                            if (!jdtlsMetadata(p, locations)) {
                                report.files.put(root.relativize(p).toString(), kind);
                            }
                        });
                    }
                }
            } catch (OperationCanceledException e) {
                throw new Cancelled(where, "reconciling " + root + " with the disk");
            } finally {
                Job.getJobManager().endRule(rule);
            }
            long join = System.nanoTime();
            try {
                report.joined = jobs.join(mark, monitor.deadline);
            } catch (Jobs.Expired e) {
                throw new Cancelled(where, e.getMessage());
            }
            report.joinMs = ms(join);
            synchronized (t) {
                t.files = now;
                t.poms = hashes;
                t.first = false;
                t.generation++;
            }
            long saving = System.nanoTime();
            save(t);
            report.saveMs = ms(saving);
            done = true;
            t.lock.readLock().lock(); // downgraded: the answer is read before any other fixed point of the worktree runs
            reading = true;
        } catch (Exception e) {
            if (written && !(e instanceof Problem)) {
                throw new IllegalStateException("the edit was written to " + root + ", but taking it in failed; the next fixed point refreshes", e);
            }
            throw e;
        } finally {
            if (!done) {
                t.unchanged = false;
            }
            t.lock.writeLock().unlock();
        }
        try {
            if (!written) {
                expired(deadline, "running", "reading the answer for " + root);
            }
            JsonObject answer = reader.read();
            if (!written) {
                expired(deadline, "running", "reading the answer for " + root);
            }
            report.totalMs = ms(start);
            return new Result(report, answer);
        } finally {
            if (reading) {
                t.lock.readLock().unlock();
            }
        }
    }

    /** Takes the lock by the deadline, or {@link Cancelled} in {@code waiting-for-rule}. */
    private static void lock(java.util.concurrent.locks.Lock lock, long deadline, String what) throws Cancelled, InterruptedException {
        if (!lock.tryLock(Math.max(0, deadline - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS)) {
            throw new Cancelled("waiting-for-rule", what);
        }
    }

    /** A request whose time is up doesn't get an answer, even a ready one (T002.05's review, R3). */
    private static void expired(long deadline, String phase, String what) throws Cancelled {
        if (System.nanoTime() - deadline >= 0) {
            throw new Cancelled(phase, what);
        }
    }

    /**
     * Whether the file is one of JDT LS's own project files, which HTTP mode keeps under {@code -data}
     * ({@code -Djava.import.generatesMetadataFilesAtProjectRoot=false}) and Eclipse's tree shows at the project's
     * folder: {@code .project}, {@code .classpath}, {@code .factorypath} or {@code .settings/*} of a project. They're
     * never in the worktree, so the report leaves them out; they still count as a change to the worktree.
     */
    static boolean jdtlsMetadata(Path file, Set<Path> projectLocations) {
        String name = file.getFileName().toString();
        Path parent = file.getParent();
        if (parent == null) {
            return false;
        }
        if (name.equals(".project") || name.equals(".classpath") || name.equals(".factorypath")) {
            return projectLocations.contains(parent);
        }
        return parent.getFileName() != null && parent.getFileName().toString().equals(".settings") && projectLocations.contains(parent.getParent());
    }

    /** Whether Eclipse's tree matches the disk for every project: the same timestamp comparison as a refresh. */
    private static boolean synchronizedWithDisk(List<IProject> projects) {
        for (IProject p : projects) {
            if (!p.isSynchronized(IResource.DEPTH_INFINITE)) {
                return false;
            }
        }
        return !projects.isEmpty();
    }

    /** The open projects located in the worktree, sorted by name. */
    static List<IProject> projects(Path root) {
        List<IProject> result = new ArrayList<>();
        for (IProject p : ws().getRoot().getProjects()) {
            if (p.isOpen() && p.getLocation() != null && Path.of(p.getLocation().toOSString()).startsWith(root)) {
                result.add(p);
            }
        }
        result.sort(java.util.Comparator.comparing(IProject::getName));
        return result;
    }

    private static List<Path> locations(List<IProject> projects) {
        return projects.stream().map(p -> Path.of(p.getLocation().toOSString())).toList();
    }

    /**
     * Makes Eclipse take in files it may have missed, without touching them on disk: {@link IResource#touch} changes
     * only Eclipse's record of the file, and fires a content change. A file Eclipse doesn't have is left to the
     * refresh, which added it if it's there.
     */
    private static void touch(Set<Path> files, IWorkspaceRoot rule, IProgressMonitor monitor) throws CoreException {
        if (files.isEmpty()) {
            return;
        }
        ws().run(m -> {
            for (Path p : files) {
                for (IFile f : ws().getRoot().findFilesForLocationURI(p.toUri())) {
                    if (f.exists() && Files.isRegularFile(p)) {
                        f.touch(m);
                    }
                }
            }
        }, rule, 0, monitor);
    }

    /** The SHA-256 of every {@code pom.xml} in the record. */
    private static Map<Path, String> pomHashes(Map<Path, FileRecords.State> files) throws IOException {
        Map<Path, String> hashes = new TreeMap<>();
        for (Path p : files.keySet()) {
            if (p.getFileName().toString().equals(Workspace.POM)) {
                try {
                    hashes.put(p, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));
                } catch (java.nio.file.NoSuchFileException e) {
                    // deleted since the scan: the next fixed point sees it
                } catch (NoSuchAlgorithmException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return hashes;
    }

    /**
     * m2e's multi-project update for the projects whose {@code pom.xml} changed, as JDT LS calls it: configuration
     * updated and projects cleaned. The public method throws for more than one POM.
     */
    private static void updatePoms(Path root, Set<Path> poms, Report report, IProgressMonitor monitor) {
        Set<IProject> projects = new LinkedHashSet<>();
        for (Path pom : poms) {
            for (IProject p : ws().getRoot().getProjects()) {
                if (p.isOpen() && p.getLocation() != null && Path.of(p.getLocation().toOSString()).equals(pom.getParent())) {
                    projects.add(p);
                }
            }
        }
        if (projects.isEmpty()) {
            return;
        }
        for (IProject p : projects) {
            report.poms.add(Workspace.relative(root, Path.of(p.getLocation().toOSString())));
        }
        Collections.sort(report.poms);
        Map<String, IStatus> statuses = ((ProjectConfigurationManager) MavenPlugin.getProjectConfigurationManager())
                .updateProjectConfiguration(new MavenUpdateRequest(projects, false, false), true, true, monitor);
        statuses.forEach((project, status) -> {
            if (!status.isOK()) {
                Log.info("m2e's update of " + project + ": " + status);
            }
        });
    }

    /**
     * The endpoint's {@code .git} filter on each project that lacks it (design §11 "Taking in changes exactly"), so a
     * main checkout's refresh neither walks nor reports Git's internals. Run when a worktree is added or restored, and
     * at each fixed point, for projects made since. The caller holds the root rule, or it's taken here.
     */
    static void filterGit(List<IProject> projects, IProgressMonitor monitor) throws CoreException {
        List<IProject> missing = new ArrayList<>();
        for (IProject p : projects) {
            if (p.isOpen() && !hasGitFilter(p)) {
                missing.add(p);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        // No BACKGROUND_REFRESH, the flag JDT LS's own filter uses: that refresh would run in a job nothing joins, at
        // any time, and refreshing a project whose folder is gone deletes it. The filter applies from the next
        // refresh, the worktree's first fixed point.
        ws().run(m -> {
            for (IProject p : missing) {
                p.createFilter(GIT_FILTER_TYPE, new FileInfoMatcherDescription(REGEX_MATCHER, GIT_FILTER), IResource.NONE, m);
            }
        }, ws().getRoot(), 0, monitor);
    }

    /** {@link #filterGit(List, IProgressMonitor)} for every project of the worktree. */
    static void filterGit(Path root) throws CoreException {
        filterGit(projects(root), new NullProgressMonitor());
    }

    static boolean hasGitFilter(IProject p) throws CoreException {
        for (IResourceFilterDescription f : p.getFilters()) {
            FileInfoMatcherDescription d = f.getFileInfoMatcherDescription();
            if (REGEX_MATCHER.equals(d.getId()) && GIT_FILTER.equals(d.getArguments())) {
                return true;
            }
        }
        return false;
    }
}
