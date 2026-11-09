// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import org.eclipse.core.resources.IResource;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IInitializer;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.ILocalVariable;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.ISourceReference;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeParameter;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;

/**
 * Java elements in the caller's terms (design §11 "Navigation and search"): their {@code ElementKind}, their
 * {@code JavaName} ({@link JavaNames}), their declaration as Java source without its body, and where they are, as the
 * contract's {@code Location}: a module, a file relative to it, 1-based lines and columns ({@link Positions}) and the
 * source line, or, for a library or JDK element, the jar's path or {@code jrt:/<module>}.
 */
final class Locations {

    static final int SOURCE_MAX = 500;

    private Locations() {}

    // --- kinds and names -------------------------------------------------------------------------

    static String kind(IJavaElement e) throws JavaModelException {
        if (e instanceof IPackageFragment) {
            return "package";
        }
        if (e instanceof IType t) {
            return t.isAnnotation() ? "annotation" : t.isEnum() ? "enum" : t.isRecord() ? "record" : t.isInterface() ? "interface" : "class";
        }
        if (e instanceof IMethod m) {
            return m.isConstructor() ? "constructor" : "method";
        }
        if (e instanceof IField f) {
            return f.isEnumConstant() ? "enum-constant" : "field";
        }
        if (e instanceof ILocalVariable v) {
            return v.isParameter() ? "parameter" : "local-variable";
        }
        if (e instanceof ITypeParameter) {
            return "type-parameter";
        }
        if (e instanceof IInitializer) {
            return "initializer";
        }
        return null;
    }

    /** The element's {@code JavaName}: a package's name, or the nearest named declaration's ({@link JavaNames#name}). */
    static String name(IJavaElement e) throws JavaModelException {
        if (e instanceof IPackageFragment p) {
            return p.getElementName();
        }
        return JavaNames.name(e);
    }

    /** Whether the element has a {@code JavaName} of its own: a package, a named type, or a member of one. */
    static boolean named(IJavaElement e) throws JavaModelException {
        if (e instanceof IPackageFragment) {
            return true;
        }
        if (e instanceof IType t) {
            return !t.isAnonymous() && !t.isLocal() && !t.isLambda();
        }
        if (e instanceof IMethod m && m.isLambdaMethod()) {
            return false;
        }
        if (e instanceof IField || e instanceof IMethod) {
            return named(((IMember) e).getDeclaringType());
        }
        return false;
    }

    /** The simple name for an {@code about}: a member's name, or a type's simple name. */
    static String simple(IJavaElement e) {
        return e.getElementName();
    }

    // --- libraries -------------------------------------------------------------------------------

    /** Whether the element comes from the classpath (a jar or the JDK), not the worktree's sources. */
    static boolean library(IJavaElement e, Path root) {
        IPackageFragmentRoot r = (IPackageFragmentRoot) e.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
        if (r == null) {
            return false;
        }
        try {
            if (r.getKind() == IPackageFragmentRoot.K_BINARY) {
                return true;
            }
        } catch (JavaModelException ex) {
            return true;
        }
        IResource resource = r.getResource();
        return resource == null || resource.getLocation() == null || !Path.of(resource.getLocation().toOSString()).startsWith(root);
    }

    /** A library element's jar, or {@code jrt:/<module>} for the JDK's modules. */
    static String libraryName(IJavaElement e) {
        IPackageFragmentRoot r = (IPackageFragmentRoot) e.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
        if (r == null) {
            return "unknown";
        }
        String last = r.getPath().lastSegment();
        if ("jrt-fs.jar".equals(last)) {
            return "jrt:/" + r.getElementName();
        }
        return r.getPath().toOSString();
    }

    // --- locations -------------------------------------------------------------------------------

    /** Where the element is declared: its name in its file, or its library. */
    static JsonObject of(IJavaElement e, Targets.Context ctx) throws JavaModelException {
        String name = name(e);
        if (library(e, ctx.root)) {
            JsonObject o = new JsonObject();
            o.addProperty("element", name);
            o.addProperty("library", libraryName(e));
            return o;
        }
        ICompilationUnit unit = (ICompilationUnit) e.getAncestor(IJavaElement.COMPILATION_UNIT);
        if (unit == null || !(e instanceof ISourceReference ref)) {
            JsonObject o = new JsonObject();
            o.addProperty("element", name);
            if (e instanceof IPackageFragment p && p.getResource() != null && p.getResource().getLocation() != null) {
                Path folder = Path.of(p.getResource().getLocation().toOSString());
                String module = ctx.moduleOf(folder);
                if (module != null) {
                    o.addProperty("module", module);
                }
            }
            return o;
        }
        ISourceRange range = ref.getNameRange();
        if (range == null || range.getOffset() < 0) {
            range = ref.getSourceRange();
        }
        int start = range == null ? -1 : range.getOffset();
        int end = range == null ? -1 : range.getOffset() + range.getLength();
        return at(unit, start, end, name, ctx);
    }

