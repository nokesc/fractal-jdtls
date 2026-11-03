// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The diagnostic log never stops silently (review R3). */
class DiagnosticLogTest {

    @TempDir
    Path tmp;

    @AfterEach
    void reset() {
        System.clearProperty(DiagnosticLog.PROPERTY);
    }

    @Test
    void aWriteFailureIsReportedOnce() {
        Path full = Path.of("/dev/full"); // Linux: every write fails with ENOSPC
        System.setProperty(DiagnosticLog.PROPERTY, full.toString());
        String err = Fakes.stderr(() -> {
            DiagnosticLog.event("one");
            DiagnosticLog.event("two");
            DiagnosticLog.event("three");
        });
        List<String> lines = err.lines().toList();
        assertEquals(1, lines.size(), err);
        assertTrue(lines.get(0).startsWith("fractal-jdtls agent: ERROR: the diagnostic log can't write /dev/full"
                + " (java.io.IOException: No space left on device); it has no events from here on."), err);
    }

    @Test
    void anOpenFailureIsReportedOnce() {
        Path missing = tmp.resolve("no-such-folder/diagnostic.jsonl");
        System.setProperty(DiagnosticLog.PROPERTY, missing.toString());
        String err = Fakes.stderr(() -> {
            DiagnosticLog.event("one");
            DiagnosticLog.event("two");
        });
        assertEquals(1, err.lines().count(), err);
        assertTrue(err.contains("the diagnostic log can't open " + missing), err);
    }

    @Test
    void eventsAreWrittenAsJsonLines() throws Exception {
        Path log = Fakes.diagnosticLog(tmp);
        DiagnosticLog.event("x", "s", "a\"b\\c\n", "n", 3, "l", List.of("p", "q"), "z", null);
        List<String> lines = Files.readAllLines(log);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).endsWith("\"event\":\"x\",\"s\":\"a\\\"b\\\\c\\n\",\"n\":3,\"l\":[\"p\",\"q\"],\"z\":null}"),
                lines.get(0));
    }
}
