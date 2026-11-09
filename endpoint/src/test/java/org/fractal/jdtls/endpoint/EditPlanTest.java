// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A {@code WorkspaceEdit}'s effect on the files, in memory (design §11 "Refactorings and edits"). */
class EditPlanTest {

    static final Path A = Path.of("/w/m/src/a/A.java");
    static final Path B = Path.of("/w/m/src/a/B.java");
    static final Path C = Path.of("/w/m/src/c/A.java");

    static EditPlan.Source files(Map<Path, String> texts, Set<Path> folders) {
        return new EditPlan.Source() {
            public String text(Path file) {
                return texts.get(file);
            }

            public boolean isDirectory(Path file) {
                return folders.contains(file);
            }
        };
    }

    static EditPlan.Edit edit(int sl, int sc, int el, int ec, String text) {
        return new EditPlan.Edit(sl, sc, el, ec, text);
    }

    @Test
    void editsApplyToTheTextBeforeThemAndKeepEverythingElse() throws Exception {
        String text = "class A {\r\n    int a;\r\n}\r\n";
        assertEquals("class B {\r\n    long a;\r\n}\r\n",
                EditPlan.edit(text, List.of(edit(1, 4, 1, 7, "long"), edit(0, 6, 0, 7, "B")), A), "out of order, CRLF kept");
        assertEquals("xyclass A {\r\n    int a;\r\n}\r\n", EditPlan.edit(text, List.of(edit(0, 0, 0, 0, "x"), edit(0, 0, 0, 0, "y")), A),
                "two inserts at one position keep their order");
        assertEquals(text + "// end\r\n", EditPlan.edit(text, List.of(edit(3, 0, 3, 0, "// end\r\n")), A), "the text's end");
        assertEquals("a\rB\rc", EditPlan.edit("a\rb\rc", List.of(edit(1, 0, 1, 1, "B")), A), "a lone CR ends a line, as JDT counts lines");
        assertEquals("aé€😀Z", EditPlan.edit("aé€😀z", List.of(edit(0, 5, 0, 6, "Z")), A), "characters are UTF-16 units");
    }

    @Test
    void invalidEditsAreRefusedNotGuessed() {
        String text = "ab\ncd\n";
        assertThrows(EditPlan.Invalid.class, () -> EditPlan.edit(text, List.of(edit(0, 0, 0, 2, "x"), edit(0, 1, 1, 0, "y")), A), "overlap");
        assertThrows(EditPlan.Invalid.class, () -> EditPlan.edit(text, List.of(edit(0, 3, 0, 3, "x")), A), "past the line, not clamped");
        assertThrows(EditPlan.Invalid.class, () -> EditPlan.edit(text, List.of(edit(5, 0, 5, 0, "x")), A), "past the text");
        assertThrows(EditPlan.Invalid.class, () -> EditPlan.edit(text, List.of(edit(1, 0, 0, 0, "x")), A), "ends before it starts");
    }

    @Test
    void aTypeRenameEditsThenRenamesItsFile() throws Exception {
        Map<Path, String> texts = new HashMap<>(Map.of(A, "class A {}\n", B, "class B { A a; }\n"));
        List<EditPlan.Change> changes = EditPlan.plan(List.of(
                new EditPlan.TextEdits(A, List.of(edit(0, 6, 0, 7, "C"))),
                new EditPlan.TextEdits(B, List.of(edit(0, 10, 0, 11, "C"))),
                new EditPlan.Rename(A, A.resolveSibling("C.java"), false, false)), files(texts, Set.of()));
        assertEquals(2, changes.size());
        assertEquals(new EditPlan.Change(B, null, "modified", "class B { A a; }\n", "class B { C a; }\n").toString(), changes.get(0).toString());
        EditPlan.Change renamed = changes.get(1);
        assertEquals(List.of(A.resolveSibling("C.java"), A, "renamed", "class A {}\n", "class C {}\n"),
                List.of(renamed.path(), renamed.from(), renamed.kind(), renamed.before(), renamed.after()));
    }

