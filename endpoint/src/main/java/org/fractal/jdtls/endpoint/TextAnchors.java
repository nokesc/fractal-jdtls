// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Finding a text anchor's {@code text} in a file's source (design §11 "Addressing code"), without Eclipse: exact,
 * character for character, except that a line break in {@code text} ({@code \n} or {@code \r\n}) matches either in
 * the file. For a text that occurs nowhere, the occurrences with different whitespace and the closest lines; for one
 * that occurs more than once, every occurrence. Offsets are in characters (UTF-16 units), as JDT counts them.
 */
final class TextAnchors {

    /** Where a match is in the source: {@code [start, end)}. */
    record Match(int start, int end) {}

    /** A source line close to the text that wasn't found: its 1-based number and text. */
    record Near(int line, String source) {}

    private TextAnchors() {}

    /**
     * Every match of {@code text} in {@code source} inside {@code [from, to)}, in order, overlapping ones too:
     * {@code a.a} occurs twice in {@code a.a.a}, and the two can be different accesses (T002.06's review, R6).
     */
    static List<Match> find(String source, String text, int from, int to) {
        String t = text.replace("\r\n", "\n");
        List<Match> found = new ArrayList<>();
        for (int i = from; i < to; i++) {
            int end = matchAt(source, i, t, t.length(), to);
            if (end >= 0) {
                found.add(new Match(i, end));
            }
        }
        return found;
    }

    /**
     * The end of the match of {@code t[0, length)} at {@code pos}, or -1: each {@code \n} of {@code t} matches
     * {@code \r\n} or {@code \n} in the source, every other character itself.
     */
    static int matchAt(String source, int pos, String t, int length, int limit) {
        int j = pos;
        for (int k = 0; k < length; k++) {
            char c = t.charAt(k);
            if (c == '\n') {
                if (j + 1 < limit && source.charAt(j) == '\r' && source.charAt(j + 1) == '\n') {
                    j += 2;
                    continue;
                }
                if (j < limit && source.charAt(j) == '\n') {
                    j++;
                    continue;
                }
                return -1;
            }
            if (j >= limit || source.charAt(j) != c) {
                return -1;
            }
            j++;
        }
        return j;
    }

    /**
     * The declarations, of {@code ranges} (a Java name to its whole source range), in whose range {@code text} occurs
     * exactly once: the only {@code within} values that make an anchor unique.
     */
    static List<String> onceWithin(String source, String text, java.util.Map<String, Match> ranges) {
        List<String> once = new ArrayList<>();
        ranges.forEach((name, r) -> {
            if (find(source, text, r.start(), r.end()).size() == 1) {
                once.add(name);
            }
        });
        return once;
    }

    /** The source offsets of {@code select} inside the match at {@code start}; {@code at} is its index in the text. */
    static Match select(String source, int start, String text, int at, int length, int limit) {
        String t = text.replace("\r\n", "\n");
        int s = matchAt(source, start, t, at, limit);
        int e = matchAt(source, start, t, at + length, limit);
        return new Match(s, e);
    }

    /** Where {@code part} occurs in {@code text}, line breaks matched as in the file: every index. */
    static List<Integer> indexes(String text, String part) {
        String t = text.replace("\r\n", "\n");
        String p = part.replace("\r\n", "\n");
        List<Integer> at = new ArrayList<>();
        for (int i = t.indexOf(p); i >= 0; i = t.indexOf(p, i + 1)) {
            at.add(i);
        }
        return at;
    }

    /**
     * The occurrences of {@code text} with different whitespace: the same characters other than whitespace, in the
     * same order, with any whitespace (or none) between them. Each is the file's exact text there.
     */
    static List<Match> withOtherWhitespace(String source, String text, int from, int to) {
        StringBuilder stripped = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                stripped.append(text.charAt(i));
            }
        }
        if (stripped.isEmpty()) {
            return List.of();
        }
        StringBuilder squeezed = new StringBuilder();
        List<Integer> origin = new ArrayList<>();
        for (int i = from; i < to; i++) {
            if (!Character.isWhitespace(source.charAt(i))) {
                squeezed.append(source.charAt(i));
                origin.add(i);
            }
        }
        List<Match> found = new ArrayList<>();
        String needle = stripped.toString();
        for (int i = squeezed.indexOf(needle); i >= 0; i = squeezed.indexOf(needle, i + 1)) {
            found.add(new Match(origin.get(i), origin.get(i + needle.length() - 1) + 1));
        }
        return found;
    }

    /**
     * Up to {@code max} lines of {@code [from, to)} most like the text's first non-blank line: those sharing the
     * longest common run of characters with it, at least half its length, best first, then by line.
     */
    static List<Near> closest(Positions positions, String text, int fromLine, int toLine, int max) {
        String first = text.replace("\r\n", "\n").lines().map(String::strip).filter(l -> !l.isEmpty()).findFirst().orElse("");
        if (first.length() < 2) {
            return List.of();
        }
        record Scored(int line, String source, int score) {}
        List<Scored> scored = new ArrayList<>();
        for (int line = fromLine; line <= toLine; line++) {
            String source = positions.line(line);
            if (source == null || source.isBlank()) {
                continue;
            }
            int score = commonRun(first, source.strip());
            if (score * 2 >= first.length()) {
                scored.add(new Scored(line, source, score));
            }
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed().thenComparingInt(Scored::line));
        return scored.stream().limit(max).map(s -> new Near(s.line(), s.source())).toList();
    }

    /** The length of the longest run of characters that both strings contain. */
    static int commonRun(String a, String b) {
        int best = 0;
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                current[j] = a.charAt(i - 1) == b.charAt(j - 1) ? previous[j - 1] + 1 : 0;
                best = Math.max(best, current[j]);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return best;
    }
}
