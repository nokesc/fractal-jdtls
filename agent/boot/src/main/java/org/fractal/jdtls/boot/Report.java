// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.boot;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Loud failures (design §6): each problem goes to stderr at once and to the JDT LS log, the
 * workspace's {@code .metadata/.log}.
 *
 * <p>Stderr is the process's own (file descriptor 2), not {@code System.err}: JDT LS replaces
 * {@code System.err} once it has started (in {@code LanguageServerApplication}), so a report made
 * after that through {@code System.err} would be lost.
 *
 * <p>The JDT LS log is Eclipse's platform log, reached by reflection through a bundle class
 * loader that can see {@code org.eclipse.core.runtime.Platform}. Problems found while a class is
 * being transformed are queued, not logged there and then, so the agent never loads Eclipse
 * classes from inside a class transformation. The queue is flushed by the next call into the
 * helper from advised code, at the checkpoint and at shutdown.
 */
public final class Report {

    /** The prefix of every line the agent writes to stderr. */
    public static final String PREFIX = "fractal-jdtls agent: ";
    /** The plug-in ID of the agent's entries in the JDT LS log. */
    public static final String PLUGIN_ID = "org.fractal.jdtls.agent";

    private static final String PLATFORM = "org.eclipse.core.runtime.Platform";
    private static final int ERROR = 4; // org.eclipse.core.runtime.IStatus.ERROR

    /** The process's stderr. Tests in this package replace it to capture reports. */
    static volatile PrintStream stderr =
            new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);

    private static final Queue<String> PENDING = new ConcurrentLinkedQueue<>();
    private static final CopyOnWriteArrayList<ClassLoader> LOADERS = new CopyOnWriteArrayList<>();
    private static volatile Object eclipseLog;
    private static volatile Method logMethod;
    private static volatile java.lang.reflect.Constructor<?> statusConstructor;

    private Report() {}

    /**
     * Captures the process's original stderr. Called from {@code premain}, before JDT LS replaces
     * {@code System.err} (design §6); the stream is file descriptor 2, so it's the same either way.
     */
    public static void captureStderr() {
        stderr.flush();
    }

    /** Reports a problem: stderr now, the JDT LS log as soon as it can be reached. */
    public static void error(String message) {
        PrintStream out = stderr;
        out.println(PREFIX + "ERROR: " + message);
        out.flush();
        PENDING.add(message);
    }

    /**
     * Offers the class loader of a target class that has just loaded. It is only remembered here:
     * {@link #flush} tries it later, outside the class transformation.
     */
    public static void offerLoader(ClassLoader loader) {
        if (loader != null && eclipseLog == null) {
            LOADERS.addIfAbsent(loader);
        }
    }

    /** Writes queued problems to the JDT LS log, if it can be reached yet. Cheap when none are queued. */
    public static void flush() {
        if (PENDING.isEmpty() || !reachEclipseLog()) {
            return;
        }
        for (String message; (message = PENDING.poll()) != null;) {
            try {
                logMethod.invoke(eclipseLog, statusConstructor.newInstance(ERROR, PLUGIN_ID, message));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                stderr.println(PREFIX + "ERROR: can't write to the JDT LS log (" + e + "): " + message);
            }
        }
    }

    /** Whether problems are still waiting for the JDT LS log. For tests. */
    static boolean pending() {
        return !PENDING.isEmpty();
    }

    private static synchronized boolean reachEclipseLog() {
        if (eclipseLog != null) {
            return true;
        }
        for (ClassLoader loader : LOADERS) {
            try {
                Class<?> platform = Class.forName(PLATFORM, true, loader);
                Object log = platform.getMethod("getLog", Class.class).invoke(null, platform);
                Class<?> status = Class.forName("org.eclipse.core.runtime.Status", true, loader);
                Class<?> iStatus = Class.forName("org.eclipse.core.runtime.IStatus", true, loader);
                Class<?> iLog = Class.forName("org.eclipse.core.runtime.ILog", true, loader);
                statusConstructor = status.getConstructor(int.class, String.class, String.class);
                logMethod = iLog.getMethod("log", iStatus);
                eclipseLog = log;
                LOADERS.clear();
                return true;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                // This loader can't see the platform log (a Maven runtime class, say); try the next.
            }
        }
        return false;
    }
}