    @Test
    void aPackageRenameMovesEachFileThroughJdtLssTemporaryFile() throws Exception {
        // ChangeUtil's package rename: the package statements, a .temp file to make the folder, each file moved, the
        // .temp deleted. The .temp is no change at all.
        Path temp = Path.of("/w/m/src/c/.temp");
        List<EditPlan.Change> changes = EditPlan.plan(List.of(
                new EditPlan.TextEdits(A, List.of(edit(0, 8, 0, 9, "c"))),
                new EditPlan.Create(temp, false, true),
                new EditPlan.Rename(A, C, false, false),
                new EditPlan.Delete(temp, false, true)), files(Map.of(A, "package a;\nclass A {}\n"), Set.of()));
        assertEquals(1, changes.size());
        assertEquals(List.of(C, A, "renamed", "package c;\nclass A {}\n"),
                List.of(changes.get(0).path(), changes.get(0).from(), changes.get(0).kind(), changes.get(0).after()));
    }

    @Test
    void aTemporaryFileThatAlreadyExistsIsNeverDeleted() {
        // T002.07's review, R2: the package rename's create is skipped (ignoreIfExists) for a .temp the user has, and
        // its delete would then remove their file. The edit is refused before anything is written.
        Path temp = Path.of("/w/m/src/c/.temp");
        Map<Path, String> texts = Map.of(A, "package a;\nclass A {}\n", temp, "my notes\n");
        Problem p = assertThrows(Problem.class, () -> EditPlan.plan(List.of(
                new EditPlan.TextEdits(A, List.of(edit(0, 8, 0, 9, "c"))),
                new EditPlan.Create(temp, false, true),
                new EditPlan.Rename(A, C, false, false),
                new EditPlan.Delete(temp, false, true)), files(texts, Set.of())));
        assertEquals(Code.REFACTORING_REFUSED, p.code);
        assertTrue(p.detail().contains(".temp") && p.detail().contains("Nothing was written."), p.detail());
        // The same for a text edit of it: the edit would treat the user's file as one it made.
        assertThrows(Problem.class, () -> EditPlan.plan(List.of(new EditPlan.Create(temp, false, true),
                new EditPlan.TextEdits(temp, List.of(edit(0, 0, 0, 0, "x")))), files(texts, Set.of())));
    }

    @Test
    void createdDeletedAndUnchangedFiles() throws Exception {
        Map<Path, String> texts = Map.of(A, "a\n", B, "b\n");
        List<EditPlan.Change> changes = EditPlan.plan(List.of(
                new EditPlan.Create(C, false, false),
                new EditPlan.TextEdits(C, List.of(edit(0, 0, 0, 0, "class A {}\n"))),
                new EditPlan.Delete(B, false, false),
                new EditPlan.TextEdits(A, List.of(edit(0, 0, 0, 1, "a")))), files(texts, Set.of()));
        assertEquals(List.of("deleted " + B, "created " + C), changes.stream().map(c -> c.kind() + " " + c.path()).toList(),
                "A's edit left it as it was");
        assertEquals("class A {}\n", changes.get(1).after());
    }

    @Test
    void overwritesFoldersAndMissingFilesAreInvalid() {
        Map<Path, String> texts = Map.of(A, "a\n", B, "b\n");
        Path folder = Path.of("/w/m/src/a");
        assertThrows(EditPlan.Invalid.class, () -> EditPlan.plan(List.of(new EditPlan.Rename(A, B, false, false)), files(texts, Set.of())));
        assertThrows(EditPlan.Invalid.class, () -> EditPlan.plan(List.of(new EditPlan.Rename(A, B, true, false)), files(texts, Set.of())),
                "never overwrites");
        assertThrows(EditPlan.Invalid.class, () -> EditPlan.plan(List.of(new EditPlan.Create(A, true, false)), files(texts, Set.of())));
        assertThrows(EditPlan.Invalid.class, () -> EditPlan.plan(List.of(new EditPlan.TextEdits(C, List.of())), files(texts, Set.of())));
        assertThrows(EditPlan.Invalid.class,
                () -> EditPlan.plan(List.of(new EditPlan.Rename(folder, Path.of("/w/m/src/b"), false, false)), files(texts, Set.of(folder))));
        assertTrue(assertThrows(EditPlan.Invalid.class, () -> EditPlan.plan(List.of(new EditPlan.Delete(C, false, false)), files(texts, Set.of())))
                .getMessage().contains("doesn't exist"));
    }
}
