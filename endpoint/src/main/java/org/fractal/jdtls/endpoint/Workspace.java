// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.ls.core.internal.managers.BasicFileDetector;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.embedder.ArtifactKey;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.m2e.core.project.LocalProjectScanner;
import org.eclipse.m2e.core.project.MavenProjectInfo;

/**
 * What the endpoint reads from JDT LS's workspace, through the Eclipse and m2e APIs: a worktree's Maven modules, the
 * project at a module's folder, and the import health check (design §11 "Worktrees").
 */
final class Workspace {

    static final String POM = "pom.xml";
    static final String JAVA_NATURE = "org.eclipse.jdt.core.javanature";
    /** How a health failure quotes m2e's errors on the module's POM. */
    static final String POM_ERRORS = " (its pom.xml: ";

    private Workspace() {}

    /**
     * A Maven module: its folder, its path relative to the worktree ({@code .} for the root), its POM's GAV and
     * packaging, and why its POM can't be read, if it can't.
     */
    record Found(Path folder, String module, String gav, String packaging, String pomError) {
        Found(Path folder, String module, String gav, String packaging) {
            this(folder, module, gav, packaging, null);
        }
    }

    /**
     * The worktree's Maven modules, found as JDT LS's Maven importer finds them: the top-most {@code pom.xml} folders
     * ({@code BasicFileDetector}, nested ones not included, {@code target} and JDT LS's import exclusions skipped),
     * then each one's reactor through m2e's {@code LocalProjectScanner}, which follows {@code <modules>}. Sorted by
     * path.
     *
     * <p>The scanner leaves out a POM it can't read, and only reports why (T002.04's review, R1), so the endpoint also
     * reads each POM's {@code <modules>} itself ({@link Poms}), from the same top folders: a module either finds is a
     * module, and one whose POM can't be read keeps the reason, so it fails the health check instead of vanishing.
     */
    static List<Found> modules(Path root) throws CoreException, InterruptedException {
        Collection<Path> tops = new BasicFileDetector(root, POM).includeNested(false).addExclusions("**/target")
                .scan(new NullProgressMonitor());
        List<String> folders = tops.stream().map(p -> p.toAbsolutePath().toString()).toList();
        Map<Path, Found> found = new java.util.TreeMap<>();
        if (!folders.isEmpty()) {
            LocalProjectScanner scanner = new LocalProjectScanner(folders, false, MavenPlugin.getMavenModelManager());
            scanner.run(new NullProgressMonitor());
            collect(root, scanner.getProjects(), found);
            for (Throwable t : scanner.getErrors()) {
                Log.info("m2e couldn't read a POM of " + root + ": " + t);
            }
        }
        java.util.Set<Path> visited = new java.util.HashSet<>();
        for (Path top : tops) {
            walk(root, top.toAbsolutePath().normalize(), found, visited);
        }
        return new ArrayList<>(found.values());
    }

    /** The endpoint's own reading of a reactor, from its POM down through {@code <modules>}, inside the worktree. */
    private static void walk(Path root, Path folder, Map<Path, Found> found, java.util.Set<Path> visited) {
        if (!folder.startsWith(root) || !visited.add(folder)) {
            return;
        }
        String module = relative(root, folder);
        Poms.Pom pom;
        try {
            pom = Poms.read(folder.resolve(POM));
        } catch (Poms.Unreadable e) {
            Found known = found.get(folder);
            found.put(folder, new Found(folder, module, known == null ? "unknown" : known.gav(), known == null ? null : known.packaging(),
                    "it " + e.getMessage()));
            return;
        }
        found.putIfAbsent(folder, new Found(folder, module, pom.gav(), pom.packaging()));
        for (String m : pom.modules()) {
            Path child = folder.resolve(m).normalize();
            if (java.nio.file.Files.isRegularFile(child)) {
                child = child.getParent(); // <module> may name the POM file itself
            }
            if (java.nio.file.Files.isRegularFile(child.resolve(POM))) {
                walk(root, child, found, visited);
            }
        }
    }

    private static void collect(Path root, Collection<MavenProjectInfo> infos, Map<Path, Found> found) {
        for (MavenProjectInfo info : infos) {
            File pom = info.getPomFile();
            if (pom != null) {
                Path folder = pom.toPath().toAbsolutePath().normalize().getParent();
                if (folder.startsWith(root)) {
                    String[] gav = gav(info);
                    found.putIfAbsent(folder, new Found(folder, relative(root, folder), gav[0], gav[1]));
                }
            }
            collect(root, info.getProjects(), found);
        }
    }

    /**
     * {@code groupId:artifactId:version} and packaging from the POM's model, the parent's group and version where the
     * POM inherits them. By reflection: the model is Maven's class, inside m2e's Maven runtime, which the bundle
     * doesn't compile against.
     */
    private static String[] gav(MavenProjectInfo info) {
        try {
            Object model = MavenProjectInfo.class.getMethod("getModel").invoke(info);
            Object parent = call(model, "getParent");
            String group = (String) call(model, "getGroupId");
            String version = (String) call(model, "getVersion");
            if (group == null && parent != null) {
                group = (String) call(parent, "getGroupId");
            }
            if (version == null && parent != null) {
                version = (String) call(parent, "getVersion");
            }
            String packaging = (String) call(model, "getPackaging");
            return new String[] {group + ":" + call(model, "getArtifactId") + ":" + version, packaging == null ? "jar" : packaging};
        } catch (ReflectiveOperationException | RuntimeException e) {
            return new String[] {"unknown (" + e + ")", null};
        }
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        if (target == null) {
            return null;
        }
        Method m = target.getClass().getMethod(name);
        return m.invoke(target);
    }

