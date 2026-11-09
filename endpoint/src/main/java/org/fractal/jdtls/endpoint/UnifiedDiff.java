// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A unified diff of one file (design §11 "Refactorings and edits"), as {@code diff -u} and Git write it: 3 lines of
 * context, {@code --- a/<path>} and {@code +++ b/<path>} ({@code /dev/null} for a created or deleted file), and
 * {@code \ No newline at end of file} where a side's last line has none. Lines are compared whole, their terminators
 * included, so a line whose {@code \r\n} became {@code \n} shows as changed. Plain Java.
 *
 * <p>Myers' O(ND) algorithm in its linear-space form (T002.07's review, R3): the middle snake of each part, found from
 * both ends, splits it in two, so the memory is linear in the files' size, whatever the number of changed lines. The
 * lines are compared as numbers, one per distinct line. {@code check} runs once per step of the search, and throws to
 * stop it: the endpoint passes one that stops at the request's deadline.
 */
final class UnifiedDiff {

    static final int CONTEXT = 3;

    private UnifiedDiff() {}

    private static final Runnable NO_CHECK = () -> {};

    /** The diff, or the empty string when the texts are equal; a null text is a file that doesn't exist. */
    static String diff(String oldPath, String newPath, String before, String after) {
        return diff(oldPath, newPath, before, after, NO_CHECK);
    }

    /** {@link #diff(String, String, String, String)}, running {@code check} as it goes. */
    static String diff(String oldPath, String newPath, String before, String after, Runnable check) {
        List<String> a = lines(before == null ? "" : before);
        List<String> b = lines(after == null ? "" : after);
        if (a.equals(b) && (before == null) == (after == null)) {
            return oldPath.equals(newPath) ? "" : "--- a/" + oldPath + "\n+++ b/" + newPath + "\n";
        }
        List<int[]> script = script(a, b, check); // each: {kind, aIndex, bIndex}; kind 0 same, 1 delete, 2 insert
        StringBuilder out = new StringBuilder();
        out.append("--- ").append(before == null ? "/dev/null" : "a/" + oldPath).append('\n');
        out.append("+++ ").append(after == null ? "/dev/null" : "b/" + newPath).append('\n');
        List<Integer> changed = new ArrayList<>();
        for (int i = 0; i < script.size(); i++) {
            if (script.get(i)[0] != 0) {
                changed.add(i);
            }
        }
        int h = 0;
        while (h < changed.size()) {
            int first = changed.get(h);
            int last = first;
            int j = h + 1;
            // One hunk while at most 2 * CONTEXT unchanged lines separate two changes, as diff -u does.
            while (j < changed.size() && changed.get(j) - last - 1 <= 2 * CONTEXT) {
                last = changed.get(j++);
            }
            hunk(out, script.subList(Math.max(0, first - CONTEXT), Math.min(script.size(), last + CONTEXT + 1)), a, b);
            h = j;
        }
        return out.toString();
    }

    private static void hunk(StringBuilder out, List<int[]> ops, List<String> a, List<String> b) {
        int aStart = -1;
        int bStart = -1;
        int aCount = 0;
        int bCount = 0;
        for (int[] op : ops) {
            if (op[0] != 2) {
                if (aStart < 0) {
                    aStart = op[1];
                }
                aCount++;
            }
            if (op[0] != 1) {
                if (bStart < 0) {
                    bStart = op[2];
                }
                bCount++;
            }
        }
        int[] first = ops.get(0);
        int aBefore = first[1]; // an empty side's number is the line before the hunk, as diff -u writes it
        int bBefore = first[2];
        out.append("@@ -").append(range(aCount == 0 ? aBefore : aStart + 1, aCount)).append(" +")
                .append(range(bCount == 0 ? bBefore : bStart + 1, bCount)).append(" @@\n");
        for (int[] op : ops) {
            String line = op[0] == 2 ? b.get(op[2]) : a.get(op[1]);
            out.append(op[0] == 0 ? ' ' : op[0] == 1 ? '-' : '+');
            if (line.endsWith("\n")) {
                out.append(line);
            } else {
                out.append(line).append("\n\\ No newline at end of file\n");
            }
        }
    }

    private static String range(int start, int count) {
        return count == 1 ? String.valueOf(start) : start + "," + count;
    }

    /** The lines, each with its {@code \n} (a {@code \r} before it stays part of the line). */
    static List<String> lines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    /**
     * The edit script from {@code a} to {@code b}, in order: each entry {kind, index in a, index in b}, kind 0 for a
     * line both have, 1 for a line deleted from {@code a}, 2 for one inserted from {@code b}. The indexes of an
     * insertion's {@code a} and a deletion's {@code b} are where they'd be, for empty ranges' line numbers. It has the
     * fewest insertions and deletions there are.
     */
    static List<int[]> script(List<String> a, List<String> b) {
        return script(a, b, NO_CHECK);
    }

    static List<int[]> script(List<String> a, List<String> b, Runnable check) {
        Map<String, Integer> ids = new HashMap<>();
        int[] x = new int[a.size()];
        int[] y = new int[b.size()];
        for (int i = 0; i < x.length; i++) {
            x[i] = ids.computeIfAbsent(a.get(i), k -> ids.size());
        }
        for (int j = 0; j < y.length; j++) {
            y[j] = ids.computeIfAbsent(b.get(j), k -> ids.size());
        }
        Myers m = new Myers(x, y, check);
        m.diff(0, x.length, 0, y.length);
        return grouped(m.script);
    }

