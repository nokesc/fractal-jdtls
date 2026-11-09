// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The passthrough's classification, refusals and URI check (design §11 "The LSP passthrough"), without JDT LS. */
class LspPassthroughTest {

    static final LspPassthrough.Served SERVED = new LspPassthrough.Served(
            Map.of("textDocument/hover", false, "textDocument/didOpen", true, "java/validateDocument", true, "java/newThing", false,
                    "java/newNotice", true, "workspace/executeCommand", false),
            List.of("java.project.getClasspaths", "java.project.import", "java.new.command"));

    static JsonObject json(String s) {
        return Json.parseStrict(s).getAsJsonObject();
    }

    static JsonObject body(Problem p) {
        return p.toJson(DescriptionTest.description(), null);
    }

    @Test
    void noMethodOrCommandIsBothAllowedAndRefused() {
        Set<String> both = new TreeSet<>(LspPassthrough.ALLOWED_METHODS.keySet());
        both.retainAll(LspPassthrough.REFUSED_METHODS.keySet());
        assertTrue(both.isEmpty(), both.toString());
        both = new TreeSet<>(LspPassthrough.ALLOWED_COMMANDS.keySet());
        both.retainAll(LspPassthrough.REFUSED_COMMANDS.keySet());
        assertTrue(both.isEmpty(), both.toString());
        // The five commands design §11 names, and the methods of each refused kind.
        for (String c : List.of("java.clean.workspace", "java.project.import", "java.project.changeImportedProjects", "java.project.addToSourcePath",
                "java.project.removeFromSourcePath")) {
            assertNotNull(LspPassthrough.REFUSED_COMMANDS.get(c), c);
        }
        for (String m : List.of("initialize", "initialized", "shutdown", "exit", "workspace/didChangeWorkspaceFolders", "textDocument/didOpen",
                "textDocument/didChange", "textDocument/didSave", "textDocument/didClose", "workspace/didChangeConfiguration", "$/cancelRequest")) {
            assertNotNull(LspPassthrough.REFUSED_METHODS.get(m), m);
        }
    }

    @Test
    void refusedMethodsAndCommandsNameWhatToUseInstead() {
        JsonObject p = body(LspPassthrough.refusal("shutdown", null, SERVED));
        assertEquals("lsp-method-refused", p.get("code").getAsString());
        assertEquals(422, p.get("status").getAsInt());
        assertEquals("/method", p.get("field").getAsString());
        assertTrue(p.get("hint").getAsString().contains("POST /v1/shutdown"), p.toString());

        p = body(LspPassthrough.refusal("workspace/didChangeWorkspaceFolders", json("{\"event\": {}}"), SERVED));
        assertTrue(p.get("hint").getAsString().contains("POST /v1/worktrees/add"), p.toString());

        p = body(LspPassthrough.refusal("workspace/executeCommand", json("{\"command\": \"java.project.import\", \"arguments\": []}"), SERVED));
        assertEquals("/params/command", p.get("field").getAsString());
        assertEquals("The command java.project.import changes which projects JDT LS has.", p.get("detail").getAsString());

        p = body(LspPassthrough.refusal("workspace/executeCommand", json("{\"command\": \"java.reloadBundles\"}"), SERVED));
        assertTrue(p.get("detail").getAsString().contains("loads OSGi bundles"), p.toString());

        // Review R2: the pinned JDT LS's paste handler leaks a working copy.
        p = body(LspPassthrough.refusal("workspace/executeCommand", json("{\"command\": \"java.edit.handlePasteEvent\", \"arguments\": []}"), SERVED));
        assertEquals("lsp-method-refused", p.get("code").getAsString());
        assertTrue(p.get("detail").getAsString().contains("leaks a working copy"), p.toString());
        assertTrue(p.get("hint").getAsString().contains("java.edit.organizeImports"), p.toString());
    }