    static String relative(Path root, Path folder) {
        String rel = root.relativize(folder).toString();
        return rel.isEmpty() ? "." : rel;
    }

    private static Path location(IProject project) {
        return project.getLocation() == null ? null : Path.of(project.getLocation().toOSString());
    }

    /** The Eclipse project whose location is exactly the folder, or null. */
    private static IProject projectAt(Path folder) {
        for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (folder.equals(location(p))) {
                return p;
            }
        }
        return null;
    }

    /** The name of the Eclipse project at the folder, or null. */
    static String projectNameAt(Path folder) {
        IProject p = projectAt(folder);
        return p == null ? null : p.getName();
    }

    /** Where the Eclipse project of that name is on disk, or null if JDT LS has no such project (the passthrough's {@code jdt:} URIs). */
    static Path projectLocation(String name) {
        try {
            IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
            return p.exists() ? location(p) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The names of the Eclipse projects located in the worktree. */
    static List<String> projectsUnder(Path root) {
        List<String> result = new ArrayList<>();
        for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            Path location = location(p);
            if (location != null && location.startsWith(root)) {
                result.add(p.getName());
            }
        }
        return result;
    }

    /** The POMs under the worktree that m2e's registry still knows. */
    static List<String> registeredPomsUnder(Path root) {
        List<String> result = new ArrayList<>();
        for (IMavenProjectFacade f : MavenPlugin.getMavenProjectRegistry().getProjects()) {
            if (f.getPom() != null && f.getPom().getLocation() != null) {
                Path pom = Path.of(f.getPom().getLocation().toOSString());
                if (pom.startsWith(root)) {
                    result.add(pom.toString());
                }
            }
        }
        return result;
    }

    /** One module's health (design §11 "Worktrees"), with its GAV and packaging from m2e when it has a facade. */
    static Registry.Module health(Found m) {
        IProject project = projectAt(m.folder());
        if (project == null) {
            return new Registry.Module(m.module(), m.gav(), m.packaging(), Registry.State.IMPORT_FAILED,
                    "JDT LS has no project at the module's folder"
                            + (m.pomError() != null ? POM_ERRORS + m.pomError() + ")" : elsewhere(m.folder())));
        }
        return health(m, project);
    }

    private static Registry.Module health(Found m, IProject project) {
        if (!project.isOpen()) {
            return new Registry.Module(m.module(), m.gav(), m.packaging(), Registry.State.IMPORT_FAILED, "the project is closed");
        }
        IMavenProjectFacade facade = facade(project);
        String gav = m.gav();
        String packaging = m.packaging();
        if (facade != null) {
            ArtifactKey key = facade.getArtifactKey();
            gav = key.groupId() + ":" + key.artifactId() + ":" + key.version();
            packaging = facade.getPackaging();
        }
        boolean java;
        try {
            java = project.hasNature(JAVA_NATURE);
        } catch (CoreException e) {
            java = false;
        }
        List<String> missing = new ArrayList<>();
        if (facade == null) {
            missing.add("no m2e facade");
        }
        if (!java && !"pom".equals(packaging)) {
            missing.add("no Java nature");
        }
        if (missing.isEmpty()) {
            return new Registry.Module(m.module(), gav, packaging, Registry.State.IMPORTED, null);
        }
        String markers = pomErrors(m.folder().resolve(POM));
        String failure = "the project has " + String.join(" and ", missing)
                + (markers.isEmpty() && m.pomError() != null ? POM_ERRORS + m.pomError() + ")" : markers);
        return new Registry.Module(m.module(), gav, packaging, Registry.State.IMPORT_FAILED, failure);
    }

    /**
     * m2e's facade for the project, as its registry holds it now, or null. Read only: never m2e's
     * {@code getProject(IProject)}, whose {@code getPom} refreshes the POM it locates ({@code File.refreshLocal}), so the
     * mapping's checks took a POM changed on disk in outside a fixed point (T002.06's observation; T002.11). Only a
     * fixed point takes disk changes in.
     */
    private static IMavenProjectFacade facade(IProject project) {
        for (IMavenProjectFacade f : MavenPlugin.getMavenProjectRegistry().getProjects()) {
            if (project.equals(f.getProject())) {
                return f;
            }
        }
        return null;
    }

    /** A project for the folder at another location: the broken import of HTTP-FINDINGS left one at {@code <data>}. */
    private static String elsewhere(Path folder) {
        String name = folder.getFileName().toString();
        for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (p.getName().endsWith("." + name) || p.getName().equals(name)) {
                Path location = location(p);
                if (location != null && !location.equals(folder) && !Files.exists(location.resolve(POM))) {
                    return "; a project named like it is at " + location;
                }
            }
        }
        return "";
    }

    /** m2e's error markers on the POM, which say why it couldn't read it: the first two, quoted. */
    private static String pomErrors(Path pom) {
        Map<String, Boolean> messages = new LinkedHashMap<>();
        for (org.eclipse.core.resources.IFile f : ResourcesPlugin.getWorkspace().getRoot().findFilesForLocationURI(pom.toUri())) {
            try {
                for (IMarker marker : f.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO)) {
                    if (marker.getAttribute(IMarker.SEVERITY, 0) == IMarker.SEVERITY_ERROR) {
                        messages.put(marker.getAttribute(IMarker.MESSAGE, ""), true);
                    }
                }
            } catch (CoreException e) {
                // no markers to quote
            }
        }
        if (messages.isEmpty()) {
            return "";
        }
        List<String> first = messages.keySet().stream().limit(2).toList();
        return POM_ERRORS + String.join("; ", first) + ")";
    }
}
