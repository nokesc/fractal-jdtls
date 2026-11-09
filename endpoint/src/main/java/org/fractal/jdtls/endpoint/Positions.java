// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import java.util.Arrays;

/**
 * Lines and columns in a source file's text, for problems (design §11 "Builds and problems"): 1-based lines, and
 * 1-based columns counted in characters, from the character offsets JDT and m2e put on their markers. A line ends at
 * {@code \n}, {@code \r\n} or {@code \r}, as JDT counts them. An end column is the column just after the last
 * character, so a problem on {@code WorkType} at column 12 ends at column 20.
 */
final class Positions {

    private final String text;
    private final int[] starts;

    Positions(String text) {
        this.text = text;
        int[] s = new int[16];
        int n = 0;
        s[n++] = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                if (n == s.length) {
                    s = Arrays.copyOf(s, n * 2);
                }
                s[n++] = i + 1;
            }
        }
        this.starts = Arrays.copyOf(s, n);
    }

    int lines() {
        return starts.length;
    }

    /** The 1-based line holding the offset (an offset past the end is on the last line). */
    int lineOf(int offset) {
        int i = Arrays.binarySearch(starts, Math.max(0, Math.min(offset, text.length())));
        return i >= 0 ? i + 1 : -i - 1;
    }

    /** The 1-based column of the offset on its line. */
    int columnOf(int offset) {
        int line = lineOf(offset);
        return Math.max(0, Math.min(offset, text.length())) - starts[line - 1] + 1;
    }

    /** The offset of a 1-based line and column, clamped to the text: the inverse of {@link #lineOf} and {@link #columnOf}. */
    int offsetOf(int line, int column) {
        int l = Math.max(1, Math.min(line, starts.length));
        return Math.max(0, Math.min(starts[l - 1] + column - 1, text.length()));
    }

    String text() {
        return text;
    }

    /** The text of a 1-based line, without its line terminator, or null if there's no such line. */
    String line(int line) {
        if (line < 1 || line > starts.length) {
            return null;
        }
        int end = line < starts.length ? starts[line] : text.length();
        while (end > starts[line - 1] && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
            end--;
        }
        return text.substring(starts[line - 1], end);
    }
}
