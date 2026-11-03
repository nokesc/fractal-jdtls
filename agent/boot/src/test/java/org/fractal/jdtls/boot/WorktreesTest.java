// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Worktree detection and prefix collisions (design §6). */
class WorktreesTest {

    @TempDir
    Path tmp;

    @BeforeEach
    @AfterEach
    void reset() {
        Worktrees.reset();
        System.clearProperty(DiagnosticLog.PROPERTY);
    }

    @Test
    void aGitFolderMarksAMainCheckout() {
        Path main = Fakes.mainCheckout(tmp.resolve("main"));
        assertEquals(main, Worktrees.root(Fakes.pom(main, "harness-app")));
    }

    @Test
    void aGitFileMarksALinkedWorktree() {
        Path wt = Fakes.linkedWorktree(tmp.resolve("wt-a"));
        assertEquals(wt, Worktrees.root(Fakes.pom(wt, "harness-app")));
    }

    @Test
    void noGitAncestorIsNoWorktree() {
        Path outside = tmp.resolve("outside");
        Fakes.assertNoGitAbove(tmp);
        assertNull(Worktrees.root(Fakes.pom(outside, "harness-app")));
        assertNull(Worktrees.root(null));
    }

    @Test
    void theNearestAncestorWins() {
        Path main = Fakes.mainCheckout(tmp.resolve("main"));
        Path nested = Fakes.linkedWorktree(main.resolve("nested"));
        assertEquals(nested, Worktrees.root(Fakes.pom(nested, "harness-app")));
        assertEquals(main, Worktrees.root(Fakes.pom(main, "harness-app")));
    }

    @Test
    void theRootItselfAndARelativePathResolve() {
        Path wt = Fakes.linkedWorktree(tmp.resolve("wt-a"));
        assertEquals(wt, Worktrees.root(Fakes.pom(wt, ".")));
        assertEquals(wt, Worktrees.root(wt.resolve("a/../harness-app/pom.xml").toFile()));
    }

    @Test
    void thePrefixIsTheFolderName() {
        assertEquals("wt-a", Worktrees.prefix(Fakes.linkedWorktree(tmp.resolve("wt-a"))));
        assertNull(Worktrees.prefix(null));
    }

    @Test
    void aPrefixCollisionIsReportedOncePerWorktree() {
        Path log = Fakes.diagnosticLog(tmp);
        Path first = Fakes.linkedWorktree(tmp.resolve("x/wt-a"));
        Path second = Fakes.linkedWorktree(tmp.resolve("y/wt-a"));
        Path third = Fakes.linkedWorktree(tmp.resolve("z/wt-a"));
        Path other = Fakes.linkedWorktree(tmp.resolve("x/wt-b"));

        String err = Fakes.stderr(() -> {
            for (int i = 0; i < 3; i++) {
                Worktrees.prefix(first);
                Worktrees.prefix(second);
                Worktrees.prefix(other);
            }
        });
        List<String> reports = err.lines().filter(l -> l.contains("same folder name")).toList();
        assertEquals(1, reports.size(), err);
        assertTrue(reports.get(0).startsWith("fractal-jdtls agent: ERROR: two worktrees in this JDT LS process have"
                + " the same folder name 'wt-a': " + first + " and " + second), reports.get(0));

        String again = Fakes.stderr(() -> Worktrees.prefix(third));
        assertTrue(again.contains(first + " and " + third), again);

        List<String> events = Fakes.lines(log).stream().filter(l -> l.contains("\"event\":\"prefix-collision\"")).toList();
        assertEquals(2, events.size(), String.join("\n", Fakes.lines(log)));
        assertTrue(events.get(0).contains("\"prefix\":\"wt-a\",\"worktrees\":[\"" + first + "\",\"" + second + "\"]"),
                events.get(0));
    }
}
