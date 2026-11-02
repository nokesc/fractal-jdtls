package org.fractal.spike.jdtls.boot;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Logic behind the inlined advice. Loaded from the bootstrap class path so Equinox bundle class
 * loaders can see it (with org.osgi.framework.bootdelegation); talks to m2e only via reflection.
 *
 * Modes (-Dspike.mode): trace = log only; names = also prefix project names; fix = also prefer
 * same-worktree candidates when several workspace POMs share a GAV.
 */
public final class Spike {

    private static final String MODE = System.getProperty("spike.mode", "trace");
    private static final boolean NAMES = MODE.equals("names") || MODE.equals("fix");
    private static final boolean RESOLVE = MODE.equals("fix");
    /** -Dspike.mavenRequester=false leaves Maven parent resolution without a requester (H4 evidence). */
    private static final boolean MAVEN_REQUESTER = !"false".equals(System.getProperty("spike.mavenRequester"));
    private static final ThreadLocal<File> REQUESTER = new ThreadLocal<>();
    private static final Map<Path, String> WORKTREES = new ConcurrentHashMap<>();
    private static PrintWriter out;

    private Spike() {
    }

    // ---- logging

    public static synchronized void log(String event, Object... kv) {
        if (out == null) {
            try {
                out = new PrintWriter(new FileWriter(System.getProperty("spike.log", "spike-agent.jsonl"), true), true);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        StringBuilder b = new StringBuilder("{\"ts\":\"").append(Instant.now()).append("\",\"thread\":")
                .append(Thread.currentThread().threadId()).append(",\"event\":").append(json(event));
        for (int i = 0; i + 1 < kv.length; i += 2) {
            b.append(',').append(json(String.valueOf(kv[i]))).append(':').append(json(kv[i + 1]));
        }
        out.println(b.append('}'));
    }

    private static String json(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Number || v instanceof Boolean) {
            return v.toString();
        }
        if (v instanceof Collection<?> c) {
            return c.stream().map(Spike::json).collect(Collectors.joining(",", "[", "]"));
        }
        StringBuilder b = new StringBuilder("\"");
        for (char ch : v.toString().toCharArray()) {
            switch (ch) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                default -> b.append(ch < 0x20 ? String.format("\\u%04x", (int) ch) : String.valueOf(ch));
            }
        }
        return b.append('"').toString();
    }

    public static void started(String agentVersion) {
        log("agent-start", "mode", MODE, "mavenRequester", MAVEN_REQUESTER, "agent", agentVersion, "java", Runtime.version().toString(),
                "instanceArea", System.getProperty("osgi.instance.area"), "worktrees",
                System.getProperty("spike.worktrees"));
    }

    public static void transformed(String type) {
        log("transformed", "type", type);
    }

    public static void transformError(String type, Throwable t) {
        log("transform-error", "type", type, "error", String.valueOf(t));
    }

    // ---- worktree identity

    /** Nearest ancestor of the file that contains .git (a file in a linked worktree, a directory in a main checkout). */
    static Path worktreeRoot(File file) {
        if (file == null) {
            return null;
        }
        for (Path p = file.toPath().toAbsolutePath().normalize(); p != null; p = p.getParent()) {
            if (Files.exists(p.resolve(".git"))) {
                return p;
            }
        }
        return null;
    }

    static String worktreeToken(Path root) {
        return root == null ? null : WORKTREES.computeIfAbsent(root, r -> r.getFileName().toString());
    }

    // ---- naming: ProjectConfigurationManager.getProjectName(ProjectImportConfiguration, Model)

    public static String projectName(Object model, String name) {
        File pom = (File) call(model, "getPomFile");
        Path root = worktreeRoot(pom);
        String token = worktreeToken(root);
        String result = name;
        if (NAMES && token != null && name != null && !name.startsWith(token + ".")) {
            result = token + "." + name;
        }
        log("getProjectName", "pom", pom, "worktree", root, "original", name, "result", result);
        return result;
    }

    // ---- ProjectConfigurationManager.create(MavenProjectInfo, ...)

    public static void createEnter(Object projectInfo) {
        log("create-enter", "pom", call(projectInfo, "getPomFile"), "requester", REQUESTER.get());
    }

    public static void createExit(Object projectInfo, Object project, Throwable thrown) {
        log("create-exit", "pom", call(projectInfo, "getPomFile"), "project",
                project == null ? null : call(project, "getName"), "thrown", thrown == null ? null : thrown.toString());
    }

    // ---- requester carried from DefaultClasspathManagerDelegate.populateClasspath(desc, facade, kind, monitor)

    public static File requesterEnter(Object facade, String site) {
        File previous = REQUESTER.get();
        File pom = toFile(call(facade, "getPom"));
        REQUESTER.set(pom);
        log("requester-enter", "site", site, "pom", pom, "previous", previous);
        return previous;
    }

    /**
     * Maven parent resolution: DefaultModelBuilder.readParentExternally(childModel, ...) and
     * DefaultProjectBuilder.initParent(childProject, ...). The child's own POM is the requester.
     */
    public static File requesterEnterModel(Object child, String site) {
        File previous = REQUESTER.get();
        File pom = (File) call(child, site.equals("initParent") ? "getFile" : "getPomFile");
        if (pom != null && MAVEN_REQUESTER) {
            REQUESTER.set(pom);
        }
        log("requester-enter", "site", site, "pom", pom, "previous", previous);
        return previous;
    }

