// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.boot;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.eclipse.m2e.core.embedder.ArtifactKey;
import org.junit.jupiter.api.Assertions;

/**
 * Stand-ins for the m2e and Maven objects the helper reaches by reflection. Each has the
 * members the helper uses, with the same names.
 */
final class Fakes {

    private Fakes() {}

    /** Maven's {@code Model}. */
    static final class Model {
        private final File pomFile;

        Model(File pomFile) {
            this.pomFile = pomFile;
        }

        public File getPomFile() {
            return pomFile;
        }
    }

    /** Maven's {@code MavenProject}. */
    static final class MavenProject {
        private final File file;

        MavenProject(File file) {
            this.file = file;
        }

        public File getFile() {
            return file;
        }
    }

    /** Eclipse's {@code IPath}. */
    static final class Location {
        private final File file;

        Location(File file) {
            this.file = file;
        }

        public File toFile() {
            return file;
        }
    }

    /** Eclipse's {@code IFile}, equal by location as in Eclipse. */
    static final class IFile {
        private final File file;

        IFile(File file) {
            this.file = file;
        }

        public Location getLocation() {
            return new Location(file);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof IFile other && file.equals(other.file);
        }

        @Override
        public int hashCode() {
            return Objects.hash(file);
        }

        @Override
        public String toString() {
            return "IFile(" + file + ")";
        }
    }

    /** m2e's {@code MavenProjectFacade}. */
    static final class Facade {
        private final IFile pom;

        Facade(IFile pom) {
            this.pom = pom;
        }

        public IFile getPom() {
            return pom;
        }
    }

    /** m2e's {@code IProjectRegistry}: {@code getWorkspaceArtifacts(groupId, artifactId)}. */
    static final class State {
        private final Map<ArtifactKey, Collection<IFile>> artifacts;

        State(Map<ArtifactKey, Collection<IFile>> artifacts) {
            this.artifacts = artifacts;
        }

        public Map<ArtifactKey, Collection<IFile>> getWorkspaceArtifacts(String groupId, String artifactId) {
            Map<ArtifactKey, Collection<IFile>> result = new LinkedHashMap<>();
            artifacts.forEach((k, v) -> {
                if (k.groupId().equals(groupId) && k.artifactId().equals(artifactId)) {
                    result.put(k, v);
                }
            });
            return result;
        }
    }

    /** m2e's {@code ProjectRegistryManager.Context}. */
    static final class Context {
        final State state;
        final IFile pom;

        Context(State state, IFile pom) {
            this.state = state;
            this.pom = pom;
        }
    }

    /** m2e's {@code BasicProjectRegistry}. */
    static class Registry {
        protected final Map<ArtifactKey, Set<IFile>> workspaceArtifacts = new LinkedHashMap<>();
        protected final Map<IFile, Facade> workspacePoms = new LinkedHashMap<>();

        Registry add(ArtifactKey key, IFile... poms) {
            workspaceArtifacts.computeIfAbsent(key, k -> new LinkedHashSet<>()).addAll(List.of(poms));
            for (IFile pom : poms) {
                workspacePoms.put(pom, new Facade(pom));
            }
            return this;
        }
    }

    /** m2e's {@code ProjectRegistry}, a subclass, as the helper sees it at runtime. */
    static final class SubRegistry extends Registry {}

    // ---- folders

    /** A main checkout: {@code root/.git/} is a folder. */
    static Path mainCheckout(Path root) {
        mkdirs(root.resolve(".git"));
        return root;
    }

    /** A linked worktree: {@code root/.git} is a file. */
    static Path linkedWorktree(Path root) {
        mkdirs(root);
        try {
            Files.writeString(root.resolve(".git"), "gitdir: /elsewhere/.git/worktrees/" + root.getFileName() + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return root;
    }

    /**
     * Fails, naming it, if a {@code .git} sits in {@code dir} or any folder above it. Tests of
     * projects outside any worktree assume none does; a stray one (seen: an empty {@code /tmp/.git})
     * would otherwise fail them on a naming assertion that doesn't say why.
     */
    static void assertNoGitAbove(Path dir) {
        for (Path folder = dir.toAbsolutePath(); folder != null; folder = folder.getParent()) {
            Path git = folder.resolve(".git");
            if (Files.exists(git, LinkOption.NOFOLLOW_LINKS)) {
                Assertions.fail("this test needs no .git in " + dir + " or above it, but found " + git
                        + ", which makes every folder below it look like a Git checkout. Remove the stray "
                        + git + " (or set java.io.tmpdir outside it) and run the tests again.");
            }
        }
    }

    /** {@code <root>/<module>/pom.xml}, created. */
    static File pom(Path root, String module) {
        Path pom = root.resolve(module).resolve("pom.xml");
        mkdirs(pom.getParent());
        try {
            Files.writeString(pom, "<project/>\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return pom.toFile();
    }

    private static void mkdirs(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- stderr and the diagnostic log

    /** Captures the agent's stderr reports while {@code body} runs. */
    static String stderr(Runnable body) {
        PrintStream original = Report.stderr;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Report.stderr = new PrintStream(captured, true, StandardCharsets.UTF_8);
        try {
            body.run();
        } finally {
            Report.stderr = original;
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    /** Points the diagnostic log at a fresh file in {@code dir}. */
    static Path diagnosticLog(Path dir) {
        Path log = dir.resolve("diagnostic-" + System.nanoTime() + ".jsonl");
        System.setProperty(DiagnosticLog.PROPERTY, log.toString());
        return log;
    }

    static List<String> lines(Path log) {
        try {
            return Files.exists(log) ? Files.readAllLines(log) : List.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
