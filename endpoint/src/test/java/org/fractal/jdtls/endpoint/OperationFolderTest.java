// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Operation folders (design §11 "Operation folders"): the ID, the folder and its files, the digest, and what skips them. */
class OperationFolderTest {

    @TempDir
    Path dir;

    static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static List<String> files(Path folder) throws Exception {
        try (Stream<Path> s = Files.list(folder)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void theIdIsTheTimeToTheMillisecondInUtcAndEightRandomHexDigits() {
        Instant at = Instant.parse("2026-11-08T23:55:01.123456Z");
        String id = OperationFolder.newId(at);
        assertTrue(id.matches("20261108T235501\\.123Z-[0-9a-f]{8}"), id);
        assertNotEquals(id, OperationFolder.newId(at), "a new random part each time");
    }

    @Test
    void theFolderHoldsItsFilesInOrderAndTheResponseIsRenamedIntoPlace() throws Exception {
        Path root = Files.createDirectories(dir.resolve("wt-a"));
        List<String> named = new ArrayList<>();
        OperationFolder f = OperationFolder.create(root, Instant.parse("2026-11-08T23:55:01.123Z"), named::add);
        assertEquals(List.of(f.id), named, "the ID is told before the folder exists");
        assertEquals("temp/fractal-jdtls/" + f.id, f.relative);
        Path folder = root.resolve(f.relative);
        JsonObject body = new JsonObject();
        body.addProperty("newName", "canonical");
        f.request(Instant.parse("2026-11-08T23:55:01.123456Z"), "refactor/rename", body);
        assertEquals(List.of("request.json"), files(folder));
        JsonObject request = Json.parseStrict(Files.readString(folder.resolve("request.json"))).getAsJsonObject();
        assertEquals(f.id, request.get("id").getAsString());
        assertEquals("2026-11-08T23:55:01.123Z", request.get("received").getAsString());
        assertEquals("refactor/rename", request.get("operation").getAsString());
        assertEquals(body, request.get("request"));
        f.diff("--- a/A.java\n+++ b/A.java\n");
        assertEquals("--- a/A.java\n+++ b/A.java\n", Files.readString(folder.resolve("diff.patch")));
        JsonObject answer = new JsonObject();
        answer.addProperty("applied", true);
        f.response(answer);
        assertEquals(List.of("diff.patch", "request.json", "response.json"), files(folder), "no temporary file left");
        assertEquals(answer, Json.parseStrict(Files.readString(folder.resolve("response.json"))));
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> f.diff("again"), "each file is written once");
    }

    @Test
    void aTempThatLinksOutOfTheWorktreeIsRefusedAndNothingIsMadeThere() throws Exception {
        Path root = Files.createDirectories(dir.resolve("wt-a"));
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Files.createSymbolicLink(root.resolve("temp"), outside);
        List<String> named = new ArrayList<>();
        Problem p = assertThrows(Problem.class, () -> OperationFolder.create(root, Instant.now(), named::add));
        assertEquals(Code.EDIT_OUTSIDE_WORKTREE, p.code);
        assertEquals(List.of(), files(outside), "nothing made outside");
        Files.delete(root.resolve("temp"));
        Files.createSymbolicLink(root.resolve("temp"), dir.resolve("nowhere"));
        assertEquals(Code.EDIT_OUTSIDE_WORKTREE, assertThrows(Problem.class, () -> OperationFolder.create(root, Instant.now(), named::add)).code,
                "a link that leads nowhere");
        assertFalse(Files.exists(dir.resolve("nowhere")));
    }

    /** T002.10's review, R2: a temp that links inside the worktree is refused too, since the folder must be exactly where the record skips it. */
    @Test
    void aTempThatLinksInsideTheWorktreeIsRefusedAndNothingIsMadeThere() throws Exception {
        Path root = Files.createDirectories(dir.resolve("wt-a"));
        Path scratch = Files.createDirectories(root.resolve("scratch"));
        Files.createSymbolicLink(root.resolve("temp"), scratch);
        Problem p = assertThrows(Problem.class, () -> OperationFolder.create(root, Instant.now(), id -> {}));
        assertEquals(Code.EDIT_OUTSIDE_WORKTREE, p.code);
        assertEquals(List.of(), files(scratch), "nothing made through the link");
        Files.delete(root.resolve("temp"));
        Files.createDirectories(root.resolve("temp"));
        Files.createSymbolicLink(root.resolve("temp/fractal-jdtls"), scratch);
        assertEquals(Code.EDIT_OUTSIDE_WORKTREE, assertThrows(Problem.class, () -> OperationFolder.create(root, Instant.now(), id -> {})).code,
                "temp/fractal-jdtls a link");
        assertEquals(List.of(), files(scratch));
    }

    /** T002.10's review, R1: only the worktree root's temp/fractal-jdtls is skipped, never a module's folder of that name. */
    @Test
    void theRecordSkipsOnlyTheWorktreeRootsTempFractalJdtls() throws Exception {
        Path root = Files.createDirectories(dir.resolve("wt-a"));
        Files.createDirectories(root.resolve("temp/fractal-jdtls/20261108T235501.123Z-3f9a2c41"));
        Files.writeString(root.resolve("temp/fractal-jdtls/20261108T235501.123Z-3f9a2c41/request.json"), "{}");
        Files.writeString(root.resolve("temp/notes.txt"), "an agent's");
        Files.createDirectories(root.resolve("services/temp/fractal-jdtls/src/main/java"));
        Files.writeString(root.resolve("services/temp/fractal-jdtls/pom.xml"), "<project/>");
        Files.writeString(root.resolve("services/temp/fractal-jdtls/src/main/java/A.java"), "class A {}");
        assertTrue(FileRecords.skipped(root.resolve("temp/fractal-jdtls"), root));
        assertFalse(FileRecords.skipped(root.resolve("services/temp/fractal-jdtls"), root), "a module folder of that name is recorded");
        List<String> recorded = List.of("services/temp/fractal-jdtls/pom.xml", "services/temp/fractal-jdtls/src/main/java/A.java", "temp/notes.txt");
        assertEquals(recorded, FileRecords.scan(root, List.of(root)).keySet().stream().map(p -> root.relativize(p).toString()).sorted().toList());
        assertEquals(recorded.subList(0, 2), FileRecords.scan(root, List.of(root.resolve("services/temp/fractal-jdtls"))).keySet().stream()
                .map(p -> root.relativize(p).toString()).sorted().toList(), "walked from the module's own folder too");
    }

    @Test
    void theWriteSetDigestIsOverTheExactWritesLengthFramed() {
        Path root = Path.of("/work/wt-a");
        Path a = root.resolve("src/A.java");
        Path c = root.resolve("src/C.java");
        String modified = Edits.writeSetDigest(List.of(new EditWriter.Write(a, null, b("x"), b("y"))), root);
        assertTrue(modified.matches("[0-9a-f]{64}"), modified);
        assertEquals(modified, Edits.writeSetDigest(List.of(new EditWriter.Write(a, null, b("before differs"), b("y"))), root),
                "the result bytes count, not the bytes before");
        assertNotEquals(modified, Edits.writeSetDigest(List.of(new EditWriter.Write(a, null, b("x"), b("﻿y"))), root), "a byte order mark");
        assertNotEquals(Edits.writeSetDigest(List.of(new EditWriter.Write(a, null, b("x"), new byte[0])), root),
                Edits.writeSetDigest(List.of(new EditWriter.Write(a, null, b("x"), null)), root), "a delete isn't an empty file");
        assertNotEquals(Edits.writeSetDigest(List.of(new EditWriter.Write(c, a, b("x"), b("x"))), root),
                Edits.writeSetDigest(List.of(new EditWriter.Write(c, null, null, b("x"))), root), "a rename isn't a create");
        assertNotEquals(Edits.writeSetDigest(List.of(new EditWriter.Write(a, null, b("x"), b("y")), new EditWriter.Write(c, null, b("x"), b("y"))), root),
                Edits.writeSetDigest(List.of(new EditWriter.Write(c, null, b("x"), b("y")), new EditWriter.Write(a, null, b("x"), b("y"))), root),
                "in order");
        assertNotEquals(Edits.writeSetDigest(List.of(new EditWriter.Write(root.resolve("ab"), null, b("x"), b("c"))), root),
                Edits.writeSetDigest(List.of(new EditWriter.Write(root.resolve("a"), null, b("x"), b("bc"))), root), "framed: no two sets run together");
        // The framing, computed independently: SHA-256 of (8-byte length, bytes) for "1", "modified", "src/A.java", "y".
        assertEquals("3a02c11c51e8be4cf45dce670620de5330aff607850a58d2328b7a12a312ed12", modified);
        assertEquals("338a3d986bbcbc43f2a3206a429aaa307429eeded38ff142415fe9538fa163fc", Edits.writeSetDigest(List.of(), root), "nothing to write: the count alone");
    }

    @Test
    void theInFlightEntryHasItsPathAndARefactoringsId() {
        InFlight inFlight = new InFlight();
        try (InFlight.Op read = inFlight.begin("/v1/references", "/work/wt-a", "harness-app");
                InFlight.Op edit = inFlight.begin("/v1/refactor/rename", "/work/wt-a", "harness-contract")) {
            edit.operation("20261108T235501.123Z-3f9a2c41");
            var list = inFlight.toJson();
            assertEquals("/v1/references", list.get(0).getAsJsonObject().get("path").getAsString());
            assertFalse(list.get(0).getAsJsonObject().has("operation"));
            assertEquals("20261108T235501.123Z-3f9a2c41", list.get(1).getAsJsonObject().get("operation").getAsString());
        }
        assertEquals(0, inFlight.toJson().size());
    }
}
