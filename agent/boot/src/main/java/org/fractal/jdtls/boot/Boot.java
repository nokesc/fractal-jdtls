// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.boot;

import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The helper the agent's advice calls (design §6, FINDINGS "Result" items 1 and 2). It's on the
 * boot class path, and its package is in {@code org.osgi.framework.bootdelegation}, so advice
 * inlined into m2e and Maven classes can call it. It reaches m2e and Maven only by reflection,
 * and its signatures use JDK types only.
 *
 * <ul>
 *   <li><b>Naming:</b> a project in a worktree is named {@code <worktree-dir>.<name>}.
 *   <li><b>Resolution:</b> when several workspace POMs share a GAV, the one in the requester's
 *       worktree wins; otherwise m2e's choice stands.
 *   <li><b>Requester:</b> {@code context.pom} when m2e sets it, else the innermost POM in a
 *       per-thread stack kept around {@code populateClasspath}, {@code readParentExternally} and
 *       {@code initParent}.
 * </ul>
 *
 * Outside any worktree, or without a requester, nothing changes. Any unexpected failure in here
 * is reported once per entry point, and m2e's own result is kept.
 */
public final class Boot {

    static final String GET_WORKSPACE_ARTIFACT = "getWorkspaceArtifact";
    static final String GET_PROJECT_FACADE = "getProjectFacade";
    private static final String ARTIFACT_KEY = "org.eclipse.m2e.core.embedder.ArtifactKey";

    private static final ThreadLocal<File> REQUESTER = new ThreadLocal<>();
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();
    private static final ClassValue<Constructor<?>> ARTIFACT_KEYS = new ClassValue<>() {
        @Override
        protected Constructor<?> computeValue(Class<?> registry) {
            try {
                Class<?> key = Class.forName(ARTIFACT_KEY, false, registry.getClassLoader());
                return key.getConstructor(String.class, String.class, String.class, String.class);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("can't construct " + ARTIFACT_KEY, e);
            }
        }
    };

    private Boot() {}

    // ---- naming: static ProjectConfigurationManager.getProjectName(ProjectImportConfiguration, Model)

    /** Exit advice: the project name for {@code model}, given m2e's {@code name}. */
    public static String projectName(Object model, String name) {
        Report.flush();
        try {
            File pom = (File) Reflect.call(model, "getPomFile");
            return projectName(pom, name);
        } catch (RuntimeException | LinkageError e) {
            failed("getProjectName", e);
            return name;
        }
    }

    static String projectName(File pom, String name) {
        Path root = Worktrees.root(pom);
        String prefix = Worktrees.prefix(root);
        // Always prefixed (design §6): m2e computes the name afresh from the POM on each creation,
        // so it never carries the prefix already; skipping names that look prefixed would make
        // harness-app and wt-a.harness-app collide.
        String result = prefix != null && name != null ? prefix + "." + name : name;
        DiagnosticLog.event("naming", "pom", pom, "worktree", root, "m2e", name, "result", result);
        return result;
    }

    // ---- requester

    /** Enter advice on {@code DefaultClasspathManagerDelegate.populateClasspath(…, facade, …)}. */
    public static File populateClasspathEnter(Object facade) {
        Report.flush();
        File previous = REQUESTER.get();
        try {
            REQUESTER.set(Reflect.toFile(Reflect.call(facade, "getPom")));
        } catch (RuntimeException | LinkageError e) {
            failed("populateClasspath", e);
            REQUESTER.remove();
        }
        return previous;
    }

    /** Exit advice on {@code populateClasspath}: restores the requester, and the first return is the checkpoint. */
    public static void populateClasspathExit(File previous) {
        requesterExit(previous);
        Hooks.checkpoint();
    }

    /** Enter advice on Maven's {@code DefaultModelBuilder.readParentExternally(childModel, …)}. */
    public static File readParentExternallyEnter(Object childModel) {
        return mavenRequesterEnter(childModel, "getPomFile", "readParentExternally");
    }

    /** Enter advice on Maven's {@code DefaultProjectBuilder.initParent(childProject, …)}. */
    public static File initParentEnter(Object childProject) {
        return mavenRequesterEnter(childProject, "getFile", "initParent");
    }

    /**
     * The child's own POM is the requester for its parent. A child read from a repository has no
     * POM file; it keeps the enclosing requester, the workspace POM whose parents are being read.
     */
    private static File mavenRequesterEnter(Object child, String getter, String site) {
        Report.flush();
        File previous = REQUESTER.get();
        try {
            File pom = (File) Reflect.call(child, getter);
            if (pom != null) {
                REQUESTER.set(pom);
            }
        } catch (RuntimeException | LinkageError e) {
            failed(site, e);
        }
        return previous;
    }

    /** Exit advice on the Maven requester hooks: restores the enclosing requester. */
    public static void requesterExit(File previous) {
        if (previous == null) {
            REQUESTER.remove();
        } else {
            REQUESTER.set(previous);
        }
    }

    /** The current thread's requester, or null. */
    static File requester() {
        return REQUESTER.get();
    }

    // ---- resolution: BasicProjectRegistry.getProjectFacade(String groupId, String artifactId, String version)

