// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a {@code WorkspaceEdit} does to the files (design §11 "Refactorings and edits"), worked out in memory before
 * anything is written: its text edits, file creates, renames and deletes, applied in order as the LSP says
 * ({@code documentChanges} are applied one after another, and a text edit's positions are in the document as the
 * operations before it left it). The result is each file's text before and after, and whether it was modified,
 * created, deleted or renamed, which {@link EditWriter} then writes all or nothing. Plain Java, with no Eclipse class,
 * so it's tested on its own.
 *
 * <p>A create that {@code ignoreIfExists} skips leaves the existing file the user's: a later edit or delete of it is
 * {@code refactoring-refused}, nothing written.
 *
 * <p>Strict: an operation the LSP would refuse (an edit of a missing file, overlapping edits, a position past its
 * line), one that would overwrite a file, and a rename or delete of a folder, are {@link Invalid}: JDT LS never sends
 * them for the endpoint's refactorings, so one would be a defect, never something to guess around.
 */
final class EditPlan {

    /** A text edit: a 0-based LSP range, in UTF-16 units, and its new text. */
    record Edit(int startLine, int startCharacter, int endLine, int endCharacter, String newText) {}

    sealed interface Op permits TextEdits, Create, Rename, Delete {}

    /** One {@code TextDocumentEdit}: edits that don't overlap, all in the document as it is before them. */
    record TextEdits(Path file, List<Edit> edits) implements Op {}

    record Create(Path file, boolean overwrite, boolean ignoreIfExists) implements Op {}

    record Rename(Path from, Path to, boolean overwrite, boolean ignoreIfExists) implements Op {}

    record Delete(Path file, boolean recursive, boolean ignoreIfNotExists) implements Op {}

    /** The texts the edit starts from: a file's text, or null when there's no such file. */
    interface Source {
        String text(Path file) throws IOException, Problem;

        boolean isDirectory(Path file);

        /** The file as the caller names it, in a refusal. */
        default String name(Path file) {
            return file.toString();
        }
    }

    /**
     * One file the edit changes: {@code kind} is {@code modified}, {@code created}, {@code deleted} or {@code renamed}
     * (then {@code from} is the old path). {@code before} is null for a created file, {@code after} for a deleted one.
     */
    record Change(Path path, Path from, String kind, String before, String after) {}

    /** An operation the LSP or the endpoint doesn't allow. */
    static final class Invalid extends Exception {
        private static final long serialVersionUID = 1L;

        Invalid(String message) {
            super(message);
        }
    }

    /** A file's state during the plan: its text (null when it doesn't exist) and the original file it came from. */
    private record Slot(String text, Path origin) {}

    private final Source source;
    private final Map<Path, Slot> slots = new LinkedHashMap<>();
    private final Map<Path, String> originals = new TreeMap<>();
    /** Files a create skipped because they exist ({@code ignoreIfExists}): the user's, never the edit's own. */
    private final java.util.Set<Path> skipped = new java.util.TreeSet<>();

    private EditPlan(Source source) {
        this.source = source;
    }

    /** The changes {@code ops} make, by path; a file left as it was isn't one. */
    static List<Change> plan(List<Op> ops, Source source) throws Invalid, IOException, Problem {
        EditPlan p = new EditPlan(source);
        for (Op op : ops) {
            p.apply(op);
        }
        return p.changes();
    }

    private Slot slot(Path file) throws IOException, Problem, Invalid {
        Slot s = slots.get(file);
        if (s == null) {
            if (source.isDirectory(file)) {
                throw new Invalid(file + " is a folder; the endpoint renames, creates and deletes only files");
            }
            String text = source.text(file);
            if (text != null) {
                originals.put(file, text);
            }
            s = new Slot(text, text == null ? null : file);
            slots.put(file, s);
        }
        return s;
    }

    private void apply(Op op) throws Invalid, IOException, Problem {
        switch (op) {
            case TextEdits t -> {
                Slot s = slot(t.file());
                notTheEditsOwn(t.file(), s, "change");
                if (s.text() == null) {
                    throw new Invalid("a text edit of " + t.file() + ", which doesn't exist");
                }
                slots.put(t.file(), new Slot(edit(s.text(), t.edits(), t.file()), s.origin()));
            }
            case Create c -> {
                Slot s = slot(c.file());
                if (s.text() != null) {
                    if (c.ignoreIfExists() && !c.overwrite()) {
                        skipped.add(c.file());
                        return;
                    }
                    throw new Invalid("creating " + c.file() + ", which exists");
                }
                slots.put(c.file(), new Slot("", null));
            }
            case Rename r -> {
                Slot from = slot(r.from());
                if (from.text() == null) {
                    throw new Invalid("renaming " + r.from() + ", which doesn't exist");
                }
                Slot to = slot(r.to());
                if (to.text() != null) {
                    if (r.ignoreIfExists() && !r.overwrite()) {
                        return;
                    }
                    throw new Invalid("renaming " + r.from() + " to " + r.to() + ", which exists");
                }
                slots.put(r.to(), from);
                slots.put(r.from(), new Slot(null, null));
            }
            case Delete d -> {
                Slot s = slot(d.file());
                notTheEditsOwn(d.file(), s, "delete");
                if (s.text() == null) {
                    if (d.ignoreIfNotExists()) {
                        return;
                    }
                    throw new Invalid("deleting " + d.file() + ", which doesn't exist");
                }
                slots.put(d.file(), new Slot(null, null));
            }
        }
    }