    @Test
    void anUnclassifiedMethodOrCommandIsRefusedAndSaysWhy() {
        assertTrue(body(LspPassthrough.refusal("java/newThing", null, SERVED)).get("detail").getAsString().contains("hasn't classified yet"));
        assertTrue(body(LspPassthrough.refusal("java/newNotice", null, SERVED)).get("detail").getAsString().contains("is a notification"));
        assertTrue(body(LspPassthrough.refusal("textDocument/nothing", null, SERVED)).get("detail").getAsString()
                .contains("isn't a method JDT LS serves"));
        assertTrue(body(LspPassthrough.refusal("workspace/executeCommand", json("{\"command\": \"java.new.command\"}"), SERVED)).get("detail")
                .getAsString().contains("advertises that the endpoint hasn't classified yet"));
        assertTrue(body(LspPassthrough.refusal("workspace/executeCommand", json("{\"command\": \"java.made.up\"}"), SERVED)).get("detail")
                .getAsString().contains("doesn't advertise it"));
        JsonObject invalid = body(LspPassthrough.refusal("workspace/executeCommand", json("{\"arguments\": []}"), SERVED));
        assertEquals("invalid-request", invalid.get("code").getAsString());
        assertEquals("/params/command", invalid.getAsJsonArray("errors").get(0).getAsJsonObject().get("field").getAsString());
    }

    @Test
    void allowedRequestsAndCommandsPass() {
        assertNull(LspPassthrough.refusal("textDocument/hover", json("{}"), SERVED));
        assertNull(LspPassthrough.refusal("workspace/executeCommand", json("{\"command\": \"java.project.getClasspaths\", \"arguments\": []}"),
                SERVED));
    }

    @Test
    void coverageNamesWhatIsUnclassified() {
        String log = LspPassthrough.coverage(SERVED);
        assertEquals("passthrough: JDT LS serves 6 methods (3 requests, 3 notifications): 2 allowed, 2 refused, 2 unclassified"
                + " [java/newNotice (notification), java/newThing]; it advertises 3 commands: 1 allowed, 1 refused, 1 unclassified"
                + " [java.new.command]", log);
        LspPassthrough.served = new LspPassthrough.Served(Map.of(), List.of());
    }

    // --- URIs ------------------------------------------------------------------------------------

    record Setup(Registry registry, Registry.Worktree a, Registry.Worktree b, Registry.Worktree feature, Map<String, Path> locations) {}

