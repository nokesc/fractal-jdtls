// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.ISourceReference;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jdt.core.search.TypeNameMatchRequestor;

/**
 * From a request's target to a Java element (design §11 "Addressing code", "How names resolve"), with every
 * addressing error as a problem an LLM can act on.
 *
 * <ul>
 *   <li><b>Names.</b> Types come from a JDT {@code SearchEngine} type-name search scoped to the sources of the
 *       worktree's Java projects, which never finds another worktree's copy. A type two modules declare is the
 *       requesting module's, else the one on its classpath, else {@code ambiguous-type}. Members are matched on the
 *       {@code IType} model, comparing erased parameter types resolved in the declaring type ({@link JavaNames}).
 *       For the read-only operations, a name that matches nothing in the sources is looked up on the requesting
 *       module's classpath (its Maven dependencies and the JDK), as a second scope.
 *   <li><b>Text anchors.</b> A file relative to the module, and {@code text} that occurs exactly once in it, or once
 *       within a named declaration of it ({@link TextAnchors}); the element is the one JDT selects at {@code select}.
 * </ul>
 */
final class Targets {

    /** The request's view of its worktree: its projects, the requesting module, its search scope, and file texts. */
    static final class Context {
        final Path root;
        final Path folder;
        final String module;
        final List<IProject> projects;
        final IProject project;
        final IJavaProject java;
        final IProgressMonitor monitor;
        private final Map<Path, Positions> texts = new HashMap<>();
        private Set<IProject> searched;

        Context(CodeRequest.Scope s) {
            this.root = s.root();
            this.folder = s.folder();
            this.module = s.module().module();
            this.projects = DiskSync.projects(root);
            this.project = projects.stream().filter(p -> folder.equals(location(p))).findFirst().orElse(null);
            IJavaProject j = project == null ? null : JavaCore.create(project);
            this.java = j != null && j.exists() ? j : null;
            this.monitor = new DiskSync.Deadline(s.deadline());
        }

        static Path location(IProject p) {
            return p.getLocation() == null ? null : Path.of(p.getLocation().toOSString());
        }

        /** The worktree project the file or folder belongs to: the one whose folder is nearest above it. */
        IProject projectOf(Path file) {
            IProject best = null;
            int depth = -1;
            for (IProject p : projects) {
                Path l = location(p);
                if (l != null && file.startsWith(l) && l.getNameCount() > depth) {
                    best = p;
                    depth = l.getNameCount();
                }
            }
            return best;
        }

        /** The module (relative to the worktree's root) of the file, or null when it isn't in the worktree. */
        String moduleOf(Path file) {
            IProject p = projectOf(file);
            return p == null ? null : Workspace.relative(root, location(p));
        }

        Path moduleFolder(String module) {
            return module.equals(".") ? root : root.resolve(module);
        }

        /** The Java projects of the worktree. */
        List<IJavaProject> javaProjects() {
            List<IJavaProject> list = new ArrayList<>();
            for (IProject p : projects) {
                IJavaProject j = JavaCore.create(p);
                if (j != null && j.exists()) {
                    list.add(j);
                }
            }
            return list;
        }

        IJavaSearchScope sources() {
            return SearchEngine.createJavaSearchScope(javaProjects().toArray(IJavaElement[]::new), IJavaSearchScope.SOURCES);
        }

        /**
         * What a search covers (design §11 "The contract"): the requesting module and the worktree's modules that
         * depend on it. A module that isn't a Java project, such as the root's {@code pom} aggregator, covers every
         * module in its folder.
         */
        Set<IProject> searched() throws JavaModelException {
            if (searched != null) {
                return searched;
            }
            Set<IProject> s = new LinkedHashSet<>();
            if (java == null) {
                for (IProject p : projects) {
                    Path l = location(p);
                    if (l != null && l.startsWith(folder)) {
                        s.add(p);
                    }
                }
            } else {
                s.add(project);
                for (IJavaProject j : javaProjects()) {
                    if (requires(j, project.getName(), new java.util.HashSet<>())) {
                        s.add(j.getProject());
                    }
                }
            }
            searched = s;
            return s;
        }

