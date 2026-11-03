// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.m2e.core.embedder.ArtifactKey;
import org.fractal.jdtls.boot.Fakes.Context;
import org.fractal.jdtls.boot.Fakes.Facade;
import org.fractal.jdtls.boot.Fakes.IFile;
import org.fractal.jdtls.boot.Fakes.MavenProject;
import org.fractal.jdtls.boot.Fakes.Model;
import org.fractal.jdtls.boot.Fakes.Registry;
import org.fractal.jdtls.boot.Fakes.State;
import org.fractal.jdtls.boot.Fakes.SubRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Naming, requester selection and the resolution rule (design §6, FINDINGS "Result" 1 and 2). */
class BootTest {

    private static final String G = "org.fractal";
    private static final String CONTRACT = "harness-contract";
    private static final String V = "0.1.0-SNAPSHOT";
    private static final ArtifactKey KEY = new ArtifactKey(G, CONTRACT, V, null);

    @TempDir
    Path tmp;

    Path main;
    Path wtA;
    Path wtB;
    Path outside;
    IFile mainContract;
    IFile aContract;
    IFile bContract;
    State state;

    @BeforeEach
    void fixture() {
        Worktrees.reset();
        main = Fakes.mainCheckout(tmp.resolve("main"));
        wtA = Fakes.linkedWorktree(tmp.resolve("wt-a"));
        wtB = Fakes.linkedWorktree(tmp.resolve("wt-b"));
        outside = tmp.resolve("outside");
        mainContract = new IFile(Fakes.pom(main, CONTRACT));
        aContract = new IFile(Fakes.pom(wtA, CONTRACT));
        bContract = new IFile(Fakes.pom(wtB, CONTRACT));
        Map<ArtifactKey, Collection<IFile>> artifacts = new LinkedHashMap<>();
        artifacts.put(KEY, List.of(mainContract, aContract, bContract));
        artifacts.put(new ArtifactKey(G, CONTRACT, V, "tests"), List.of(new IFile(Fakes.pom(main, "other"))));
        state = new State(artifacts);
    }

    @AfterEach
    void reset() {
        Worktrees.reset();
        System.clearProperty(DiagnosticLog.PROPERTY);
        Boot.requesterExit(null);
    }

    // ---- naming

    @Test
    void aProjectInAWorktreeIsPrefixedWithItsFolderName() {
        assertEquals("wt-a.harness-app", Boot.projectName(new Model(Fakes.pom(wtA, "harness-app")), "harness-app"));
        assertEquals("main.harness-app", Boot.projectName(new Model(Fakes.pom(main, "harness-app")), "harness-app"));
    }

    @Test
    void aProjectOutsideAnyWorktreeKeepsM2esName() {
        Fakes.assertNoGitAbove(tmp);
        assertEquals("harness-app", Boot.projectName(new Model(Fakes.pom(outside, "harness-app")), "harness-app"));
        assertEquals("harness-app", Boot.projectName(new Model(null), "harness-app"));
    }

    @Test
    void aNameThatLooksPrefixedIsStillPrefixed() {
        // Distinct artifact IDs stay distinct project names (design §6, review R1).
        assertEquals("wt-a.harness-app", Boot.projectName(new Model(Fakes.pom(wtA, "harness-app")), "harness-app"));
        assertEquals("wt-a.wt-a.harness-app",
                Boot.projectName(new Model(Fakes.pom(wtA, "wt-a.harness-app")), "wt-a.harness-app"));
        assertNull(Boot.projectName(new Model(Fakes.pom(wtA, "harness-app")), null));
    }

    @Test
    void namingIsLogged() {
        Path log = Fakes.diagnosticLog(tmp);
        File pom = Fakes.pom(wtA, "harness-app");
        Boot.projectName(new Model(pom), "harness-app");
        List<String> lines = Fakes.lines(log);
        assertEquals(1, lines.size(), String.join("\n", lines));
        assertTrue(lines.get(0).contains("\"event\":\"naming\",\"pom\":\"" + pom + "\",\"worktree\":\"" + wtA
                + "\",\"m2e\":\"harness-app\",\"result\":\"wt-a.harness-app\""), lines.get(0));
    }

