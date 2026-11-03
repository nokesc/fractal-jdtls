// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.agent;

import static net.bytebuddy.matcher.ElementMatchers.named;

import java.lang.instrument.Instrumentation;
import java.util.List;

/**
 * Test only: the product agent with one hook's matcher naming a method that doesn't exist, to
 * show that a broken hook is reported on stderr and in the JDT LS log (design §6). The product
 * has no switch for this.
 *
 * <p>Built by {@code mise run build} as {@code agent/agent/target/fractal-jdtls-agent-broken-hook.jar},
 * which is never in the release zip. Put it in an install's {@code lib/} (or a copy of it) and use
 * it instead of the product jar: {@code -javaagent:<lib>/fractal-jdtls-agent-broken-hook.jar[=<hook>]}.
 * The argument names the hook to break; the default is {@value #DEFAULT}.
 */
public final class BrokenHookAgent {

    static final String DEFAULT = "getProjectFacade";

    private BrokenHookAgent() {}

    public static void premain(String arguments, Instrumentation instrumentation) {
        Agent.requireClassPath();
        Installer.install(instrumentation, broken(arguments == null || arguments.isBlank() ? DEFAULT : arguments));
    }

    /** The product's hooks, with the named one's matcher naming {@code <id>ThatDoesNotExist}. */
    static List<Hook> broken(String id) {
        if (Hook.all().stream().noneMatch(h -> h.id().equals(id))) {
            throw new IllegalArgumentException("no hook named " + id);
        }
        return Hook.all().stream()
                .map(h -> h.id().equals(id) ? h.withMatcher(named(h.id() + "ThatDoesNotExist")) : h)
                .toList();
    }
}
