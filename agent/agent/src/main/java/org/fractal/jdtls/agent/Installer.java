// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.agent;

import static net.bytebuddy.matcher.ElementMatchers.named;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

import org.fractal.jdtls.boot.DiagnosticLog;
import org.fractal.jdtls.boot.Hooks;
import org.fractal.jdtls.boot.Report;

/**
 * Installs hooks with Byte Buddy, and tells {@link Hooks} which are in place (design §6).
 *
 * <p>A hook is in place only when its method was advised, not when its class was transformed:
 * Byte Buddy reports a class as transformed even when no method in it matched. So, as each
 * target class loads, the installer counts the declared methods each hook's matcher selects. A
 * hook that selects exactly one is advised, and is in place once the class has transformed. One
 * that selects none, or more than one, isn't advised, and is reported then and there. A class
 * that fails to transform is reported from Byte Buddy's error listener.
 */
public final class Installer {

    /** The hooks advised in the class being transformed, by class name, on the transforming thread. */
    private static final ThreadLocal<Map<String, List<Hook>>> ADVISING = ThreadLocal.withInitial(HashMap::new);

    private Installer() {}

    /** Registers the hooks with {@link Hooks}, then installs them. */
    public static void install(Instrumentation instrumentation, List<Hook> hooks) {
        Report.captureStderr();
        for (Hook hook : hooks) {
            Hooks.register(hook.id(), hook.type(), hook.method());
        }
        DiagnosticLog.event("agent-start", "java", Runtime.version().toString(), "hooks",
                hooks.stream().map(Hook::id).toList());
        Map<String, List<Hook>> byType = new LinkedHashMap<>();
        for (Hook hook : hooks) {
            byType.computeIfAbsent(hook.type(), t -> new ArrayList<>()).add(hook);
        }
        AgentBuilder builder = new AgentBuilder.Default().disableClassFormatChanges().with(new Listener());
        for (Map.Entry<String, List<Hook>> entry : byType.entrySet()) {
            List<Hook> typeHooks = List.copyOf(entry.getValue());
            builder = builder.type(named(entry.getKey()))
                    .transform((b, type, loader, module, domain) -> transform(b, type, loader, typeHooks));
        }
        builder.installOn(instrumentation);
    }

    static DynamicType.Builder<?> transform(DynamicType.Builder<?> builder, TypeDescription type, ClassLoader loader,
            List<Hook> hooks) {
        Report.offerLoader(loader);
        List<Hook> advised = new ArrayList<>();
        for (Hook hook : hooks) {
            int selected = count(type, hook.matcher());
            if (selected == 1) {
                builder = builder.visit(Advice.to(hook.advice()).on(hook.matcher()));
                advised.add(hook);
            } else {
                Hooks.notInPlace(hook.id(), problem(type, selected));
            }
        }
        ADVISING.get().put(type.getName(), advised);
        return builder;
    }

    /** How many of the type's declared methods the matcher selects. */
    static int count(TypeDescription type, ElementMatcher<? super MethodDescription> matcher) {
        return type.getDeclaredMethods().filter(matcher).size();
    }

    /** Why a hook whose matcher selected {@code selected} methods of {@code type} isn't in place. */
    static String problem(TypeDescription type, int selected) {
        return selected == 0
                ? "its matcher selects no method of " + type.getName()
                : "its matcher selects " + selected + " methods of " + type.getName() + ", not one";
    }

    private static final class Listener extends AgentBuilder.Listener.Adapter {
        @Override
        public void onTransformation(TypeDescription type, ClassLoader loader, JavaModule module, boolean loaded,
                DynamicType dynamicType) {
            List<Hook> advised = ADVISING.get().remove(type.getName());
            if (advised != null) {
                for (Hook hook : advised) {
                    Hooks.inPlace(hook.id(), String.valueOf(loader));
                }
            }
        }

        @Override
        public void onError(String typeName, ClassLoader loader, JavaModule module, boolean loaded, Throwable error) {
            Hooks.transformError(typeName, error);
            List<Hook> advised = ADVISING.get().remove(typeName);
            if (advised != null) {
                for (Hook hook : advised) {
                    Hooks.notInPlace(hook.id(), "its class failed to transform: " + error);
                }
            }
        }

        @Override
        public void onComplete(String typeName, ClassLoader loader, JavaModule module, boolean loaded) {
            ADVISING.get().remove(typeName);
        }
    }
}
