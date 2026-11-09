// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.lsp4j.CreateFile;
import org.eclipse.lsp4j.DeleteFile;
import org.eclipse.lsp4j.RenameFile;
import org.eclipse.lsp4j.ResourceOperation;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

/**
 * A refactoring's {@code WorkspaceEdit}, from JDT LS, as the endpoint applies it (design §11 "Refactorings and
 * edits"), being the client. Built under the worktree's read lock, right after the edit is computed:
 *
 * <ul>
 *   <li>every file it names must really be in the requesting worktree, symbolic links resolved (T002.07's review, R1),
 *       else {@code edit-outside-worktree}, nothing written;
 *   <li>each file it changes is read once, in its charset and byte order (a mark Eclipse leaves out kept aside), and
 *       must be exactly the text JDT
 *       computed the edit from (the compilation unit's buffer), else {@code files-changed}: the disk moved on after
 *       the fixed point;
 *   <li>its operations are worked out in memory ({@link EditPlan}), giving the files' new texts, the diff (in memory
 *       linear in the files' size, with the request's deadline), and the new bytes: the same charset, byte order, mark
 *       and line separators, nothing outside the edit's text changed.
 * </ul>
 *
 * <p>The baseline is the worktree's record as that fixed point left it (status-change time, size, inode and
 * modification time of every file, which the fixed point takes anyway), plus the bytes just read. Under the write lock
 * and the workspace rule, {@link #write} checks each file the edit reads against both, and that Eclipse's view is in
 * sync with the disk ({@code IFile.isSynchronized}), and that no file it creates appeared; any difference is
 * {@code files-changed}, nothing written. The deadline is checked once more just before the first write
 * ({@code timeout}, {@code phase: applying}, nothing written). Then {@link EditWriter} writes them all or none.
 */
final class Edits implements CodeRequest.Pending {

    /** A file the edit reads: what it was at the baseline. */
    private record Read(Path path, IFile file, byte[] bytes, Coding coding, FileRecords.State baseline) {}

    private final Path root;
    private final long deadline;
    private final Set<String> severities;
    private final int limit;
    private final List<String> warnings;
    private final Map<Path, Read> reads = new TreeMap<>();
    private final Set<Path> absent = new java.util.TreeSet<>();
    private final List<EditPlan.Change> changes;
    private final String diff;
    private final String digest;
    private final List<Path> named = new ArrayList<>();
    private final List<EditWriter.Write> writes = new ArrayList<>();
    private final Set<Path> keep;
    private double checkMs;
    private double writeMs;

    /**
     * The edit, read and planned, with its baseline. {@code warnings} are JDT's, given with the answer; the problems
     * returned after the apply are the worktree's, of {@code severities}, at most {@code limit}.
     */
    static Edits of(WorkspaceEdit edit, Targets.Context ctx, DiskSync sync, List<String> warnings, Set<String> severities, int limit)
            throws Problem, CoreException, IOException {
        return new Edits(edit, ctx, sync, warnings, severities, limit);
    }