    @Test
    void aBrokenModelIsReportedOnceAndKeepsM2esName() {
        String err = Fakes.stderr(() -> {
            assertEquals("harness-app", Boot.projectName(new Object(), "harness-app"));
            assertEquals("harness-app", Boot.projectName(new Object(), "harness-app"));
        });
        assertEquals(1, err.lines().filter(l -> l.contains("the getProjectName hook failed")).count(), err);
    }

    // ---- requester selection

    @Test
    void contextPomIsTheRequesterWhenSet() {
        IFile requester = new IFile(Fakes.pom(wtA, "harness-app"));
        assertSame(aContract, Boot.workspaceArtifact(new Context(state, requester), G, CONTRACT, V, bContract));
    }

    @Test
    void populateClasspathCarriesTheFacadesPomOnTheThread() {
        Facade app = new Facade(new IFile(Fakes.pom(wtB, "harness-app")));
        File previous = Boot.populateClasspathEnter(app);
        try {
            assertNull(previous);
            assertEquals(Fakes.pom(wtB, "harness-app"), Boot.requester());
            assertSame(bContract, Boot.workspaceArtifact(new Context(state, null), G, CONTRACT, V, mainContract));
        } finally {
            Boot.requesterExit(previous);
        }
        assertNull(Boot.requester());
    }

    @Test
    void contextPomWinsOverTheThreadsRequester() {
        File previous = Boot.populateClasspathEnter(new Facade(new IFile(Fakes.pom(wtB, "harness-app"))));
        try {
            IFile requester = new IFile(Fakes.pom(wtA, "harness-app"));
            assertSame(aContract, Boot.workspaceArtifact(new Context(state, requester), G, CONTRACT, V, bContract));
        } finally {
            Boot.requesterExit(previous);
        }
    }

    @Test
    void mavenParentResolutionUsesTheChildsPomAndNestsSafely() {
        File appA = Fakes.pom(wtA, "harness-app");
        File appB = Fakes.pom(wtB, "harness-app");
        File outer = Boot.populateClasspathEnter(new Facade(new IFile(appA)));
        File middle = Boot.readParentExternallyEnter(new Model(appB));
        assertEquals(appA, middle);
        assertEquals(appB, Boot.requester());
        File inner = Boot.initParentEnter(new MavenProject(null)); // read from a repository: no POM file
        assertEquals(appB, inner);
        assertEquals(appB, Boot.requester(), "a child without a POM file keeps the enclosing requester");
        Boot.requesterExit(inner);
        assertEquals(appB, Boot.requester());
        File innermost = Boot.initParentEnter(new MavenProject(appA));
        assertEquals(appA, Boot.requester());
        Boot.requesterExit(innermost);
        Boot.requesterExit(middle);
        assertEquals(appA, Boot.requester());
        Boot.requesterExit(outer);
        assertNull(Boot.requester());
    }

    @Test
    void theRequesterIsPerThread() throws Exception {
        File previous = Boot.populateClasspathEnter(new Facade(new IFile(Fakes.pom(wtA, "harness-app"))));
        try {
            File[] seen = new File[1];
            Thread other = new Thread(() -> seen[0] = Boot.requester());
            other.start();
            other.join();
            assertNull(seen[0]);
        } finally {
            Boot.requesterExit(previous);
        }
    }

    // ---- the resolution rule

    @Test
    void withoutARequesterM2esChoiceStands() {
        assertSame(bContract, Boot.workspaceArtifact(new Context(state, null), G, CONTRACT, V, bContract));
        assertSame(mainContract, Boot.workspaceArtifact(new Context(state, null), G, CONTRACT, V, mainContract));
    }

    @Test
    void aRequesterOutsideAnyWorktreeKeepsM2esChoice() {
        IFile requester = new IFile(Fakes.pom(outside, "harness-app"));
        assertSame(bContract, Boot.workspaceArtifact(new Context(state, requester), G, CONTRACT, V, bContract));
    }

    @Test
    void noCandidateInTheRequestersWorktreeKeepsM2esChoice() {
        Path wtC = Fakes.linkedWorktree(tmp.resolve("wt-c"));
        IFile requester = new IFile(Fakes.pom(wtC, "harness-app"));
        assertSame(bContract, Boot.workspaceArtifact(new Context(state, requester), G, CONTRACT, V, bContract));
    }

    @Test
    void nothingInTheWorkspaceStaysNothing() {
        IFile requester = new IFile(Fakes.pom(wtA, "harness-app"));
        assertNull(Boot.workspaceArtifact(new Context(state, requester), G, "junit", "4", null));
        assertNull(Boot.workspaceArtifact(null, G, CONTRACT, V, null));
    }

