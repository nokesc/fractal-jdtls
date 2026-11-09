// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IInitializer;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IOrdinaryClassFile;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IRegion;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.jdt.internal.corext.callhierarchy.CallHierarchyCore;
import org.eclipse.jdt.internal.corext.callhierarchy.CallLocation;
import org.eclipse.jdt.internal.corext.callhierarchy.MethodWrapper;
import org.eclipse.jdt.ls.core.internal.JDTUtils;
import org.eclipse.jdt.ls.core.internal.javadoc.JavadocContentAccess2;

/**
 * The navigation operations' answers (design §11 "Navigation and search"), read once the fixed point is done.
 *
 * <p>JDT LS's own LSP handlers answer, through the in-JVM client, wherever the target has a position in the
 * worktree's sources: {@code textDocument/definition} (for a text anchor), {@code /references},
 * {@code /implementation}, {@code typeHierarchy/subtypes} and {@code textDocument/documentSymbol}. Their answers, in
 * LSP's URIs and 0-based positions, become the caller's terms: Java names, modules, files relative to them, 1-based
 * lines and the source line. JDT answers directly only where a handler can't express the contract (design §11
 * "Navigation and search"):
 *
 * <ul>
 *   <li>resolving a name or a text anchor to an element ({@link Targets}); a name's definition is that element;
 *   <li>a library or JDK element ({@code from the classpath}), which may have no source and so no position for a
 *       handler to take: references and implementations through JDT search and type hierarchies;
 *   <li>supertypes, through {@code IType.newSupertypeHierarchy}, which {@code typeHierarchy/supertypes} reads too: the
 *       handler leaves out a library supertype unless the client opens {@code jdt://} class files;
 *   <li>the call hierarchy, through {@code CallHierarchyCore}, the engine of JDT LS's {@code CallHierarchyHandler}: the
 *       handler finds each item's element again from its URI and position, which a library method without source
 *       doesn't have, so the item's Java name, which the contract requires, would be lost;
 *   <li>{@code symbols}: JDT LS's {@code workspace/symbol} finds only types (methods only with a client setting) and
 *       never fields, across every worktree, so a JDT declaration search scoped to the worktree's sources answers;
 *   <li>{@code describe}'s Javadoc, from JDT LS's {@code JavadocContentAccess2}, which its hover uses: the hover adds a
 *       "Source:" line with the Eclipse project's name and the file's URI.
 * </ul>
 *
 * <p>Results never cross worktrees: anything outside the request's worktree is dropped, and a search keeps only the
 * requesting module and the worktree's modules that depend on it ({@link Targets.Context#searched}).
 */
final class Navigation {

    private static final Comparator<JsonObject> BY_PLACE = Comparator.<JsonObject, String>comparing(o -> str(o, "module"))
            .thenComparing(o -> str(o, "file")).thenComparingInt(o -> num(o, "line")).thenComparingInt(o -> num(o, "column"))
            .thenComparing(o -> str(o, "element"));

    private Navigation() {}

    /** An operation's answer; JDT's cancellation at the deadline is {@code timeout}, phase {@code running}. */
    static JsonObject answer(NavOps.Op op, CodeRequest.Scope s) throws Exception {
        try {
            return op.answer(s);
        } catch (OperationCanceledException c) {
            throw new DiskSync.Cancelled("running", "answering on " + s.root());
        }
    }

    // --- definition ------------------------------------------------------------------------------

    static JsonObject definition(CodeRequest.Scope s, LspClient lsp, NavOps.Target target) throws Exception {
        Targets.Context ctx = new Targets.Context(s);
        Targets.Target t = resolve(ctx, target);
        refuse(t, "definition", "a type, member or variable", t.element() instanceof IPackageFragment,
                " A package has no one declaration: describe lists its types.");
        JsonArray definitions = new JsonArray();
        if (!t.anchor() || t.library()) {
            // A name's definition is the declaration it resolved to.
            definitions.add(Locations.of(t.element(), ctx));
        } else {
            for (JsonObject l : lspLocations(call(lsp, "textDocument/definition", position(t, ctx), s.deadline()))) {
                JsonObject location = fromLsp(l, ctx);
                if (location != null) {
                    definitions.add(location);
                }
            }
        }
        JsonObject o = new JsonObject();
        o.add("definitions", definitions);
        return o;
    }

    // --- references ------------------------------------------------------------------------------

