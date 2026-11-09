// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Writes an edit's files all or nothing (design §11 "Refactorings and edits"): every file of the edit, or, if any
 * step fails, none. Each step is journaled, and a failure undoes the steps already taken, newest first, so every file
 * gets back its exact earlier bytes, a moved file its old path, a deleted one its bytes and permissions, and each
 * folder made or emptied is removed or made again. Plain Java, with no Eclipse class.
 *
 * <ul>
 *   <li>A modified file is written in place, so it keeps its inode, owner and permissions, as an editor's save does;
 *       a file that can't be written (no permission) fails there.
 *   <li>A renamed file is moved first (never over an existing file), then written in place if its text changed.
 *   <li>A created file never replaces one ({@code CREATE_NEW}); the folders it needs are made.
 *   <li>A folder that the edit's renames and deletes emptied is removed, so a renamed package leaves no empty folder,
 *       but never a folder in {@code keep} (the modules' source folders) or above one.
 * </ul>
 */
final class EditWriter {

    /**
     * One file to write: {@code before} is its bytes now (null for a created file), {@code after} its new bytes (null
     * to delete it); a rename has {@code from}, where the file is now.
     */
    record Write(Path path, Path from, byte[] before, byte[] after) {}

    /** A step failed; the steps before it were undone, except {@code unrestored}. */
    static final class Failed extends Exception {
        private static final long serialVersionUID = 1L;
        final Path path;
        final List<String> unrestored;

        Failed(Path path, IOException cause, List<String> unrestored) {
            super(cause.toString(), cause);
            this.path = path;
            this.unrestored = unrestored;
        }
    }

    private interface Undo {
        void run() throws IOException;

        String what();
    }

    /** How a file's new bytes reach it, through its open channel: {@link #DISK}, or in a test, one that fails partway. */
    interface Output {
        void write(FileChannel ch, byte[] bytes) throws IOException;
    }

    /** Truncates the file, then writes the bytes. */
    static final Output DISK = EditWriter::replace;

    /** Run once, after the first step that changes a file: a test pause, so a test can stop the process there. */
    interface Hook {
        void run() throws IOException;
    }

    private final Deque<Undo> journal = new ArrayDeque<>();
    private final Output output;
    private Hook afterFirstWrite;

    private EditWriter(Output output, Hook afterFirstWrite) {
        this.output = output;
        this.afterFirstWrite = afterFirstWrite;
    }

    /** Writes them all, or none: {@link Failed} after undoing whatever was written. */
    static void apply(Collection<Write> writes, Set<Path> keep) throws Failed {
        apply(writes, keep, DISK, () -> {});
    }

    /** {@link #apply(Collection, Set)}, writing through {@code output}, with {@code afterFirstWrite} run after the first file's step. */
    static void apply(Collection<Write> writes, Set<Path> keep, Output output, Hook afterFirstWrite) throws Failed {
        EditWriter w = new EditWriter(output, afterFirstWrite);
        Path current = null;
        try {
            List<Write> sorted = new ArrayList<>(writes);
            sorted.sort(Comparator.comparing(Write::path));
            Set<Path> emptied = new TreeSet<>(Comparator.reverseOrder()); // deepest first
            for (Write x : sorted) {
                if (x.from() != null) {
                    current = x.path();
                    w.move(x.from(), x.path());
                    emptied.add(x.from().getParent());
                    w.wrote();
                }
            }
            for (Write x : sorted) {
                current = x.path();
                if (x.after() == null) {
                    w.delete(x.path());
                    emptied.add(x.path().getParent());
                    w.wrote();
                } else if (x.before() == null) {
                    w.create(x.path(), x.after());
                    w.wrote();
                } else if (x.from() == null || !java.util.Arrays.equals(x.before(), x.after())) {
                    w.rewrite(x.path(), x.before(), x.after());
                    w.wrote();
                }
            }
            for (Path dir : emptied) {
                current = dir;
                w.removeEmpty(dir, keep);
            }
        } catch (IOException e) {
            throw new Failed(current, e, w.undo());
        }
    }

    private void rewrite(Path file, byte[] before, byte[] after) throws IOException {
        // Opened first: a file that can't be opened for writing is unchanged, and needs no undo. Once it's open, the
        // undo is journaled before the write, which may fail after truncating it.
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            journal.push(new Undo() {
                public void run() throws IOException {
                    try (FileChannel back = FileChannel.open(file, StandardOpenOption.WRITE)) {
                        replace(back, before);
                    }
                }

                public String what() {
                    return "restore " + file;
                }
            });
            output.write(ch, after);
        }
    }

    /** After a file's step: the first time, the hook. */
    private void wrote() throws IOException {
        Hook h = afterFirstWrite;
        afterFirstWrite = null;
        if (h != null) {
            h.run();
        }
    }

    private static void replace(FileChannel ch, byte[] bytes) throws IOException {
        ch.truncate(0);
        ByteBuffer b = ByteBuffer.wrap(bytes);
        while (b.hasRemaining()) {
            ch.write(b, b.position());
        }
    }

    private void create(Path file, byte[] after) throws IOException {
        makeFolders(file.getParent());
        // Created first, never over an existing file: one that can't be created isn't the edit's, and needs no undo.
        // Once it exists, the undo is journaled before the write, which may fail partway (T002.07's review).
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)) {
            journal.push(new Undo() {
                public void run() throws IOException {
                    Files.delete(file);
                }

                public String what() {
                    return "delete the created " + file;
                }
            });
            output.write(ch, after);
        }
    }

    private void move(Path from, Path to) throws IOException {
        makeFolders(to.getParent());
        Files.move(from, to); // never replaces an existing file
        journal.push(new Undo() {
            public void run() throws IOException {
                Files.move(to, from);
            }

            public String what() {
                return "move " + to + " back to " + from;
            }
        });
    }

    private void delete(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
        Files.delete(file);
        journal.push(new Undo() {
            public void run() throws IOException {
                Files.write(file, bytes, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW);
                Files.setPosixFilePermissions(file, permissions);
            }

            public String what() {
                return "restore the deleted " + file;
            }
        });
    }

    private void makeFolders(Path dir) throws IOException {
        List<Path> missing = new ArrayList<>();
        for (Path d = dir; d != null && !Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS); d = d.getParent()) {
            missing.add(0, d);
        }
        for (Path d : missing) {
            Files.createDirectory(d);
            journal.push(new Undo() {
                public void run() throws IOException {
                    Files.delete(d);
                }

                public String what() {
                    return "remove the created folder " + d;
                }
            });
        }
    }

    /** Removes the folder if it's empty and inside a kept folder, then its parents the same way. */
    private void removeEmpty(Path dir, Set<Path> keep) throws IOException {
        for (Path d = dir; d != null && inside(d, keep) && empty(d); d = d.getParent()) {
            Files.delete(d);
            Path removed = d;
            journal.push(new Undo() {
                public void run() throws IOException {
                    Files.createDirectory(removed);
                }

                public String what() {
                    return "make the removed folder " + removed + " again";
                }
            });
        }
    }

    private static boolean inside(Path dir, Set<Path> keep) {
        for (Path k : keep) {
            if (dir.startsWith(k) && !dir.equals(k)) {
                return true;
            }
        }
        return false;
    }

    private static boolean empty(Path dir) throws IOException {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
            return !s.iterator().hasNext();
        }
    }

    /** Undoes every step taken, newest first; what couldn't be undone. */
    private List<String> undo() {
        List<String> failed = new ArrayList<>();
        while (!journal.isEmpty()) {
            Undo u = journal.pop();
            try {
                u.run();
            } catch (IOException | RuntimeException e) {
                failed.add(u.what() + ": " + e);
            }
        }
        return failed;
    }
}
