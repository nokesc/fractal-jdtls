// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.agent;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;

import org.fractal.jdtls.boot.DiagnosticLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The hook-in-place rule (design §6), and the hook table. */
class InstallerTest {

    /** A small target class: one method named {@code one}, two named {@code two}. */
    static final class Target {
        void one() {}

        void two(int x) {}

        void two(String x) {}
    }

    static final class NoOpAdvice {
        @Advice.OnMethodEnter
        static void enter() {}
    }

    private static final TypeDescription TARGET = TypeDescription.ForLoadedType.of(Target.class);

    @Test
    void theMatcherCountIsOverDeclaredMethods() {
        assertEquals(1, Installer.count(TARGET, named("one")));
        assertEquals(2, Installer.count(TARGET, named("two")));
        assertEquals(0, Installer.count(TARGET, named("three")));
    }

    @Test
    void aMatcherSelectingNoneOrTwoMethodsIsReportedWhenTheClassLoads(@TempDir Path tmp) throws Exception {
        Path log = tmp.resolve("diagnostic.jsonl");
        System.setProperty(DiagnosticLog.PROPERTY, log.toString());
        String type = Target.class.getName();
        Hook none = new Hook("t-none", type, "three()", named("three"), NoOpAdvice.class);
        Hook two = new Hook("t-two", type, "two(…)", named("two"), NoOpAdvice.class);
        Hook one = new Hook("t-one", type, "one()", named("one"), NoOpAdvice.class);
        for (Hook hook : List.of(none, two, one)) {
            org.fractal.jdtls.boot.Hooks.register(hook.id(), hook.type(), hook.method());
        }
        try {
            Installer.transform(new ByteBuddy().redefine(Target.class), TARGET, Target.class.getClassLoader(),
                    List.of(none, two, one));
        } finally {
            System.clearProperty(DiagnosticLog.PROPERTY);
        }
        List<String> lines = Files.readAllLines(log);
        assertEquals(2, lines.size(), String.join("\n", lines));
        assertTrue(lines.get(0).contains("\"event\":\"hook-not-in-place\",\"hook\":\"t-none\",\"class\":\"" + type
                + "\",\"method\":\"three()\",\"reason\":\"its matcher selects no method of " + type + "\""),
                lines.get(0));
        assertTrue(lines.get(1).contains("\"event\":\"hook-not-in-place\",\"hook\":\"t-two\",\"class\":\"" + type
                + "\",\"method\":\"two(…)\",\"reason\":\"its matcher selects 2 methods of " + type + ", not one\""),
                lines.get(1));
    }

    @Test
    void theTableHasTheSixHooksOfDesignSection6() {
        List<Hook> hooks = Hook.all();
        assertEquals(List.of("getProjectName", "getWorkspaceArtifact", "getProjectFacade", "populateClasspath",
                "readParentExternally", "initParent"), hooks.stream().map(Hook::id).toList());
        Set<String> types = hooks.stream().map(Hook::type).collect(Collectors.toSet());
        assertEquals(Set.of("org.eclipse.m2e.core.internal.project.ProjectConfigurationManager",
                "org.eclipse.m2e.core.internal.project.registry.EclipseWorkspaceArtifactRepository",
                "org.eclipse.m2e.core.internal.project.registry.BasicProjectRegistry",
                "org.eclipse.m2e.jdt.internal.DefaultClasspathManagerDelegate",
                "org.apache.maven.model.building.DefaultModelBuilder",
                "org.apache.maven.project.DefaultProjectBuilder"), types);
        for (Hook hook : hooks) {
            Advice.to(hook.advice()); // the advice class is valid
        }
    }

    /** The overloads of m2e's {@code BasicProjectRegistry.getProjectFacade}. */
    static final class Overloads {
        Object getProjectFacade(java.io.File pom) {
            return null;
        }

        Object getProjectFacade(Object pom) {
            return null;
        }

        Object getProjectFacade(String g, String a, String v) {
            return null;
        }

        static Object getProjectFacade(String g, String a, String v, String classifier) {
            return null;
        }
    }

    @Test
    void theProductMatcherSelectsOneOverload() {
        Hook facade = Hook.all().stream().filter(h -> h.id().equals("getProjectFacade")).findFirst().orElseThrow();
        assertEquals(1, Installer.count(TypeDescription.ForLoadedType.of(Overloads.class), facade.matcher()));
    }

    @Test
    void theBrokenHookVariantBreaksOnlyTheNamedHook() {
        List<Hook> broken = BrokenHookAgent.broken("getProjectFacade");
        assertEquals(Hook.all().stream().map(Hook::id).toList(), broken.stream().map(Hook::id).toList());
        assertThrows(IllegalArgumentException.class, () -> BrokenHookAgent.broken("noSuchHook"));
    }
}