    /** Exit advice: the facade for the GAV, given m2e's {@code chosen} one. */
    @SuppressWarnings("unchecked")
    public static Object projectFacade(Object registry, String g, String a, String v, Object chosen) {
        Report.flush();
        try {
            Map<Object, Collection<Object>> artifacts = (Map<Object, Collection<Object>>) Reflect.field(registry,
                    "workspaceArtifacts");
            Collection<Object> candidates = artifacts.get(ARTIFACT_KEYS.get(registry.getClass()).newInstance(g, a, v, null));
            File requester = REQUESTER.get();
            if (candidates == null || candidates.isEmpty()) {
                if (DiagnosticLog.enabled()) {
                    lookup(GET_PROJECT_FACADE, g, a, v, requester, "thread", List.of(), facadePom(chosen),
                            facadePom(chosen));
                }
                return chosen;
            }
            Object preferred = choose(requester, candidates, chosen == null ? null : Reflect.call(chosen, "getPom"));
            Object result = chosen;
            if (preferred != null) {
                Object facade = ((Map<Object, Object>) Reflect.field(registry, "workspacePoms")).get(preferred);
                if (facade != null) {
                    result = facade;
                }
            }
            if (DiagnosticLog.enabled()) {
                lookup(GET_PROJECT_FACADE, g, a, v, requester, "thread", candidates, facadePom(chosen),
                        facadePom(result));
            }
            return result;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            failed(GET_PROJECT_FACADE, e);
            return chosen;
        }
    }

    // ---- resolution: EclipseWorkspaceArtifactRepository.getWorkspaceArtifact(String groupId, String artifactId, String version)

    /** Exit advice: the workspace POM for the GAV, given m2e's {@code chosen} one. */
    @SuppressWarnings("unchecked")
    public static Object workspaceArtifact(Object context, String g, String a, String v, Object chosen) {
        Report.flush();
        if (chosen == null || context == null) {
            // Not a workspace artifact: nothing to choose. Logged, so the log has every lookup.
            if (DiagnosticLog.enabled()) {
                try {
                    Object contextPom = context == null ? null : Reflect.field(context, "pom");
                    File requester = contextPom != null ? Reflect.toFile(contextPom) : REQUESTER.get();
                    lookup(GET_WORKSPACE_ARTIFACT, g, a, v, requester, contextPom != null ? "context.pom" : "thread",
                            List.of(), Reflect.toFile(chosen), Reflect.toFile(chosen));
                } catch (RuntimeException | LinkageError e) {
                    failed(GET_WORKSPACE_ARTIFACT, e);
                }
            }
            return chosen;
        }
        try {
            Object contextPom = Reflect.field(context, "pom");
            File requester = contextPom != null ? Reflect.toFile(contextPom) : REQUESTER.get();
            Object state = Reflect.field(context, "state");
            Map<Object, Collection<Object>> artifacts = (Map<Object, Collection<Object>>) Reflect.call(state,
                    "getWorkspaceArtifacts", new Class<?>[] {String.class, String.class}, g, a);
            Collection<Object> candidates = null;
            for (Collection<Object> poms : artifacts.values()) {
                if (poms.contains(chosen)) {
                    candidates = poms;
                }
            }
            if (candidates == null) {
                candidates = List.of(chosen);
            }
            Object preferred = choose(requester, candidates, chosen);
            Object result = preferred != null ? preferred : chosen;
            if (DiagnosticLog.enabled()) {
                lookup(GET_WORKSPACE_ARTIFACT, g, a, v, requester, contextPom != null ? "context.pom" : "thread",
                        candidates, Reflect.toFile(chosen), Reflect.toFile(result));
            }
            return result;
        } catch (RuntimeException | LinkageError e) {
            failed(GET_WORKSPACE_ARTIFACT, e);
            return chosen;
        }
    }

    // ---- the rule

    /**
     * Among several candidate POMs, the one in the requester's worktree, or null to keep m2e's
     * choice: with one candidate or none, with no requester, with a requester outside any
     * worktree, when m2e's choice is already in the requester's worktree, or when no candidate is.
     */
    static Object choose(File requester, Collection<?> candidatePoms, Object chosenPom) {
        if (requester == null || candidatePoms.size() < 2) {
            return null;
        }
        Path root = Worktrees.root(requester);
        if (root == null) {
            return null;
        }
        Worktrees.prefix(root); // registers the worktree, so a prefix collision is seen on a restart too
        if (chosenPom != null && root.equals(Worktrees.root(Reflect.toFile(chosenPom)))) {
            return null;
        }
        for (Object pom : candidatePoms) {
            File f = Reflect.toFile(pom);
            if (f != null && root.equals(Worktrees.root(f))) {
                return pom;
            }
        }
        return null;
    }

    private static void lookup(String site, String g, String a, String v, File requester, String requesterSource,
            Collection<?> candidates, File m2e, File result) {
        List<File> files = new ArrayList<>();
        candidates.forEach(c -> files.add(Reflect.toFile(c)));
        DiagnosticLog.event("lookup", "site", site, "gav", g + ":" + a + ":" + v, "requester", requester,
                "requesterSource", requester == null ? null : requesterSource, "candidates", files, "m2e", m2e,
                "result", result);
    }

    private static File facadePom(Object facade) {
        return facade == null ? null : Reflect.toFile(Reflect.call(facade, "getPom"));
    }

    /** Reports an unexpected failure once per entry point; the caller keeps m2e's result. */
    private static void failed(String site, Throwable e) {
        if (FAILED.add(site)) {
            Report.error("the " + site + " hook failed, and keeps m2e's result: " + e
                    + (e.getCause() == null ? "" : " (caused by " + e.getCause() + ")"));
            DiagnosticLog.event("hook-failed", "hook", site, "error", String.valueOf(e));
        }
    }
}
