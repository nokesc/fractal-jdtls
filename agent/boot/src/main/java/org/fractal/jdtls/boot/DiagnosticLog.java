// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.boot;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The optional diagnostic log (design §6): one JSON object per line, appended to the file named
 * by the system property {@value #PROPERTY}. Off when the property isn't set, which is the
 * default; then {@link #event} does nothing.
 *
 * <p>Each line has {@code ts}, {@code thread} and {@code event}, then the event's own fields, in
 * the order given. The integration suite (design §8) asserts on these events:
 * <ul>
 *   <li>{@code agent-start};
 *   <li>{@code hook-in-place} and {@code hook-not-in-place}, and {@code transform-error};
 *   <li>{@code checkpoint}, with every hook's state, at the first return from
 *       {@code populateClasspath};
 *   <li>{@code naming}: the POM, the worktree, m2e's name and the result;
 *   <li>{@code lookup}, for every call at either resolution site: the site, the GAV, the
 *       requester (or null), the candidate POMs (empty when the GAV isn't in the workspace),
 *       m2e's choice and the final choice;
 *   <li>{@code prefix-collision}, and {@code hook-failed}.
 * </ul>
 *
 * <p>If the file can't be opened or written, that's reported once on stderr and in the JDT LS log,
 * and nothing more is written to it, so the log never stops silently partway through a run.
 */
public final class DiagnosticLog {

    /** The system property that names the log file, and so enables the log. */
    public static final String PROPERTY = "fractal.jdtls.diagnosticLog";

    private static Writer out;
    private static String openPath;
    /** A file that failed: reported once, then no more writes to it. */
    private static String failedPath;

    private DiagnosticLog() {}

    /** Whether the log is enabled, so callers can skip work that only feeds it. */
    public static boolean enabled() {
        return System.getProperty(PROPERTY) != null;
    }

    /** Appends one event with the given key/value pairs. Does nothing when the log is off. */
    public static void event(String event, Object... keyValues) {
        String path = System.getProperty(PROPERTY);
        if (path == null) {
            return;
        }
        StringBuilder line = new StringBuilder("{\"ts\":\"").append(Instant.now()).append("\",\"thread\":")
                .append(Thread.currentThread().threadId()).append(",\"event\":").append(json(event));
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            line.append(',').append(json(String.valueOf(keyValues[i]))).append(':').append(json(keyValues[i + 1]));
        }
        write(path, line.append('}').append('\n').toString());
    }

    private static synchronized void write(String path, String line) {
        if (!path.equals(openPath)) {
            // The first event opens the file. The property is read once per event, so a test
            // that points it at another file gets that file.
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    // nothing more to report
                }
                out = null;
            }
            openPath = path;
            if (path.equals(failedPath)) {
                return;
            }
            try {
                // Not a PrintWriter: it would swallow write errors, and the log would stop silently.
                out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(path, true), StandardCharsets.UTF_8));
            } catch (IOException e) {
                fail("can't open", path, e);
                return;
            }
        }
        if (out == null) {
            return;
        }
        try {
            out.write(line);
            out.flush();
        } catch (IOException e) {
            fail("can't write", path, e);
        }
    }

    /** Reports a failed diagnostic log once, loudly, and stops writing to it: later events would be missing. */
    private static void fail(String what, String path, IOException e) {
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // already reporting
            }
            out = null;
        }
        failedPath = path;
        Report.error("the diagnostic log " + what + " " + path + " (" + e + "); it has no events from here on.");
    }

    static String json(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Collection<?> c) {
            return c.stream().map(DiagnosticLog::json).collect(Collectors.joining(",", "[", "]"));
        }
        if (value instanceof Map<?, ?> m) {
            return m.entrySet().stream().map(e -> json(String.valueOf(e.getKey())) + ":" + json(e.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
        }
        if (value instanceof File f) {
            value = f.getPath();
        }
        StringBuilder b = new StringBuilder("\"");
        for (char ch : value.toString().toCharArray()) {
            switch (ch) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> b.append(ch < 0x20 ? String.format("\\u%04x", (int) ch) : String.valueOf(ch));
            }
        }
        return b.append('"').toString();
    }
}
