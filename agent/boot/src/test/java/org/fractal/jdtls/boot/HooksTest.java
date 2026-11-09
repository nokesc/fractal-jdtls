// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** When a hook that isn't in place is reported (design §6). */
class HooksTest {

    @TempDir
    Path tmp;

    @BeforeEach
    void register() {
        Hooks.reset();
        Hooks.register("getProjectName", "m2e.ProjectConfigurationManager", "getProjectName(…)");
        Hooks.register("populateClasspath", "m2e.DefaultClasspathManagerDelegate", "populateClasspath(…)");
        Hooks.register("initParent", "maven.DefaultProjectBuilder", "initParent(…)");
    }

    @AfterEach
    void reset() {
        Hooks.reset();
        System.clearProperty(DiagnosticLog.PROPERTY);
    }

    @Test
    void aHookNotInPlaceIsReportedAtOnce() {
        Path log = Fakes.diagnosticLog(tmp);
        String err = Fakes.stderr(() -> Hooks.notInPlace("initParent", "its matcher selects no method of X"));
        assertTrue(err.startsWith("fractal-jdtls agent: ERROR: hook initParent (maven.DefaultProjectBuilder.initParent(…))"
                + " is not in place: its matcher selects no method of X."), err);
        assertTrue(Fakes.lines(log).get(0).contains("\"event\":\"hook-not-in-place\",\"hook\":\"initParent\""));
        assertEquals("not-in-place", Hooks.states().get("initParent"));
    }

    @Test
    void theCheckpointReportsEveryClassNotYetLoadedOnce() {
        Path log = Fakes.diagnosticLog(tmp);
        Hooks.inPlace("getProjectName", "loader");
        Hooks.inPlace("populateClasspath", "loader");
        String err = Fakes.stderr(() -> {
            Hooks.checkpoint();
            Hooks.checkpoint();
        });
        List<String> reports = err.lines().toList();
        assertEquals(1, reports.size(), err);
        assertTrue(reports.get(0).contains("hook initParent (maven.DefaultProjectBuilder.initParent(…)) is not in place:"
                + " class maven.DefaultProjectBuilder never loaded by the checkpoint"), err);
        assertEquals(Map.of("getProjectName", "in-place", "populateClasspath", "in-place", "initParent", "never-loaded"),
                Hooks.states());
        List<String> lines = Fakes.lines(log);
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"event\":\"checkpoint\",\"hooks\":{\"getProjectName\":"
                + "\"in-place\",\"populateClasspath\":\"in-place\",\"initParent\":\"never-loaded\"}")),
                String.join("\n", lines));
    }

    @Test
    void theCheckpointReportsNothingWhenEveryHookIsInPlace() {
        Hooks.inPlace("getProjectName", "loader");
        Hooks.inPlace("populateClasspath", "loader");
        Hooks.inPlace("initParent", "loader");
        assertEquals("", Fakes.stderr(Hooks::checkpoint));
        assertTrue(Hooks.checkpointPassed());
    }

    @Test
    void aHookAlreadyReportedIsNotReportedAgainAtTheCheckpoint() {
        Fakes.stderr(() -> Hooks.notInPlace("initParent", "its matcher selects 2 methods of X, not one"));
        Hooks.inPlace("getProjectName", "loader");
        Hooks.inPlace("populateClasspath", "loader");
        Hooks.inPlace("initParent", "another loader"); // a second copy of the class can't undo the report
        assertEquals("", Fakes.stderr(Hooks::checkpoint));
        assertEquals("not-in-place", Hooks.states().get("initParent"));
    }

    @Test
    void atShutdownWithoutPopulateClasspathTheMissingHooksAreReported() {
        Hooks.inPlace("getProjectName", "loader");
        Hooks.mavenProjectRead();
        String err = Fakes.stderr(Hooks::shutdown);
        assertEquals(2, err.lines().count(), err);
        assertTrue(err.contains("hook populateClasspath (m2e.DefaultClasspathManagerDelegate.populateClasspath(…)) is not"
                + " in place: class m2e.DefaultClasspathManagerDelegate never loaded by shutdown"), err);
        assertTrue(err.contains("hook initParent"), err);
    }

    @Test
    void atShutdownNothingIsReportedWhenNoHookWasInPlace() {
        assertEquals("", Fakes.stderr(Hooks::shutdown), "a workspace with no Maven projects reports nothing");
    }

    /**
     * T002.03 found it: with no Maven project, m2e's resolution classes and Maven's requester classes still load at
     * startup, and their hooks are in place, but nothing reads a Maven project, so populateClasspath's class never
     * loads. That's a workspace with no Maven projects, which reports nothing (design §6).
     */
    @Test
    void atShutdownNothingIsReportedWhenHooksAreInPlaceButNoMavenProjectWasRead() {
        Hooks.inPlace("getProjectName", "loader");
        Hooks.inPlace("initParent", "loader");
        assertEquals("", Fakes.stderr(Hooks::shutdown));
        assertEquals("pending", Hooks.states().get("populateClasspath"));
    }

    @Test
    void aRequesterHookThatRanCountsAsAMavenProjectRead() {
        Hooks.inPlace("initParent", "loader");
        Boot.requesterExit(Boot.initParentEnter(new Fakes.MavenProject(tmp.resolve("pom.xml").toFile())));
        String err = Fakes.stderr(Hooks::shutdown);
        assertTrue(err.contains("hook populateClasspath") && err.contains("never loaded by shutdown"), err);
    }

    @Test
    void atShutdownNothingIsReportedWhenPopulateClasspathWasAdvised() {
        Hooks.inPlace("populateClasspath", "loader");
        Hooks.mavenProjectRead();
        assertEquals("", Fakes.stderr(Hooks::shutdown), "advised but never called: the checkpoint's job, not shutdown's");
    }

    @Test
    void atShutdownNothingMoreIsReportedAfterTheCheckpoint() {
        Hooks.inPlace("getProjectName", "loader");
        Hooks.mavenProjectRead();
        Hooks.inPlace("populateClasspath", "loader");
        Fakes.stderr(Hooks::checkpoint);
        assertEquals("", Fakes.stderr(Hooks::shutdown));
    }
}
