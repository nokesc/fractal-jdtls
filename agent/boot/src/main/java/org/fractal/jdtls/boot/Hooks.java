// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.boot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Whether each hook is in place (design §6). The agent registers every hook in {@code premain},
 * then reports each one as its class loads: in place when its matcher selected exactly one
 * method and the class transformed, not in place otherwise. A hook whose class never loads is
 * reported at the checkpoint, the first return from {@code populateClasspath}, or, when
 * {@code populateClasspath} itself was never advised, at JVM shutdown, provided another hook was.
 */
public final class Hooks {

    /** The hook whose first return is the checkpoint. */
    public static final String CHECKPOINT_HOOK = "populateClasspath";

    enum State {
        /** Its class hasn't loaded yet. */
        PENDING,
        IN_PLACE,
        /** Reported already: its matcher didn't select exactly one method, or its class failed to transform. */
        NOT_IN_PLACE,
        /** Reported already: its class never loaded by the checkpoint or by shutdown. */
        NEVER_LOADED
    }

    private record Hook(String id, String type, String method) {}

    private static final Map<String, Hook> HOOKS = new LinkedHashMap<>();
    private static final Map<String, State> STATES = new LinkedHashMap<>();
    private static final AtomicBoolean CHECKPOINT = new AtomicBoolean();
    private static final AtomicBoolean SHUTDOWN_HOOK = new AtomicBoolean();

    private Hooks() {}

    /** Registers a hook, before any class loads. The first call also registers the shutdown check. */
    public static synchronized void register(String id, String type, String method) {
        HOOKS.put(id, new Hook(id, type, method));
        STATES.put(id, State.PENDING);
        if (SHUTDOWN_HOOK.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(Hooks::shutdown, "fractal-jdtls-agent-shutdown"));
        }
    }

    /** The hook's method was advised in a class that has transformed. */
    public static void inPlace(String id, String loader) {
        Hook hook;
        synchronized (Hooks.class) {
            hook = HOOKS.get(id);
            if (hook == null) {
                return;
            }
            if (STATES.get(id) != State.NOT_IN_PLACE) {
                STATES.put(id, State.IN_PLACE);
            }
        }
        DiagnosticLog.event("hook-in-place", "hook", id, "class", hook.type(), "method", hook.method(), "loader", loader);
    }

    /** The hook isn't in place, for the reason given; reported now. */
    public static void notInPlace(String id, String reason) {
        Hook hook;
        synchronized (Hooks.class) {
            hook = HOOKS.get(id);
            if (hook == null) {
                return;
            }
            STATES.put(id, State.NOT_IN_PLACE);
        }
        Report.error("hook " + id + " (" + hook.type() + "." + hook.method() + ") is not in place: " + reason
                + ". Fractal JDT LS can't keep worktrees apart in this process; this JDT LS release doesn't match"
                + " the agent.");
        DiagnosticLog.event("hook-not-in-place", "hook", id, "class", hook.type(), "method", hook.method(), "reason",
                reason);
    }

    /** A class failed to transform: Byte Buddy's error listener. */
    public static void transformError(String type, Throwable error) {
        Report.error("class " + type + " failed to transform: " + error);
        DiagnosticLog.event("transform-error", "class", type, "error", String.valueOf(error));
    }

    /** The checkpoint: the first call reports every hook whose class hasn't loaded. Later calls do nothing. */
    public static void checkpoint() {
        if (!CHECKPOINT.compareAndSet(false, true)) {
            return;
        }
        reportNeverLoaded("by the checkpoint (the first return from populateClasspath)");
        DiagnosticLog.event("checkpoint", "hooks", states());
        Report.flush();
    }

    /** Whether the checkpoint has passed. */
    public static boolean checkpointPassed() {
        return CHECKPOINT.get();
    }

    static void shutdown() {
        boolean report;
        synchronized (Hooks.class) {
            report = !CHECKPOINT.get() && STATES.get(CHECKPOINT_HOOK) != State.IN_PLACE
                    && STATES.containsValue(State.IN_PLACE);
        }
        if (report) {
            reportNeverLoaded("by shutdown, and populateClasspath was never advised");
            DiagnosticLog.event("shutdown", "hooks", states());
        }
        Report.flush();
    }

    private static void reportNeverLoaded(String when) {
        List<Hook> missing = new ArrayList<>();
        synchronized (Hooks.class) {
            for (Map.Entry<String, State> e : STATES.entrySet()) {
                if (e.getValue() == State.PENDING) {
                    e.setValue(State.NEVER_LOADED);
                    missing.add(HOOKS.get(e.getKey()));
                }
            }
        }
        for (Hook hook : missing) {
            String reason = "class " + hook.type() + " never loaded " + when;
            Report.error("hook " + hook.id() + " (" + hook.type() + "." + hook.method() + ") is not in place: "
                    + reason + ". Fractal JDT LS can't keep worktrees apart in this process.");
            DiagnosticLog.event("hook-not-in-place", "hook", hook.id(), "class", hook.type(), "method",
                    hook.method(), "reason", reason);
        }
    }

    /** Each hook's state, in registration order. */
    static synchronized Map<String, String> states() {
        Map<String, String> result = new LinkedHashMap<>();
        STATES.forEach((id, state) -> result.put(id, state.name().toLowerCase().replace('_', '-')));
        return result;
    }

    /** Forgets every hook. For tests; the shutdown check stays registered. */
    static synchronized void reset() {
        HOOKS.clear();
        STATES.clear();
        CHECKPOINT.set(false);
    }
}
