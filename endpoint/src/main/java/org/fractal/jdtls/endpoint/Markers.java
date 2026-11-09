// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.JavaCore;

/**
 * Problems from Eclipse's markers (design §11 "Builds and problems"), read after the fixed point's build has been
 * joined. Diagnostics JDT LS pushed are never used. Each problem has its module, its file relative to the module,
 * 1-based line and column ({@link Positions}), the source line, and the declaration enclosing it ({@link JavaNames}).
 */
final class Markers {

    static final String JAVA_PROBLEM = "org.eclipse.jdt.core.problem";
    static final String BUILD_PATH_PROBLEM = "org.eclipse.jdt.core.buildpath_problem";
    static final String MAVEN_PREFIX = "org.eclipse.m2e.core.maven2Problem";

    private Markers() {}

    /** One problem, before it's written out. */
    private record Found(IMarker marker, String severity, String kind, String module, Path location, Path file, int line, String message) {}

    /**
     * The problems of the module's project, or with a null {@code moduleFolder} of every project of the worktree: those of the given severities, sorted by module, file, line and column,
     * at most {@code limit}; {@code counts} covers every problem in scope, before the severities and the limit.
     */
    static JsonObject read(Path root, Path moduleFolder, Set<String> severities, int limit) throws CoreException {
        List<IProject> projects = DiskSync.projects(root);
        if (moduleFolder != null) {
            projects = projects.stream().filter(p -> Path.of(p.getLocation().toOSString()).equals(moduleFolder)).toList();
        }
        List<Found> all = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        ProblemOps.SEVERITIES.forEach(s -> counts.put(s, 0));
        Set<String> seen = new java.util.HashSet<>();
        for (IProject project : projects) {
            if (!project.isOpen() || project.getLocation() == null) {
                continue;
            }
            Path location = Path.of(project.getLocation().toOSString());
            String module = Workspace.relative(root, location);
            for (IMarker m : project.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE)) {
                String severity = severity(m.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO));
                IResource r = m.getResource();
                Path file = r.getType() == IResource.FILE && r.getLocation() != null ? Path.of(r.getLocation().toOSString()) : null;
                int line = m.getAttribute(IMarker.LINE_NUMBER, -1);
                String message = m.getAttribute(IMarker.MESSAGE, "");
                if (!seen.add(module + "\0" + file + "\0" + line + "\0" + m.getAttribute(IMarker.CHAR_START, -1) + "\0" + message)) {
                    continue; // the same problem twice, as m2e and JDT may report one
                }
                counts.merge(severity, 1, Integer::sum);
                all.add(new Found(m, severity, kind(m.getType()), module, location, file, line, message));
            }
        }
        List<Found> wanted = new ArrayList<>(all.stream().filter(f -> severities.contains(f.severity())).toList());
        Map<Path, Positions> texts = new HashMap<>();
        List<JsonObject> problems = new ArrayList<>();
        for (Found f : wanted) {
            problems.add(toJson(f, texts));
        }
        problems.sort(Comparator.<JsonObject, String>comparing(o -> o.get("module").getAsString())
                .thenComparing(o -> o.has("file") ? o.get("file").getAsString() : "")
                .thenComparingInt(o -> o.has("line") ? o.get("line").getAsInt() : 0)
                .thenComparingInt(o -> o.has("column") ? o.get("column").getAsInt() : 0)
                .thenComparing(o -> o.get("message").getAsString()));
        JsonArray list = new JsonArray();
        problems.stream().limit(limit).forEach(list::add);
        JsonObject o = new JsonObject();
        o.add("problems", list);
        JsonObject c = new JsonObject();
        counts.forEach(c::addProperty);
        o.add("counts", c);
        o.addProperty("truncated", problems.size() > limit);
        return o;
    }

    static String severity(int severity) {
        return switch (severity) {
            case IMarker.SEVERITY_ERROR -> "error";
            case IMarker.SEVERITY_WARNING -> "warning";
            default -> "info";
        };
    }

    static String kind(String type) {
        if (JAVA_PROBLEM.equals(type)) {
            return "java";
        }
        if (BUILD_PATH_PROBLEM.equals(type)) {
            return "build-path";
        }
        return type != null && type.startsWith(MAVEN_PREFIX) ? "maven" : "other";
    }

    private static JsonObject toJson(Found f, Map<Path, Positions> texts) {
        JsonObject o = new JsonObject();
        o.addProperty("severity", f.severity());
        o.addProperty("kind", f.kind());
        o.addProperty("message", f.message());
        o.addProperty("module", f.module());
        if (f.file() == null) {
            return o;
        }
        o.addProperty("file", f.location().relativize(f.file()).toString());
        Positions text = texts.computeIfAbsent(f.file(), p -> text((IFile) f.marker().getResource()));
        int start = f.marker().getAttribute(IMarker.CHAR_START, -1);
        int end = f.marker().getAttribute(IMarker.CHAR_END, -1);
        int line = f.line();
        if (line < 1 && start >= 0 && text != null) {
            line = text.lineOf(start);
        }
        if (line >= 1) {
            o.addProperty("line", line);
        }
        if (text != null && start >= 0 && line >= 1 && text.lineOf(start) == line) {
            o.addProperty("column", text.columnOf(start));
            if (end > start) {
                o.addProperty("endLine", text.lineOf(end));
                o.addProperty("endColumn", text.columnOf(end));
            }
        }
        if (text != null && line >= 1) {
            String source = text.line(line);
            if (source != null) {
                o.addProperty("source", source);
            }
        }
        if (f.file().getFileName().toString().endsWith(".java")) {
            String element = element((IFile) f.marker().getResource(), start);
            if (element != null) {
                o.addProperty("element", element);
            }
        }
        return o;
    }

    /** The file's text as JDT read it, in the file's charset, or null if it's gone. */
    private static Positions text(IFile file) {
        try {
            Charset charset = Charset.forName(file.getCharset());
            return new Positions(new String(Files.readAllBytes(Path.of(file.getLocation().toOSString())), charset));
        } catch (IOException | CoreException | IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The enclosing declaration's name, or none. Naming never fails the answer (T002.05's review, R4): broken source
     * is exactly when the problems are needed, so anything naming throws, a {@code StackOverflowError} included, is
     * logged and the problem goes without its element.
     */
    private static String element(IFile file, int offset) {
        try {
            IJavaElement e = JavaCore.create(file);
            return e instanceof ICompilationUnit unit && unit.exists() ? JavaNames.enclosing(unit, offset) : null;
        } catch (Throwable t) {
            Log.info("no element for the problem at offset " + offset + " of " + file.getFullPath() + ": " + t);
            return null;
        }
    }
}
