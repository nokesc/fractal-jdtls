// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * From a request's {@code worktree} and {@code module} to its Eclipse project, with every check of design §11 "The
 * contract": the worktree is registered (and its folder still there), the module's {@code pom.xml} exists, its
 * project is imported and passes the import health check, and the agent placed it in that worktree. Each failure is a
 * catalogued problem that says what to do. Callers never see Eclipse project names.
 */
final class Mapping {

    private Mapping() {}

    /** The module path's syntax ({@code ModulePath}): null when it's valid, else why not. */
    static String moduleSyntax(String module) {
        if (module.equals(".")) {
            return null;
        }
        if (module.startsWith("/") || module.endsWith("/") || module.contains("\\") || module.contains("\0")) {
            return "must be relative to the worktree's root, with / separators and no leading or trailing /, such as harness-app, or . for the root POM";
        }
        for (String segment : module.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return "must name a folder inside the worktree: no empty, . or .. segments, such as harness-app or services/billing, or . for the root POM";
            }
        }
        return null;
    }

    /** The worktree's root as add expects it: an absolute path to an existing folder that directly holds {@code .git}. */
    static Path worktreeRoot(String worktree) throws Problem {
        Path given;
        try {
            given = Path.of(worktree);
        } catch (InvalidPathException e) {
            throw notAWorktree(worktree + " isn't a path: " + e.getReason() + ".", null);
        }
        if (!given.isAbsolute()) {
            throw notAWorktree(worktree + " isn't an absolute path.", null);
        }
        if (!Files.exists(given)) {
            throw notAWorktree(worktree + " doesn't exist.", null);
        }
        Path real;
        try {
            real = given.toRealPath();
        } catch (IOException e) {
            throw notAWorktree(worktree + " can't be read: " + e.getMessage() + ".", null);
        }
        if (!Files.isDirectory(real) || real.getFileName() == null) {
            throw notAWorktree(worktree + " isn't a folder.", null);
        }
        if (!Files.exists(real.resolve(".git"))) {
            Path nearest = real.getParent() == null ? null : nearestGit(real.getParent());
            throw notAWorktree(real + " isn't the root of a Git worktree: it has no .git"
                    + (nearest == null ? ", and neither has any folder above it." : "; its nearest .git is in " + nearest + "."), nearest);
        }
        return real;
    }

    private static Problem notAWorktree(String detail, Path nearest) {
        Problem p = Problem.of(Code.NOT_A_WORKTREE).detail(detail).with("field", "/worktree");
        return nearest == null ? p : p.hint("Pass the worktree's root, " + nearest + ".");
    }

    /**
     * The registered worktree that {@code root} is inside, or that is inside {@code root}, or null (design §11
     * "Worktrees": nested worktrees are refused). JDT LS's folder removal deletes every project under the removed
     * root, and its import finds every POM under it, so nested worktrees can't be kept apart.
     */
    static Registry.Worktree overlapping(Registry registry, Path root) {
        for (Registry.Worktree w : registry.all()) {
            if (!w.root.equals(root) && (root.startsWith(w.root) || w.root.startsWith(root))) {
                return w;
            }
        }
        return null;
    }

    static Problem nested(Path root, Registry.Worktree other) {
        String detail = root.startsWith(other.root)
                ? root + " is inside the registered worktree " + other.root + "."
                : root + " contains the registered worktree " + other.root + ".";
        return Problem.of(Code.WORKTREE_NESTED).detail(detail + " Worktrees that share a JDT LS can't be nested.").with("field", "/worktree");
    }

    /** The nearest folder, from {@code folder} up, that holds {@code .git}: the agent's rule (design §6). */
    static Path nearestGit(Path folder) {
        for (Path p = folder; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve(".git"))) {
                return p;
            }
        }
        return null;
    }

    /** The registered worktree the request names, by its real path or as given (a deleted one has no real path). */
    static Registry.Worktree registered(Registry registry, String worktree) throws Problem {
        Registry.Worktree w = null;
        try {
            Path given = Path.of(worktree);
            if (given.isAbsolute()) {
                w = registry.get(given.normalize());
                if (w == null && Files.exists(given)) {
                    w = registry.get(given.toRealPath());
                }
            }
        } catch (InvalidPathException | IOException e) {
            w = null;
        }
        if (w == null || w.state == Registry.State.REMOVING) {
            JsonArray candidates = new JsonArray();
            for (Registry.Worktree r : registry.all()) {
                if (r.state != Registry.State.REMOVING) {
                    candidates.add(candidate("/worktree", r.root.toString(), r.name + ", " + r.modules.size()
                            + (r.modules.size() == 1 ? " module, " : " modules, ") + r.state.id));
                }
            }
            String detail = w != null ? worktree + " is being removed."
                    : worktree + " isn't a registered worktree. " + (candidates.isEmpty() ? "None is registered."
                    : candidates.size() == 1 ? "1 is registered." : candidates.size() + " are registered.");
            throw Problem.of(Code.WORKTREE_NOT_REGISTERED).detail(detail).with("field", "/worktree").with("candidates", candidates);
        }
        return w;
    }

    static JsonObject candidate(String pointer, String value, String about) {
        JsonObject c = new JsonObject();
        JsonObject set = new JsonObject();
        set.addProperty(pointer, value);
        c.add("set", set);
        c.addProperty("about", about);
        return c;
    }

    static Problem importFailed(Registry.Worktree w, java.util.List<Registry.Module> failing, String detail) {
        JsonArray modules = new JsonArray();
        for (Registry.Module m : failing) {
            JsonObject o = new JsonObject();
            o.addProperty("module", m.module());
            o.addProperty("failure", m.failure());
            modules.add(o);
        }
        Problem p = Problem.of(Code.IMPORT_FAILED).detail(detail).with("worktree", w.root.toString()).with("modules", modules);
        if (failing.stream().anyMatch(m -> m.failure() != null && m.failure().contains(Workspace.POM_ERRORS))) {
            p.hint("Fix the pom.xml errors that `modules` quotes, then remove the worktree and add it again.");
        }
        return p;
    }

    static Problem importFailed(Registry.Worktree w) {
        java.util.List<Registry.Module> failing = w.failing();
        return importFailed(w, failing, (failing.size() == 1 ? "1 module of " : failing.size() + " modules of ") + w.root
                + " failed the import health check, and the repair (removing and adding the worktree again) didn't fix it.");
    }

    /**
     * Every check, in order; the module as it is now. Called with no worktree change running (imports run alone), so
     * the workspace doesn't change under it.
     */
    static Registry.Module resolve(Registry registry, String worktree, String module) throws Problem {
        Registry.Worktree w = registered(registry, worktree);
        if (!Files.isDirectory(w.root)) {
            throw Problem.of(Code.WORKTREE_DELETED).detail("The registered worktree " + w.root + " no longer exists on disk.")
                    .with("field", "/worktree");
        }
        if (w.state == Registry.State.IMPORT_FAILED) {
            throw importFailed(w);
        }
        Path folder = module.equals(".") ? w.root : w.root.resolve(module).normalize();
        String rel = Workspace.relative(w.root, folder);
        if (!Files.isRegularFile(folder.resolve(Workspace.POM))) {
            JsonArray candidates = new JsonArray();
            for (Registry.Module m : w.modules) {
                String[] gav = m.gav().split(":");
                String about = gav.length == 3 ? gav[0] + ":" + gav[1] : m.gav();
                candidates.add(candidate("/module", m.module(), "pom".equals(m.packaging()) ? about + " (pom)" : about));
            }
            throw Problem.of(Code.MODULE_NOT_FOUND).detail("There's no pom.xml in " + folder + ".").with("field", "/module")
                    .with("candidates", candidates);
        }
        String project = Workspace.projectNameAt(folder);
        if (project == null) {
            throw Problem.of(Code.MODULE_NOT_IMPORTED).detail(folder + " has a pom.xml, but JDT LS has no project for it.")
                    .with("field", "/module");
        }
        Path nearest = nearestGit(folder);
        if (!w.root.equals(nearest)) {
            String detail = nearest == null
                    ? "The project for " + folder + " can't be placed in any worktree: there's no .git in " + w.root + " or above it any more."
                    : "The project for " + folder + " was placed in the worktree " + nearest + ", because " + nearest.resolve(".git")
                            + " is nearer the module than " + w.root.resolve(".git") + ".";
            Problem p = Problem.of(Code.MODULE_IN_OTHER_WORKTREE).detail(detail).with("field", "/module");
            throw nearest == null ? p : p.hint("Remove the stray " + nearest.resolve(".git") + ", then remove the worktree and add it again.");
        }
        if (!project.startsWith(w.name + ".")) {
            throw Problem.of(Code.MODULE_IN_OTHER_WORKTREE)
                    .detail("The project for " + folder + " was placed in another worktree when it was imported, though " + w.root.resolve(".git")
                            + " is now the nearest .git: a .git nearer the module, since removed, decided it then.")
                    .hint("Remove the worktree and add it again, so the module is imported into it.").with("field", "/module");
        }
        Registry.Module known = w.modules.stream().filter(m -> m.module().equals(rel)).findFirst().orElse(null);
        Registry.Module now = Workspace.health(new Workspace.Found(folder, rel, known == null ? "unknown" : known.gav(),
                known == null ? null : known.packaging()));
        if (now.state() == Registry.State.IMPORT_FAILED) {
            throw importFailed(w, java.util.List.of(now), "The module " + rel + " of " + w.root + " fails the import health check now: " + now.failure() + ".");
        }
        return now;
    }
}
