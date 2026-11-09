// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;

/** What the lifecycle routes need from the running endpoint: an interface, so tests run them without JDT LS. */
interface Lifecycle {

    String release();

    String jdtlsVersion();

    long pid();

    /** Milliseconds since the process started. */
    long uptimeMs();

    /** Completes when JDT LS is ready: {@code initialize} answered and checked, and {@code ServiceReady}. */
    CompletableFuture<Void> ready();

    /** Shuts JDT LS down through LSP {@code shutdown} and {@code exit}; called after the response is sent. */
    void shutdown();

    /** Sets the status's worktrees, import in progress, dropped worktrees and operations in flight; none by default. */
    default void status(JsonObject status) {}
}