    /**
     * Refuses an edit that would change or delete a file it means to have made, which was there already (T002.07's
     * review, R2). JDT LS's package rename creates a {@code .temp} file in the new package's folder, with
     * {@code ignoreIfExists}, and deletes it at the end: had the user a {@code .temp} there, the create is skipped and the
     * delete would remove their file. The endpoint never deletes a file the edit didn't create or JDT didn't ask to
     * delete as its own.
     */
    private void notTheEditsOwn(Path file, Slot s, String what) throws Problem {
        if (skipped.contains(file) && s.text() != null && file.equals(s.origin())) {
            String name = source.name(file);
            throw Problem.of(Code.REFACTORING_REFUSED)
                    .detail(name + " already exists, and JDT's edit would " + what + " it as a temporary file of its own (JDT LS's package rename makes and"
                            + " deletes one there). Nothing was written.")
                    .hint("Move " + name + " away (it's yours, not JDT's), then send the request again.");
        }
    }

    private List<Change> changes() {
        List<Change> changes = new ArrayList<>();
        Map<Path, Path> placed = new LinkedHashMap<>(); // origin -> where it is now
        slots.forEach((path, s) -> {
            if (s.text() != null && s.origin() != null) {
                placed.put(s.origin(), path);
            }
        });
        originals.forEach((origin, before) -> {
            Path now = placed.get(origin);
            if (now == null) {
                changes.add(new Change(origin, null, "deleted", before, null));
            } else if (!now.equals(origin)) {
                changes.add(new Change(now, origin, "renamed", before, slots.get(now).text()));
            } else if (!before.equals(slots.get(now).text())) {
                changes.add(new Change(origin, null, "modified", before, slots.get(now).text()));
            }
        });
        slots.forEach((path, s) -> {
            if (s.text() != null && s.origin() == null) {
                changes.add(new Change(path, null, "created", null, s.text()));
            }
        });
        changes.sort(Comparator.comparing(Change::path));
        return changes;
    }

    /** The text with the edits applied: each range converted on the text before any of them, as the LSP says. */
    static String edit(String text, List<Edit> edits, Path file) throws Invalid {
        Positions lines = new Positions(text);
        record At(int start, int end, int index, String newText) {}
        List<At> at = new ArrayList<>();
        for (int i = 0; i < edits.size(); i++) {
            Edit e = edits.get(i);
            int start = offset(text, lines, e.startLine(), e.startCharacter(), file);
            int end = offset(text, lines, e.endLine(), e.endCharacter(), file);
            if (end < start) {
                throw new Invalid("a text edit of " + file + " ends before it starts");
            }
            at.add(new At(start, end, i, e.newText() == null ? "" : e.newText()));
        }
        at.sort(Comparator.comparingInt(At::start).thenComparingInt(At::index));
        StringBuilder b = new StringBuilder(text.length());
        int done = 0;
        for (At a : at) {
            if (a.start() < done) {
                throw new Invalid("two text edits of " + file + " overlap");
            }
            b.append(text, done, a.start()).append(a.newText());
            done = a.end();
        }
        return b.append(text, done, text.length()).toString();
    }

    /** A 0-based line and character as an offset: strictly on the line (its terminator excluded), or the text's end. */
    private static int offset(String text, Positions lines, int line, int character, Path file) throws Invalid {
        if (line < 0 || character < 0) {
            throw new Invalid("a text edit of " + file + " has a negative position");
        }
        if (line == lines.lines() && character == 0 && (text.isEmpty() || text.endsWith("\n") || text.endsWith("\r"))) {
            return text.length();
        }
        if (line >= lines.lines()) {
            throw new Invalid("a text edit of " + file + " is on line " + (line + 1) + ", past its " + lines.lines() + " lines");
        }
        String content = lines.line(line + 1);
        if (character > content.length()) {
            throw new Invalid("a text edit of " + file + " is at character " + character + " of line " + (line + 1) + ", which has "
                    + content.length());
        }
        return lines.offsetOf(line + 1, character + 1);
    }
}
