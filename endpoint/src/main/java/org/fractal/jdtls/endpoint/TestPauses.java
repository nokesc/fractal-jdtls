// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A test-only mechanism: with {@code -Dfractal.jdtls.endpoint.testPauses=<folder>}, a refactoring stops at a named
 * point when a test has armed it, so the integration suite can change a file between the edit's computation and its
 * apply ({@code before-apply}: {@code files-changed}, or a refactoring {@code /v1/status} lists as running), make one
 * unwritable after the apply's baseline check ({@code before-write}: a write that fails), or kill JDT LS after the
 * apply's first write ({@code after-first-write}: an operation folder without {@code response.json}). The test
 * arms a point by creating {@code <folder>/<point>.armed}; the next refactoring to reach it deletes that file, creates
 * {@code <folder>/<point>}, and waits until the test deletes it, or the request's deadline. Without the property, which the launcher never passes, or with the point unarmed, it
 * does nothing.
 */
final class TestPauses {

    static final String PROPERTY = "fractal.jdtls.endpoint.testPauses";

    private TestPauses() {}

    static void pause(String point, long deadline) throws InterruptedException, IOException {
        String folder = System.getProperty(PROPERTY);
        if (folder == null || folder.isEmpty()) {
            return;
        }
        Path armed = Path.of(folder).resolve(point + ".armed");
        if (!Files.deleteIfExists(armed)) {
            return;
        }
        Path marker = Path.of(folder).resolve(point);
        Files.writeString(marker, Long.toString(ProcessHandle.current().pid()));
        Log.info("test pause: waiting at " + point + " until " + marker + " is deleted");
        while (Files.exists(marker) && System.nanoTime() - deadline < 0) {
            Thread.sleep(10);
        }
        Files.deleteIfExists(marker);
        Log.info("test pause: going on from " + point);
    }
}
