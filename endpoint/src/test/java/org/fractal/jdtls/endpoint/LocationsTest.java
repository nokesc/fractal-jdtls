// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Declarations as Java source without their bodies ({@link Locations#declaration}). */
class LocationsTest {

    private static final char[] FIELD = {'=', ';', ','};
    private static final char[] METHOD = {'{', ';'};

    @Test
    void genericTypesKeepTheirCommas() {
        // T002.06's review, R5: the comma inside Map<String, Integer> isn't the end of the field.
        assertEquals("public Map<String, Integer> entries", Locations.declaration("public Map<String, Integer> entries;", 0, FIELD));
        assertEquals("Map<String, List<Map<Integer, Long>>> deep", Locations.declaration("Map<String, List<Map<Integer, Long>>> deep = null;", 0, FIELD));
        assertEquals("int a", Locations.declaration("int a, b;", 0, FIELD), "a multi-field declaration still ends at its comma");
        assertEquals("public <K, V extends Comparable<V>> Map<K, V> index(List<V> values)",
                Locations.declaration("public <K, V extends Comparable<V>> Map<K, V> index(List<V> values) {\n return null;\n}", 0, METHOD));
    }

    @Test
    void annotationsCommentsAndStrings() {
        assertEquals("@Named(value = \"a<b\") String s", Locations.declaration("@Named(value = \"a<b\") String s = \"x\";", 0, FIELD));
        assertEquals("@Size(max = 1 < 2 ? 3 : 4) int n", Locations.declaration("@Size(max = 1 < 2 ? 3 : 4) int n;", 0, FIELD),
                "a < inside an annotation's parentheses isn't a type argument");
        assertEquals("public String greet(String who)", Locations.declaration("public String greet(String who) // hello\n {", 0, METHOD));
    }
}
