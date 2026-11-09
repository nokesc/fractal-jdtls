// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The JDK HTTP server's limits that HTTP mode fixes, so no caller, with or without the token, can hold the server's
 * threads (T002.03's review, R2). They're JVM system properties the JDK reads once, when its server classes load
 * (documented in the {@code jdk.httpserver} module), so the launcher passes them on the command line, and the
 * endpoint refuses to start unless each has exactly this value.
 *
 * <ul>
 *   <li>{@code sun.net.httpserver.maxReqTime}, in seconds (the module's documentation says milliseconds, but the
 *       JDK 21 to 25 code multiplies by 1,000): from the first byte of a request to the end of its body. A caller
 *       that stops sending its headers or its body is disconnected after it, and the thread reading it is freed.
 *   <li>{@code sun.net.httpserver.maxRspTime}, in seconds: from the end of the request to the end of the response,
 *       so it includes the operation itself. It's above the longest {@code timeoutMs} the contract allows.
 *   <li>{@code sun.net.httpserver.drainAmount}, 0: the body of a request that isn't read, such as a refused one,
 *       is never drained; its connection is closed once the answer is written.
 *   <li>{@code jdk.httpserver.maxConnections}: the most connections open at once, which bounds the threads that
 *       read them.
 * </ul>
 */
final class ServerLimits {

    static final long MAX_REQUEST_SECONDS = 10;
    static final Map<String, String> PROPERTIES;

    static {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("sun.net.httpserver.maxReqTime", Long.toString(MAX_REQUEST_SECONDS));
        p.put("sun.net.httpserver.maxRspTime", Long.toString(HttpApi.TIMEOUT_MAX_MS / 1000 + 300));
        p.put("sun.net.httpserver.drainAmount", "0");
        p.put("jdk.httpserver.maxConnections", "256");
        PROPERTIES = java.util.Collections.unmodifiableMap(p);
    }

    private ServerLimits() {}

    static void check() throws StartupException {
        StringBuilder wrong = new StringBuilder();
        for (Map.Entry<String, String> e : PROPERTIES.entrySet()) {
            String actual = System.getProperty(e.getKey());
            if (!e.getValue().equals(actual)) {
                wrong.append(wrong.length() == 0 ? "" : ", ").append("-D").append(e.getKey()).append(" is ")
                        .append(actual == null ? "not set" : actual).append(", not ").append(e.getValue());
            }
        }
        if (wrong.length() > 0) {
            throw new StartupException("the HTTP server's limits aren't HTTP mode's: " + wrong
                    + ". Start JDT LS in HTTP mode with the install's bin/jdtls --http-port-file, which sets them.");
        }
    }
}