        private static boolean requires(IJavaProject j, String name, Set<String> seen) throws JavaModelException {
            for (String required : j.getRequiredProjectNames()) {
                if (required.equals(name)) {
                    return true;
                }
                if (seen.add(required)) {
                    IJavaProject next = JavaCore.create(j.getProject().getWorkspace().getRoot().getProject(required));
                    if (next != null && next.exists() && requires(next, name, seen)) {
                        return true;
                    }
                }
            }
            return false;
        }

        List<IJavaProject> searchedJava() throws JavaModelException {
            List<IJavaProject> list = new ArrayList<>();
            for (IProject p : searched()) {
                IJavaProject j = JavaCore.create(p);
                if (j != null && j.exists()) {
                    list.add(j);
                }
            }
            return list;
        }

        /** Whether a file is in a module the search covers. */
        boolean inSearch(Path file) throws JavaModelException {
            IProject p = projectOf(file);
            return p != null && searched().contains(p);
        }

        Path path(ICompilationUnit unit) {
            return unit.getResource() == null || unit.getResource().getLocation() == null ? null
                    : Path.of(unit.getResource().getLocation().toOSString());
        }

        /** The compilation unit's text, as JDT reads it: its buffer, which the fixed point made the file's. */
        Positions text(ICompilationUnit unit) {
            Path p = path(unit);
            return texts.computeIfAbsent(p, k -> {
                try {
                    String s = unit.getSource();
                    return s == null ? null : new Positions(s);
                } catch (JavaModelException e) {
                    return null;
                }
            });
        }
    }

    /**
     * What a target resolved to: the element, whether it's from the classpath, and for code in the worktree its
     * compilation unit and the offset to ask JDT LS at (the anchor's {@code select}, or the declaration's name).
     */
    record Target(IJavaElement element, boolean library, ICompilationUnit unit, int offset, int length, boolean anchor) {}

    private Targets() {}

    // --- targets ---------------------------------------------------------------------------------

    /** A name ({@code name} non-null) or an anchor; {@code readOnly} allows names on the classpath. */
    static Target resolve(String name, NavOps.Anchor anchor, Context ctx, boolean readOnly) throws Problem, CoreException {
        if (name != null) {
            IJavaElement e = element(name, ctx, readOnly, "/target");
            return named(e, ctx);
        }
        return anchor(anchor, ctx);
    }

    static Target named(IJavaElement e, Context ctx) throws JavaModelException {
        boolean library = Locations.library(e, ctx.root);
        ICompilationUnit unit = library ? null : (ICompilationUnit) e.getAncestor(IJavaElement.COMPILATION_UNIT);
        int offset = -1;
        int length = 0;
        if (unit != null && e instanceof ISourceReference ref) {
            ISourceRange r = ref.getNameRange();
            if (r != null && r.getOffset() >= 0) {
                offset = r.getOffset();
                length = r.getLength();
            }
        }
        return new Target(e, library, unit, offset, length, false);
    }

    // --- files -----------------------------------------------------------------------------------

    /** A Java source file of the requesting module, named relative to it; {@code pointer} is the field's. */
    static ICompilationUnit unit(String file, Context ctx, String pointer) throws Problem, JavaModelException {
        String syntax = fileSyntax(file);
        if (syntax != null) {
            throw Problem.of(Code.INVALID_REQUEST).detail("1 field is invalid.").error(pointer, syntax);
        }
        Path path = ctx.folder.resolve(file).normalize();
        if (!Files.isRegularFile(path)) {
            throw Problem.of(Code.FILE_NOT_FOUND).detail("There's no " + file + " in " + ctx.folder + ".").with("field", pointer);
        }
        IProject owner = ctx.projectOf(path);
        if (owner == null) {
            throw Problem.of(Code.NOT_JAVA_SOURCE).detail(file + " isn't in any module of " + ctx.root + ".").with("field", pointer);
        }
        if (!owner.equals(ctx.project)) {
            String module = Workspace.relative(ctx.root, Context.location(owner));
            String rel = Context.location(owner).relativize(path).toString();
            JsonObject set = new JsonObject();
            set.addProperty("/module", module);
            set.addProperty(pointer, rel);
            JsonObject candidate = new JsonObject();
            candidate.add("set", set);
            candidate.addProperty("about", "the module " + module + ", and the file relative to it");
            JsonArray candidates = new JsonArray();
            candidates.add(candidate);
            throw Problem.of(Code.FILE_IN_OTHER_MODULE).detail(file + " belongs to the module " + module + ", not '" + ctx.module + "'.")
                    .with("field", pointer).with("candidates", candidates);
        }
        IFile f = owner.getFile(IPath.fromOSString(Context.location(owner).relativize(path).toString()));
        IJavaElement e = f.exists() ? JavaCore.create(f) : null;
        if (!file.endsWith(".java") || !(e instanceof ICompilationUnit unit) || !unit.exists() || ctx.java == null || !ctx.java.isOnClasspath(unit)) {
            throw Problem.of(Code.NOT_JAVA_SOURCE).detail(file + " isn't a .java file on a source folder of " + ctx.module + ".")
                    .with("field", pointer);
        }
        return unit;
    }