    private Edits(WorkspaceEdit edit, Targets.Context ctx, DiskSync sync, List<String> warnings, Set<String> severities, int limit)
            throws Problem, CoreException, IOException {
        this.root = ctx.root;
        this.deadline = ((DiskSync.Deadline) ctx.monitor).deadline;
        this.severities = severities;
        this.limit = limit;
        this.warnings = warnings;
        List<String> outside = new ArrayList<>();
        List<EditPlan.Op> ops = ops(edit, root, outside);
        ops.forEach(o -> named.addAll(paths(o)));
        if (!outside.isEmpty()) {
            throw Problem.of(Code.EDIT_OUTSIDE_WORKTREE)
                    .detail("The edit JDT computed would change " + (outside.size() == 1 ? "1 file" : outside.size() + " files") + " outside " + root
                            + ". Nothing was written.")
                    .with("files", strings(outside));
        }
        EditPlan.Source source = new EditPlan.Source() {
            public String text(Path file) throws IOException, Problem {
                return read(file, sync);
            }

            public boolean isDirectory(Path file) {
                return Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS);
            }

            public String name(Path file) {
                return relative(file);
            }
        };
        try {
            this.changes = EditPlan.plan(ops, source);
        } catch (EditPlan.Invalid e) {
            throw new IllegalStateException("JDT LS's edit can't be applied: " + e.getMessage(), e);
        }
        for (EditPlan.Change c : changes) {
            Read r = reads.get(c.from() != null ? c.from() : c.path());
            byte[] after = c.after() == null ? null : encode(c, r, ctx);
            writes.add(new EditWriter.Write(c.path(), c.from(), r == null ? null : r.bytes(), after));
        }
        this.keep = keep(ctx);
        // Here, under the request's deadline, not in the answer after the writes (T002.07's review, R3).
        Runnable check = () -> {
            if (ctx.monitor.isCanceled()) {
                throw new org.eclipse.core.runtime.OperationCanceledException();
            }
        };
        StringBuilder d = new StringBuilder();
        for (EditPlan.Change c : changes) {
            String from = relative(c.from() != null ? c.from() : c.path());
            d.append(UnifiedDiff.diff(from, relative(c.path()), c.before(), c.after(), check));
        }
        this.diff = d.toString();
        this.digest = writeSetDigest(writes, root);
    }

    /**
     * The write set's SHA-256 (design §11 "Operation folders"), in lowercase hex: over the number of entries, then
     * one entry per file operation, in the order they're written, each its kind, its path relative to the worktree,
     * for a rename the path it's moved from, and but for a delete its new bytes. Each field is framed by its length
     * (8 bytes, big-endian), so no two write sets frame to the same bytes: a delete, which has no bytes, differs from
     * an empty file.
     */
    static String writeSetDigest(List<EditWriter.Write> writes, Path root) {
        java.security.MessageDigest sha;
        try {
            sha = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        frame(sha, Long.toString(writes.size()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (EditWriter.Write w : writes) {
            String kind = w.after() == null ? "deleted" : w.from() != null ? "renamed" : w.before() == null ? "created" : "modified";
            frame(sha, kind.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            frame(sha, root.relativize(w.path()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (w.from() != null) {
                frame(sha, root.relativize(w.from()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            if (w.after() != null) {
                frame(sha, w.after());
            }
        }
        return java.util.HexFormat.of().formatHex(sha.digest());
    }

    private static void frame(java.security.MessageDigest sha, byte[] field) {
        sha.update(ByteBuffer.allocate(Long.BYTES).putLong(field.length).array());
        sha.update(field);
    }

    // --- reading the files -------------------------------------------------------------------------

    /** The file's text, read once, in its charset: null when it doesn't exist (then it must still not, at the apply). */
    private String read(Path file, DiskSync sync) throws IOException, Problem {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            absent.add(file);
            return null;
        }
        FileRecords.State recorded = sync.recorded(root, file);
        FileRecords.State now = FileRecords.state(file);
        byte[] bytes = Files.readAllBytes(file);
        IFile f = ResourcesPlugin.getWorkspace().getRoot().getFileForLocation(IPath.fromOSString(file.toString()));
        Charset charset;
        try {
            charset = Charset.forName(f != null ? f.getCharset() : ResourcesPlugin.getEncoding());
        } catch (CoreException | IllegalArgumentException e) {
            throw new IllegalStateException("can't tell the charset of " + file, e);
        }
        Coding coding = coding(bytes, charset);
        String text = decode(bytes, coding, file);
        if (f != null && JavaCore.create(f) instanceof ICompilationUnit unit && unit.exists()) {
            String jdt;
            try {
                jdt = unit.getSource();
            } catch (CoreException e) {
                throw new IllegalStateException("JDT has no source for " + file, e);
            }
            if (jdt != null && !jdt.equals(text)) {
                throw filesChanged(List.of(file), "isn't what JDT computed the edit from: it changed on disk after the fixed point.");
            }
        }
        reads.put(file, new Read(file, f, bytes, coding, recorded != null ? recorded : now));
        return text;
    }

    /**
     * How a file's bytes are its text: the charset that decodes them in their byte order, and the byte order mark that
     * precedes them, kept aside. As JDT reads a file (its compiler {@code Util.getBytesAsCharArray}): a UTF-8 mark is
     * left out; {@code UTF-16} and {@code UTF-32} take their byte order from their mark, which Java's decoder consumes,
     * and are big-endian without one; a charset with its byte order named ({@code UTF-16LE}) reads a mark as a
     * character, U+FEFF, as JDT does. Encoding with the same charset and mark gives the same bytes back (T002.07's
     * review, R5).
     */
    record Coding(Charset charset, byte[] bom) {}

    static Coding coding(byte[] bytes, Charset charset) {
        byte[] none = new byte[0];
        switch (charset.name().toUpperCase(java.util.Locale.ROOT)) {
            case "UTF-8" -> {
                byte[] mark = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
                return new Coding(charset, startsWith(bytes, mark) ? mark : none);
            }
            case "UTF-16" -> {
                byte[] be = {(byte) 0xFE, (byte) 0xFF};
                byte[] le = {(byte) 0xFF, (byte) 0xFE};
                return startsWith(bytes, le) ? new Coding(java.nio.charset.StandardCharsets.UTF_16LE, le)
                        : new Coding(java.nio.charset.StandardCharsets.UTF_16BE, startsWith(bytes, be) ? be : none);
            }
            case "UTF-32" -> {
                byte[] be = {0, 0, (byte) 0xFE, (byte) 0xFF};
                byte[] le = {(byte) 0xFF, (byte) 0xFE, 0, 0};
                return startsWith(bytes, le) ? new Coding(Charset.forName("UTF-32LE"), le)
                        : new Coding(Charset.forName("UTF-32BE"), startsWith(bytes, be) ? be : none);
            }
            default -> {
                return new Coding(charset, none);
            }
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] mark) {
        return bytes.length >= mark.length && Arrays.equals(Arrays.copyOf(bytes, mark.length), mark);
    }

    /**
     * The text, decoded strictly: a file that isn't valid in its charset, or wouldn't encode back to the same bytes,
     * can't be edited without changing bytes outside the edit, so the edit is refused.
     */
    static String decode(byte[] bytes, Coding coding, Path file) throws Problem {
        int skip = coding.bom().length;
        try {
            String text = coding.charset().newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, skip, bytes.length - skip)).toString();
            if (!Arrays.equals(encode(text, coding), bytes)) {
                throw new CharacterCodingException();
            }
            return text;
        } catch (CharacterCodingException e) {
            throw Problem.of(Code.REFACTORING_REFUSED)
                    .detail(file.getFileName() + " isn't valid " + coding.charset().name() + " text, its charset in Eclipse, so the endpoint can't edit it"
                            + " without changing bytes outside the edit. Nothing was written.")
                    .hint("Fix the file's encoding, or its charset (a .settings/org.eclipse.core.resources.prefs entry), then send the request again.");
        }
    }

    /** The text's bytes: the mark, then the text in the coding's charset and byte order. */
    static byte[] encode(String text, Coding coding) throws CharacterCodingException {
        ByteBuffer b = coding.charset().newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(text));
        byte[] all = Arrays.copyOf(coding.bom(), coding.bom().length + b.remaining());
        b.get(all, coding.bom().length, b.remaining());
        return all;
    }

    /** The new bytes: a changed file's own charset, byte order and mark, a created one's in its module's charset. */
    private byte[] encode(EditPlan.Change c, Read r, Targets.Context ctx) throws Problem, CoreException {
        Coding coding;
        if (r != null) {
            coding = r.coding();
        } else {
            IProject p = ctx.projectOf(c.path());
            coding = new Coding(Charset.forName(p != null ? p.getDefaultCharset() : ResourcesPlugin.getEncoding()), new byte[0]);
        }
        try {
            return encode(c.after(), coding);
        } catch (CharacterCodingException e) {
            throw Problem.of(Code.REFACTORING_REFUSED)
                    .detail("The edit's text for " + relative(c.path()) + " can't be written in " + coding.charset().name()
                            + ", the file's charset. Nothing was written.")
                    .hint("Use names and text that " + coding.charset().name() + " can encode.");
        }
    }

    /** The folders an emptied package folder is never removed above: each module's folder and source folders. */
    private static Set<Path> keep(Targets.Context ctx) throws CoreException {
        Set<Path> keep = new LinkedHashSet<>();
        for (IProject p : ctx.projects) {
            if (Targets.Context.location(p) != null) {
                keep.add(Targets.Context.location(p));
            }
        }
        for (IJavaProject j : ctx.javaProjects()) {
            for (IPackageFragmentRoot r : j.getPackageFragmentRoots()) {
                if (r.getKind() == IPackageFragmentRoot.K_SOURCE && r.getResource() != null && r.getResource().getLocation() != null) {
                    keep.add(Path.of(r.getResource().getLocation().toOSString()));
                }
            }
        }
        return keep;
    }

    // --- the WorkspaceEdit -------------------------------------------------------------------------

    /**
     * Its operations, in order: {@code documentChanges} when present, as the endpoint prefers (they carry the file
     * operations), else {@code changes}, by path. A URI outside the worktree, or not a file, goes to {@code outside}.
     */
    static List<EditPlan.Op> ops(WorkspaceEdit edit, Path root, List<String> outside) {
        List<EditPlan.Op> ops = new ArrayList<>();
        if (edit == null) {
            return ops;
        }
        if (edit.getDocumentChanges() != null && !edit.getDocumentChanges().isEmpty()) {
            for (Either<TextDocumentEdit, ResourceOperation> e : edit.getDocumentChanges()) {
                if (e.isLeft()) {
                    TextDocumentEdit t = e.getLeft();
                    List<EditPlan.Edit> edits = new ArrayList<>();
                    for (Either<TextEdit, ?> x : t.getEdits()) {
                        if (!x.isLeft()) {
                            throw new IllegalStateException("JDT LS sent a snippet edit, which the endpoint doesn't apply");
                        }
                        edits.add(edit(x.getLeft()));
                    }
                    ops.add(new EditPlan.TextEdits(path(t.getTextDocument().getUri(), root, outside), edits));
                } else if (e.getRight() instanceof CreateFile c) {
                    ops.add(new EditPlan.Create(path(c.getUri(), root, outside), c.getOptions() != null && Boolean.TRUE.equals(c.getOptions().getOverwrite()),
                            c.getOptions() != null && Boolean.TRUE.equals(c.getOptions().getIgnoreIfExists())));
                } else if (e.getRight() instanceof RenameFile r) {
                    ops.add(new EditPlan.Rename(path(r.getOldUri(), root, outside), path(r.getNewUri(), root, outside),
                            r.getOptions() != null && Boolean.TRUE.equals(r.getOptions().getOverwrite()),
                            r.getOptions() != null && Boolean.TRUE.equals(r.getOptions().getIgnoreIfExists())));
                } else if (e.getRight() instanceof DeleteFile d) {
                    ops.add(new EditPlan.Delete(path(d.getUri(), root, outside), d.getOptions() != null && Boolean.TRUE.equals(d.getOptions().getRecursive()),
                            d.getOptions() != null && Boolean.TRUE.equals(d.getOptions().getIgnoreIfNotExists())));
                } else {
                    throw new IllegalStateException("JDT LS sent a resource operation the endpoint doesn't know: " + e.getRight());
                }
            }
        } else if (edit.getChanges() != null) {
            for (Map.Entry<String, List<TextEdit>> e : new TreeMap<>(edit.getChanges()).entrySet()) {
                ops.add(new EditPlan.TextEdits(path(e.getKey(), root, outside), e.getValue().stream().map(Edits::edit).toList()));
            }
        }
        return ops;
    }

    private static EditPlan.Edit edit(TextEdit t) {
        return new EditPlan.Edit(t.getRange().getStart().getLine(), t.getRange().getStart().getCharacter(), t.getRange().getEnd().getLine(),
                t.getRange().getEnd().getCharacter(), t.getNewText());
    }

    private static Path path(String uri, Path root, List<String> outside) {
        URI u = URI.create(uri);
        if (!"file".equals(u.getScheme())) {
            outside.add(uri);
            return Path.of("/nonexistent-" + outside.size());
        }
        Path p = Path.of(u).normalize();
        if (!inside(p, root)) {
            outside.add(p.toString());
        }
        return p;
    }

    /**
     * Whether the file is really in the worktree (T002.07's review, R1): compared by where each really is, symbolic
     * links resolved, so a file or folder of wt-a that links into wt-b is outside wt-a. A file that doesn't exist yet
     * is where its nearest existing folder really is; a link that leads nowhere is outside.
     */
    static boolean inside(Path file, Path root) {
        Path real = real(file);
        Path realRoot = real(root);
        return real != null && realRoot != null && real.startsWith(realRoot) && !real.equals(realRoot);
    }

    /** Where the path really is: its nearest existing ancestor's real path, with the names below it; null for a dangling link. */
    static Path real(Path path) {
        Path p = path.toAbsolutePath().normalize();
        for (Path at = p; at != null; at = at.getParent()) {
            if (Files.exists(at)) {
                try {
                    return at.toRealPath().resolve(at.relativize(p));
                } catch (IOException e) {
                    return null;
                }
            }
            if (Files.isSymbolicLink(at)) {
                return null;
            }
        }
        return null;
    }

    /** The files an operation reads, writes, creates, moves or deletes. */
    private static List<Path> paths(EditPlan.Op op) {
        return switch (op) {
            case EditPlan.TextEdits t -> List.of(t.file());
            case EditPlan.Create c -> List.of(c.file());
            case EditPlan.Rename r -> List.of(r.from(), r.to());
            case EditPlan.Delete d -> List.of(d.file());
        };
    }

    // --- the answer and the apply ------------------------------------------------------------------

    @Override
    public JsonObject preview() {
        JsonObject o = new JsonObject();
        JsonArray files = new JsonArray();
        for (EditPlan.Change c : changes) {
            JsonObject f = new JsonObject();
            f.addProperty("path", relative(c.path()));
            f.addProperty("change", c.kind());
            if (c.from() != null) {
                f.addProperty("from", relative(c.from()));
            }
            files.add(f);
        }
        o.add("files", files);
        o.addProperty("diff", diff);
        o.addProperty("writeSetDigest", digest);
        if (!warnings.isEmpty()) {
            o.add("warnings", strings(warnings));
        }
        return o;
    }

    @Override
    public String diff() {
        return diff;
    }

    @Override
    public String writeSetDigest() {
        return digest;
    }

    @Override
    public boolean unchanged() {
        return changes.isEmpty();
    }

    @Override
    public List<Path> written() {
        Set<Path> all = new LinkedHashSet<>();
        for (EditPlan.Change c : changes) {
            all.add(c.path());
            if (c.from() != null) {
                all.add(c.from());
            }
        }
        return List.copyOf(all);
    }

    @Override
    public void write() throws Problem, IOException, InterruptedException, DiskSync.Cancelled {
        long checking = System.nanoTime();
        List<String> outside = named.stream().filter(p -> !inside(p, root)).map(Path::toString).distinct().toList();
        if (!outside.isEmpty()) {
            // A link made since the edit was computed: still never written through.
            throw Problem.of(Code.EDIT_OUTSIDE_WORKTREE)
                    .detail("The edit would now change " + (outside.size() == 1 ? "1 file" : outside.size() + " files") + " outside " + root
                            + ", through a symbolic link. Nothing was written.")
                    .with("files", strings(outside));
        }
        List<Path> changed = new ArrayList<>();
        for (Read r : reads.values()) {
            if (!unchangedSince(r)) {
                changed.add(r.path());
            }
        }
        for (Path p : absent) {
            if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
                changed.add(p);
            }
        }
        if (!changed.isEmpty()) {
            throw filesChanged(changed, changed.size() == 1 ? "changed on disk after the edit's baseline was taken." : null);
        }
        checkMs = (System.nanoTime() - checking) / 1e6;
        TestPauses.pause("before-write", deadline);
        if (System.nanoTime() - deadline >= 0) {
            // The last moment nothing is written yet (T002.07's review, R4): the check above may have used the time up.
            throw new DiskSync.Cancelled("applying", "applying the edit to " + root + " after checking its files, before writing any");
        }
        long writing = System.nanoTime();
        try {
            EditWriter.apply(writes, keep, EditWriter.DISK, () -> {
                try {
                    TestPauses.pause("after-first-write", deadline);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.io.InterruptedIOException("interrupted at a test pause");
                }
            });
            writeMs = (System.nanoTime() - writing) / 1e6;
        } catch (EditWriter.Failed e) {
            String failed = e.path == null ? "a file" : relative(e.path);
            throw Problem.of(Code.WRITE_FAILED)
                    .detail("Writing the edit failed at " + failed + ": " + e.getMessage() + ". "
                            + (e.unrestored.isEmpty() ? "Every file the edit had written was restored to its earlier bytes, so the worktree is as it was."
                                    : "These couldn't be restored: " + String.join("; ", e.unrestored) + "."))
                    .with("files", strings(List.of(failed)));
        }
    }

    /** Whether the file is as it was at the baseline: the record, the bytes, and Eclipse's view in sync with it. */
    private static boolean unchangedSince(Read r) {
        FileRecords.State now = FileRecords.state(r.path());
        if (now == null || !now.equals(r.baseline())) {
            return false;
        }
        try {
            if (!Arrays.equals(Files.readAllBytes(r.path()), r.bytes())) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        return r.file() == null || r.file().isSynchronized(IResource.DEPTH_ZERO);
    }

    @Override
    public JsonObject after() throws CoreException {
        return Markers.read(root, null, severities, limit);
    }

    private Problem filesChanged(List<Path> files, String why) {
        String detail = files.size() == 1 ? relative(files.get(0)) + " " + why + " Nothing was written."
                : files.size() + " files changed on disk after the edit's baseline was taken. Nothing was written.";
        return Problem.of(Code.FILES_CHANGED).detail(detail).with("files", strings(files.stream().map(this::relative).toList()));
    }

    private String relative(Path p) {
        return p.startsWith(root) ? root.relativize(p).toString() : p.toString();
    }

    private static JsonArray strings(List<String> list) {
        JsonArray a = new JsonArray();
        list.forEach(a::add);
        return a;
    }

    @Override
    public String log() {
        Map<String, Integer> kinds = new LinkedHashMap<>();
        changes.forEach(c -> kinds.merge(c.kind(), 1, Integer::sum));
        return String.format(java.util.Locale.ROOT, "%d files %s, %d read; baseline check %.2f ms, writes %.2f ms", changes.size(), kinds, reads.size(),
                checkMs, writeMs);
    }
}
