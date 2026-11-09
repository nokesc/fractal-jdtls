// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The parts of the worktree operations that need no JDT LS: the gate, the saved state, the field and path checks. */
class WorktreesTest {

    @TempDir
    Path tmp;

    static final Description DESCRIPTION = DescriptionTest.description();

    static JsonObject body(Problem p) {
        return p.toJson(DESCRIPTION, null);
    }

    // --- imports run alone -----------------------------------------------------------------------

    @Test
    void aRequestWaitsForAnAnnouncedChangeAndTheChangeWaitsForRunningRequests() throws Exception {
        Alone alone = new Alone();
        Alone.Pass first = alone.enter(System.nanoTime() + 1_000_000_000L, true, null);
        Alone.Change change = new Alone.Change(Alone.Kind.ADD, tmp);
        alone.announce(change);
        CompletableFuture<Void> began = CompletableFuture.runAsync(() -> {
            try {
                alone.begin(change);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(200);
        assertFalse(began.isDone(), "a change runs only when no request does");
        Problem refused = assertThrows(Problem.class, () -> alone.enter(System.nanoTime() + 1_000_000_000L, false, null));
        JsonObject o = body(refused);
        assertEquals("not-ready", o.get("code").getAsString());
        assertEquals("importing", o.get("reason").getAsString());
        assertEquals(tmp.toString(), o.get("worktree").getAsString());
        assertTrue(o.get("detail").getAsString().startsWith("The worktree " + tmp + " is being imported ("), o.toString());

        CompletableFuture<Long> held = CompletableFuture.supplyAsync(() -> {
            try (Alone.Pass p = alone.enter(System.nanoTime() + 10_000_000_000L, true, null)) {
                return p.waitedMs;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        first.close();
        began.get(5, TimeUnit.SECONDS);
        assertEquals(change, alone.current());
        Thread.sleep(300);
        assertFalse(held.isDone(), "requests wait while the change runs");
        alone.end(change);
        assertTrue(held.get(5, TimeUnit.SECONDS) >= 300);
        assertNull(alone.blocking());
    }

    @Test
    void aRequestThatWaitsTooLongIsNotReady() throws Exception {
        Alone alone = new Alone();
        alone.announce(new Alone.Change(Alone.Kind.REMOVE, tmp));
        long start = System.nanoTime();
        Problem p = assertThrows(Problem.class, () -> alone.enter(System.nanoTime() + 200_000_000L, true, null));
        assertTrue(System.nanoTime() - start >= 200_000_000L);
        assertEquals(2, body(p).get("retryAfter").getAsInt());
        assertTrue(body(p).get("detail").getAsString().contains("is being removed"), body(p).toString());
    }

    // --- the saved worktrees ---------------------------------------------------------------------

    @Test
    void theRegisteredWorktreesAreSavedAndLoadedInOrder() throws Exception {
        Path file = tmp.resolve("data/.fractal-jdtls/worktrees.json");
        assertEquals(List.of(), Registry.load(file));
        Registry r = new Registry(file);
        r.add(Path.of("/work/wt-b"));
        r.add(Path.of("/work/main"));
        r.save();
        assertEquals("{\"version\":1,\"worktrees\":[{\"worktree\":\"/work/main\"},{\"worktree\":\"/work/wt-b\"}]}", Files.readString(file));
        assertEquals(List.of(Path.of("/work/main"), Path.of("/work/wt-b")), Registry.load(file));
        assertFalse(Files.exists(file.resolveSibling("worktrees.json.tmp")));
    }

    @Test
    void aDamagedStateFileStopsStartupWithTheReason() throws Exception {
        Path file = tmp.resolve("worktrees.json");
        Files.writeString(file, "{\"version\": 1, \"worktrees\": [{\"worktree\": \"relative\"}]}");
        StartupException e = assertThrows(StartupException.class, () -> Registry.load(file));
        assertTrue(e.getMessage().contains("isn't an absolute path: relative"), e.getMessage());
        Files.writeString(file, "{nope");
        e = assertThrows(StartupException.class, () -> Registry.load(file));
        assertTrue(e.getMessage().contains("Fix or delete the file"), e.getMessage());
    }

    @Test
    void aSavedWorktreeWhoseFolderIsGoneIsDroppedAndReported() throws Exception {
        Path main = Files.createDirectories(tmp.resolve("main/.git"));
        Path file = tmp.resolve("worktrees.json");
        Files.writeString(file, "{\"version\":1,\"worktrees\":[{\"worktree\":\"" + main.getParent() + "\"},{\"worktree\":\"" + tmp.resolve("gone") + "\"}]}");
        Registry r = new Registry(file);
        List<Registry.Worktree> restored = Endpoint.restoreSaved(r, file);
        assertEquals(List.of(main.getParent()), restored.stream().map(w -> w.root).toList());
        assertEquals(List.of(new Registry.Dropped(tmp.resolve("gone").toString(), "its folder is gone")), r.dropped());
        assertEquals(List.of(main.getParent()), Registry.load(file), "dropped from the saved state");
    }

    // --- fields and paths ------------------------------------------------------------------------

    @Test
    void fieldsAreReadStrictlyAndEveryErrorIsListed() {
        JsonObject b = Json.parseStrict("{\"worktree\": 3, \"wait\": \"yes\", \"timeoutMs\": 0, \"extra\": 1}").getAsJsonObject();
        Fields f = new Fields(b, "POST /v1/worktrees/add");
        f.string("worktree", true);
        f.integer("timeoutMs", 1, 10, 5);
        f.bool("wait", true);
        Problem p = assertThrows(Problem.class, f::done);
        JsonObject o = body(p);
        assertEquals("4 fields are invalid.", o.get("detail").getAsString());
        assertEquals("[{\"field\":\"/worktree\",\"message\":\"must be a string\"},{\"field\":\"/timeoutMs\",\"message\":\"must be an integer from 1 to 10\"},"
                + "{\"field\":\"/wait\",\"message\":\"must be true or false\"},"
                + "{\"field\":\"/extra\",\"message\":\"unknown field; POST /v1/worktrees/add takes worktree, timeoutMs, wait\"}]",
                o.get("errors").toString());
    }

    @Test
    void modulePathsAreRelativeFoldersWithSlashes() {
        for (String ok : List.of(".", "harness-app", "services/billing", "a.b")) {
            assertNull(Mapping.moduleSyntax(ok), ok);
        }
        for (String bad : List.of("/abs", "a/", "a//b", "./a", "a/../b", "..", "a\\b")) {
            assertTrue(Mapping.moduleSyntax(bad) != null, bad);
        }
    }

    @Test
    void addTakesOnlyAWorktreesRoot() throws Exception {
        Path main = Files.createDirectories(tmp.resolve("main"));
        Files.createDirectories(main.resolve(".git"));
        Path module = Files.createDirectories(main.resolve("harness-app"));
        Path wt = Files.createDirectories(tmp.resolve("wt-a"));
        Files.writeString(wt.resolve(".git"), "gitdir: " + main.resolve(".git/worktrees/wt-a") + "\n");
        Path link = Files.createSymbolicLink(tmp.resolve("link"), wt);

        assertEquals(main.toRealPath(), Mapping.worktreeRoot(main.toString()));
        assertEquals(wt.toRealPath(), Mapping.worktreeRoot(link.toString()), "symbolic links are resolved");

        JsonObject o = body(assertThrows(Problem.class, () -> Mapping.worktreeRoot(module.toString())));
        assertEquals("not-a-worktree", o.get("code").getAsString());
        assertEquals(module.toRealPath() + " isn't the root of a Git worktree: it has no .git; its nearest .git is in " + main.toRealPath() + ".",
                o.get("detail").getAsString());
        assertEquals("Pass the worktree's root, " + main.toRealPath() + ".", o.get("hint").getAsString());
        assertEquals("harness-app isn't an absolute path.", body(assertThrows(Problem.class, () -> Mapping.worktreeRoot("harness-app"))).get("detail").getAsString());
        assertTrue(body(assertThrows(Problem.class, () -> Mapping.worktreeRoot(tmp.resolve("nope").toString()))).get("detail").getAsString().endsWith("doesn't exist."));
    }

    @Test
    void anUnregisteredWorktreeListsTheRegisteredOnesAsCandidates() throws Exception {
        Registry r = new Registry(tmp.resolve("worktrees.json"));
        r.add(Path.of("/work/wt-a")).state = Registry.State.IMPORTED;
        JsonObject o = body(assertThrows(Problem.class, () -> Mapping.registered(r, "/work/wt-c")));
        assertEquals("worktree-not-registered", o.get("code").getAsString());
        assertEquals("/work/wt-c isn't a registered worktree. 1 is registered.", o.get("detail").getAsString());
        assertEquals("[{\"set\":{\"/worktree\":\"/work/wt-a\"},\"about\":\"wt-a, 0 modules, imported\"}]", o.get("candidates").toString());
        assertEquals(Path.of("/work/wt-a"), Mapping.registered(r, "/work/wt-a/").root, "a trailing slash is the same folder");
    }

    @Test
    void foldersAreSentAsFileUrisWithoutATrailingSlash() throws Exception {
        Path main = Files.createDirectories(tmp.resolve("main dir"));
        assertEquals(main.toUri().toString().replaceAll("/$", ""), WorktreeOps.uri(main));
        assertEquals("file:///work/gone", WorktreeOps.uri(Path.of("/work/gone")));
        JsonObject init = Endpoint.initializeParams(new JsonObject(), "1.63.0-1", List.of(Path.of("/work/main")));
        assertEquals("[{\"uri\":\"file:///work/main\",\"name\":\"main\"}]", init.get("workspaceFolders").toString());
        assertEquals("[\"file:///work/main\"]", init.getAsJsonObject("initializationOptions").get("workspaceFolders").toString());
    }
    // --- nested worktrees (T002.04's review, R2) --------------------------------------------------

    @Test
    void aWorktreeInsideOrContainingARegisteredOneIsRefusedNamingIt() {
        Registry r = new Registry(tmp.resolve("worktrees.json"));
        r.add(Path.of("/work/main"));
        assertNull(Mapping.overlapping(r, Path.of("/work/main")), "the same worktree isn't nested");
        assertNull(Mapping.overlapping(r, Path.of("/work/main-b")), "a sibling whose name starts the same isn't nested");
        Registry.Worktree inside = Mapping.overlapping(r, Path.of("/work/main/tools"));
        assertEquals(Path.of("/work/main"), inside.root);
        JsonObject o = body(Mapping.nested(Path.of("/work/main/tools"), inside));
        assertEquals("worktree-nested", o.get("code").getAsString());
        assertEquals(409, o.get("status").getAsInt());
        assertEquals("/work/main/tools is inside the registered worktree /work/main. Worktrees that share a JDT LS can't be nested.",
                o.get("detail").getAsString());
        assertEquals("/work contains the registered worktree /work/main. Worktrees that share a JDT LS can't be nested.",
                body(Mapping.nested(Path.of("/work"), Mapping.overlapping(r, Path.of("/work")))).get("detail").getAsString());
    }

    @Test
    void aSavedWorktreeThatOverlapsAnotherIsDroppedAtARestart() throws Exception {
        Path main = Files.createDirectories(tmp.resolve("main"));
        Files.createDirectories(main.resolve(".git"));
        Path inner = Files.createDirectories(main.resolve("inner"));
        Files.createDirectories(inner.resolve(".git"));
        Path file = tmp.resolve("worktrees.json");
        Files.writeString(file, "{\"version\":1,\"worktrees\":[{\"worktree\":\"" + main + "\"},{\"worktree\":\"" + inner + "\"}]}");
        Registry r = new Registry(file);
        assertEquals(List.of(main), Endpoint.restoreSaved(r, file).stream().map(w -> w.root).toList());
        assertEquals(List.of(new Registry.Dropped(inner.toString(), "it overlaps the saved worktree " + main + ": nested worktrees are refused")),
                r.dropped());
    }

    // --- deadlines (T002.04's review, R3) --------------------------------------------------------

    static HttpApi.Request arrivedAgo(long ms) {
        return new HttpApi.Request("POST", "/v1/modules/check", java.util.Map.of(), new byte[0], "urn:fractal-jdtls:request:t",
                System.nanoTime() - ms * 1_000_000);
    }

    @Test
    void aRequestWhoseTimeIsUpIsNeverRun() throws Exception {
        CompletableFuture<Void> ready = new CompletableFuture<>();
        WorktreeOps ops = new WorktreeOps(null, new Registry(tmp.resolve("worktrees.json")), () -> ready, () -> 4000);
        HttpApi.Request old = arrivedAgo(3000);
        long deadline = old.received() + 1_000_000_000L; // timeoutMs 1000, 3 s ago

        JsonObject o = body(assertThrows(Problem.class, () -> ops.refuseIfExpired(old, deadline, 1000)));
        assertEquals("not-ready", o.get("code").getAsString());
        assertEquals("starting", o.get("reason").getAsString());

        ready.complete(null);
        Alone.Change change = new Alone.Change(Alone.Kind.ADD, tmp);
        ops.alone().announce(change);
        o = body(assertThrows(Problem.class, () -> ops.refuseIfExpired(old, deadline, 1000)));
        assertEquals("not-ready", o.get("code").getAsString());
        assertEquals("importing", o.get("reason").getAsString());
        ops.alone().end(change);

        o = body(assertThrows(Problem.class, () -> ops.refuseIfExpired(old, deadline, 1000)));
        assertEquals("busy", o.get("code").getAsString());
        assertEquals(1, o.get("retryAfter").getAsInt());
        assertTrue(o.get("detail").getAsString().matches("The request waited 3,\\d{3} ms for a worker thread, past its timeoutMs of 1,000, so it wasn't run\\."),
                o.toString());

        HttpApi.Request fresh = arrivedAgo(0);
        ops.refuseIfExpired(fresh, fresh.received() + 1_000_000_000L, 1000); // in time: no problem
    }

    @Test
    void aRequestThatWaitedForAChangeThatEndedTooLateIsNotReady() throws Exception {
        Alone alone = new Alone();
        Alone.Change change = new Alone.Change(Alone.Kind.ADD, tmp);
        alone.announce(change);
        long deadline = System.nanoTime() + 300_000_000L;
        CompletableFuture<Problem> waited = CompletableFuture.supplyAsync(() -> {
            try (Alone.Pass p = alone.enter(deadline, true, null)) {
                return null;
            } catch (Problem p) {
                return p;
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(500); // past the deadline; enter's own wait timed out already, or ends now
        alone.end(change);
        Problem p = waited.get(5, TimeUnit.SECONDS);
        assertTrue(p != null && body(p).get("reason").getAsString().equals("importing"), "the request didn't run");
    }
}

