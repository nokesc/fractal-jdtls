// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * A refactoring's record (design §11 "Operation folders"): {@code <worktree>/temp/fractal-jdtls/<id>/}, holding,
 * in this order, {@code request.json}, {@code diff.patch} once the edit is computed, and {@code response.json}, the
 * answer, last. The files are the interface; nothing reads them back. Plain Java, with no Eclipse class.
 */
final class OperationFolder {

    static final String TEMP = "temp";
    static final String NAME = "fractal-jdtls";
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int CLASHES_MAX = 16;
    private static final com.google.gson.Gson PRETTY = new GsonBuilder().disableHtmlEscaping().serializeNulls().setPrettyPrinting().create();

    /** The ID, {@code <UTC time to the millisecond>-<8 random hex digits>}. */
    final String id;
    /** The folder, as the worktree names it: {@code temp/fractal-jdtls/<id>}. */
    final String relative;
    private final Path folder;

    private OperationFolder(String id, Path root, Path folder) {
        this.id = id;
        this.relative = root.relativize(folder).toString();
        this.folder = folder;
    }

    /** A new ID for a request received at {@code received}. */
    static String newId(Instant received) {
        byte[] random = new byte[4];
        RANDOM.nextBytes(random);
        return TIME.format(received) + "-" + HexFormat.of().formatHex(random);
    }

    /**
     * Makes the folder, exclusively, with the ID {@code id} gives (a new random part on a clash), after {@code named}
     * has been told it. Its real path must be exactly {@code <the worktree's real root>/temp/fractal-jdtls/<id>}, else
     * {@code edit-outside-worktree}, and nothing is made or written there: a {@code temp} or {@code temp/fractal-jdtls}
     * that's a symbolic link, to anywhere, is refused, so the endpoint never writes outside the worktree and its records
     * are always where the file record skips them (T002.10's review, R2).
     */
    static OperationFolder create(Path root, Instant received, java.util.function.Consumer<String> named) throws Problem, IOException {
        Path parent = root.resolve(TEMP).resolve(NAME);
        for (int clash = 0;; clash++) {
            String id = newId(received);
            named.accept(id); // before the folder exists: a running operation never looks stopped
            Path folder = parent.resolve(id);
            refuseElsewhere(folder, root, id);
            Files.createDirectories(parent);
            refuseElsewhere(folder, root, id); // again, now that temp/ is made: a link raced in is never written through
            try {
                Files.createDirectory(folder);
            } catch (FileAlreadyExistsException e) {
                if (clash < CLASHES_MAX) {
                    continue;
                }
                throw e;
            }
            return new OperationFolder(id, root, folder);
        }
    }

    private static void refuseElsewhere(Path folder, Path root, String id) throws Problem, IOException {
        Path expected = root.toRealPath().resolve(TEMP).resolve(NAME).resolve(id);
        Path real = Edits.real(folder);
        if (!expected.equals(real)) {
            throw Problem.of(Code.EDIT_OUTSIDE_WORKTREE)
                    .detail("The operation folder " + root.relativize(folder) + " would be "
                            + (real == null ? "nowhere (a symbolic link that leads nowhere)" : real) + ", not " + expected
                            + ", through a symbolic link. Nothing was written.")
                    .hint("Make " + TEMP + "/ and " + TEMP + "/" + NAME + "/ in the worktree folders, not symbolic links, then send the request again.")
                    .with("files", files(root.relativize(folder).toString()));
        }
    }

    private static com.google.gson.JsonArray files(String file) {
        com.google.gson.JsonArray a = new com.google.gson.JsonArray();
        a.add(file);
        return a;
    }

    /** {@code request.json}: the ID, the time received, the operation, and the request body (the token is a header, never in it). */
    void request(Instant received, String operation, JsonObject body) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("received", DateTimeFormatter.ISO_INSTANT.format(received.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)));
        o.addProperty("operation", operation);
        o.add("request", body);
        write("request.json", json(o), false);
    }

    /** {@code diff.patch}: the full unified diff, before any source file is written. */
    void diff(String diff) throws IOException {
        write("diff.patch", diff.getBytes(StandardCharsets.UTF_8), false);
    }

    /** {@code response.json}, last: the answer as the caller gets it, success or problem, to a temporary name and renamed. */
    void response(JsonElement answer) throws IOException {
        write("response.json", json(answer), true);
    }

    private static byte[] json(JsonElement e) {
        return (PRETTY.toJson(e) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private void write(String name, byte[] bytes, boolean byRename) throws IOException {
        Path target = folder.resolve(name);
        if (!byRename) {
            Files.write(target, bytes, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW);
            return;
        }
        Path temporary = folder.resolve("." + name + ".tmp");
        Files.write(temporary, bytes, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW);
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
    }
}
