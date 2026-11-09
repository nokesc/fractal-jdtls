// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** All-or-nothing writes (design §11 "Refactorings and edits"): every file, or every file restored. */
class EditWriterTest {

    @TempDir
    Path dir;

    static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Every file and folder under the root, with each file's bytes: the state to compare before and after. */
    static Map<String, String> tree(Path root) throws Exception {
        Map<String, String> t = new TreeMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.toList()) {
                String rel = root.relativize(p).toString();
                t.put(rel, Files.isDirectory(p) ? "<dir>" : new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
            }
        }
        return t;
    }

    @Test
    void writesEveryFileAndRemovesTheFoldersItEmptied() throws Exception {
        Path src = dir.resolve("src");
        Path a = Files.createDirectories(src.resolve("a"));
        Files.write(a.resolve("A.java"), b("package a;\r\n"));
        Files.write(src.resolve("B.java"), b("b"));
        Files.write(src.resolve("Gone.java"), b("gone"));
        Path moved = src.resolve("c/d/A.java");
        EditWriter.apply(List.of(
                new EditWriter.Write(moved, a.resolve("A.java"), b("package a;\r\n"), b("package c.d;\r\n")),
                new EditWriter.Write(src.resolve("B.java"), null, b("b"), b("B")),
                new EditWriter.Write(src.resolve("New.java"), null, null, b("new")),
                new EditWriter.Write(src.resolve("Gone.java"), null, b("gone"), null)), Set.of(src));
        assertEquals(Map.of("", "<dir>", "B.java", "B", "New.java", "new", "c", "<dir>", "c/d", "<dir>", "c/d/A.java", "package c.d;\r\n"),
                tree(src), "a/ emptied and removed; the kept source folder stays");
    }

    @Test
    void aFileThatCantBeWrittenRestoresEveryFileWrittenBeforeIt() throws Exception {
        Path src = Files.createDirectories(dir.resolve("src"));
        Files.write(src.resolve("A.java"), b("class A { int x; }\n"));
        Files.write(src.resolve("Z.java"), b("class Z {}\n"));
        Path locked = Files.createDirectories(src.resolve("p")).resolve("Old.java");
        Files.write(locked, b("class Old {}\n"));
        Files.write(src.resolve("Y.java"), b("class Y {}\n"));
        Files.setPosixFilePermissions(src.resolve("Z.java"), PosixFilePermissions.fromString("r--r--r--"));
        Map<String, String> before = tree(src);
        long inode = (Long) Files.getAttribute(src.resolve("A.java"), "unix:ino");
        EditWriter.Failed failed = assertThrows(EditWriter.Failed.class, () -> EditWriter.apply(List.of(
                new EditWriter.Write(src.resolve("A.java"), null, b("class A { int x; }\n"), b("class A { long x; }\n")),
                new EditWriter.Write(src.resolve("q/New.java"), locked, b("class Old {}\n"), b("class New {}\n")),
                new EditWriter.Write(src.resolve("B.java"), null, null, b("class B {}\n")),
                new EditWriter.Write(src.resolve("Y.java"), null, b("class Y {}\n"), null),
                new EditWriter.Write(src.resolve("Z.java"), null, b("class Z {}\n"), b("class Z { }\n"))), Set.of(src)));
        assertEquals(src.resolve("Z.java"), failed.path);
        assertEquals(List.of(), failed.unrestored);
        assertEquals(before, tree(src), "every file back to its exact bytes, the moved one at its old path, the made folder gone");
        assertEquals(inode, Files.getAttribute(src.resolve("A.java"), "unix:ino"), "restored in place");
        assertTrue(Files.exists(locked));
        assertFalse(Files.exists(src.resolve("q")));
    }

    @Test
    void neverReplacesAFileThatAppeared() throws Exception {
        Path src = Files.createDirectories(dir.resolve("src"));
        Files.write(src.resolve("A.java"), b("a"));
        Files.write(src.resolve("C.java"), b("someone else's"));
        Map<String, String> before = tree(src);
        assertThrows(EditWriter.Failed.class, () -> EditWriter.apply(List.of(
                new EditWriter.Write(src.resolve("C.java"), src.resolve("A.java"), b("a"), b("c"))), Set.of(src)));
        assertThrows(EditWriter.Failed.class, () -> EditWriter.apply(List.of(
                new EditWriter.Write(src.resolve("C.java"), null, null, b("c"))), Set.of(src)));
        assertEquals(before, tree(src));
        assertArrayEquals(b("someone else's"), Files.readAllBytes(src.resolve("C.java")));
    }

    /**
     * T002.07's review: a create whose write fails partway (a full disk, say) leaves no half-written file behind. Its
     * undo is registered before the bytes are written, so the live undo deletes it with the folders it made.
     */
    @Test
    void aCreateWhoseWriteFailsPartwayIsUndone() throws Exception {
        Path src = Files.createDirectories(dir.resolve("src"));
        Files.write(src.resolve("A.java"), b("class A { int x; }\n"));
        Map<String, String> before = tree(src);
        byte[] created = b("class B {}\n");
        EditWriter.Output halfThenFull = (ch, bytes) -> {
            if (bytes == created) {
                ch.write(ByteBuffer.wrap(bytes, 0, bytes.length / 2));
                throw new IOException("No space left on device (the test's)");
            }
            EditWriter.DISK.write(ch, bytes);
        };
        EditWriter.Failed failed = assertThrows(EditWriter.Failed.class, () -> EditWriter.apply(List.of(
                new EditWriter.Write(src.resolve("A.java"), null, b("class A { int x; }\n"), b("class A { long x; }\n")),
                new EditWriter.Write(src.resolve("q/B.java"), null, null, created)), Set.of(src), halfThenFull, () -> {}));
        assertEquals(src.resolve("q/B.java"), failed.path);
        assertEquals(List.of(), failed.unrestored);
        assertEquals(before, tree(src), "the half-written file and its folder gone, A.java back to its bytes");
    }
}
