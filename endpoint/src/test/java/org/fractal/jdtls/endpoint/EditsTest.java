// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.lsp4j.CreateFile;
import org.eclipse.lsp4j.CreateFileOptions;
import org.eclipse.lsp4j.DeleteFile;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.RenameFile;
import org.eclipse.lsp4j.ResourceOperation;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading JDT LS's {@code WorkspaceEdit} (design §11 "Refactorings and edits"): its operations, and files outside the worktree. */
class EditsTest {

    static final Path ROOT = Path.of("/work/wt-a");

    static TextEdit edit(int line, int from, int to, String text) {
        return new TextEdit(new Range(new Position(line, from), new Position(line, to)), text);
    }

    static Either<TextDocumentEdit, ResourceOperation> text(String uri, TextEdit... edits) {
        List<Either<TextEdit, org.eclipse.lsp4j.SnippetTextEdit>> list = new ArrayList<>();
        for (TextEdit e : edits) {
            list.add(Either.forLeft(e));
        }
        return Either.forLeft(new TextDocumentEdit(new VersionedTextDocumentIdentifier(uri, null), list));
    }

    @Test
    void documentChangesInOrderWithTheirFileOperations() {
        WorkspaceEdit we = new WorkspaceEdit();
        CreateFile create = new CreateFile("file:///work/wt-a/m/src/c/.temp", new CreateFileOptions(false, true));
        we.setDocumentChanges(List.of(text("file:///work/wt-a/m/src/a/A.java", edit(0, 8, 9, "c")), Either.forRight(create),
                Either.forRight(new RenameFile("file:///work/wt-a/m/src/a/A.java", "file:///work/wt-a/m/src/c/A.java")),
                Either.forRight(new DeleteFile("file:///work/wt-a/m/src/c/.temp"))));
        we.setChanges(Map.of("file:///work/wt-a/ignored.java", List.of(edit(0, 0, 0, "x"))));
        List<String> outside = new ArrayList<>();
        List<EditPlan.Op> ops = Edits.ops(we, ROOT, outside);
        assertEquals(List.of(), outside);
        assertEquals(List.of(new EditPlan.TextEdits(Path.of("/work/wt-a/m/src/a/A.java"), List.of(new EditPlan.Edit(0, 8, 0, 9, "c"))),
                new EditPlan.Create(Path.of("/work/wt-a/m/src/c/.temp"), false, true),
                new EditPlan.Rename(Path.of("/work/wt-a/m/src/a/A.java"), Path.of("/work/wt-a/m/src/c/A.java"), false, false),
                new EditPlan.Delete(Path.of("/work/wt-a/m/src/c/.temp"), false, false)), ops, "documentChanges win over changes");
    }

    @Test
    void changesByPathWhenThereAreNoDocumentChanges() {
        WorkspaceEdit we = new WorkspaceEdit(Map.of("file:///work/wt-a/b/B.java", List.of(edit(1, 0, 0, "b")), "file:///work/wt-a/a/A.java",
                List.of(edit(0, 0, 0, "a"))));
        List<EditPlan.Op> ops = Edits.ops(we, ROOT, new ArrayList<>());
        assertEquals(List.of(Path.of("/work/wt-a/a/A.java"), Path.of("/work/wt-a/b/B.java")),
                ops.stream().map(o -> ((EditPlan.TextEdits) o).file()).toList());
    }

    @Test
    void filesOutsideTheWorktreeOrNotOnDiskAreOutside() {
        WorkspaceEdit we = new WorkspaceEdit();
        we.setDocumentChanges(List.of(text("file:///work/wt-b/m/A.java", edit(0, 0, 0, "x")), text("file:///work/wt-a/../wt-b/B.java"),
                text("jdt://contents/rt.jar/java.lang/String.class"), text("file:///work/wt-a/m/Fine.java")));
        List<String> outside = new ArrayList<>();
        Edits.ops(we, ROOT, outside);
        assertEquals(List.of("/work/wt-b/m/A.java", "/work/wt-b/B.java", "jdt://contents/rt.jar/java.lang/String.class"), outside);
        assertTrue(Edits.ops(null, ROOT, outside).isEmpty());
    }

