// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class TransportsTest {

    static Properties properties(String... pairs) {
        Properties p = new Properties();
        for (int i = 0; i < pairs.length; i += 2) {
            p.setProperty(pairs[i], pairs[i + 1]);
        }
        return p;
    }

    @Test
    void noOtherTransportPasses() {
        assertDoesNotThrow(() -> Transports.check(Map.of("CLIENT_PORT", ""), properties("eclipse.commands", "-data\n/ws\n")));
    }

    @Test
    void aClientSocketOrANamedPipeIsAStartupFailure() {
        StartupException e = assertThrows(StartupException.class, () -> Transports.check(Map.of("CLIENT_PORT", "5036"),
                properties("CLIENT_HOST", "localhost", "eclipse.commands", "-data\n/ws\n--pipe=/tmp/x\n")));
        assertEquals("JDT LS would use another LSP transport than the endpoint's pipes: the environment variable CLIENT_PORT, "
                + "the system property CLIENT_HOST, an argument with --pipe= is set. HTTP mode allows none; the launcher removes or"
                + " refuses them.", e.getMessage());
    }

    @Test
    void theServerLimitsAreHttpModesInTheseTests() {
        assertDoesNotThrow(ServerLimits::check);
        assertEquals("3900", ServerLimits.PROPERTIES.get("sun.net.httpserver.maxRspTime"));
    }
}
