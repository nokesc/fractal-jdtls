// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Text anchors (design §11 "Addressing code"): exact matches, line breaks, whitespace candidates and closest lines. */
class TextAnchorsTest {

    static final String GREETER = """
            public class Greeter {

                public String greet(String who) {
                    String clean = Names.normalize(who);
                    int width = 10 - clean.length();
                    return clean;
                }

                public String shout(String who) {
                    return Names.normalize(who).toUpperCase();
                }
            }
            """;

    @Test
    void exactTextOnceTwiceOrNowhere() {
        assertEquals(1, TextAnchors.find(GREETER, "int width = 10 - clean.length();", 0, GREETER.length()).size());
        List<TextAnchors.Match> twice = TextAnchors.find(GREETER, "Names.normalize(who)", 0, GREETER.length());
        assertEquals(2, twice.size());
        assertEquals("Names.normalize(who)", GREETER.substring(twice.get(1).start(), twice.get(1).end()));
        assertEquals(0, TextAnchors.find(GREETER, "String clean=Names.normalize(who);", 0, GREETER.length()).size());
        int shout = GREETER.indexOf("public String shout");
        assertEquals(1, TextAnchors.find(GREETER, "Names.normalize(who)", shout, GREETER.length()).size(), "within a range");
    }

    @Test
    void aLineBreakMatchesLfOrCrLf() {
        String crlf = GREETER.replace("\n", "\r\n");
        String text = "String clean = Names.normalize(who);\n        int width";
        List<TextAnchors.Match> found = TextAnchors.find(crlf, text, 0, crlf.length());
        assertEquals(1, found.size());
        assertTrue(crlf.substring(found.get(0).start(), found.get(0).end()).contains("\r\n"));
        assertEquals(1, TextAnchors.find(GREETER, text.replace("\n", "\r\n"), 0, GREETER.length()).size(), "the text's CRLF matches an LF");
        TextAnchors.Match select = TextAnchors.select(crlf, found.get(0).start(), text, text.indexOf("width"), 5, crlf.length());
        assertEquals("width", crlf.substring(select.start(), select.end()), "select maps through the CRLF");
    }

    @Test
    void otherWhitespaceGivesTheFilesExactText() {
        List<TextAnchors.Match> other = TextAnchors.withOtherWhitespace(GREETER, "String clean=Names.normalize( who );", 0, GREETER.length());
        assertEquals(1, other.size());
        assertEquals("String clean = Names.normalize(who);", GREETER.substring(other.get(0).start(), other.get(0).end()));
    }

    @Test
    void closestLinesShareTheLongestRun() {
        Positions p = new Positions(GREETER);
        List<TextAnchors.Near> near = TextAnchors.closest(p, "int width = 20 - clean.length();", 1, p.lines(), 3);
        assertEquals(5, near.get(0).line());
        assertEquals(List.of(), TextAnchors.closest(p, "zzzzzzzzzzzzzzzz", 1, p.lines(), 3));
    }

    @Test
    void overlappingOccurrencesAllCount() {
        // T002.06's review, R6: a.a occurs twice in a.a.a, at 0 and 2; skipping the first match's length missed the second.
        assertEquals(List.of(new TextAnchors.Match(0, 3), new TextAnchors.Match(2, 5)), TextAnchors.find("a.a.a", "a.a", 0, 5));
        assertEquals(2, TextAnchors.withOtherWhitespace("a . a . a", "a.a", 0, 9).size());
    }

    @Test
    void aWithinIsOfferedOnlyIfTheTextOccursOnceInItsWholeRange() {
        // T002.06's review, R4: in class Foo { Foo() {} }, Foo's range holds both occurrences; only Foo#Foo() tells them apart.
        String source = "class Foo { Foo() {} }";
        java.util.Map<String, TextAnchors.Match> ranges = new java.util.LinkedHashMap<>();
        ranges.put("Foo", new TextAnchors.Match(0, source.length()));
        ranges.put("Foo#Foo()", new TextAnchors.Match(12, 20));
        assertEquals(List.of("Foo#Foo()"), TextAnchors.onceWithin(source, "Foo", ranges));
    }

    @Test
    void indexesOfSelect() {
        assertEquals(List.of(4), TextAnchors.indexes("int width = 10", "width"));
        assertEquals(2, TextAnchors.indexes("clean + clean", "clean").size());
    }
}