    /** A range of a compilation unit in the worktree, with {@code element} as its Java name. */
    static JsonObject at(ICompilationUnit unit, int start, int end, String element, Targets.Context ctx) {
        JsonObject o = new JsonObject();
        o.addProperty("element", element);
        Path file = ctx.path(unit);
        if (file == null) {
            return o;
        }
        String module = ctx.moduleOf(file);
        if (module != null) {
            o.addProperty("module", module);
            o.addProperty("file", ctx.moduleFolder(module).relativize(file).toString());
        }
        Positions text = ctx.text(unit);
        if (text == null || start < 0) {
            return o;
        }
        int line = text.lineOf(start);
        o.addProperty("line", line);
        o.addProperty("column", text.columnOf(start));
        if (end >= start) {
            o.addProperty("endLine", text.lineOf(end));
            o.addProperty("endColumn", text.columnOf(end));
        }
        String source = text.line(line);
        if (source != null) {
            o.addProperty("source", source.length() > SOURCE_MAX ? source.substring(0, SOURCE_MAX) + "…" : source);
        }
        return o;
    }

    // --- signatures ------------------------------------------------------------------------------

    /** The declaration as Java source without its body: from the source when there is one, else from the model. */
    static String signature(IJavaElement e) throws JavaModelException {
        if (e instanceof IPackageFragment p) {
            return "package " + p.getElementName();
        }
        // Library elements from the model always, so a JDK or jar with attached source answers as one without.
        if (e instanceof ISourceReference ref && e.getAncestor(IJavaElement.COMPILATION_UNIT) != null) {
            String s = sourceSignature(e, ref);
            if (s != null && !s.isEmpty()) {
                return s;
            }
        }
        return modelSignature(e);
    }

    /** The declaration's source, from after its Javadoc to its body, comments dropped and whitespace collapsed. */
    static String sourceSignature(IJavaElement e, ISourceReference ref) throws JavaModelException {
        String source = ref.getSource();
        ISourceRange range = ref.getSourceRange();
        if (source == null || range == null || range.getOffset() < 0) {
            return null;
        }
        int from = 0;
        if (e instanceof IMember m && m.getJavadocRange() != null) {
            ISourceRange doc = m.getJavadocRange();
            from = Math.max(0, Math.min(source.length(), doc.getOffset() + doc.getLength() - range.getOffset()));
        }
        char[] stops = e instanceof IType ? new char[] {'{'}
                : e instanceof IMethod ? new char[] {'{', ';'}
                : e instanceof IField f && f.isEnumConstant() ? new char[] {'(', '{', ',', ';'}
                : e instanceof IField || e instanceof ILocalVariable ? new char[] {'=', ';', ','}
                : new char[] {'{', ';'};
        return declaration(source, from, stops);
    }