    public static void requesterExit(File previous, String site) {
        log("requester-exit", "site", site, "pom", REQUESTER.get(), "restored", previous);
        if (previous == null) {
            REQUESTER.remove();
        } else {
            REQUESTER.set(previous);
        }
    }

    // ---- BasicProjectRegistry.getProjectFacade(String groupId, String artifactId, String version)

    @SuppressWarnings("unchecked")
    public static Object projectFacade(Object registry, String g, String a, String v, Object chosen) {
        Collection<Object> candidates = null;
        Map<Object, Collection<Object>> artifacts = (Map<Object, Collection<Object>>) field(registry, "workspaceArtifacts");
        for (Map.Entry<Object, Collection<Object>> e : artifacts.entrySet()) {
            Object key = e.getKey();
            if (g.equals(call(key, "groupId")) && a.equals(call(key, "artifactId")) && v.equals(call(key, "version"))
                    && call(key, "classifier") == null) {
                candidates = e.getValue();
            }
        }
        File requester = REQUESTER.get();
        Object result = chosen;
        if (RESOLVE && requester != null && candidates != null && candidates.size() > 1) {
            Object preferred = sameWorktree(requester, candidates);
            if (preferred != null) {
                result = ((Map<Object, Object>) field(registry, "workspacePoms")).get(preferred);
            }
        }
        if (candidates != null && candidates.size() > 1 || result != chosen) {
            log("getProjectFacade(gav)", "gav", g + ":" + a + ":" + v, "requester", requester, "candidates",
                    files(candidates), "original", facadePom(chosen), "result", facadePom(result), "callers", callers(requester == null ? 40 : 6));
        }
        return result;
    }

    // ---- EclipseWorkspaceArtifactRepository.getWorkspaceArtifact(String groupId, String artifactId, String version)

    @SuppressWarnings("unchecked")
    public static Object workspaceArtifact(Object context, String g, String a, String v, Object chosen) {
        if (chosen == null || context == null) {
            return chosen;
        }
        Object requesterPom = field(context, "pom");
        File requester = requesterPom != null ? toFile(requesterPom) : REQUESTER.get();
        Object state = field(context, "state");
        Map<Object, Collection<Object>> artifacts = (Map<Object, Collection<Object>>) call(state, "getWorkspaceArtifacts",
                new Class<?>[] { String.class, String.class }, g, a);
        Collection<Object> candidates = null;
        for (Collection<Object> files : artifacts.values()) {
            if (files.contains(chosen)) {
                candidates = files;
            }
        }
        Object result = chosen;
        if (RESOLVE && requester != null && candidates != null && candidates.size() > 1) {
            Object preferred = sameWorktree(requester, candidates);
            if (preferred != null) {
                result = preferred;
            }
        }
        if (candidates != null && candidates.size() > 1 || result != chosen) {
            log("getWorkspaceArtifact", "gav", g + ":" + a + ":" + v, "requester", requester, "requesterSource",
                    requesterPom != null ? "context.pom" : "threadLocal", "candidates", files(candidates), "original",
                    toFile(chosen), "result", toFile(result), "callers", callers(requester == null ? 40 : 6));
        }
        return result;
    }

    // ---- ClasspathDescriptor.addProjectEntry / addLibraryEntry (observation only)

    public static void classpathEntry(String kind, Object path) {
        log("classpath-" + kind, "requester", REQUESTER.get(), "path", String.valueOf(path));
    }

    // ---- helpers

    private static Object sameWorktree(File requester, Collection<Object> candidatePoms) {
        Path root = worktreeRoot(requester);
        if (root == null) {
            return null;
        }
        for (Object pom : candidatePoms) {
            File f = toFile(pom);
            if (f != null && root.equals(worktreeRoot(f))) {
                return pom;
            }
        }
        return null;
    }

    private static List<File> files(Collection<Object> poms) {
        List<File> result = new ArrayList<>();
        if (poms != null) {
            poms.forEach(p -> result.add(toFile(p)));
        }
        return result;
    }

    private static File facadePom(Object facade) {
        return facade == null ? null : toFile(call(facade, "getPom"));
    }

    /** IFile → java.io.File via getLocation().toFile(). */
    private static File toFile(Object resource) {
        if (resource == null || resource instanceof File) {
            return (File) resource;
        }
        Object location = call(resource, "getLocation");
        return location == null ? null : (File) call(location, "toFile");
    }

    private static List<String> callers(int depth) {
        return StackWalker.getInstance().walk(s -> s.skip(3).limit(depth)
                .map(f -> f.getClassName().replaceFirst("^org\\.eclipse\\.", "") + "." + f.getMethodName() + ":" + f.getLineNumber())
                .toList());
    }

    private static Object call(Object target, String name) {
        return call(target, name, new Class<?>[0]);
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... args) {
        if (target == null) {
            return null;
        }
        try {
            Method m = findMethod(target.getClass(), name, types);
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(target.getClass().getName() + "." + name, e);
        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?>[] types) throws NoSuchMethodException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, types);
            } catch (NoSuchMethodException e) {
                // keep walking
            }
        }
        return type.getMethod(name, types);
    }

    private static Object field(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                // keep walking
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException(target.getClass().getName() + " has no field " + name);
    }
}
