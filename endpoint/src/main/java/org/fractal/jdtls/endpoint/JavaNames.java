// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IImportContainer;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IInitializer;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageDeclaration;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeParameter;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;

/**
 * The contract's fully qualified {@code JavaName} of a Java element (design §11 "Addressing code"): a package
 * {@code com.acme}, a type {@code com.acme.Foo} or {@code com.acme.Foo.Bar}, a field {@code com.acme.Foo#count}, a
 * method {@code com.acme.Foo#bar(int,java.lang.String)}, a constructor {@code com.acme.Foo#Foo(int)}. Parameter types
 * are erased and fully qualified; varargs end in {@code ...}. Anonymous and local types, lambdas and local variables
 * have no name: the enclosing named declaration stands for them.
 */
final class JavaNames {

    private JavaNames() {}

    /** The declaration enclosing the offset in the compilation unit, or null when there's none. */
    static String enclosing(ICompilationUnit unit, int offset) throws JavaModelException {
        IJavaElement e = offset >= 0 ? unit.getElementAt(offset) : null;
        if (e == null || e instanceof IImportDeclaration || e instanceof IImportContainer) {
            IType primary = unit.findPrimaryType();
            if (primary == null) {
                IType[] types = unit.getTypes();
                primary = types.length > 0 ? types[0] : null;
            }
            return primary != null ? name(primary) : packageName(unit);
        }
        if (e instanceof IPackageDeclaration p) {
            return p.getElementName();
        }
        return name(e);
    }

    private static String packageName(ICompilationUnit unit) throws JavaModelException {
        IPackageDeclaration[] p = unit.getPackageDeclarations();
        return p.length > 0 ? p[0].getElementName() : null;
    }

    /** The element's name, or its nearest named enclosing declaration's. */
    static String name(IJavaElement element) throws JavaModelException {
        IJavaElement e = element;
        while (e != null && !named(e)) {
            e = e.getParent();
        }
        if (e == null) {
            return null;
        }
        if (e instanceof IType t) {
            return t.getFullyQualifiedName('.');
        }
        if (e instanceof IInitializer i) {
            return name(i.getDeclaringType());
        }
        IMember m = (IMember) e;
        String owner = name(m.getDeclaringType());
        if (m instanceof IField) {
            return owner + "#" + m.getElementName();
        }
        IMethod method = (IMethod) m;
        String member = method.isConstructor() ? method.getDeclaringType().getElementName() : method.getElementName();
        String[] params = method.getParameterTypes();
        StringBuilder sb = new StringBuilder(owner).append('#').append(member).append('(');
        boolean varargs = Flags.isVarargs(method.getFlags());
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            String type = parameter(method, params[i]);
            if (varargs && i == params.length - 1 && type.endsWith("[]")) {
                type = type.substring(0, type.length() - 2) + "...";
            }
            sb.append(type);
        }
        return sb.append(')').toString();
    }

    /** Whether the element has a JavaName: a named, non-local type, a field, a method or an initializer's type. */
    private static boolean named(IJavaElement e) throws JavaModelException {
        if (e instanceof IType t) {
            return !t.isAnonymous() && !t.isLocal() && !t.isLambda();
        }
        if (e instanceof IField || e instanceof IInitializer) {
            return named(((IMember) e).getDeclaringType());
        }
        if (e instanceof IMethod m) {
            return !m.isLambdaMethod() && named(m.getDeclaringType());
        }
        return false;
    }

    /** A parameter's type signature, erased and fully qualified where the source names a type. */
    static String parameter(IMethod method, String signature) throws JavaModelException {
        String erased = Signature.getTypeErasure(signature);
        int dims = Signature.getArrayCount(erased);
        String element = Signature.getElementType(erased);
        String type;
        if (element.length() == 1) { // a primitive
            type = Signature.toString(element);
        } else if (element.charAt(0) == Signature.C_RESOLVED) {
            type = Signature.toString(element).replace('$', '.');
        } else {
            type = resolve(method, Signature.toString(element), new java.util.HashSet<>());
        }
        return type + "[]".repeat(dims);
    }

    /**
     * A source type name, resolved in the method's declaring type; a type variable is its first bound's erasure. A
     * cycle of bounds, which only broken source has ({@code <T extends T>}, {@code <A extends B, B extends A>}), is
     * {@code java.lang.Object}, the erasure javac gives it (T002.05's review, R4).
     */
    private static String resolve(IMethod method, String simple, java.util.Set<String> seen) throws JavaModelException {
        for (ITypeParameter p : method.getTypeParameters()) {
            if (p.getElementName().equals(simple)) {
                return bound(method, p, seen);
            }
        }
        for (IType t = method.getDeclaringType(); t != null; t = t.getDeclaringType()) {
            for (ITypeParameter p : t.getTypeParameters()) {
                if (p.getElementName().equals(simple)) {
                    return bound(method, p, seen);
                }
            }
        }
        String[][] found = method.getDeclaringType().resolveType(simple);
        if (found == null || found.length != 1) {
            return simple;
        }
        return found[0][0].isEmpty() ? found[0][1] : found[0][0] + "." + found[0][1];
    }

    private static String bound(IMethod method, ITypeParameter p, java.util.Set<String> seen) throws JavaModelException {
        String[] bounds = p.getBounds();
        if (bounds.length == 0 || !seen.add(p.getElementName())) {
            return "java.lang.Object";
        }
        String first = bounds[0];
        int lt = first.indexOf('<');
        return resolve(method, lt < 0 ? first.trim() : first.substring(0, lt).trim(), seen);
    }
}