    /**
     * The text of {@code source} from {@code from} up to the first of {@code stops} outside parentheses, brackets,
     * type arguments, strings and comments, with comments dropped and runs of whitespace made one space. A {@code <}
     * outside parentheses can only open type arguments in a declaration, which ends before any initializer; inside
     * them (an annotation's arguments) it's left alone (T002.06's review, R5: {@code Map<String, Integer> entries}).
     */
    static String declaration(String source, int from, char[] stops) {
        StringBuilder b = new StringBuilder();
        int depth = 0;
        int angles = 0;
        int i = from;
        boolean space = false;
        scan:
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                int nl = source.indexOf('\n', i);
                i = nl < 0 ? source.length() : nl;
                space = true;
                continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                int close = source.indexOf("*/", i + 2);
                i = close < 0 ? source.length() : close + 2;
                space = true;
                continue;
            }
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < source.length() && source.charAt(j) != c) {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                append(b, source.substring(i, Math.min(source.length(), j + 1)), space);
                space = false;
                i = j + 1;
                continue;
            }
            if (depth == 0 && angles == 0) {
                for (char stop : stops) {
                    if (c == stop) {
                        break scan;
                    }
                }
            }
            if (c == '(' || c == '[') {
                depth++;
            } else if ((c == ')' || c == ']') && depth > 0) {
                depth--;
            } else if (c == '<' && depth == 0) {
                angles++;
            } else if (c == '>' && depth == 0 && angles > 0) {
                angles--;
            }
            if (Character.isWhitespace(c)) {
                space = true;
            } else {
                append(b, String.valueOf(c), space);
                space = false;
            }
            i++;
        }
        return b.toString().strip();
    }

    private static void append(StringBuilder b, String s, boolean space) {
        if (space && !b.isEmpty()) {
            b.append(' ');
        }
        b.append(s);
    }

    /** The declaration from JDT's model, for a library element without source. */
    static String modelSignature(IJavaElement e) throws JavaModelException {
        if (e instanceof IType t) {
            int keep = Flags.AccPublic | Flags.AccPrivate | Flags.AccProtected | Flags.AccStatic | Flags.AccFinal | Flags.AccAbstract;
            int flags = t.getFlags() & keep;
            String keyword = t.isAnnotation() ? "@interface" : t.isEnum() ? "enum" : t.isRecord() ? "record" : t.isInterface() ? "interface" : "class";
            if (t.isInterface() || t.isAnnotation()) {
                flags &= ~Flags.AccAbstract;
            }
            if (t.isEnum() || t.isRecord()) {
                flags &= ~Flags.AccFinal;
            }
            StringBuilder b = new StringBuilder(modifiers(flags)).append(keyword).append(' ').append(t.getElementName());
            typeParameters(b, t.getTypeParameters());
            return b.toString();
        }
        if (e instanceof IMethod m) {
            int keep = Flags.AccPublic | Flags.AccPrivate | Flags.AccProtected | Flags.AccStatic | Flags.AccFinal | Flags.AccSynchronized
                    | Flags.AccNative | Flags.AccAbstract | Flags.AccStrictfp;
            StringBuilder b = new StringBuilder(modifiers(m.getFlags() & keep));
            if (Flags.isDefaultMethod(m.getFlags())) {
                b.append("default ");
            }
            if (m.getTypeParameters().length > 0) {
                typeParameters(b, m.getTypeParameters());
                b.append(' ');
            }
            if (!m.isConstructor()) {
                b.append(Signature.getSignatureSimpleName(m.getReturnType())).append(' ');
            }
            b.append(m.isConstructor() ? m.getDeclaringType().getElementName() : m.getElementName()).append('(');
            String[] types = m.getParameterTypes();
            String[] names;
            try {
                names = m.getParameterNames();
            } catch (JavaModelException ex) {
                names = null;
            }
            boolean varargs = Flags.isVarargs(m.getFlags());
            for (int i = 0; i < types.length; i++) {
                if (i > 0) {
                    b.append(", ");
                }
                String type = Signature.getSignatureSimpleName(types[i]);
                if (varargs && i == types.length - 1 && type.endsWith("[]")) {
                    type = type.substring(0, type.length() - 2) + "...";
                }
                b.append(type);
                if (names != null && i < names.length) {
                    b.append(' ').append(names[i]);
                }
            }
            return b.append(')').toString();
        }
        if (e instanceof IField f) {
            if (f.isEnumConstant()) {
                return f.getElementName();
            }
            int keep = Flags.AccPublic | Flags.AccPrivate | Flags.AccProtected | Flags.AccStatic | Flags.AccFinal | Flags.AccVolatile
                    | Flags.AccTransient;
            return modifiers(f.getFlags() & keep) + Signature.getSignatureSimpleName(f.getTypeSignature()) + " " + f.getElementName();
        }
        if (e instanceof ILocalVariable v) {
            return Signature.getSignatureSimpleName(v.getTypeSignature()) + " " + v.getElementName();
        }
        return e.getElementName();
    }

    private static String modifiers(int flags) {
        String s = Flags.toString(flags);
        return s.isEmpty() ? "" : s + " ";
    }

    private static void typeParameters(StringBuilder b, ITypeParameter[] params) throws JavaModelException {
        if (params.length == 0) {
            return;
        }
        b.append('<');
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                b.append(", ");
            }
            b.append(params[i].getElementName());
            String[] bounds = params[i].getBounds();
            if (bounds.length > 0) {
                b.append(" extends ").append(String.join(" & ", bounds));
            }
        }
        b.append('>');
    }
}
