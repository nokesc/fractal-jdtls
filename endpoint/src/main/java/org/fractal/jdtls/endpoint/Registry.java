// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/**
 * The registered worktrees (design §11 "Worktrees"), keyed by their real path, and the file under {@code -data} that
 * keeps them for a restart: {@code {"version": 1, "worktrees": [{"worktree": "/abs/path"}]}}, written atomically
 * whenever the set changes.
 */
final class Registry {

    enum State {
        IMPORTING("importing"), IMPORTED("imported"), IMPORT_FAILED("import-failed"), REMOVING("removing");

        final String id;

        State(String id) {
            this.id = id;
        }
    }

    /** A Maven module of a worktree, as its last import or health check found it. */
    record Module(String module, String gav, String packaging, State state, String failure) {
        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("module", module);
            o.addProperty("gav", gav);
            if (packaging != null) {
                o.addProperty("packaging", packaging);
            }
            o.addProperty("state", state.id);
            if (failure != null) {
                o.addProperty("failure", failure);
            }
            return o;
        }
    }

    /** The one repair of a broken import (design §11 "Worktrees"). */
    record Repair(String reason, String result, long ms) {
        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("reason", reason);
            o.addProperty("result", result);
            o.addProperty("ms", ms);
            return o;
        }
    }

    record Dropped(String worktree, String reason) {}

    static final class Worktree {
        final Path root;
        final String name;
        volatile State state = State.IMPORTING;
        volatile List<Module> modules = List.of();
        volatile Repair repair;
        /** The change that last started for it: its import, the restart's check, or its removal. */
        volatile Alone.Change change;
        volatile long importMs;
        /** Completes when its import (or the restart's check) and health check are done, whatever the result. */
        final CompletableFuture<Void> settled = new CompletableFuture<>();

        Worktree(Path root) {
            this.root = root;
            this.name = root.getFileName().toString();
        }

        List<Module> failing() {
            return modules.stream().filter(m -> m.state() == State.IMPORT_FAILED).toList();
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("worktree", root.toString());
            o.addProperty("name", name);
            o.addProperty("state", state.id);
            JsonArray list = new JsonArray();
            modules.forEach(m -> list.add(m.toJson()));
            o.add("modules", list);
            if (repair != null) {
                o.add("repair", repair.toJson());
            }
            return o;
        }

        JsonObject summary() {
            JsonObject o = new JsonObject();
            o.addProperty("worktree", root.toString());
            o.addProperty("name", name);
            o.addProperty("state", state.id);
            o.addProperty("modules", modules.size());
            if (repair != null) {
                o.add("repair", repair.toJson());
            }
            return o;
        }
    }

    private final Path file;
    private final Map<Path, Worktree> worktrees = new TreeMap<>();
    private final List<Dropped> dropped = new ArrayList<>();

    Registry(Path file) {
        this.file = file;
    }

    /** The folder of the state file, under {@code -data}. */
    Path folder() {
        return file.getParent();
    }

    synchronized Worktree get(Path root) {
        return worktrees.get(root);
    }

    synchronized Worktree named(String name) {
        return worktrees.values().stream().filter(w -> w.name.equals(name)).findFirst().orElse(null);
    }

    synchronized List<Worktree> all() {
        return new ArrayList<>(worktrees.values());
    }

    synchronized Worktree add(Path root) {
        Worktree w = new Worktree(root);
        worktrees.put(root, w);
        return w;
    }

    synchronized void remove(Worktree w) {
        worktrees.remove(w.root, w);
    }

    synchronized void drop(String worktree, String reason) {
        dropped.add(new Dropped(worktree, reason));
    }

    synchronized List<Dropped> dropped() {
        return new ArrayList<>(dropped);
    }

    /** Writes the registered worktrees, atomically. */
    synchronized void save() throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("version", 1);
        JsonArray list = new JsonArray();
        for (Worktree w : worktrees.values()) {
            JsonObject e = new JsonObject();
            e.addProperty("worktree", w.root.toString());
            list.add(e);
        }
        o.add("worktrees", list);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, Json.bytes(o));
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** The worktrees saved by the last run, in order; none if there's no file. A damaged file stops startup. */
    static List<Path> load(Path file) throws StartupException {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            JsonObject o = Json.parseStrict(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.get("version").getAsInt() != 1) {
                throw new StartupException(file + " has version " + o.get("version") + ", which this release doesn't know.");
            }
            List<Path> paths = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("worktrees")) {
                Path p = Path.of(e.getAsJsonObject().get("worktree").getAsString());
                if (!p.isAbsolute()) {
                    throw new StartupException(file + " lists a worktree that isn't an absolute path: " + p + ".");
                }
                paths.add(p);
            }
            return paths;
        } catch (IOException | JsonParseException | IllegalStateException | NullPointerException | UnsupportedOperationException e) {
            throw new StartupException("can't read the saved worktrees in " + file + ": " + e
                    + ". Fix or delete the file; deleting it starts with no worktrees, and JDT LS then deletes the projects it had.");
        }
    }
}
