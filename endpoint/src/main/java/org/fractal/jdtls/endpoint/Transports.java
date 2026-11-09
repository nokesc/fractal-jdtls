// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JDT LS's other LSP transports must be off, so the endpoint's pipes are its only connection (design §11 "Inside JDT
 * LS"; T002.03's review, R1). The pinned JDT LS's {@code ConnectionStreamFactory} picks a client socket when
 * {@code CLIENT_PORT} is set (as an environment variable, else a system property, with {@code CLIENT_HOST}), then a
 * named pipe when an argument contains {@code --pipe=}, and stdio, the endpoint's, only otherwise. The launcher
 * refuses and removes them in HTTP mode; this check, before JDT LS's application starts, makes any that remain a
 * loud startup failure.
 */
final class Transports {

    static final List<String> VARIABLES = List.of("CLIENT_PORT", "CLIENT_HOST");
    static final String PIPE = "--pipe=";

    private Transports() {}

    static void check() throws StartupException {
        check(System.getenv(), System.getProperties());
    }

    static void check(Map<String, String> environment, java.util.Properties properties) throws StartupException {
        List<String> found = new ArrayList<>();
        for (String name : VARIABLES) {
            String variable = environment.get(name);
            if (variable != null && !variable.isEmpty()) {
                found.add("the environment variable " + name);
            }
            String property = properties.getProperty(name);
            if (property != null && !property.isEmpty()) {
                found.add("the system property " + name);
            }
        }
        if (properties.getProperty("eclipse.commands", "").contains(PIPE)) {
            found.add("an argument with " + PIPE);
        }
        if (!found.isEmpty()) {
            throw new StartupException("JDT LS would use another LSP transport than the endpoint's pipes: "
                    + String.join(", ", found) + " is set. HTTP mode allows none; the launcher removes or refuses them.");
        }
    }
}
