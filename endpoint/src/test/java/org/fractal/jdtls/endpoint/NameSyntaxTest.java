// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The {@code JavaName} syntax (design §11 "Addressing code"), and where an invalid name breaks. */
class NameSyntaxTest {

    private static NameSyntax.Name parse(String s) throws NameSyntax.Invalid {
        return NameSyntax.parse(s);
    }

    @Test
    void typesNestedTypesAndPackages() throws Exception {
        assertEquals(List.of("com", "acme", "Foo"), parse("com.acme.Foo").segments());
        assertNull(parse("com.acme.Foo").member());
        assertEquals(List.of("com", "acme", "Foo", "Bar"), parse("com.acme.Foo.Bar").segments());
        assertEquals(List.of("com", "acme", "Foo", "Bar"), parse("com.acme.Foo$Bar").segments(), "$ nests like .");
        assertEquals("com.acme", parse("com.acme").qualified());
    }

    @Test
    void membersWithAndWithoutParameters() throws Exception {
        NameSyntax.Name bare = parse("com.acme.Foo#bar");
        assertEquals("bar", bare.member());
        assertNull(bare.params(), "no parentheses: any overload");
        assertEquals(List.of(), parse("com.acme.Foo#bar()").params(), "() means no parameters");
        NameSyntax.Name two = parse("com.acme.Foo#bar( int , java.lang.String )");
        assertEquals(List.of(new NameSyntax.Param("int", 0), new NameSyntax.Param("java.lang.String", 0)), two.params(), "whitespace is ignored");
        assertEquals("bar(int,java.lang.String)", two.memberText());
        assertEquals("Foo", parse("com.acme.Foo#Foo(int)").member(), "a constructor is the type's simple name");
        assertEquals("count", parse("com.acme.Foo#count").member());
        assertEquals(List.of("org", "fractal", "Names", "Inner"), parse("org.fractal.Names$Inner#label()").segments());
    }

    @Test
    void arraysVarargsTypeArgumentsAndNestedParameterTypes() throws Exception {
        assertEquals(List.of(new NameSyntax.Param("String", 1)), parse("a.B#c(String...)").params(), "varargs are an array");
        assertEquals(parse("a.B#c(String...)").params(), parse("a.B#c(String[])").params());
        assertEquals(List.of(new NameSyntax.Param("int", 2)), parse("a.B#c(int[][])").params());
        assertEquals(List.of(new NameSyntax.Param("java.util.List", 0), new NameSyntax.Param("java.util.Map", 0)),
                parse("a.B#c(java.util.List<String>, java.util.Map<String, List<Integer>>)").params(), "type arguments are ignored, commas in them too");
        assertEquals(List.of(new NameSyntax.Param("java.util.Map.Entry", 0)), parse("a.B#c(java.util.Map$Entry)").params());
        assertTrue(parse("a.B#c(Map.Entry)").params().get(0).qualified());
        assertEquals("Entry", parse("a.B#c(Map.Entry)").params().get(0).simple());
    }

    @Test
    void aQualifiedParameterTypeMatchesOnlyThatType() {
        // T002.06's review, R2: a.Date isn't b.a.Date, so the fully qualified candidate of either overload resolves.
        assertFalse(new NameSyntax.Param("a.Date", 0).matches("b.a.Date"));
        assertTrue(new NameSyntax.Param("a.Date", 0).matches("a.Date"));
        assertFalse(new NameSyntax.Param("Map.Entry", 0).matches("java.util.Map.Entry"), "partly qualified is qualified: no suffix match");
        assertTrue(new NameSyntax.Param("Date", 0).matches("java.util.Date"), "a simple name matches the last segment");
        assertTrue(new NameSyntax.Param("Entry", 0).matches("java.util.Map.Entry"));
        assertTrue(new NameSyntax.Param("Missing", 0).matches("Missing"), "a type JDT can't resolve stays simple");
        assertFalse(new NameSyntax.Param("Date", 0).matches("java.util.Update"));
    }

    @Test
    void invalidNamesSayWhereTheyBreak() {
        assertBreaks("org.fractal.harness.contract.Names#describe(int", "has no closing parenthesis");
        assertBreaks("a.B#c(int)x", "text after its closing parenthesis");
        assertBreaks("a..B", "empty name segment");
        assertBreaks("a.B#", "no member after #");
        assertBreaks("a.B#c#d", "more than one #");
        assertBreaks("a.B(int)", "parameter types but no member");
        assertBreaks("a.1B", "'1B' isn't a Java identifier");
        assertBreaks("a.B#c(int,)", "empty parameter type");
        assertBreaks("a.B#c(List<String)", "a < without its >");
        assertBreaks("a.B#c(Str ing.x-y)", "isn't a Java type name");
        assertBreaks("  ", "empty");
    }

    private static void assertBreaks(String name, String where) {
        NameSyntax.Invalid e = assertThrows(NameSyntax.Invalid.class, () -> NameSyntax.parse(name), name);
        assertTrue(e.getMessage().contains(where), name + ": " + e.getMessage());
    }
}
