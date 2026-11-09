// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PortFileTest {

    @Test
    void itIsWrittenWholeWithMode0600AndNoTemporaryFileLeft(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("port.json");
        Files.writeString(file, "stale");
        PortFile.write(file, PortFile.contents(4711, 42, "1.63.0-1", "1.0.0"));
        assertEquals("{\"port\":4711,\"pid\":42,\"release\":\"1.63.0-1\",\"api\":\"1.0.0\"}", Files.readString(file));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        try (var listing = Files.list(dir)) {
            assertEquals(1, listing.count());
        }
    }

    @Test
    void aFolderThatDoesntExistIsAStartupFailure(@TempDir Path dir) {
        StartupException e = assertThrows(StartupException.class,
                () -> PortFile.write(dir.resolve("no/port.json"), PortFile.contents(1, 2, "r", "a")));
        assertTrue(e.getMessage().startsWith("can't write the port file "), e.getMessage());
    }

    @Test
    void itIsRemovedOnlyIfItStillNamesThisProcess(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("port.json");
        PortFile.write(file, PortFile.contents(4711, 42, "r", "a"));
        PortFile.remove(file, 43);
        assertTrue(Files.exists(file));
        PortFile.remove(file, 42);
        assertFalse(Files.exists(file));
        PortFile.remove(file, 42); // already gone: nothing to do
    }
}
