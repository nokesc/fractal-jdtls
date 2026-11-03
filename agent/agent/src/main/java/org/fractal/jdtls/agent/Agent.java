// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.agent;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

/**
 * The agent's entry point (design §6). Always on: there are no modes and no off switch.
 *
 * <p>Byte Buddy isn't bundled. The jar's manifest names it with {@code Class-Path}, and the boot
 * helper with {@code Boot-Class-Path}; both resolve next to this jar, in the install's
 * {@code lib/}. If either is missing, {@code premain} fails with a message that says which file
 * and what to do, and the JVM doesn't start: JDT LS must never run without the agent on a
 * workspace that uses it (design §6, FINDINGS run 07).
 *
 * <p>This class names no Byte Buddy type, so it loads, and can report, without Byte Buddy.
 */
public final class Agent {

    private static final String BYTE_BUDDY = "net.bytebuddy.agent.builder.AgentBuilder";
    private static final String BOOT = "org.fractal.jdtls.boot.Boot";

    private Agent() {}

    public static void premain(String arguments, Instrumentation instrumentation) {
        requireClassPath();
        Installer.install(instrumentation, Hook.all());
    }

    /** Fails clearly unless Byte Buddy and the boot helper can be loaded. Used by test variants too. */
    public static void requireClassPath() {
        require(BYTE_BUDDY, "Class-Path", "Byte Buddy");
        require(BOOT, "Boot-Class-Path", "the boot helper");
    }

    private static void require(String className, String attribute, String what) {
        try {
            Class.forName(className, false, Agent.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            String message = "fractal-jdtls agent: ERROR: can't load " + what + " (" + className + "). " + where(attribute)
                    + " The install is incomplete: check it with bin/install.py --verify, or install the release again.";
            System.err.println(message);
            throw new IllegalStateException(message, e);
        }
    }

    /** Where the manifest says the missing jar should be. */
    private static String where(String attribute) {
        try {
            Path jar = Path.of(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            try (JarFile file = new JarFile(jar.toFile())) {
                Attributes main = file.getManifest().getMainAttributes();
                String entries = main.getValue(attribute);
                Path dir = jar.getParent();
                return "The agent jar's " + attribute + " names " + entries + ", expected next to it in " + dir
                        + (entries == null ? "" : "; " + missing(dir, entries)) + ".";
            }
        } catch (IOException | URISyntaxException | RuntimeException e) {
            return "Can't read the agent jar's manifest: " + e + ".";
        }
    }

    private static String missing(Path dir, String entries) {
        StringBuilder result = new StringBuilder();
        for (String entry : entries.trim().split("\\s+")) {
            if (!dir.resolve(entry).toFile().isFile()) {
                result.append(result.length() == 0 ? "missing: " : ", ").append(dir.resolve(entry));
            }
        }
        return result.length() == 0 ? "it is there but can't be loaded" : result.toString();
    }
}
