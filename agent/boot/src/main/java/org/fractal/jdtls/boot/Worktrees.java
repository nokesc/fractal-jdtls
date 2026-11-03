// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.boot;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Worktree identity (design §6). A file's worktree is the nearest ancestor that contains
 * {@code .git}: a file in a linked worktree, a folder in a main checkout. Nothing is passed in by
 * the launcher or the client, so folders added at runtime work the same way.
 *
 * <p>A worktree's prefix is its folder name. Two worktrees with the same folder name in one
 * process would share a prefix; that's reported once per worktree that collides, and is the
 * client's to fix (design §10).
 */
public final class Worktrees {

    private static final ConcurrentHashMap<String, Path> FIRST_BY_PREFIX = new ConcurrentHashMap<>();
    private static final Set<Path> REPORTED = ConcurrentHashMap.newKeySet();

    private Worktrees() {}

    /** The worktree that contains {@code file}, or null when no ancestor contains {@code .git}. */
    public static Path root(File file) {
        if (file == null) {
            return null;
        }
        for (Path p = file.toPath().toAbsolutePath().normalize(); p != null; p = p.getParent()) {
            if (Files.exists(p.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                return p;
            }
        }
        return null;
    }

    /**
     * The project-name prefix for a worktree, its folder name, or null for null. Reports a
     * collision the first time a second worktree with the same prefix is seen.
     */
    public static String prefix(Path root) {
        if (root == null || root.getFileName() == null) {
            return null;
        }
        String prefix = root.getFileName().toString();
        Path first = FIRST_BY_PREFIX.putIfAbsent(prefix, root);
        if (first != null && !first.equals(root) && REPORTED.add(root)) {
            Report.error("two worktrees in this JDT LS process have the same folder name '" + prefix + "': " + first
                    + " and " + root + ". Their projects get the same names. Give each worktree that shares a JDT LS"
                    + " process a unique folder name.");
            DiagnosticLog.event("prefix-collision", "prefix", prefix, "worktrees", java.util.List.of(first, root));
        }
        return prefix;
    }

    /** Forgets every worktree seen. For tests. */
    static void reset() {
        FIRST_BY_PREFIX.clear();
        REPORTED.clear();
    }
}