    /**
     * Each run of changed lines as {@code diff -u} and Git print it: its removed lines, then its added ones. The search
     * may interleave them; the script is as short either way.
     */
    private static List<int[]> grouped(List<int[]> script) {
        List<int[]> out = new ArrayList<>(script.size());
        int i = 0;
        while (i < script.size()) {
            if (script.get(i)[0] == 0) {
                out.add(script.get(i++));
                continue;
            }
            int aStart = script.get(i)[1];
            int bStart = script.get(i)[2];
            int removed = 0;
            int added = 0;
            for (; i < script.size() && script.get(i)[0] != 0; i++) {
                if (script.get(i)[0] == 1) {
                    removed++;
                } else {
                    added++;
                }
            }
            for (int k = 0; k < removed; k++) {
                out.add(new int[] {1, aStart + k, bStart});
            }
            for (int k = 0; k < added; k++) {
                out.add(new int[] {2, aStart + removed, bStart + k});
            }
        }
        return out;
    }

    /** Myers' linear-space search: two diagonal arrays, reused by every part. */
    private static final class Myers {
        final int[] a;
        final int[] b;
        final Runnable check;
        final int offset;
        final int[] forward;
        final int[] backward;
        final List<int[]> script = new ArrayList<>();

        Myers(int[] a, int[] b, Runnable check) {
            this.a = a;
            this.b = b;
            this.check = check;
            int max = (a.length + b.length + 1) / 2 + 1;
            offset = max + 1;
            forward = new int[2 * max + 3];
            backward = new int[2 * max + 3];
        }

        /** The script of a[aLo..aHi) to b[bLo..bHi), appended in order. */
        void diff(int aLo, int aHi, int bLo, int bHi) {
            while (aLo < aHi && bLo < bHi && a[aLo] == b[bLo]) {
                script.add(new int[] {0, aLo++, bLo++});
            }
            int aEnd = aHi;
            int bEnd = bHi;
            while (aLo < aEnd && bLo < bEnd && a[aEnd - 1] == b[bEnd - 1]) {
                aEnd--;
                bEnd--;
            }
            if (aLo == aEnd) {
                for (int j = bLo; j < bEnd; j++) {
                    script.add(new int[] {2, aLo, j});
                }
            } else if (bLo == bEnd) {
                for (int i = aLo; i < aEnd; i++) {
                    script.add(new int[] {1, i, bLo});
                }
            } else {
                int[] snake = middle(aLo, aEnd, bLo, bEnd);
                diff(aLo, snake[0], bLo, snake[1]);
                for (int i = snake[0], j = snake[1]; i < snake[2]; i++, j++) {
                    script.add(new int[] {0, i, j});
                }
                diff(snake[2], aEnd, snake[3], bEnd);
            }
            for (int i = aEnd, j = bEnd; i < aHi; i++, j++) {
                script.add(new int[] {0, i, j});
            }
        }

        /**
         * The middle snake of a[aLo..aHi) and b[bLo..bHi), both non-empty: {x, y, u, v}, the diagonal run from (x, y) to
         * (u, v) that the middle of a shortest script follows, found by searching from both ends until they overlap.
         */
        int[] middle(int aLo, int aHi, int bLo, int bHi) {
            int n = aHi - aLo;
            int m = bHi - bLo;
            int delta = n - m;
            boolean odd = (delta & 1) != 0;
            int max = (n + m + 1) / 2;
            forward[offset + 1] = 0;
            backward[offset + 1] = 0;
            for (int d = 0; d <= max; d++) {
                check.run();
                for (int k = -d; k <= d; k += 2) {
                    int x = k == -d || k != d && forward[offset + k - 1] < forward[offset + k + 1] ? forward[offset + k + 1] : forward[offset + k - 1] + 1;
                    int y = x - k;
                    int x0 = x;
                    int y0 = y;
                    while (x < n && y < m && a[aLo + x] == b[bLo + y]) {
                        x++;
                        y++;
                    }
                    forward[offset + k] = x;
                    int c = delta - k; // the same diagonal, as the backward search numbers it
                    if (odd && c >= -(d - 1) && c <= d - 1 && x + backward[offset + c] >= n) {
                        return new int[] {aLo + x0, bLo + y0, aLo + x, bLo + y};
                    }
                }
                for (int k = -d; k <= d; k += 2) {
                    int x = k == -d || k != d && backward[offset + k - 1] < backward[offset + k + 1] ? backward[offset + k + 1] : backward[offset + k - 1] + 1;
                    int y = x - k;
                    int x0 = x;
                    int y0 = y;
                    while (x < n && y < m && a[aHi - 1 - x] == b[bHi - 1 - y]) {
                        x++;
                        y++;
                    }
                    backward[offset + k] = x;
                    int c = delta - k;
                    if (!odd && c >= -d && c <= d && x + forward[offset + c] >= n) {
                        return new int[] {aHi - x, bHi - y, aHi - x0, bHi - y0};
                    }
                }
            }
            throw new IllegalStateException("no middle snake: a defect in the diff");
        }
    }
}
