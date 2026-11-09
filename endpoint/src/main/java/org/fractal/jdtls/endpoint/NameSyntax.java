// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import java.util.ArrayList;
import java.util.List;

/**
 * The syntax of a {@code JavaName} (design §11 "Addressing code"), parsed without Eclipse: a type
 * {@code com.acme.Foo} or {@code com.acme.Foo.Bar} ({@code com.acme.Foo$Bar} too), a member {@code com.acme.Foo#bar},
 * {@code com.acme.Foo#bar(int,java.lang.String)} or {@code com.acme.Foo#Foo(int)}, a field {@code com.acme.Foo#count},
 * or a package {@code com.acme}. Whitespace in the parameter list is ignored, type arguments are dropped
 * ({@code List<String>} is {@code List}), and varargs {@code String...} are {@code String[]}. What the name refers to
 * is {@code Targets}' business; this only checks the form, and says where it breaks.
 */
final class NameSyntax {

    /** A parameter type as written: a simple or qualified name (with {@code $} as {@code .}), and its array depth. */
    record Param(String type, int dims) {
        boolean qualified() {
            return type.indexOf('.') >= 0;
        }

        String simple() {
            return type.substring(type.lastIndexOf('.') + 1);
        }

        /**
         * Whether a parameter's erased, fully qualified type (without its array part) is this one: a qualified name only
         * that type, never one whose name merely ends the same way (T002.06's review, R2: {@code a.Date} isn't
         * {@code b.a.Date}); a simple name the last segment, so {@code Date} can match two types. A type JDT couldn't
         * resolve stays simple, and matches its simple name.
         */
        boolean matches(String actual) {
            return qualified() ? actual.equals(type) : actual.equals(type) || actual.endsWith("." + type);
        }

        @Override
        public String toString() {
            return type + "[]".repeat(dims);
        }
    }

    /**
     * A parsed name: {@code segments} of the type or package ({@code $} split like {@code .}); {@code member} after
     * {@code #}, or null; {@code params}, or null when the name has no parentheses.
     */
    record Name(String text, List<String> segments, String member, List<Param> params) {
        String qualified() {
            return String.join(".", segments);
        }

        /** The member part as the caller wrote it, normalized: {@code describe(int,java.lang.String[])}. */
        String memberText() {
            if (member == null) {
                return null;
            }
            if (params == null) {
                return member;
            }
            return member + "(" + String.join(",", params.stream().map(Param::toString).toList()) + ")";
        }
    }

    static final class Invalid extends Exception {
        private static final long serialVersionUID = 1L;

        Invalid(String message) {
            super(message, null, false, false);
        }
    }

    private NameSyntax() {}

    static Name parse(String text) throws Invalid {
        String quoted = "'" + HttpApi.printable(text) + "'";
        if (text.isBlank()) {
            throw new Invalid("The name is empty.");
        }
        String s = text.strip();
        int hash = s.indexOf('#');
        String owner = hash < 0 ? s : s.substring(0, hash);
        if (hash >= 0 && s.indexOf('#', hash + 1) >= 0) {
            throw new Invalid(quoted + " has more than one #: a name is a type, then # and one member.");
        }
        if (hash < 0 && (s.indexOf('(') >= 0 || s.indexOf(')') >= 0)) {
            throw new Invalid(quoted + " has parameter types but no member: write the type, # and the method, such as com.acme.Foo#bar(int).");
        }
        List<String> segments = new ArrayList<>();
        for (String part : owner.split("[.$]", -1)) {
            if (part.isEmpty()) {
                throw new Invalid(quoted + " has an empty name segment: each part between dots must be a Java identifier.");
            }
            if (!identifier(part)) {
                throw new Invalid(quoted + ": '" + HttpApi.printable(part) + "' isn't a Java identifier.");
            }
            segments.add(part);
        }
        if (hash < 0) {
            return new Name(text, List.copyOf(segments), null, null);
        }
        String rest = s.substring(hash + 1);
        int open = rest.indexOf('(');
        String member = (open < 0 ? rest : rest.substring(0, open)).strip();
        if (member.isEmpty()) {
            throw new Invalid(quoted + " has no member after #.");
        }
        if (!identifier(member)) {
            throw new Invalid(quoted + ": the member '" + HttpApi.printable(member) + "' isn't a Java identifier.");
        }
        if (open < 0) {
            if (rest.indexOf(')') >= 0) {
                throw new Invalid(quoted + " has a ) but no (.");
            }
            return new Name(text, List.copyOf(segments), member, null);
        }
        if (!rest.endsWith(")")) {
            throw new Invalid(rest.indexOf(')') < 0 ? quoted + " has no closing parenthesis." : quoted + " has text after its closing parenthesis.");
        }
        String inside = rest.substring(open + 1, rest.length() - 1);
        List<Param> params = new ArrayList<>();
        if (!inside.isBlank()) {
            for (String p : split(inside, quoted)) {
                params.add(param(p, quoted));
            }
        }
        return new Name(text, List.copyOf(segments), member, List.copyOf(params));
    }

    /** The parameter list split at the commas outside type arguments. */
    private static List<String> split(String inside, String quoted) throws Invalid {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < inside.length(); i++) {
            char c = inside.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                if (--depth < 0) {
                    throw new Invalid(quoted + " has a > without its <.");
                }
            } else if (c == '(' || c == ')') {
                throw new Invalid(quoted + " has a parenthesis inside its parameter list.");
            } else if (c == ',' && depth == 0) {
                parts.add(inside.substring(start, i));
                start = i + 1;
            }
        }
        if (depth != 0) {
            throw new Invalid(quoted + " has a < without its >.");
        }
        parts.add(inside.substring(start));
        return parts;
    }

    private static Param param(String written, String quoted) throws Invalid {
        StringBuilder b = new StringBuilder();
        int depth = 0;
        for (char c : written.toCharArray()) {
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (depth == 0 && !Character.isWhitespace(c)) {
                b.append(c);
            }
        }
        String p = b.toString();
        if (p.isEmpty()) {
            throw new Invalid(quoted + " has an empty parameter type: parameter types are separated by single commas.");
        }
        int dims = 0;
        if (p.endsWith("...")) {
            dims = 1;
            p = p.substring(0, p.length() - 3);
        }
        while (p.endsWith("[]")) {
            dims++;
            p = p.substring(0, p.length() - 2);
        }
        if (p.contains("...")) {
            throw new Invalid(quoted + ": only the last part of a parameter type can be ..., as in String....");
        }
        List<String> parts = new ArrayList<>();
        for (String part : p.split("[.$]", -1)) {
            if (part.isEmpty() || !identifier(part)) {
                throw new Invalid(quoted + ": the parameter type '" + HttpApi.printable(written.strip()) + "' isn't a Java type name"
                        + " (write it as int, String, java.lang.String, String[] or String...).");
            }
            parts.add(part);
        }
        return new Param(String.join(".", parts), dims);
    }

    static boolean identifier(String s) {
        if (s.isEmpty() || !Character.isJavaIdentifierStart(s.codePointAt(0))) {
            return false;
        }
        for (int i = Character.charCount(s.codePointAt(0)); i < s.length(); i += Character.charCount(s.codePointAt(i))) {
            if (!Character.isJavaIdentifierPart(s.codePointAt(i))) {
                return false;
            }
        }
        return true;
    }
}
