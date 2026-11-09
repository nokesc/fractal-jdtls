// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * The endpoint's log: one file, {@code <data>/.fractal-jdtls/<release>/endpoint.log}, named by the launcher. Never
 * stdout, which carries LSP to JDT LS, and never the token or an {@code Authorization} header.
 */
final class Log {

    private static Path file;

    private Log() {}

    static synchronized void open(Path path) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        file = path;
    }

    static synchronized void info(String message) {
        if (file == null) {
            return;
        }
        String line = Instant.now() + " [" + Thread.currentThread().getName() + "] " + message + "\n";
        try {
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Nowhere else to write it: stdout is the LSP stream, and failures that matter also go to stderr.
        }
    }

    static void error(String message, Throwable t) {
        info("ERROR " + message + "\n" + stackTrace(t));
    }

    static String stackTrace(Throwable t) {
        StringWriter s = new StringWriter();
        t.printStackTrace(new PrintWriter(s));
        return s.toString();
    }
}