    @Test
    void symbolicLinksAreFollowedToWhereTheyReallyAre(@TempDir Path dir) throws Exception {
        // T002.07's review, R1: a file in wt-a that links into wt-b, or a folder that does, is outside wt-a.
        Path a = Files.createDirectories(dir.resolve("wt-a/m/src"));
        Path b = Files.createDirectories(dir.resolve("wt-b/m/src"));
        Files.writeString(a.resolve("A.java"), "class A {}\n");
        Files.writeString(b.resolve("B.java"), "class B {}\n");
        Files.createSymbolicLink(a.resolve("B.java"), b.resolve("B.java"));
        Files.createSymbolicLink(a.resolve("linked"), b);
        Files.createSymbolicLink(a.resolve("Dangling.java"), dir.resolve("nowhere/D.java"));
        Path alias = Files.createSymbolicLink(dir.resolve("alias"), dir.resolve("wt-a"));
        Path root = dir.resolve("wt-a");
        WorkspaceEdit we = new WorkspaceEdit();
        we.setDocumentChanges(List.of(text(a.resolve("A.java").toUri().toString(), edit(0, 6, 7, "C")),
                text(a.resolve("B.java").toUri().toString(), edit(0, 6, 7, "C")),
                Either.forRight(new CreateFile(a.resolve("linked/sub/New.java").toUri().toString())),
                Either.forRight(new RenameFile(a.resolve("A.java").toUri().toString(), a.resolve("linked/A.java").toUri().toString())),
                Either.forRight(new DeleteFile(a.resolve("Dangling.java").toUri().toString())),
                Either.forRight(new CreateFile(a.resolve("new/pkg/Fine.java").toUri().toString()))));
        List<String> outside = new ArrayList<>();
        Edits.ops(we, root, outside);
        assertEquals(List.of(a.resolve("B.java").toString(), a.resolve("linked/sub/New.java").toString(), a.resolve("linked/A.java").toString(),
                a.resolve("Dangling.java").toString()), outside);
        // A worktree registered through a link is compared by where it really is too.
        List<String> none = new ArrayList<>();
        Edits.ops(aliased(a, alias), alias, none);
        assertEquals(List.of(), none);
    }

    private static WorkspaceEdit aliased(Path a, Path alias) {
        WorkspaceEdit we = new WorkspaceEdit();
        we.setDocumentChanges(List.of(text(a.resolve("A.java").toUri().toString(), edit(0, 6, 7, "C")),
                Either.forRight(new CreateFile(alias.resolve("m/src/new/Fine.java").toUri().toString()))));
        return we;
    }

    @Test
    void utf16AndUtf32RoundTripInEitherByteOrderWithOrWithoutTheirMark() throws Exception {
        // T002.07's review, R5: a file is decoded and encoded again in its own charset and byte order, as read.
        String text = "package a;\n// caf\u00e9 \u2713 \ud834\udd1e\nclass A {}\n";
        Path file = Path.of("/work/wt-a/m/A.java");
        byte[] feff = {(byte) 0xFE, (byte) 0xFF};
        byte[] fffe = {(byte) 0xFF, (byte) 0xFE};
        byte[] utf8Mark = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] utf32Be = {0, 0, (byte) 0xFE, (byte) 0xFF};
        byte[] utf32Le = {(byte) 0xFF, (byte) 0xFE, 0, 0};
        Charset be = StandardCharsets.UTF_16BE;
        Charset le = StandardCharsets.UTF_16LE;
        Charset u32be = Charset.forName("UTF-32BE");
        Charset u32le = Charset.forName("UTF-32LE");
        // {the file's charset in Eclipse, its mark, the byte order it's in, the text Eclipse reads (an explicit
        // byte order keeps U+FEFF as a character, as Java's decoder and so JDT do)}
        Object[][] cases = {
            {StandardCharsets.UTF_16, feff, be, text}, {StandardCharsets.UTF_16, fffe, le, text}, {StandardCharsets.UTF_16, new byte[0], be, text},
            {be, new byte[0], be, text}, {le, new byte[0], le, text}, {be, feff, be, "\ufeff" + text}, {le, fffe, le, "\ufeff" + text},
            {Charset.forName("UTF-32"), utf32Be, u32be, text}, {Charset.forName("UTF-32"), utf32Le, u32le, text},
            {StandardCharsets.UTF_8, utf8Mark, StandardCharsets.UTF_8, text}, {StandardCharsets.UTF_8, new byte[0], StandardCharsets.UTF_8, text},
            {StandardCharsets.ISO_8859_1, new byte[0], StandardCharsets.ISO_8859_1, "package a;\n// caf\u00e9\nclass A {}\n"},
        };
        for (Object[] c : cases) {
            Charset charset = (Charset) c[0];
            byte[] mark = (byte[]) c[1];
            Charset order = (Charset) c[2];
            String read = (String) c[3];
            String body = read.startsWith("\ufeff") ? read.substring(1) : read;
            byte[] bytes = concat(mark, body.getBytes(order));
            String what = charset + " with " + mark.length + " bytes of mark, in " + order;
            Edits.Coding coding = Edits.coding(bytes, charset);
            assertEquals(read, Edits.decode(bytes, coding, file), what);
            assertArrayEquals(bytes, Edits.encode(read, coding), what + ": the same bytes back");
            String after = read.replace("class A", "class Bee");
            String afterBody = after.startsWith("\ufeff") ? after.substring(1) : after;
            assertArrayEquals(concat(mark, afterBody.getBytes(order)), Edits.encode(after, coding), what + ": an edit keeps the mark and the order");
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] all = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
    }

    @Test
    void byteOrderMarks() {
        assertEquals(3, Edits.coding(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'a'}, StandardCharsets.UTF_8).bom().length);
        assertEquals(0, Edits.coding(new byte[] {'a'}, StandardCharsets.UTF_8).bom().length);
        assertEquals(0, Edits.coding(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, StandardCharsets.ISO_8859_1).bom().length);
    }
}
