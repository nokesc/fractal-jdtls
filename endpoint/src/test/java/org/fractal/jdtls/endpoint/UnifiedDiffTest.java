// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Unified diffs as {@code diff -u} and Git write them. */
class UnifiedDiffTest {

    static String numbered(int from, int to) {
        StringBuilder b = new StringBuilder();
        for (int i = from; i <= to; i++) {
            b.append("line ").append(i).append('\n');
        }
        return b.toString();
    }

    /** Where the class was loaded from: a folder or a jar. */
    static String location(Class<?> c) throws Exception {
        return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    @Test
    void aRenameOfTwelveThousandLinesDiffsInA32MegabyteHeap() throws Exception {
        // T002.07's review, R3: the diff's memory is linear in the files' size. The edit-distance trace it replaced
        // needed about 4.6 GB for this, and exhausted a 64 MB heap at 2,000 lines.
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = String.join(File.pathSeparator, location(UnifiedDiff.class), location(UnifiedDiffProbe.class),
                location(com.google.gson.JsonObject.class));
        Process p = new ProcessBuilder(java, "-Xmx32m", "-cp", classpath, UnifiedDiffProbe.class.getName(), "12000").redirectErrorStream(true).start();
        boolean exited = p.waitFor(300, TimeUnit.SECONDS);
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!exited) {
            p.destroyForcibly();
        }
        System.out.println("UnifiedDiffProbe: " + out.strip());
        assertTrue(exited, "the probe finished within 300 s: " + out);
        assertEquals(0, p.exitValue(), out);
        assertTrue(out.contains("12000 removed, 12000 added"), out);
    }

    @Test
    void oneChangeWithThreeLinesOfContext() {
        String before = numbered(1, 10);
        String after = before.replace("line 5\n", "line five\n");
        assertEquals("--- a/m/A.java\n+++ b/m/A.java\n@@ -2,7 +2,7 @@\n line 2\n line 3\n line 4\n-line 5\n+line five\n line 6\n line 7\n line 8\n",
                UnifiedDiff.diff("m/A.java", "m/A.java", before, after));
    }

    @Test
    void changesSixLinesApartShareAHunkAndEightApartDont() {
        String before = numbered(1, 30);
        String near = before.replace("line 5\n", "x\n").replace("line 12\n", "y\n");
        assertEquals(1, UnifiedDiff.diff("f", "f", before, near).split("\n@@").length - 1);
        String far = before.replace("line 5\n", "x\n").replace("line 14\n", "y\n");
        assertEquals(2, UnifiedDiff.diff("f", "f", before, far).split("\n@@").length - 1);
    }

    @Test
    void createdDeletedAndRenamedFiles() {
        assertEquals("--- /dev/null\n+++ b/f\n@@ -0,0 +1,2 @@\n+a\n+b\n", UnifiedDiff.diff("f", "f", null, "a\nb\n"));
        assertEquals("--- a/f\n+++ /dev/null\n@@ -1 +0,0 @@\n-a\n", UnifiedDiff.diff("f", "f", "a\n", null));
        assertEquals("--- a/a/A.java\n+++ b/c/A.java\n@@ -1 +1 @@\n-package a;\n+package c;\n",
                UnifiedDiff.diff("a/A.java", "c/A.java", "package a;\n", "package c;\n"));
        assertEquals("", UnifiedDiff.diff("f", "f", "same\n", "same\n"));
    }

    @Test
    void insertionsAtTheTopAndMissingNewlines() {
        assertEquals("--- a/f\n+++ b/f\n@@ -0,0 +1 @@\n+top\n", UnifiedDiff.diff("f", "f", "", "top\n"));
        assertEquals("--- a/f\n+++ b/f\n@@ -1 +1 @@\n-a\n\\ No newline at end of file\n+a\n", UnifiedDiff.diff("f", "f", "a", "a\n"));
        assertEquals("--- a/f\n+++ b/f\n@@ -1 +1 @@\n-a\r\n+a\n", UnifiedDiff.diff("f", "f", "a\r\n", "a\n"), "a CRLF that became LF shows");
    }

    @Test
    void theScriptRebuildsBothSides() {
        // And it's a shortest one, with every index where it belongs (T002.07's review, R3: the linear-space search).
        Random random = new Random(7);
        for (int round = 0; round < 2000; round++) {
            int size = round < 200 ? 12 : 60;
            List<String> a = new ArrayList<>();
            List<String> b = new ArrayList<>();
            for (int i = random.nextInt(size); i > 0; i--) {
                a.add(String.valueOf(random.nextInt(4)));
            }
            for (int i = random.nextInt(size); i > 0; i--) {
                b.add(String.valueOf(random.nextInt(4)));
            }
            List<String> rebuiltA = new ArrayList<>();
            List<String> rebuiltB = new ArrayList<>();
            int kept = 0;
            int previous = 0;
            for (int[] op : UnifiedDiff.script(a, b)) {
                assertEquals(List.of(rebuiltA.size(), rebuiltB.size()), List.of(op[1], op[2]), a + " -> " + b + ": the indexes");
                if (op[0] != 2) {
                    rebuiltA.add(a.get(op[1]));
                }
                if (op[0] != 1) {
                    rebuiltB.add(b.get(op[2]));
                }
                kept += op[0] == 0 ? 1 : 0;
                assertTrue(op[0] != 1 || previous != 2, a + " -> " + b + ": a run's removed lines come before its added ones");
                previous = op[0];
            }
            assertEquals(a, rebuiltA, a + " -> " + b);
            assertEquals(b, rebuiltB, a + " -> " + b);
            assertEquals(lcs(a, b), kept, a + " -> " + b + ": as few changes as there can be");
        }
    }

    /** The longest common subsequence's length, the plain quadratic way. */
    static int lcs(List<String> a, List<String> b) {
        int[][] t = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                t[i][j] = a.get(i).equals(b.get(j)) ? t[i + 1][j + 1] + 1 : Math.max(t[i + 1][j], t[i][j + 1]);
            }
        }
        return t[0][0];
    }

    @Test
    void aChangedRunShowsItsRemovedLinesBeforeItsAddedOnes() {
        // The inline of the refactoring story, which the linear-space search finds interleaved.
        String before = "    String greet() {\n        String padding = pad(clean);\n        return GREETING + clean + padding;\n    }\n";
        String after = "    String greet() {\n        return GREETING + clean + pad(clean);\n    }\n";
        assertEquals("--- a/m/A.java\n+++ b/m/A.java\n@@ -1,4 +1,3 @@\n     String greet() {\n-        String padding = pad(clean);\n"
                + "-        return GREETING + clean + padding;\n+        return GREETING + clean + pad(clean);\n     }\n",
                UnifiedDiff.diff("m/A.java", "m/A.java", before, after));
    }

    @Test
    void theCheckStopsALongDiff() {
        // The endpoint's check throws at the request's deadline; the diff stops there.
        String before = numbered(1, 3000);
        String after = before.replace("line", "row");
        int[] calls = {0};
        IllegalStateException stopped = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> UnifiedDiff.diff("m/A.java", "m/A.java", before, after, () -> {
                    if (++calls[0] == 50) {
                        throw new IllegalStateException("deadline");
                    }
                }));
        assertEquals("deadline", stopped.getMessage());
        assertEquals(50, calls[0]);
    }
}