    @Test
    void projectFacadePrefersTheRequestersWorktree() {
        Registry registry = new SubRegistry().add(KEY, mainContract, aContract, bContract);
        Facade chosen = registry.workspacePoms.get(mainContract);
        File previous = Boot.populateClasspathEnter(new Facade(new IFile(Fakes.pom(wtB, "harness-app"))));
        try {
            Object result = Boot.projectFacade(registry, G, CONTRACT, V, chosen);
            assertSame(registry.workspacePoms.get(bContract), result);
        } finally {
            Boot.requesterExit(previous);
        }
        assertSame(chosen, Boot.projectFacade(registry, G, CONTRACT, V, chosen), "no requester: m2e's choice");
        assertNull(Boot.projectFacade(registry, G, "absent", V, null));
    }

    @Test
    void lookupsOutsideTheWorkspaceAreLoggedToo() {
        Path log = Fakes.diagnosticLog(tmp);
        IFile requester = new IFile(Fakes.pom(wtA, "harness-app"));
        assertNull(Boot.workspaceArtifact(new Context(state, requester), "junit", "junit", "4.13.2", null));
        assertNull(Boot.workspaceArtifact(null, "junit", "junit", "4.13.2", null));
        Registry registry = new Registry().add(KEY, mainContract);
        assertNull(Boot.projectFacade(registry, "junit", "junit", "4.13.2", null));
        Facade single = registry.workspacePoms.get(mainContract);
        assertSame(single, Boot.projectFacade(registry, G, CONTRACT, V, single));
        List<String> lines = Fakes.lines(log);
        assertEquals(4, lines.size(), String.join("\n", lines));
        assertTrue(lines.get(0).contains("\"site\":\"getWorkspaceArtifact\",\"gav\":\"junit:junit:4.13.2\",\"requester\":\""
                + requester.getLocation().toFile() + "\",\"requesterSource\":\"context.pom\",\"candidates\":[],\"m2e\":null,"
                + "\"result\":null"), lines.get(0));
        assertTrue(lines.get(1).contains("\"site\":\"getWorkspaceArtifact\",\"gav\":\"junit:junit:4.13.2\",\"requester\":null,"
                + "\"requesterSource\":null,\"candidates\":[]"), lines.get(1));
        assertTrue(lines.get(2).contains("\"site\":\"getProjectFacade\",\"gav\":\"junit:junit:4.13.2\",\"requester\":null,"
                + "\"requesterSource\":null,\"candidates\":[],\"m2e\":null,\"result\":null"), lines.get(2));
        assertTrue(lines.get(3).contains("\"candidates\":[\"" + mainContract.getLocation().toFile() + "\"]"), lines.get(3));
    }

    @Test
    void lookupsAreLogged() {
        Path log = Fakes.diagnosticLog(tmp);
        IFile requester = new IFile(Fakes.pom(wtA, "harness-app"));
        Boot.workspaceArtifact(new Context(state, requester), G, CONTRACT, V, bContract);
        Registry registry = new Registry().add(KEY, mainContract, aContract, bContract);
        Boot.projectFacade(registry, G, CONTRACT, V, registry.workspacePoms.get(bContract));
        List<String> lines = Fakes.lines(log);
        assertEquals(2, lines.size(), String.join("\n", lines));
        String candidates = "\"candidates\":[\"" + mainContract.getLocation().toFile() + "\",\""
                + aContract.getLocation().toFile() + "\",\"" + bContract.getLocation().toFile() + "\"]";
        assertTrue(lines.get(0).contains("\"event\":\"lookup\",\"site\":\"getWorkspaceArtifact\",\"gav\":\"" + G + ":"
                + CONTRACT + ":" + V + "\",\"requester\":\"" + requester.getLocation().toFile()
                + "\",\"requesterSource\":\"context.pom\"," + candidates + ",\"m2e\":\""
                + bContract.getLocation().toFile() + "\",\"result\":\"" + aContract.getLocation().toFile() + "\""),
                lines.get(0));
        assertTrue(lines.get(1).contains("\"site\":\"getProjectFacade\"") && lines.get(1).contains(
                "\"requester\":null,\"requesterSource\":null," + candidates), lines.get(1));
    }
}
