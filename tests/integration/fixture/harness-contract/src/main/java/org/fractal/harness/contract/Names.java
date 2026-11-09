// SPDX-License-Identifier: Apache-2.0
package org.fractal.harness.contract;

/** Names for executions and their parts. */
public final class Names {

    /** How many names {@link #normalize(String)} has made. */
    public static int count;

    private final int width;

    /** Makes names padded to {@code width} characters. */
    public Names(int width) {
        this.width = width;
    }

    /** The name of a number. */
    public static String describe(int value) {
        return "#" + value;
    }

    /** The name of a string, normalized. */
    public static String describe(String value) {
        return normalize(value);
    }

    /** The name of an execution. */
    public static String describe(ExecutionId id) {
        return describe(id.value());
    }

    /** The names of several strings, joined with commas. */
    public static String describe(String... values) {
        return String.join(",", values);
    }

    /** A name without surrounding whitespace, in lower case. */
    public static String normalize(String raw) {
        count++;
        return raw.strip().toLowerCase(java.util.Locale.ROOT);
    }

    /** {@code name}, padded with spaces to this maker's width. */
    public String pad(String name) {
        return name + " ".repeat(Math.max(0, width - name.length()));
    }

    /** A label inside a name. */
    public static final class Inner {

        /** The label's text. */
        public String label() {
            return "inner";
        }
    }
}
