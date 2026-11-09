// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Run by {@link UnifiedDiffTest} in its own JVM with a small heap (T002.07's review, R3): a rename that changes every
 * one of {@code args[0]} lines, applied by {@link EditPlan} as JDT LS's text edits, then diffed. Prints the time, and
 * exits 0 only when the diff has every line removed and added once.
 */
public final class UnifiedDiffProbe {

    private UnifiedDiffProbe() {}

    public static void main(String[] args) throws Exception {
        int lines = Integer.parseInt(args[0]);
        StringBuilder b = new StringBuilder();
        List<EditPlan.Edit> edits = new ArrayList<>();
        String prefix = "        total += Names.";
        for (int i = 0; i < lines; i++) {
            b.append(prefix).append("normalize(values[").append(i).append("]).length();\n");
            edits.add(new EditPlan.Edit(i, prefix.length(), i, prefix.length() + "normalize".length(), "canonical"));
        }
        String before = b.toString();
        long start = System.nanoTime();
        String after = EditPlan.edit(before, edits, Path.of("/w/m/Big.java"));
        long edited = System.nanoTime();
        String diff = UnifiedDiff.diff("m/Big.java", "m/Big.java", before, after);
        long done = System.nanoTime();
        long removed = diff.lines().filter(l -> l.startsWith("-") && !l.startsWith("---")).count();
        long added = diff.lines().filter(l -> l.startsWith("+") && !l.startsWith("+++")).count();
        System.out.printf(java.util.Locale.ROOT, "%d lines changed: edit %.1f ms, diff %.1f ms, %d removed, %d added, %d chars of diff, max heap %d MB%n", lines,
                (edited - start) / 1e6, (done - edited) / 1e6, removed, added, diff.length(), Runtime.getRuntime().maxMemory() >> 20);
        System.exit(removed == lines && added == lines ? 0 : 2);
    }
}
