// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Ends the process when the endpoint can't serve: JDT LS must never keep running without its only client (design
 * §11). The reason goes to the process's stderr (file descriptor 2, whatever {@code System.err} has become) and to
 * the endpoint's log, and the JVM halts with status 1. It halts rather than exits: shutdown hooks could wait on the
 * JDT LS that just failed.
 *
 * <p>Uses only the JDK, so it works even when the endpoint's other classes can't be loaded.
 */
final class Fatal {

    private static final PrintStream STDERR = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);

    private Fatal() {}

    static void exit(String what, Throwable cause) {
        String message;
        if (cause instanceof StartupException) {
            message = what + ": " + cause.getMessage();
        } else if (cause != null) {
            message = what + ": unexpected " + cause + ". The endpoint log has the stack trace.";
        } else {
            message = what;
        }
        try {
            Log.error(message, cause == null ? new Exception("no cause") : cause);
        } catch (Throwable ignored) {
            // The log class itself may be what failed.
        }
        STDERR.println("fractal-jdtls endpoint: error: " + message);
        if (!(cause instanceof StartupException) && cause != null) {
            cause.printStackTrace(STDERR);
        }
        STDERR.flush();
        Runtime.getRuntime().halt(1);
    }
}