    static JsonObject references(CodeRequest.Scope s, LspClient lsp, NavOps.Target target, boolean declaration, int limit) throws Exception {
        Targets.Context ctx = new Targets.Context(s);
        Targets.Target t = resolve(ctx, target);
        IJavaElement e = t.element();
        List<JsonObject> found = new ArrayList<>();
        if (t.library() || t.offset() < 0) {
            SearchPattern pattern = e instanceof IPackageFragment p
                    ? SearchPattern.createPattern(p.getElementName(), IJavaSearchConstants.PACKAGE, IJavaSearchConstants.REFERENCES,
                            SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE)
                    : SearchPattern.createPattern(e, IJavaSearchConstants.REFERENCES);
            found.addAll(search(pattern, ctx));
        } else {
            JsonObject params = position(t, ctx);
            JsonObject context = new JsonObject();
            context.addProperty("includeDeclaration", declaration);
            params.add("context", context);
            for (JsonObject l : lspLocations(call(lsp, "textDocument/references", params, s.deadline()))) {
                JsonObject location = fromLsp(l, ctx);
                if (location != null && inSearch(location, ctx)) {
                    found.add(location);
                }
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("element", Locations.name(e));
        limited(o, "references", found, limit);
        return o;
    }

    // --- implementations -------------------------------------------------------------------------

    static JsonObject implementations(CodeRequest.Scope s, LspClient lsp, NavOps.Target target, int limit) throws Exception {
        Targets.Context ctx = new Targets.Context(s);
        Targets.Target t = resolve(ctx, target);
        IJavaElement e = t.element();
        refuse(t, "implementations", "a type or a method", !(e instanceof IType) && !(e instanceof IMethod), "");
        List<JsonObject> found = new ArrayList<>();
        if (t.library() || t.offset() < 0) {
            IType type = e instanceof IType it ? it : ((IMethod) e).getDeclaringType();
            for (IType sub : subtypes(type, ctx)) {
                if (e instanceof IType) {
                    found.add(Locations.of(sub, ctx));
                } else {
                    for (IMethod m : overriding((IMethod) e, sub, ctx)) {
                        found.add(Locations.of(m, ctx));
                    }
                }
            }
        } else {
            for (JsonObject l : lspLocations(call(lsp, "textDocument/implementation", position(t, ctx), s.deadline()))) {
                JsonObject location = fromLsp(l, ctx);
                if (location != null && inSearch(location, ctx)) {
                    found.add(location);
                }
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("element", Locations.name(e));
        limited(o, "implementations", found, limit);
        return o;
    }

    /**
     * The methods of {@code sub} that override {@code method}, from JDT's bindings: {@link IMethodBinding#overrides}
     * applies the subtype's type arguments, so {@code compare(String,String)} in a class implementing
     * {@code Comparator<String>} overrides {@code Comparator#compare(T,T)}. {@code IType.findMethods} compares the
     * declarations' parameter types as written, and missed it (T002.06's review, R1). The bindings come from one
     * batch, in the subtype's project, so they're comparable.
     */
    private static List<IMethod> overriding(IMethod method, IType sub, Targets.Context ctx) throws JavaModelException {
        List<IMethod> candidates = new ArrayList<>();
        for (IMethod m : sub.getMethods()) {
            if (!m.isConstructor() && m.getElementName().equals(method.getElementName()) && m.getNumberOfParameters() == method.getNumberOfParameters()
                    && !org.eclipse.jdt.core.Flags.isStatic(m.getFlags())) {
                candidates.add(m);
            }
        }
        if (candidates.isEmpty()) {
            return candidates;
        }
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setProject(sub.getJavaProject());
        List<IJavaElement> elements = new ArrayList<>();
        elements.add(method);
        elements.addAll(candidates);
        IBinding[] bindings = parser.createBindings(elements.toArray(IJavaElement[]::new), ctx.monitor);
        List<IMethod> overriding = new ArrayList<>();
        if (bindings[0] instanceof IMethodBinding overridden) {
            for (int i = 1; i < bindings.length; i++) {
                if (bindings[i] instanceof IMethodBinding b && b.overrides(overridden)) {
                    overriding.add(candidates.get(i - 1));
                }
            }
        }
        return overriding;
    }

    /** The subtypes of a library type in the modules the search covers, from a JDT type hierarchy of them. */
    private static List<IType> subtypes(IType type, Targets.Context ctx) throws Exception {
        IRegion region = JavaCore.newRegion();
        List<IJavaProject> searched = ctx.searchedJava();
        searched.forEach(region::add);
        IJavaProject project = ctx.java != null ? ctx.java : searched.isEmpty() ? type.getJavaProject() : searched.get(0);
        ITypeHierarchy h;
        try {
            h = project.newTypeHierarchy(type, region, ctx.monitor);
        } catch (OperationCanceledException c) {
            throw new DiskSync.Cancelled("running", "computing the subtypes of " + type.getFullyQualifiedName('.'));
        }
        List<IType> list = new ArrayList<>();
        for (IType sub : h.getAllSubtypes(type)) {
            Path file = sub.getCompilationUnit() == null ? null : ctx.path(sub.getCompilationUnit());
            if (file != null && !Locations.library(sub, ctx.root) && ctx.inSearch(file)) {
                list.add(sub);
            }
        }
        return list;
    }

    // --- type hierarchy --------------------------------------------------------------------------

    static JsonObject typeHierarchy(CodeRequest.Scope s, LspClient lsp, NavOps.Target target, String direction, int depth) throws Exception {
        Targets.Context ctx = new Targets.Context(s);
        Targets.Target t = resolve(ctx, target);
        refuse(t, "type-hierarchy", "a type", !(t.element() instanceof IType), "");
        IType type = (IType) t.element();
        JsonObject o = new JsonObject();
        o.add("type", typeNode(type, item(type), depth, !direction.equals("subtypes"), !direction.equals("supertypes"), lsp, ctx, s.deadline(),
                new java.util.HashMap<>()));
        return o;
    }

    /**
     * A {@code TypeHierarchyItem} for the type, as JDT LS makes them: its {@code data.element}, the type's JDT handle,
     * is what {@code typeHierarchy/supertypes} and {@code /subtypes} resolve, so a library type without source works.
     */
    static JsonObject item(IType type) throws JavaModelException {
        JsonObject item = new JsonObject();
        item.addProperty("name", type.getElementName());
        item.addProperty("kind", type.isInterface() ? 11 : type.isEnum() ? 10 : 5);
        ICompilationUnit unit = type.getCompilationUnit();
        IClassFile classFile = type.getClassFile();
        item.addProperty("uri", unit != null ? JDTUtils.toURI(unit) : classFile != null ? JDTUtils.toUri(classFile) : "");
        item.add("range", range(0, 0, 0, 0));
        item.add("selectionRange", range(0, 0, 0, 0));
        JsonObject data = new JsonObject();
        data.addProperty("element", type.getHandleIdentifier());
        item.add("data", data);
        return item;
    }

    private static JsonObject typeNode(IType type, JsonObject item, int depth, boolean up, boolean down, LspClient lsp, Targets.Context ctx, long deadline,
            Map<String, ITypeHierarchy> supertypes) throws Exception {
        JsonObject node = new JsonObject();
        node.addProperty("name", Locations.name(type));
        node.addProperty("kind", Locations.kind(type));
        node.add("location", Locations.of(type, ctx));
        if (depth <= 0) {
            return node;
        }
        if (up) {
            // JDT's supertype hierarchy, which JDT LS's typeHierarchy/supertypes reads too: the handler leaves out a
            // library supertype unless the client opens jdt:// class files, so java.lang.Object would be missing.
            ITypeHierarchy h = supertypes.computeIfAbsent(type.getHandleIdentifier(), k -> {
                try {
                    return type.newSupertypeHierarchy(ctx.monitor);
                } catch (JavaModelException ex) {
                    throw new IllegalStateException(ex);
                }
            });
            List<IType> direct = new ArrayList<>();
            IType superclass = h.getSuperclass(type);
            if (superclass != null) {
                direct.add(superclass);
            }
            direct.addAll(List.of(h.getSuperInterfaces(type)));
            JsonArray list = new JsonArray();
            for (IType st : direct) {
                if (Locations.library(st, ctx.root) || inWorktree(st, ctx)) {
                    supertypes.putIfAbsent(st.getHandleIdentifier(), h);
                    list.add(typeNode(st, null, depth - 1, true, false, lsp, ctx, deadline, supertypes));
                }
            }
            node.add("supertypes", list);
        }
        if (down) {
            List<JsonObject> nodes = new ArrayList<>();
            for (JsonObject[] pair : related(lsp, "typeHierarchy/subtypes", item, deadline)) {
                IType st = typeOf(pair[0]);
                if (st == null || Locations.library(st, ctx.root) || st.getCompilationUnit() == null) {
                    continue;
                }
                Path file = ctx.path(st.getCompilationUnit());
                if (file == null || !ctx.inSearch(file)) {
                    continue;
                }
                nodes.add(typeNode(st, pair[0], depth - 1, false, true, lsp, ctx, deadline, supertypes));
            }
            nodes.sort(Comparator.comparing(n -> n.get("name").getAsString()));
            JsonArray list = new JsonArray();
            nodes.forEach(list::add);
            node.add("subtypes", list);
        }
        return node;
    }

    private static List<JsonObject[]> related(LspClient lsp, String method, JsonObject item, long deadline) throws Exception {
        JsonObject params = new JsonObject();
        params.add("item", item);
        JsonElement result = call(lsp, method, params, deadline);
        List<JsonObject[]> list = new ArrayList<>();
        if (result != null && result.isJsonArray()) {
            for (JsonElement e : result.getAsJsonArray()) {
                list.add(new JsonObject[] {e.getAsJsonObject()});
            }
        }
        return list;
    }

    private static IType typeOf(JsonObject item) {
        JsonElement data = item.get("data");
        if (data == null || !data.isJsonObject() || !data.getAsJsonObject().has("element")) {
            return null;
        }
        IJavaElement e = JavaCore.create(data.getAsJsonObject().get("element").getAsString());
        if (e instanceof IOrdinaryClassFile cf) {
            e = cf.getType();
        }
        return e instanceof IType t && t.exists() ? t : null;
    }

    private static boolean inWorktree(IJavaElement e, Targets.Context ctx) {
        ICompilationUnit unit = (ICompilationUnit) e.getAncestor(IJavaElement.COMPILATION_UNIT);
        Path file = unit == null ? null : ctx.path(unit);
        return file != null && file.startsWith(ctx.root);
    }

    // --- call hierarchy --------------------------------------------------------------------------

    static JsonObject callHierarchy(CodeRequest.Scope s, LspClient lsp, NavOps.Target target, boolean incoming, int depth) throws Exception {
        Targets.Context ctx = new Targets.Context(s);
        Targets.Target t = resolve(ctx, target);
        refuse(t, "call-hierarchy", "a method or constructor", !(t.element() instanceof IMethod), "");
        IMethod method = (IMethod) t.element();
        CallHierarchyCore core = CallHierarchyCore.getDefault();
        MethodWrapper[] roots = incoming ? core.getCallerRoots(new IMember[] {method}) : core.getCalleeRoots(new IMember[] {method});
        JsonObject o = new JsonObject();
        o.addProperty("element", Locations.name(method));
        o.add("calls", roots.length == 0 ? new JsonArray() : calls(roots[0], depth, incoming, ctx));
        return o;
    }

    private static JsonArray calls(MethodWrapper wrapper, int depth, boolean incoming, Targets.Context ctx) throws Exception {
        MethodWrapper[] calls;
        try {
            calls = wrapper.getCalls(ctx.monitor);
        } catch (OperationCanceledException c) {
            throw new DiskSync.Cancelled("running", "computing the call hierarchy");
        }
        if (ctx.monitor.isCanceled()) {
            throw new DiskSync.Cancelled("running", "computing the call hierarchy");
        }
        List<JsonObject> nodes = new ArrayList<>();
        for (MethodWrapper c : calls) {
            IMember member = c.getMember();
            if (member == null) {
                continue;
            }
            boolean library = Locations.library(member, ctx.root);
            if (incoming) {
                // Callers are searched for: only the requesting module and the modules that depend on it.
                ICompilationUnit unit = member.getCompilationUnit();
                Path file = unit == null ? null : ctx.path(unit);
                if (library || file == null || !ctx.inSearch(file)) {
                    continue;
                }
            } else if (!library && !inWorktree(member, ctx)) {
                continue;
            }
            JsonObject node = new JsonObject();
            node.addProperty("name", Locations.name(member));
            node.add("location", Locations.of(member, ctx));
            List<JsonObject> sites = new ArrayList<>();
            for (CallLocation l : c.getMethodCall().getCallLocations()) {
                IMember in = l.getMember();
                ICompilationUnit unit = in == null ? null : in.getCompilationUnit();
                if (unit != null && inWorktree(in, ctx)) {
                    sites.add(Locations.at(unit, l.getStart(), l.getEnd(), Locations.name(in), ctx));
                }
            }
            sites.sort(BY_PLACE);
            JsonArray list = new JsonArray();
            sites.forEach(list::add);
            node.add("calls", list);
            if (depth > 1 && !c.isRecursive()) {
                node.add("children", calls(c, depth - 1, incoming, ctx));
            }
            nodes.add(node);
        }
        nodes.sort(Comparator.comparing((JsonObject n) -> n.get("name").getAsString()));
        JsonArray list = new JsonArray();
        nodes.forEach(list::add);
        return list;
    }

    // --- outline ---------------------------------------------------------------------------------

    static JsonObject outline(CodeRequest.Scope s, LspClient lsp, String file) throws Exception {
        Targets.Context ctx = new Targets.Context(s);
        ICompilationUnit unit = Targets.unit(file, ctx, "/file");
        JsonObject params = new JsonObject();
        JsonObject doc = new JsonObject();
        doc.addProperty("uri", JDTUtils.toURI(unit));
        params.add("textDocument", doc);
        JsonElement result = call(lsp, "textDocument/documentSymbol", params, s.deadline());
        JsonArray symbols = new JsonArray();
        if (result != null && result.isJsonArray()) {
            for (JsonElement e : result.getAsJsonArray()) {
                symbols.addAll(outlineNodes(e.getAsJsonObject(), unit, ctx));
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("file", file);
        o.add("symbols", symbols);
        return o;
    }

    /**
     * One {@code DocumentSymbol} as outline nodes: itself with its children, if it's a declaration with a Java name of
     * its own; else its children (a package declaration, an import or an initializer has none).
     */
    private static JsonArray outlineNodes(JsonObject symbol, ICompilationUnit unit, Targets.Context ctx) throws JavaModelException {
        JsonArray children = new JsonArray();
        if (symbol.has("children") && symbol.get("children").isJsonArray()) {
            for (JsonElement c : symbol.getAsJsonArray("children")) {
                children.addAll(outlineNodes(c.getAsJsonObject(), unit, ctx));
            }
        }
        Positions text = ctx.text(unit);
        JsonObject sel = symbol.has("selectionRange") ? symbol.getAsJsonObject("selectionRange") : null;
        if (text == null || sel == null) {
            return children;
        }
        int offset = offset(text, sel.getAsJsonObject("start"));
        IJavaElement e = unit.getElementAt(offset);
        if (e == null || e instanceof IInitializer || !Locations.named(e)) {
            return children;
        }
        JsonObject node = new JsonObject();
        node.addProperty("name", Locations.name(e));
        node.addProperty("kind", Locations.kind(e));
        node.addProperty("signature", Locations.signature(e));
        node.addProperty("line", text.lineOf(offset));
        node.addProperty("endLine", symbol.getAsJsonObject("range").getAsJsonObject("end").get("line").getAsInt() + 1);
        if (!children.isEmpty()) {
            node.add("children", children);
        }
        JsonArray one = new JsonArray();
        one.add(node);
        return one;
    }

    // --- symbols ---------------------------------------------------------------------------------

    static JsonObject symbols(CodeRequest.Scope s, String query, Set<String> kinds, int limit) throws Exception {
        Targets.Context ctx = new Targets.Context(s);
        SearchPattern pattern = null;
        int[] searchFor = {IJavaSearchConstants.TYPE, IJavaSearchConstants.METHOD, IJavaSearchConstants.CONSTRUCTOR, IJavaSearchConstants.FIELD};
        for (int kind : searchFor) {
            for (SearchPattern p : new SearchPattern[] {
                    SearchPattern.createPattern("*" + query + "*", kind, IJavaSearchConstants.DECLARATIONS, SearchPattern.R_PATTERN_MATCH),
                    SearchPattern.createPattern(query, kind, IJavaSearchConstants.DECLARATIONS, SearchPattern.R_CAMELCASE_MATCH)}) {
                if (p != null) {
                    pattern = pattern == null ? p : SearchPattern.createOrPattern(pattern, p);
                }
            }
        }
        Map<String, IJavaElement> found = new LinkedHashMap<>();
        if (pattern != null) {
            try {
                new SearchEngine().search(pattern, new org.eclipse.jdt.core.search.SearchParticipant[] {SearchEngine.getDefaultSearchParticipant()},
                        ctx.sources(), new SearchRequestor() {
                            @Override
                            public void acceptSearchMatch(SearchMatch match) {
                                if (match.getElement() instanceof IJavaElement e) {
                                    found.putIfAbsent(e.getHandleIdentifier(), e);
                                }
                            }
                        }, ctx.monitor);
            } catch (OperationCanceledException c) {
                throw new DiskSync.Cancelled("running", "searching the worktree's declarations");
            }
        }
        List<JsonObject> symbols = new ArrayList<>();
        for (IJavaElement e : found.values()) {
            if (!(e instanceof IType || e instanceof IMethod || e instanceof IField) || !Locations.named(e) || Locations.library(e, ctx.root)) {
                continue;
            }
            String kind = Locations.kind(e);
            if (kinds != null && !kinds.contains(kind)) {
                continue;
            }
            JsonObject location = Locations.of(e, ctx);
            if (!location.has("file")) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("name", Locations.name(e));
            o.addProperty("kind", kind);
            o.addProperty("signature", Locations.signature(e));
            o.addProperty("module", location.get("module").getAsString());
            o.addProperty("file", location.get("file").getAsString());
            o.addProperty("line", location.get("line").getAsInt());
            o.addProperty("simple", Locations.simple(e));
            symbols.add(o);
        }
        symbols.sort(Comparator.<JsonObject>comparingInt(o -> o.get("simple").getAsString().equalsIgnoreCase(query) ? 0 : 1)
                .thenComparing(o -> o.get("name").getAsString()).thenComparing(o -> o.get("module").getAsString()));
        symbols.forEach(o -> o.remove("simple"));
        JsonObject o = new JsonObject();
        limited(o, "symbols", symbols, limit, false);
        return o;
    }

    // --- describe --------------------------------------------------------------------------------

    static JsonObject describe(CodeRequest.Scope s, LspClient lsp, NavOps.Target target, boolean members) throws Exception {
        Targets.Context ctx = new Targets.Context(s);
        Targets.Target t = resolve(ctx, target);
        IJavaElement e = t.element();
        JsonObject o = new JsonObject();
        o.addProperty("element", Locations.name(e));
        o.addProperty("kind", Locations.kind(e));
        o.addProperty("signature", Locations.signature(e));
        // JDT LS's hover Javadoc, without the hover: it appends a "Source:" line with the Eclipse project's name.
        String markdown = e instanceof IPackageFragment || e instanceof IMember ? JavadocContentAccess2.getMarkdownContent(e) : null;
        String javadoc = markdown == null || markdown.isBlank() ? null : markdown.strip();
        if (javadoc == null) {
            o.add("javadoc", com.google.gson.JsonNull.INSTANCE);
        } else {
            o.addProperty("javadoc", javadoc);
        }
        o.add("location", Locations.of(e, ctx));
        if (members && (e instanceof IType || e instanceof IPackageFragment)) {
            JsonArray list = new JsonArray();
            for (IJavaElement m : e instanceof IType type ? declared(type) : packageTypes((IPackageFragment) e, ctx)) {
                JsonObject member = new JsonObject();
                member.addProperty("name", Locations.name(m));
                member.addProperty("kind", Locations.kind(m));
                member.addProperty("signature", Locations.signature(m));
                list.add(member);
            }
            o.add("members", list);
        }
        return o;
    }

    /** A type's declared members in source order: record components, fields, methods and nested types. */
    private static List<IJavaElement> declared(IType type) throws JavaModelException {
        List<IJavaElement> list = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (IMember m : Targets.members(type)) {
            if (m instanceof IField f && type.isRecord() && f.isRecordComponent() && seen.add(m.getHandleIdentifier())) {
                list.add(m);
            }
        }
        List<IMember> plain = Targets.members(type);
        for (IJavaElement child : type.getChildren()) {
            if (child instanceof IType nested && Locations.named(nested) && seen.add(nested.getHandleIdentifier())) {
                list.add(nested);
            } else if (plain.contains(child) && seen.add(child.getHandleIdentifier())) {
                list.add(child);
            }
        }
        return list;
    }

    /** A package's top-level types in the worktree's sources, by name. */
    private static List<IJavaElement> packageTypes(IPackageFragment p, Targets.Context ctx) throws JavaModelException {
        List<IType> types = new ArrayList<>();
        for (IPackageFragment f : Targets.sourcePackages(p.getElementName(), ctx)) {
            for (ICompilationUnit unit : f.getCompilationUnits()) {
                types.addAll(List.of(unit.getTypes()));
            }
        }
        types.sort(Comparator.comparing(t -> t.getFullyQualifiedName('.')));
        return new ArrayList<>(types);
    }

    // --- shared ----------------------------------------------------------------------------------

    private static Targets.Target resolve(Targets.Context ctx, NavOps.Target target) throws Exception {
        try {
            return Targets.resolve(target.name(), target.anchor(), ctx, true);
        } catch (OperationCanceledException c) {
            throw new DiskSync.Cancelled("running", "resolving " + target.describe());
        }
    }

    /** {@code not-applicable} when {@code wrong}: the operation needs {@code needs}. */
    private static void refuse(Targets.Target t, String operation, String needs, boolean wrong, String more) throws Problem, JavaModelException {
        if (!wrong) {
            return;
        }
        String kind = Locations.kind(t.element());
        String article = kind != null && "aeiou".indexOf(kind.charAt(0)) >= 0 ? "an " : "a ";
        throw Problem.of(Code.NOT_APPLICABLE)
                .detail(operation + " needs " + needs + "; " + Locations.name(t.element()) + " is " + (kind == null ? "something else" : article + kind) + "." + more)
                .with("field", "/target");
    }

    /** {@code textDocument} and {@code position} for the target's offset, in LSP's 0-based lines and UTF-16 columns. */
    private static JsonObject position(Targets.Target t, Targets.Context ctx) {
        Positions text = ctx.text(t.unit());
        JsonObject p = new JsonObject();
        JsonObject doc = new JsonObject();
        doc.addProperty("uri", JDTUtils.toURI(t.unit()));
        p.add("textDocument", doc);
        JsonObject pos = new JsonObject();
        pos.addProperty("line", text.lineOf(t.offset()) - 1);
        pos.addProperty("character", text.columnOf(t.offset()) - 1);
        p.add("position", pos);
        return p;
    }

    /** A request to JDT LS, cancelled at the deadline ({@code timeout}, phase {@code running}). */
    private static JsonElement call(LspClient lsp, String method, JsonElement params, long deadline) throws Exception {
        try {
            return lsp.call(method, params, deadline);
        } catch (LspClient.Expired e) {
            throw new DiskSync.Cancelled("running", e.getMessage());
        } catch (LspClient.Failed e) {
            throw new IllegalStateException(e.getMessage());
        }
    }

    /** An LSP answer's locations: a {@code Location}, an array of them, or of {@code LocationLink}s, as {uri, range}. */
    static List<JsonObject> lspLocations(JsonElement result) {
        List<JsonObject> list = new ArrayList<>();
        if (result == null || result.isJsonNull()) {
            return list;
        }
        for (JsonElement e : result.isJsonArray() ? result.getAsJsonArray() : List.of(result)) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject o = e.getAsJsonObject();
            if (o.has("targetUri")) {
                JsonObject l = new JsonObject();
                l.add("uri", o.get("targetUri"));
                l.add("range", o.has("targetSelectionRange") ? o.get("targetSelectionRange") : o.get("targetRange"));
                list.add(l);
            } else if (o.has("uri")) {
                list.add(o);
            }
        }
        return list;
    }

    /** An LSP location in the caller's terms, or null when it's outside the request's worktree. */
    static JsonObject fromLsp(JsonObject l, Targets.Context ctx) throws JavaModelException {
        String uri = l.get("uri").getAsString();
        JsonObject range = l.getAsJsonObject("range");
        if (uri.startsWith("file:")) {
            Path file = Path.of(URI.create(uri));
            if (!file.startsWith(ctx.root)) {
                Log.info("dropped a location outside the worktree " + ctx.root + ": " + file);
                return null;
            }
            ICompilationUnit unit = unitAt(file, ctx);
            if (unit == null) {
                return null;
            }
            Positions text = ctx.text(unit);
            int start = offset(text, range.getAsJsonObject("start"));
            int end = offset(text, range.getAsJsonObject("end"));
            return Locations.at(unit, start, end, JavaNames.enclosing(unit, start), ctx);
        }
        IClassFile classFile = JDTUtils.resolveClassFile(uri);
        if (classFile == null) {
            return null;
        }
        IJavaElement e = null;
        String source = classFile.getSource();
        if (source != null) {
            e = classFile.getElementAt(offset(new Positions(source), range.getAsJsonObject("start")));
        }
        if (e == null && classFile instanceof IOrdinaryClassFile cf) {
            e = cf.getType();
        }
        if (e == null) {
            return null;
        }
        JsonObject o = new JsonObject();
        o.addProperty("element", Locations.name(e));
        o.addProperty("library", Locations.libraryName(e));
        return o;
    }

    /** The compilation unit of a file of the worktree, in the project it belongs to (not the aggregator above it). */
    private static ICompilationUnit unitAt(Path file, Targets.Context ctx) {
        IProject p = ctx.projectOf(file);
        if (p == null) {
            return null;
        }
        IFile f = p.getFile(IPath.fromOSString(Targets.Context.location(p).relativize(file).toString()));
        return JavaCore.create(f) instanceof ICompilationUnit unit && unit.exists() ? unit : null;
    }

    private static int offset(Positions text, JsonObject position) {
        return text.offsetOf(position.get("line").getAsInt() + 1, position.get("character").getAsInt() + 1);
    }

    /** Accurate matches of a JDT search in the modules the search covers, as locations. */
    private static List<JsonObject> search(SearchPattern pattern, Targets.Context ctx) throws Exception {
        List<JsonObject> found = new ArrayList<>();
        if (pattern == null) {
            return found;
        }
        List<IJavaProject> searched = ctx.searchedJava();
        if (searched.isEmpty()) {
            return found;
        }
        IJavaSearchScope scope = SearchEngine.createJavaSearchScope(searched.toArray(IJavaElement[]::new), IJavaSearchScope.SOURCES);
        List<SearchMatch> matches = new ArrayList<>();
        try {
            new SearchEngine().search(pattern, new org.eclipse.jdt.core.search.SearchParticipant[] {SearchEngine.getDefaultSearchParticipant()}, scope,
                    new SearchRequestor() {
                        @Override
                        public void acceptSearchMatch(SearchMatch match) {
                            if (match.getAccuracy() == SearchMatch.A_ACCURATE) {
                                matches.add(match);
                            }
                        }
                    }, ctx.monitor);
        } catch (OperationCanceledException c) {
            throw new DiskSync.Cancelled("running", "searching for references");
        }
        for (SearchMatch m : matches) {
            if (!(m.getResource() instanceof IFile f) || f.getLocation() == null) {
                continue;
            }
            Path file = Path.of(f.getLocation().toOSString());
            ICompilationUnit unit = unitAt(file, ctx);
            if (unit == null || !ctx.inSearch(file)) {
                continue;
            }
            found.add(Locations.at(unit, m.getOffset(), m.getOffset() + m.getLength(), JavaNames.enclosing(unit, m.getOffset()), ctx));
        }
        return found;
    }

    private static boolean inSearch(JsonObject location, Targets.Context ctx) throws JavaModelException {
        if (!location.has("module") || !location.has("file")) {
            return false;
        }
        Path file = ctx.moduleFolder(location.get("module").getAsString()).resolve(location.get("file").getAsString());
        return ctx.inSearch(file);
    }

    private static void limited(JsonObject o, String field, List<JsonObject> found, int limit) {
        limited(o, field, found, limit, true);
    }

    private static void limited(JsonObject o, String field, List<JsonObject> found, int limit, boolean sort) {
        List<JsonObject> list = new ArrayList<>(found);
        if (sort) {
            list.sort(BY_PLACE);
        }
        JsonArray array = new JsonArray();
        list.stream().limit(limit).forEach(array::add);
        o.add(field, array);
        o.addProperty("total", list.size());
        o.addProperty("truncated", list.size() > limit);
    }

    private static JsonObject range(int l1, int c1, int l2, int c2) {
        JsonObject r = new JsonObject();
        JsonObject s = new JsonObject();
        s.addProperty("line", l1);
        s.addProperty("character", c1);
        JsonObject e = new JsonObject();
        e.addProperty("line", l2);
        e.addProperty("character", c2);
        r.add("start", s);
        r.add("end", e);
        return r;
    }

    private static String str(JsonObject o, String field) {
        return o.has(field) ? o.get(field).getAsString() : "";
    }

    private static int num(JsonObject o, String field) {
        return o.has(field) ? o.get(field).getAsInt() : 0;
    }
}
