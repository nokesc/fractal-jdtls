// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import java.nio.file.Path;
import java.util.Optional;

/**
 * What the launcher tells the endpoint, as system properties (design §11 "Launching"). The launcher sets all of them
 * in HTTP mode, and refuses a client's own {@code -Dfractal.jdtls.http.*}.
 */
record Config(Path portFile, Optional<Path> settings, int tokenFd, Path log, Path state, String release) {

    static final String PREFIX = "fractal.jdtls.http.";
    static final String PORT_FILE = PREFIX + "portFile";
    static final String SETTINGS = PREFIX + "settings";
    static final String TOKEN_FD = PREFIX + "tokenFd";
    static final String LOG = PREFIX + "log";
    /** The registered worktrees, kept under {@code -data} for a restart (design §11 "Worktrees"). */
    static final String STATE = PREFIX + "state";
    static final String RELEASE = PREFIX + "release";

    static Config fromSystemProperties() throws StartupException {
        String fd = required(TOKEN_FD);
        int tokenFd;
        try {
            tokenFd = Integer.parseInt(fd);
        } catch (NumberFormatException e) {
            tokenFd = -1;
        }
        if (tokenFd < 0) {
            throw new StartupException("-D" + TOKEN_FD + "=" + fd + " isn't a file descriptor number. " + LAUNCHER);
        }
        String settings = System.getProperty(SETTINGS, "");
        return new Config(
                absolute(PORT_FILE, required(PORT_FILE)),
                settings.isEmpty() ? Optional.empty() : Optional.of(absolute(SETTINGS, settings)),
                tokenFd,
                absolute(LOG, required(LOG)),
                absolute(STATE, required(STATE)),
                required(RELEASE));
    }

    private static final String LAUNCHER = "Start JDT LS in HTTP mode with the install's bin/jdtls --http-port-file, which sets it.";

    private static String required(String property) throws StartupException {
        String value = System.getProperty(property, "");
        if (value.isEmpty()) {
            throw new StartupException("the system property " + property + " isn't set. " + LAUNCHER);
        }
        return value;
    }

    private static Path absolute(String property, String value) throws StartupException {
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            throw new StartupException("-D" + property + "=" + value + " isn't an absolute path. " + LAUNCHER);
        }
        return path;
    }
}
