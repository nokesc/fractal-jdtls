// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The plain parts of the fixed point and of problems (design §11 "Keeping in step with the disk", "Builds and
 * problems"): the endpoint's record of a worktree's files, marker positions, and the request's fields.
 */
class SyncPartsTest {

    @TempDir
    Path dir;

    private Path write(String name, String text) throws Exception {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
        return p;
    }

    @Test
    void theRecordFindsAChangeThatKeptItsModificationTime() throws Exception {
        Path pom = write("pom.xml", "<project/>");
        Path java = write("src/A.java", "class A {}");
        write(".git/index", "git");
        write("target/classes/A.class", "class");
        write("sub/target/kept.txt", "no pom beside it, so not build output");
        Map<Path, FileRecords.State> before = FileRecords.scan(dir, List.of(dir, dir.resolve("src")));
        assertEquals(Set.of(pom, java, dir.resolve("sub/target/kept.txt")), before.keySet(), "no .git, no build output, each file once");

        FileTime mtime = Files.getLastModifiedTime(java);
        Thread.sleep(20); // so the status-change time moves on
        Files.writeString(java, "class A { int x; }"); // as cp -p does: new bytes, then the old mtime
        Files.setLastModifiedTime(java, mtime);
        Map<Path, FileRecords.State> now = FileRecords.scan(dir, List.of(dir));
        assertEquals(mtime.to(TimeUnit.NANOSECONDS), now.get(java).mtimeNs());
        FileRecords.Diff diff = FileRecords.diff(before, now);
        assertEquals(Set.of(java), diff.changed());
        assertEquals(Set.of(java), diff.keptMtime());
        assertTrue(diff.onlyKeptMtime());
        assertEquals(Set.of(java), diff.candidates());

        Path kept = dir.resolve("sub/target/kept.txt");
        Files.delete(kept);
        Path added = write("src/B.java", "class B {}");
        FileRecords.Diff more = FileRecords.diff(now, FileRecords.scan(dir, List.of(dir)));
        assertEquals(Set.of(added), more.added());
        assertEquals(Set.of(kept), more.removed());
        assertFalse(more.onlyKeptMtime());
        assertTrue(FileRecords.diff(now, now).isEmpty());
    }

    @Test
    void theOutermostFoldersAreWalkedOnce() {
        assertEquals(List.of(Path.of("/w/a"), Path.of("/w/b")),
                FileRecords.outermost(List.of(Path.of("/w/a/x"), Path.of("/w/b"), Path.of("/w/a"), Path.of("/w/a/x/y"))));
    }

    @Test
    void positionsAreOneBasedAndCountCharacters() {
        String text = "package p;\r\n\nimport q.WorkType;\r    public WorkType type() {\n";
        Positions p = new Positions(text);
        assertEquals(5, p.lines());
        int import_ = text.indexOf("q.WorkType");
        assertEquals(3, p.lineOf(import_));
        assertEquals(8, p.columnOf(import_));
        assertEquals("import q.WorkType;", p.line(3));
        int type = text.indexOf("WorkType type");
        assertEquals(4, p.lineOf(type));
        assertEquals(12, p.columnOf(type));
        assertEquals(20, p.columnOf(type + "WorkType".length()), "an end column is just past the last character");
        assertEquals("    public WorkType type() {", p.line(4));
        assertEquals("", p.line(2));
        assertEquals("", p.line(5));
        assertNull(p.line(6));
        assertEquals(1, p.lineOf(0));
        assertEquals(5, p.lineOf(text.length()));
    }

    @Test
    void changedPathsAreFilesInsideTheWorktree() {
        assertNull(ProblemOps.pathSyntax("harness-app/src/main/java/App.java"));
        assertNull(ProblemOps.pathSyntax("pom.xml"));
        for (String bad : List.of("/abs/App.java", "../wt-b/App.java", "a/./b", "a//b", "a/", "a\\b", ".")) {
            assertTrue(ProblemOps.pathSyntax(bad) != null, bad);
        }
    }

    @Test
    void listFieldsAreCheckedItemByItem() {
        JsonObject body = Json.parseStrict("{\"severities\": [\"error\", \"fatal\", \"error\"], \"changedPaths\": [\"\", 3],"
                + " \"scope\": \"everything\"}").getAsJsonObject();
        Fields f = new Fields(body, "POST /v1/problems");
        f.strings("severities", 3, ProblemOps.SEVERITIES);
        f.strings("changedPaths", 10, null);
        f.oneOf("scope", List.of("module", "worktree"), "module");
        try {
            f.done();
            throw new AssertionError("no problem");
        } catch (Problem p) {
            JsonObject o = p.toJson(DescriptionTest.description(), "urn:x");
            String errors = o.get("errors").toString();
            for (String pointer : List.of("/severities/1", "/severities/2", "/changedPaths/0", "/changedPaths/1", "/scope")) {
                assertTrue(errors.contains("\"" + pointer + "\""), pointer + " in " + errors);
            }
        }
        Fields ok = new Fields(Json.parseStrict("{\"severities\": [\"warning\", \"error\"]}").getAsJsonObject(), "POST /v1/problems");
        assertEquals(List.of("warning", "error"), ok.strings("severities", 3, ProblemOps.SEVERITIES));
        assertNull(ok.strings("changedPaths", 10, null));
        assertEquals("module", ok.oneOf("scope", List.of("module", "worktree"), "module"));
        Fields empty = new Fields(Json.parseStrict("{\"severities\": []}").getAsJsonObject(), "POST /v1/problems");
        assertNull(empty.strings("severities", 3, ProblemOps.SEVERITIES));
    }
}
