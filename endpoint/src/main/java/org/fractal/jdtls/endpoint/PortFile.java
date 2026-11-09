// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * The port file (design §11 "Listening"): {@code {"port", "pid", "release", "api"}}, written once the endpoint
 * listens. It's written to a temporary file beside it with mode 0600, synced, and renamed into place, so a reader
 * sees the whole file or none. On a clean shutdown it's removed, if it's still this process's.
 */
final class PortFile {

    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private PortFile() {}

    static JsonObject contents(int port, long pid, String release, String api) {
        JsonObject o = new JsonObject();
        o.addProperty("port", port);
        o.addProperty("pid", pid);
        o.addProperty("release", release);
        o.addProperty("api", api);
        return o;
    }

    static void write(Path file, JsonObject contents) throws StartupException {
        Path tmp = file.resolveSibling("." + file.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
        try {
            Files.deleteIfExists(tmp);
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
            Files.setPosixFilePermissions(tmp, OWNER_ONLY); // whatever the umask
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                channel.write(java.nio.ByteBuffer.wrap(Json.bytes(contents)));
                channel.force(true);
            }
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            deleteQuietly(tmp);
            throw new StartupException("can't rename " + tmp + " to the port file " + file + " atomically: " + e + ".");
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw new StartupException("can't write the port file " + file + ": " + e + ". Pass a --http-port-file in a writable folder.");
        }
    }

    /** Removes the port file if it still names this process. */
    static void remove(Path file, long pid) {
        try {
            JsonObject o = Json.parseStrict(Files.readString(file)).getAsJsonObject();
            if (o.get("pid").getAsLong() == pid) {
                Files.delete(file);
                Log.info("removed the port file " + file);
            }
        } catch (IOException | RuntimeException e) {
            Log.info("left the port file " + file + ": " + e);
        }
    }

    private static void deleteQuietly(Path tmp) {
        try {
            Files.deleteIfExists(tmp);
        } catch (IOException ignored) {
            // the startup failure is what matters
        }
    }
}