    /** A file relative to the module: null when its syntax is valid, else why not. */
    static String fileSyntax(String file) {
        if (file.startsWith("/") || file.contains("\\") || file.contains("\0")) {
            return "must be relative to the module, with / separators, such as src/main/java/com/acme/Foo.java";
        }
        for (String segment : file.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return "must name a file inside the module: no empty, . or .. segments";
            }
        }
        return null;
    }

    // --- text anchors ----------------------------------------------------------------------------

    /** Where a text anchor is: its file's compilation unit and text, and the range of {@code select} (or all of {@code text}). */
    record Range(ICompilationUnit unit, Positions text, int start, int end) {}

    static Target anchor(NavOps.Anchor a, Context ctx) throws Problem, CoreException {
        Range range = range(a, ctx);
        ICompilationUnit unit = range.unit();
        Positions text = range.text();
        String source = text.text();
        int start = range.start();
        int end = range.end();
        IJavaElement[] selected = unit.codeSelect(start, end - start);
        if (selected.length == 0) {
            String what = HttpApi.printable(source.substring(start, end));
            throw Problem.of(Code.NOT_APPLICABLE)
                    .detail("'" + what + "' at line " + text.lineOf(start) + " of " + a.file() + " isn't the name of one Java element.")
                    .hint("Use `select` to pick one name inside `text`: a type, member or variable name, such as the method's name in a call.")
                    .with("field", a.select() != null ? "/target/select" : "/target/text");
        }
        IJavaElement e = selected[0];
        return new Target(e, Locations.library(e, ctx.root), unit, start, end - start, true);
    }

    /** The anchor's range, with every addressing error: the file, {@code within}, {@code text} once, {@code select} once. */
    static Range range(NavOps.Anchor a, Context ctx) throws Problem, CoreException {
        ICompilationUnit unit = unit(a.file(), ctx, "/target/file");
        Positions text = ctx.text(unit);
        if (text == null) {
            throw new IllegalStateException("JDT has no source for " + a.file());
        }
        String source = text.text();
        int from = 0;
        int to = source.length();
        String within = null;
        if (a.within() != null) {
            IJavaElement w = element(a.within(), ctx, false, "/target/within");
            if (!(w instanceof ISourceReference ref) || w instanceof IPackageFragment || !unit.equals(w.getAncestor(IJavaElement.COMPILATION_UNIT))) {
                String where = w instanceof IMember m && m.getCompilationUnit() != null && ctx.path(m.getCompilationUnit()) != null
                        ? " is declared in " + ctx.root.relativize(ctx.path(m.getCompilationUnit())) : " isn't a type or member";
                throw Problem.of(Code.INVALID_REQUEST).detail("1 field is invalid.")
                        .error("/target/within", "must be a type or member declared in " + a.file() + "; " + Locations.name(w) + where);
            }
            ISourceRange r = ref.getSourceRange();
            from = r.getOffset();
            to = r.getOffset() + r.getLength();
            within = Locations.name(w);
        }
        List<TextAnchors.Match> found = TextAnchors.find(source, a.text(), from, to);
        String in = within == null ? " in " + a.file() : " within " + within + " in " + a.file();
        if (found.isEmpty()) {
            throw notFound(a, unit, text, from, to, in, ctx);
        }
        if (found.size() > 1) {
            throw notUnique(a, unit, text, found, in, ctx);
        }
        TextAnchors.Match m = found.get(0);
        int start = m.start();
        int end = m.end();
        if (a.select() != null) {
            List<Integer> at = TextAnchors.indexes(a.text(), a.select());
            if (at.size() != 1) {
                throw Problem.of(Code.INVALID_REQUEST).detail("1 field is invalid.").error("/target/select",
                        "must occur exactly once in text; it occurs " + (at.isEmpty() ? "nowhere" : at.size() + " times") + " in it");
            }
            TextAnchors.Match s = TextAnchors.select(source, m.start(), a.text(), at.get(0), a.select().replace("\r\n", "\n").length(), to);
            start = s.start();
            end = s.end();
        }
        return new Range(unit, text, start, end);
    }

    private static Problem notFound(NavOps.Anchor a, ICompilationUnit unit, Positions text, int from, int to, String in, Context ctx) throws JavaModelException {
        List<TextAnchors.Match> other = TextAnchors.withOtherWhitespace(text.text(), a.text(), from, to);
        JsonArray candidates = new JsonArray();
        for (TextAnchors.Match m : other) {
            String enclosing = JavaNames.enclosing(unit, m.start());
            candidates.add(Mapping.candidate("/target/text", text.text().substring(m.start(), m.end()),
                    "line " + text.lineOf(m.start()) + (enclosing == null ? "" : ", in " + enclosing)));
        }
        StringBuilder detail = new StringBuilder("'" + HttpApi.printable(a.text()) + "' doesn't occur" + in + ".");
        if (!other.isEmpty()) {
            detail.append(other.size() == 1 ? " It occurs once with different whitespace." : " It occurs " + other.size() + " times with different whitespace.");
        } else {
            List<TextAnchors.Near> near = TextAnchors.closest(text, a.text(), text.lineOf(from), text.lineOf(Math.max(from, to - 1)), 3);
            if (near.isEmpty()) {
                detail.append(" No line of it is close to the text's first line.");
            } else {
                detail.append(near.size() == 1 ? " The closest line is " : " The closest lines are ");
                for (int i = 0; i < near.size(); i++) {
                    detail.append(i == 0 ? "" : i == near.size() - 1 ? " and " : ", ").append(near.get(i).line()).append(": `")
                            .append(near.get(i).source().strip()).append('`');
                }
                detail.append('.');
            }
        }
        return Problem.of(Code.TEXT_NOT_FOUND).detail(detail.toString()).with("field", "/target/text").with("candidates", candidates);
    }

    private static Problem notUnique(NavOps.Anchor a, ICompilationUnit unit, Positions text, List<TextAnchors.Match> found, String in, Context ctx)
            throws JavaModelException {
        JsonArray occurrences = new JsonArray();
        Map<String, List<Integer>> byWithin = new LinkedHashMap<>();
        for (TextAnchors.Match m : found) {
            JsonObject o = new JsonObject();
            int line = text.lineOf(m.start());
            o.addProperty("line", line);
            o.addProperty("column", text.columnOf(m.start()));
            o.addProperty("source", text.line(line));
            String enclosing = JavaNames.enclosing(unit, m.start());
            if (enclosing != null) {
                o.addProperty("within", enclosing);
                byWithin.computeIfAbsent(enclosing, k -> new ArrayList<>()).add(line);
            }
            occurrences.add(o);
        }
        // Each declaration an occurrence is in is a candidate only if the text occurs exactly once in its whole source
        // range, and only once its name resolves back to it in this file: so applying any candidate makes the anchor
        // unique (T002.06's review, R4; a type's range holds its constructors' occurrences too).
        Map<String, TextAnchors.Match> ranges = new LinkedHashMap<>();
        for (String w : byWithin.keySet()) {
            IJavaElement e;
            try {
                e = element(w, ctx, false, "/target/within");
            } catch (Problem | CoreException unresolved) {
                continue;
            }
            if (e instanceof ISourceReference ref && unit.equals(e.getAncestor(IJavaElement.COMPILATION_UNIT)) && ref.getSourceRange() != null) {
                ISourceRange r = ref.getSourceRange();
                ranges.put(w, new TextAnchors.Match(r.getOffset(), r.getOffset() + r.getLength()));
            }
        }
        JsonArray candidates = new JsonArray();
        for (String w : TextAnchors.onceWithin(text.text(), a.text(), ranges)) {
            String member = w.contains("#") ? w.substring(w.indexOf('#') + 1).replaceAll("\\(.*", "") : w.substring(w.lastIndexOf('.') + 1);
            int line = text.lineOf(TextAnchors.find(text.text(), a.text(), ranges.get(w).start(), ranges.get(w).end()).get(0).start());
            candidates.add(Mapping.candidate("/target/within", w, "line " + line + ", in " + member));
        }
        return Problem.of(Code.TEXT_NOT_UNIQUE).detail("'" + HttpApi.printable(a.text()) + "' occurs " + found.size() + " times" + in + ".")
                .with("field", "/target/text").with("occurrences", occurrences).with("candidates", candidates);
    }

    // --- names -----------------------------------------------------------------------------------

    /** The element a Java name names; {@code pointer} is the request field, for the problems and their candidates. */
    static IJavaElement element(String text, Context ctx, boolean readOnly, String pointer) throws Problem, CoreException {
        NameSyntax.Name name;
        try {
            name = NameSyntax.parse(text);
        } catch (NameSyntax.Invalid e) {
            throw Problem.of(Code.INVALID_NAME).detail(e.getMessage()).with("field", pointer);
        }
        List<IType> types = sourceTypes(name.segments(), ctx);
        IType type = null;
        boolean fromClasspath = false;
        if (types.size() > 1) {
            type = preferred(types, name, ctx, pointer);
        } else if (types.size() == 1) {
            type = types.get(0);
        } else if (name.member() == null) {
            List<IPackageFragment> packages = sourcePackages(name.qualified(), ctx);
            if (!packages.isEmpty()) {
                return packages.get(0);
            }
        }
        if (type == null && readOnly && ctx.java != null) {
            IType found = ctx.java.findType(name.qualified(), ctx.monitor);
            if (found != null && found.exists()) {
                type = found;
                fromClasspath = Locations.library(found, ctx.root);
            }
        }
        if (type == null) {
            throw typeNotFound(name, ctx, readOnly, pointer);
        }
        if (name.member() == null) {
            return type;
        }
        return member(type, name, fromClasspath, ctx, pointer);
    }

    /** The top-level types named by a split of the segments into a package and a type, then their nested types. */
    static List<IType> sourceTypes(List<String> segments, Context ctx) throws JavaModelException {
        Map<String, List<Integer>> splits = new LinkedHashMap<>(); // "package\0top" -> the split index
        List<char[]> qualifications = new ArrayList<>();
        List<char[]> names = new ArrayList<>();
        for (int k = 0; k < segments.size(); k++) {
            String pkg = String.join(".", segments.subList(0, k));
            splits.computeIfAbsent(pkg + "\0" + segments.get(k), x -> new ArrayList<>()).add(k);
            qualifications.add(pkg.toCharArray());
            names.add(segments.get(k).toCharArray());
        }
        List<IType> tops = new ArrayList<>();
        new SearchEngine().searchAllTypeNames(qualifications.toArray(char[][]::new), names.toArray(char[][]::new), ctx.sources(),
                new TypeNameMatchRequestor() {
                    @Override
                    public void acceptTypeNameMatch(TypeNameMatch match) {
                        if (match.getTypeContainerName().equals(match.getPackageName())) { // top-level
                            tops.add(match.getType());
                        }
                    }
                }, IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH, ctx.monitor);
        List<IType> found = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (IType top : tops) {
            List<Integer> ks = splits.get(top.getPackageFragment().getElementName() + "\0" + top.getElementName());
            if (ks == null || Locations.library(top, ctx.root)) {
                continue;
            }
            for (int k : ks) {
                IType t = top;
                for (int i = k + 1; i < segments.size() && t != null; i++) {
                    t = t.getType(segments.get(i));
                    t = t.exists() ? t : null;
                }
                if (t != null && seen.add(t.getHandleIdentifier())) {
                    found.add(t);
                }
            }
        }
        found.sort(Comparator.comparing(t -> t.getJavaProject().getElementName()));
        return found;
    }

    /** A type two modules declare: the requesting module's, else the one on its classpath, else ambiguous-type. */
    private static IType preferred(List<IType> types, NameSyntax.Name name, Context ctx, String pointer) throws JavaModelException, Problem {
        for (IType t : types) {
            if (ctx.project != null && t.getJavaProject().getProject().equals(ctx.project)) {
                return t;
            }
        }
        if (ctx.java != null) {
            IType visible = ctx.java.findType(name.qualified(), ctx.monitor);
            for (IType t : types) {
                if (visible != null && visible.equals(t)) {
                    return t;
                }
            }
        }
        JsonArray candidates = new JsonArray();
        for (IType t : types) {
            Path file = t.getCompilationUnit() == null ? null : ctx.path(t.getCompilationUnit());
            String module = file == null ? null : ctx.moduleOf(file);
            if (module != null) {
                candidates.add(Mapping.candidate("/module", module, ctx.root.relativize(file).toString()));
            }
        }
        throw Problem.of(Code.AMBIGUOUS_TYPE).detail(name.qualified() + " is declared in " + types.size() + " modules of " + ctx.root
                + ", and " + (ctx.java == null ? "the module " + ctx.module + " has no classpath to choose by." : "neither is on " + ctx.module + "'s classpath."))
                .with("field", "/module").with("candidates", candidates);
    }

    static List<IPackageFragment> sourcePackages(String name, Context ctx) throws JavaModelException {
        List<IPackageFragment> found = new ArrayList<>();
        for (IJavaProject j : ctx.javaProjects()) {
            for (IPackageFragmentRoot r : j.getPackageFragmentRoots()) {
                if (r.getKind() == IPackageFragmentRoot.K_SOURCE && r.getJavaProject().equals(j)) {
                    IPackageFragment p = r.getPackageFragment(name);
                    if (p.exists() && p.containsJavaResources()) {
                        found.add(p);
                    }
                }
            }
        }
        return found;
    }

    private static Problem typeNotFound(NameSyntax.Name name, Context ctx, boolean readOnly, String pointer) throws JavaModelException {
        String simple = name.segments().get(name.segments().size() - 1);
        JsonArray candidates = new JsonArray();
        List<IType> same = new ArrayList<>();
        new SearchEngine().searchAllTypeNames(null, 0, simple.toCharArray(), org.eclipse.jdt.core.search.SearchPattern.R_EXACT_MATCH
                | org.eclipse.jdt.core.search.SearchPattern.R_CASE_SENSITIVE, IJavaSearchConstants.TYPE, ctx.sources(), new TypeNameMatchRequestor() {
                    @Override
                    public void acceptTypeNameMatch(TypeNameMatch match) {
                        same.add(match.getType());
                    }
                }, IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH, ctx.monitor);
        same.sort(Comparator.comparing(t -> t.getFullyQualifiedName('.')));
        for (IType t : same) {
            String fqn = t.getFullyQualifiedName('.');
            if (Locations.library(t, ctx.root) || fqn.equals(name.qualified())) {
                continue;
            }
            String member = name.memberText();
            candidates.add(Mapping.candidate(pointer, member == null ? fqn : fqn + "#" + member, Locations.kind(t) + ", " + where(t, ctx)));
        }
        boolean lower = !simple.isEmpty() && Character.isLowerCase(simple.charAt(0));
        if (name.member() == null && lower && candidates.isEmpty()) {
            return Problem.of(Code.PACKAGE_NOT_FOUND).detail("No sources in " + ctx.root + " are in the package " + name.qualified()
                    + ", and no type has that name.").with("field", pointer);
        }
        String detail = "No type " + name.qualified() + " is declared in " + ctx.root
                + (readOnly ? ctx.java == null ? " (the module " + ctx.module + " has no classpath to look on)" : ", nor on " + ctx.module + "'s classpath" : "")
                + ".";
        return Problem.of(Code.TYPE_NOT_FOUND).detail(detail).with("field", pointer).with("candidates", candidates);
    }

    /** {@code module/file:line} of a declaration in the worktree, or {@code from the classpath: <library>}. */
    static String where(IJavaElement e, Context ctx) throws JavaModelException {
        if (Locations.library(e, ctx.root)) {
            return "from the classpath: " + Locations.libraryName(e);
        }
        ICompilationUnit unit = (ICompilationUnit) e.getAncestor(IJavaElement.COMPILATION_UNIT);
        Path file = unit == null ? null : ctx.path(unit);
        if (file == null) {
            return ctx.root.toString();
        }
        String rel = ctx.root.relativize(file).toString();
        Positions text = ctx.text(unit);
        if (text != null && e instanceof ISourceReference ref && ref.getNameRange() != null && ref.getNameRange().getOffset() >= 0) {
            return rel + ":" + text.lineOf(ref.getNameRange().getOffset());
        }
        return rel;
    }

    // --- members ---------------------------------------------------------------------------------

    private static IJavaElement member(IType type, NameSyntax.Name name, boolean fromClasspath, Context ctx, String pointer) throws Problem, CoreException {
        String member = name.member();
        boolean constructor = member.equals(type.getElementName());
        List<IMember> all = members(type);
        List<IMember> named = all.stream().filter(m -> constructor ? m instanceof IMethod im && isConstructor(im) : !(m instanceof IMethod im && isConstructor(im))
                && m.getElementName().equals(member)).toList();
        List<IMember> matching = new ArrayList<>();
        List<IMember> fields = named.stream().filter(m -> m instanceof IField).toList();
        if (name.params() == null && !fields.isEmpty()) {
            // A field wins a name without parentheses, as in Javadoc's #name; a method that shares its name is written
            // with parentheses (design §11 "Addressing code"; T002.06's review, R3). So every candidate resolves.
            matching.addAll(fields);
        } else {
            for (IMember m : named) {
                if (m instanceof IMethod method && (name.params() == null || matches(method, name.params()))) {
                    matching.add(m);
                }
            }
        }
        String owner = type.getFullyQualifiedName('.');
        if (matching.size() == 1) {
            return matching.get(0);
        }
        if (matching.isEmpty()) {
            List<IMember> offered = named;
            if (offered.isEmpty()) {
                offered = all.stream().filter(m -> m.getElementName().equalsIgnoreCase(member)).toList();
            }
            String what = name.memberText();
            return failMember(Code.MEMBER_NOT_FOUND, owner + " has no member " + what
                    + (named.isEmpty() ? "." : name.params() == null ? "." : " with those parameter types."), offered, ctx, pointer);
        }
        String what = name.params() != null ? matching.size() + " overloads with those parameter types" : matching.size() + " overloads";
        String classpath = fromClasspath ? ", all on " + ctx.module + "'s classpath (" + Locations.libraryName(type) + ")" : "";
        return failMember(Code.AMBIGUOUS_MEMBER, owner + "#" + name.memberText() + " matches " + what + classpath + ".", matching, ctx, pointer);
    }

    private static IJavaElement failMember(Code code, String detail, List<IMember> offered, Context ctx, String pointer) throws Problem, JavaModelException {
        JsonArray candidates = new JsonArray();
        for (IMember m : offered) {
            candidates.add(Mapping.candidate(pointer, Locations.name(m), Locations.signature(m) + ", " + where(m, ctx)));
        }
        throw Problem.of(code).detail(detail).with("field", pointer).with("candidates", candidates);
    }

    /** The type's fields (record components and enum constants too) and methods, in declaration order. */
    static List<IMember> members(IType type) throws JavaModelException {
        List<IMember> list = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        if (type.isRecord()) {
            for (IField f : type.getRecordComponents()) {
                if (seen.add(f.getHandleIdentifier())) {
                    list.add(f);
                }
            }
        }
        for (IJavaElement child : type.getChildren()) {
            if ((child instanceof IField || child instanceof IMethod) && seen.add(child.getHandleIdentifier())) {
                IMember m = (IMember) child;
                if (!org.eclipse.jdt.core.Flags.isSynthetic(m.getFlags()) && !(m instanceof IMethod im && (im.isLambdaMethod()
                        || im.getElementName().startsWith("lambda$") || im.getElementName().equals("<clinit>")))) {
                    list.add(m);
                }
            }
        }
        return list;
    }

    private static boolean isConstructor(IMethod m) {
        try {
            return m.isConstructor();
        } catch (JavaModelException e) {
            return false;
        }
    }

    /** Whether the method's erased parameter types are the written ones: simple names compare with the last segment. */
    static boolean matches(IMethod method, List<NameSyntax.Param> params) throws JavaModelException {
        String[] types = method.getParameterTypes();
        if (types.length != params.size()) {
            return false;
        }
        for (int i = 0; i < types.length; i++) {
            String actual = JavaNames.parameter(method, types[i]);
            int dims = 0;
            while (actual.endsWith("[]")) {
                dims++;
                actual = actual.substring(0, actual.length() - 2);
            }
            NameSyntax.Param p = params.get(i);
            if (dims != p.dims()) {
                return false;
            }
            if (!p.matches(actual)) {
                return false;
            }
        }
        return true;
    }
}