    static Setup setup(Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("wt-a/app"));
        Files.createDirectories(tmp.resolve("wt-b/app/src"));
        Registry r = new Registry(tmp.resolve("worktrees.json"));
        Registry.Worktree a = r.add(tmp.resolve("wt-a"));
        Registry.Worktree b = r.add(tmp.resolve("wt-b"));
        b.modules = List.of(new Registry.Module(".", "g:root:1", "pom", Registry.State.IMPORTED, null),
                new Registry.Module("app", "g:app:1", "jar", Registry.State.IMPORTED, null));
        // Review R1: a worktree whose folder name starts with another's and a dot, so its projects' names do too.
        Files.createDirectories(tmp.resolve("wt-a.feature/app"));
        Registry.Worktree feature = r.add(tmp.resolve("wt-a.feature"));
        feature.modules = b.modules;
        Map<String, Path> locations = Map.of("wt-a.app", a.root.resolve("app"), "wt-b.app", b.root.resolve("app"), "wt-a.feature.app",
                feature.root.resolve("app"));
        return new Setup(r, a, b, feature, locations);
    }

    @Test
    void fileUrisInsideTheWorktreePass(@TempDir Path tmp) throws Exception {
        Setup s = setup(tmp);
        LspPassthrough.checkUris(json("{\"textDocument\": {\"uri\": \"" + s.a().root.resolve("app/src/Foo.java").toUri() + "\"}, \"n\": 3}"),
                s.a(), s.registry(), s.locations()::get);
        LspPassthrough.checkUris(json("{\"command\": \"x\", \"arguments\": [\"file:" + s.a().root + "/app/../app/pom.xml\", \"untitled:x\"]}"),
                s.a(), s.registry(), s.locations()::get);
        LspPassthrough.checkUris(null, s.a(), s.registry(), s.locations()::get);
    }

    @Test
    void reviewR1AJdtUrisProjectIsPlacedByItsLocationNotItsName(@TempDir Path tmp) throws Exception {
        Setup s = setup(tmp);
        // wt-a.feature's project is named wt-a.feature.app, which starts with "wt-a.": it's still not wt-a's.
        String feature = "jdt://contents/java.base/java.lang/String.class?=wt-a.feature.app/%5C/usr%5C/lib%5C/jvm%3Cjava.lang%28String.class";
        JsonObject o = body(assertThrows(Problem.class, () -> LspPassthrough.checkUris(json("{\"uri\": \"" + feature + "\"}"), s.a(), s.registry(),
                s.locations()::get)));
        assertEquals("uri-outside-worktree", o.get("code").getAsString());
        assertEquals("{\"set\":{\"/worktree\":\"" + s.feature().root + "\",\"/module\":\"app\"},\"about\":\"the worktree wt-a.feature, module app,"
                + " which holds the project\"}", o.getAsJsonArray("candidates").get(0).toString());
        assertTrue(o.get("detail").getAsString().contains("through the project wt-a.feature.app, located at " + s.feature().root.resolve("app")),
                o.toString());
        LspPassthrough.checkUris(json("{\"uri\": \"" + feature + "\"}"), s.feature(), s.registry(), s.locations()::get);
        // A project JDT LS doesn't have can't be placed in any worktree.
        String unknown = feature.replace("=wt-a.feature.app", "=wt-a.gone");
        o = body(assertThrows(Problem.class, () -> LspPassthrough.checkUris(json("{\"uri\": \"" + unknown + "\"}"), s.a(), s.registry(),
                s.locations()::get)));
        assertTrue(o.get("detail").getAsString().contains("which JDT LS doesn't have"), o.toString());
    }

    @Test
    void aFileUriInAnotherWorktreeNamesIt(@TempDir Path tmp) throws Exception {
        Setup s = setup(tmp);
        String uri = s.b().root.resolve("app/src/Foo.java").toUri().toString();
        Problem p = assertThrows(Problem.class, () -> LspPassthrough.checkUris(json("{\"textDocument\": {\"uri\": \"" + uri + "\"}}"), s.a(),
                s.registry(), s.locations()::get));
        JsonObject o = body(p);
        assertEquals("uri-outside-worktree", o.get("code").getAsString());
        assertEquals(422, o.get("status").getAsInt());
        assertEquals("/params/textDocument/uri", o.get("field").getAsString());
        assertEquals("{\"set\":{\"/worktree\":\"" + s.b().root + "\",\"/module\":\"app\"},\"about\":\"the worktree wt-b, module app, which holds the file\"}",
                o.getAsJsonArray("candidates").get(0).toString());
        assertTrue(o.get("detail").getAsString().contains("is in the worktree " + s.b().root), o.toString());

        // Out of every worktree, through a "..", or in an array: no candidate.
        p = assertThrows(Problem.class, () -> LspPassthrough.checkUris(json("{\"a\": [\"file://" + s.a().root + "/../elsewhere/X.java\"]}"),
                s.a(), s.registry(), s.locations()::get));
        o = body(p);
        assertEquals("/params/a/0", o.get("field").getAsString());
        assertEquals(0, o.getAsJsonArray("candidates").size());
        assertTrue(o.get("detail").getAsString().contains("outside every registered worktree"), o.toString());
    }

    @Test
    void aMalformedFileUriIsAnInvalidRequest(@TempDir Path tmp) throws Exception {
        Setup s = setup(tmp);
        Problem p = assertThrows(Problem.class, () -> LspPassthrough.checkUris(json("{\"uri\": \"file://host/x.java\"}"), s.a(), s.registry(), s.locations()::get));
        assertEquals("invalid-request", p.code.id);
    }

    @Test
    void aJdtUriMustNameAProjectOfTheWorktree(@TempDir Path tmp) throws Exception {
        Setup s = setup(tmp);
        String own = "jdt://contents/java.base/java.lang/String.class?=wt-a.app/%5C/usr%5C/lib%5C/jvm%3Cjava.lang%28String.class";
        String other = own.replace("=wt-a.app", "=wt-b.app");
        assertEquals("wt-a.app", LspPassthrough.jdtProject(own));
        assertNull(LspPassthrough.jdtProject("jdt://contents/x.class"));
        LspPassthrough.checkUris(json("{\"uri\": \"" + own + "\"}"), s.a(), s.registry(), s.locations()::get);
        JsonObject o = body(assertThrows(Problem.class, () -> LspPassthrough.checkUris(json("{\"uri\": \"" + other + "\"}"), s.a(), s.registry(), s.locations()::get)));
        assertEquals("uri-outside-worktree", o.get("code").getAsString());
        assertEquals(s.b().root.toString(), o.getAsJsonArray("candidates").get(0).getAsJsonObject().getAsJsonObject("set").get("/worktree")
                .getAsString());
    }
}
