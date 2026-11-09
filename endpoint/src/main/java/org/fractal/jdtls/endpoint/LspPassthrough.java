// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

/**
 * The LSP passthrough, {@code POST /v1/lsp} (design §11 "The LSP passthrough"): an LSP request sent to JDT LS
 * unchanged, after the fixed point, under the worktree's read lock, like every read ({@link CodeRequest}). Its deadline
 * cancels it with {@code $/cancelRequest}; JDT LS's error is {@code lsp-error}, kept as it came.
 *
 * <p>Every method and {@code workspace/executeCommand} command is classified here, from the pinned JDT LS's own lists
 * (the methods its server class serves, and the commands it advertises in {@code initialize}): a request is sent only
 * if it's allowed, so a method or command a later JDT LS adds is refused until it's classified, and the startup log
 * names it ({@link #coverage}). URIs ({@code file:}, and {@code jdt:} with a project) in {@code params} must lie in the
 * named worktree, the only one the fixed point reconciled. A {@code workspace/applyEdit} JDT LS sends meanwhile is
 * refused, and listed in the answer ({@link LspClient#watchApplyEdits}). No Eclipse class is named here.
 */
final class LspPassthrough {

    static final long TIMEOUT_DEFAULT_MS = NavOps.TIMEOUT_DEFAULT_MS;
    static final String EXECUTE_COMMAND = "workspace/executeCommand";
    /** The test-only point at which a passthrough request is held inside JDT LS ({@link TestJobs}). */
    static final String HOLD_POINT = "lsp-hold";

    /** Why a method or command is refused, and what to use instead (the problem's {@code hint}). */
    record Refusal(String why, String instead) {}

    private static final String FOLDERS = "Use `POST /v1/worktrees/add` and `POST /v1/worktrees/remove`: the endpoint owns JDT LS's folders.";
    private static final String ON_DISK = "Write the file on disk and send your request: every request reconciles the worktree with the disk first."
            + " Name a file whose modification time didn't change in `changedPaths`.";
    private static final String BUILD = "Use `POST /v1/build`, which also takes in changed POMs, then `POST /v1/problems`.";
    private static final String POM = "Change the module's pom.xml on disk, then call `POST /v1/build`: the POM is what decides a module's"
            + " classpath and settings.";
    private static final String NOTIFICATION = "Send a request instead: the passthrough sends requests only.";

    /** The requests the passthrough sends, each with why it's safe: it reads, or returns an edit without applying it. */
    static final Map<String, String> ALLOWED_METHODS = new LinkedHashMap<>();
    /** The methods it refuses: lifecycle, folders, document synchronization, settings, builds and notifications. */
    static final Map<String, Refusal> REFUSED_METHODS = new LinkedHashMap<>();
    /** The {@code workspace/executeCommand} commands it sends. */
    static final Map<String, String> ALLOWED_COMMANDS = new LinkedHashMap<>();
    /** The commands it refuses: each changes projects, the workspace, settings, the build state or files. */
    static final Map<String, Refusal> REFUSED_COMMANDS = new LinkedHashMap<>();

    static {
        String reads = "reads code or JDT LS's model, and changes nothing";
        for (String m : List.of("textDocument/hover", "textDocument/definition", "textDocument/declaration", "textDocument/typeDefinition",
                "textDocument/implementation", "textDocument/references", "textDocument/documentHighlight", "textDocument/documentSymbol",
                "textDocument/documentLink", "documentLink/resolve", "textDocument/foldingRange", "textDocument/selectionRange",
                "textDocument/signatureHelp", "textDocument/completion", "completionItem/resolve", "textDocument/codeLens", "codeLens/resolve",
                "textDocument/inlayHint", "inlayHint/resolve", "textDocument/semanticTokens/full", "textDocument/semanticTokens/full/delta",
                "textDocument/semanticTokens/range", "textDocument/documentColor", "textDocument/colorPresentation",
                "textDocument/linkedEditingRange", "textDocument/moniker", "textDocument/inlineValue", "textDocument/inlineCompletion",
                "textDocument/prepareCallHierarchy", "callHierarchy/incomingCalls", "callHierarchy/outgoingCalls",
                "textDocument/prepareTypeHierarchy", "typeHierarchy/supertypes", "typeHierarchy/subtypes", "textDocument/prepareRename",
                "textDocument/diagnostic", "workspace/diagnostic", "workspace/symbol", "workspaceSymbol/resolve", "workspace/textDocumentContent",
                "java/searchSymbols", "java/extendedDocumentSymbol", "java/findLinks", "java/classFileContents", "java/getMoveDestinations",
                "java/getChangeSignatureInfo", "java/listOverridableMethods", "java/checkConstructorsStatus", "java/checkDelegateMethodsStatus",
                "java/checkExtractInterfaceStatus", "java/checkHashCodeEqualsStatus", "java/checkToStringStatus",
                "java/resolveUnimplementedAccessors", "java/inferSelection")) {
            ALLOWED_METHODS.put(m, reads);
        }
        String edits = "computes an edit and returns it, unapplied: the endpoint's client capability workspace.applyEdit is false";
        for (String m : List.of("textDocument/rename", "textDocument/codeAction", "codeAction/resolve", "textDocument/formatting",
                "textDocument/rangeFormatting", "textDocument/rangesFormatting", "textDocument/onTypeFormatting", "java/organizeImports",
                "java/cleanup", "java/addOverridableMethods", "java/generateAccessors", "java/generateConstructors",
                "java/generateDelegateMethods", "java/generateHashCodeEquals", "java/generateToString", "java/getRefactorEdit", "java/move")) {
            ALLOWED_METHODS.put(m, edits);
        }
        ALLOWED_METHODS.put(EXECUTE_COMMAND, "runs a command, only if the command is allowed (the commands' own list)");

        refuse(REFUSED_METHODS, "is a lifecycle method: the endpoint initialized JDT LS when it started", "There's nothing to do:"
                + " JDT LS is initialized. `GET /v1/status` says whether it's ready.", "initialize", "initialized");
        refuse(REFUSED_METHODS, "is a lifecycle method: it would stop JDT LS under every session that shares it",
                "Use `POST /v1/shutdown`, if stopping JDT LS for every session is what you mean.", "shutdown", "exit");
        refuse(REFUSED_METHODS, "changes JDT LS's workspace folders", FOLDERS, "workspace/didChangeWorkspaceFolders");
        refuse(REFUSED_METHODS, "synchronizes a document: the endpoint reads every file from disk, so there's nothing to synchronize", ON_DISK,
                "textDocument/didOpen", "textDocument/didChange", "textDocument/willSave", "textDocument/willSaveWaitUntil", "textDocument/didSave",
                "textDocument/didClose", "notebookDocument/didOpen", "notebookDocument/didChange", "notebookDocument/didSave",
                "notebookDocument/didClose", "java/validateDocument");
        refuse(REFUSED_METHODS, "reports a change to files: the endpoint finds changes on disk itself", ON_DISK, "workspace/didChangeWatchedFiles",
                "workspace/didCreateFiles", "workspace/didRenameFiles", "workspace/didDeleteFiles");
        refuse(REFUSED_METHODS, "is part of the file operations an editor announces before it makes them: the endpoint doesn't take part in them",
                "Make the change on disk, and the next request takes it in. To rename a type with its file, use `POST /v1/refactor/rename`.",
                "workspace/willCreateFiles", "workspace/willRenameFiles", "workspace/willDeleteFiles");
        refuse(REFUSED_METHODS, "changes JDT LS's settings, which the harness fixed when JDT LS started (--http-settings), for every session",
                "Leave the settings to the harness: they're JDT LS's settings file, read once at its start.", "workspace/didChangeConfiguration");
        refuse(REFUSED_METHODS, "updates projects from their build files behind the endpoint's back", BUILD, "java/projectConfigurationUpdate",
                "java/projectConfigurationsUpdate");
        refuse(REFUSED_METHODS, "builds behind the endpoint's back: the build state is the fixed point's", BUILD, "java/buildWorkspace",
                "java/buildProjects");
        refuse(REFUSED_METHODS, "is the endpoint's own: it sends it when a request's timeoutMs passes",
                "Send the request with the `timeoutMs` you mean.", "$/cancelRequest");
        refuse(REFUSED_METHODS, "is a notification: it gets no answer to return", NOTIFICATION, "$/setTrace", "$/progress",
                "window/workDoneProgress/cancel");

        String readsProject = "reads a project's configuration, and changes nothing";
        for (String c : List.of("java.project.getClasspaths", "java.project.getSettings", "java.project.listSourcePaths", "java.project.isTestFile",
                "java.project.getAll", "java.project.resolveSourceAttachment")) {
            ALLOWED_COMMANDS.put(c, readsProject);
        }
        String readsCode = "reads code or JDT LS's model, and changes nothing";
        for (String c : List.of("java.project.resolveStackTraceLocation", "java.project.resolveWorkspaceSymbol", "java.navigate.openTypeHierarchy",
                "java.navigate.resolveTypeHierarchy", "java.decompile", "java.getFullyQualifiedName", "java.vm.getAllInstalls",
                "java.getTroubleshootingInfo")) {
            ALLOWED_COMMANDS.put(c, readsCode);
        }
        String computes = "computes text, an edit or a location from what it's given, and returns it without applying it";
        for (String c : List.of("java.edit.organizeImports", "java.edit.stringFormatting", "java.project.resolveText",
                "java.edit.smartSemicolonDetection")) {
            ALLOWED_COMMANDS.put(c, computes);
        }

        refuse(REFUSED_COMMANDS, "changes which projects JDT LS has", FOLDERS, "java.project.import", "java.project.changeImportedProjects");
        refuse(REFUSED_COMMANDS, "deletes JDT LS's workspace state under every session", "Remove the worktree and add it again, or ask the harness"
                + " to restart JDT LS.", "java.clean.workspace");
        refuse(REFUSED_COMMANDS, "changes a project's classpath or settings behind the endpoint's back", POM, "java.project.addToSourcePath",
                "java.project.removeFromSourcePath", "java.project.updateClassPaths", "java.project.updateSourceAttachment",
                "java.project.updateSettings", "java.project.updateJdk");
        refuse(REFUSED_COMMANDS, "changes JDT LS's diagnostics state and republishes diagnostics nobody reads", "Use `POST /v1/problems`.",
                "java.project.refreshDiagnostics");
        refuse(REFUSED_COMMANDS, "writes files and runs a Gradle build", "Make the change on disk yourself.", "java.project.upgradeGradle",
                "java.protobuf.generateSources");
        refuse(REFUSED_COMMANDS, "writes module-info.java and changes the project's classpath", "Write module-info.java on disk yourself.",
                "java.project.createModuleInfo");
        refuse(REFUSED_COMMANDS, "loads OSGi bundles into JDT LS, for every session", "There's no other way to do it, on purpose.",
                "java.reloadBundles");
        refuse(REFUSED_COMMANDS, "leaks a working copy in JDT LS 1.63.0: when the pasted text needs no import, its handler returns before it"
                + " discards the working copy it made, and JDT's model keeps every such copy for the life of the process shared by every session",
                "Use the curated operations, such as `POST /v1/refactor/organize-imports`, or `java.edit.organizeImports` through the passthrough,"
                        + " which reads the file and returns its edit.", "java.edit.handlePasteEvent");
        refuse(REFUSED_COMMANDS, "is an editor's callback that changes JDT LS's completion state, shared by every session",
                "Leave it out: it changes no code.", "java.completion.onDidSelect");
    }

    private static void refuse(Map<String, Refusal> into, String why, String instead, String... names) {
        for (String n : names) {
            into.put(n, new Refusal(why, instead));
        }
    }

    /** What the startup check found: the methods JDT LS serves (true for a notification) and the commands it advertises. */
    record Served(Map<String, Boolean> methods, List<String> commands) {}

    /** Set once JDT LS is initialized ({@link #coverage}); before that, nothing is served yet. */
    static volatile Served served = new Served(Map.of(), List.of());

    /**
     * After {@code initialize}: every method JDT LS serves and every command it advertises must be classified. One
     * that isn't is refused, as unclassified, and named in the log, so a re-pin's new method or command shows.
     */
    static String coverage(Served s) {
        served = s;
        Set<String> methods = new TreeSet<>();
        s.methods().forEach((m, notification) -> {
            if (!ALLOWED_METHODS.containsKey(m) && !REFUSED_METHODS.containsKey(m)) {
                methods.add(m + (notification ? " (notification)" : ""));
            }
        });
        Set<String> commands = new TreeSet<>();
        for (String c : s.commands()) {
            if (!ALLOWED_COMMANDS.containsKey(c) && !REFUSED_COMMANDS.containsKey(c)) {
                commands.add(c);
            }
        }
        long requests = s.methods().values().stream().filter(n -> !n).count();
        long allowed = s.methods().keySet().stream().filter(ALLOWED_METHODS::containsKey).count();
        long allowedCommands = s.commands().stream().filter(ALLOWED_COMMANDS::containsKey).count();
        return String.format(Locale.ROOT, "passthrough: JDT LS serves %d methods (%d requests, %d notifications): %d allowed, %d refused,"
                + " %d unclassified%s; it advertises %d commands: %d allowed, %d refused, %d unclassified%s", s.methods().size(), requests,
                s.methods().size() - requests, allowed, s.methods().size() - allowed - methods.size(), methods.size(),
                methods.isEmpty() ? "" : " " + methods, s.commands().size(), allowedCommands, s.commands().size() - allowedCommands - commands.size(),
                commands.size(), commands.isEmpty() ? "" : " " + commands);
    }

    // --- refusals --------------------------------------------------------------------------------

    /** The method's refusal, or the command's for {@code workspace/executeCommand}; null when the request may be sent. */
    static Problem refusal(String method, JsonElement params, Served s) {
        Refusal r = REFUSED_METHODS.get(method);
        if (r != null) {
            return refused(method + " " + r.why() + ".", r.instead(), "/method");
        }
        if (!ALLOWED_METHODS.containsKey(method)) {
            Boolean notification = s.methods().get(method);
            String why = notification == null
                    ? method + " isn't a method JDT LS " + "serves, so the passthrough doesn't send it."
                    : notification ? method + " is a notification: it gets no answer to return, so the passthrough doesn't send it."
                    : method + " is a request JDT LS serves that the endpoint hasn't classified yet, so the passthrough refuses it.";
            return refused(why, notification != null && notification ? NOTIFICATION
                    : "Check the method's name against the LSP specification, or JDT LS's own `java/` methods. This operation's description lists the"
                            + " ones it refuses.", "/method");
        }
        if (!EXECUTE_COMMAND.equals(method)) {
            return null;
        }
        String command = params instanceof JsonObject o && o.get("command") instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
        if (command == null) {
            return Problem.of(Code.INVALID_REQUEST).detail("1 field is invalid.").error("/params/command",
                    "required: workspace/executeCommand's params are {\"command\": ..., \"arguments\": [...]}");
        }
        Refusal c = REFUSED_COMMANDS.get(command);
        if (c != null) {
            return refused("The command " + command + " " + c.why() + ".", c.instead(), "/params/command");
        }
        if (!ALLOWED_COMMANDS.containsKey(command)) {
            String why = s.commands().contains(command)
                    ? "The command " + command + " is one JDT LS advertises that the endpoint hasn't classified yet, so the passthrough refuses it."
                    : "The command " + command + " isn't one the passthrough allows: JDT LS doesn't advertise it.";
            return refused(why, "Use one of the commands this operation's description allows.", "/params/command");
        }
        return null;
    }

    private static Problem refused(String detail, String hint, String field) {
        return Problem.of(Code.LSP_METHOD_REFUSED).detail(detail).hint(hint).with("field", field);
    }

    // --- URIs ------------------------------------------------------------------------------------

    /**
     * Every {@code file:} URI in {@code params}, and every {@code jdt:} URI that names a project, must lie in the
     * worktree: the fixed point reconciled only that one, so one elsewhere could be read stale. A {@code jdt:} URI's
     * project lies where Eclipse has it on disk ({@code projectLocation}, {@link Workspace#projectLocation}), never
     * decided by its name: worktrees {@code wt-a} and {@code wt-a.feature} both make names that start {@code wt-a.}.
     * The first that doesn't is {@code uri-outside-worktree}, with the worktree and module that hold it as the candidate
     * when one is registered.
     */
    static void checkUris(JsonElement params, Registry.Worktree w, Registry registry, java.util.function.Function<String, Path> projectLocation)
            throws Problem {
        List<String[]> found = new ArrayList<>();
        strings(params, "/params", found);
        for (String[] f : found) {
            String pointer = f[0];
            String value = f[1];
            int colon = value.indexOf(':');
            String scheme = colon > 0 ? value.substring(0, colon).toLowerCase(Locale.ROOT) : "";
            if (scheme.equals("file")) {
                Path path = filePath(value, pointer);
                if (!path.startsWith(w.root)) {
                    throw outside(value, pointer, path, w, registry, ", " + value + ", is");
                }
            } else if (scheme.equals("jdt")) {
                String project = jdtProject(value);
                if (project == null) {
                    continue;
                }
                Path location = projectLocation.apply(project);
                if (location == null) {
                    throw Problem.of(Code.URI_OUTSIDE_WORKTREE)
                            .detail("The URI at " + pointer + " names a class file through the project " + project + ", which JDT LS doesn't have,"
                                    + " so it can't be placed in the worktree " + w.root + ".")
                            .with("field", pointer).with("candidates", new JsonArray());
                }
                if (!location.normalize().startsWith(w.root)) {
                    throw outside(value, pointer, location.normalize(), w, registry,
                            " names a class file through the project " + project + ", located at " + location + ", which is");
                }
            }
        }
    }

    private static Path filePath(String value, String pointer) throws Problem {
        try {
            URI uri = new URI(value);
            if (uri.getPath() == null || (uri.getAuthority() != null && !uri.getAuthority().isEmpty())) {
                throw new URISyntaxException(value, "not a local file URI");
            }
            return Path.of(uri.getPath()).normalize();
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw Problem.of(Code.INVALID_REQUEST).detail("1 field is invalid.").error(pointer,
                    "isn't a local file: URI, such as file:///work/wt-a/app/src/main/java/com/acme/Foo.java");
        }
    }

    private static Problem outside(String value, String pointer, Path path, Registry.Worktree w, Registry registry, String what) {
        JsonArray candidates = new JsonArray();
        String where = "outside every registered worktree";
        for (Registry.Worktree r : registry.all()) {
            if (r != w && r.state != Registry.State.REMOVING && path.startsWith(r.root)) {
                String module = ".";
                for (Registry.Module m : r.modules) {
                    Path folder = m.module().equals(".") ? r.root : r.root.resolve(m.module());
                    if (path.startsWith(folder) && (module.equals(".") || m.module().length() > module.length())) {
                        module = m.module();
                    }
                }
                JsonObject c = new JsonObject();
                JsonObject set = new JsonObject();
                set.addProperty("/worktree", r.root.toString());
                set.addProperty("/module", module);
                c.add("set", set);
                c.addProperty("about", "the worktree " + r.name + ", module " + module + ", which holds " + (value.startsWith("jdt:") ? "the project" : "the file"));
                candidates.add(c);
                where = "in the worktree " + r.root;
            }
        }
        String real = "";
        try {
            if (Files.exists(path) && path.toRealPath().startsWith(w.root)) {
                real = " It leads into this worktree through a symbolic link: spell it with the worktree's real path, " + w.root + ".";
            }
        } catch (java.io.IOException | SecurityException e) {
            real = "";
        }
        return Problem.of(Code.URI_OUTSIDE_WORKTREE)
                .detail("The URI at " + pointer + what + " " + where + ", not in the worktree " + w.root + " this request names." + real)
                .with("field", pointer).with("candidates", candidates);
    }

    /** The project a {@code jdt:} URI's handle names ({@code ?=<project>/...}), or null. */
    static String jdtProject(String value) {
        try {
            String query = new URI(value).getQuery();
            if (query == null || !query.startsWith("=")) {
                return null;
            }
            StringBuilder name = new StringBuilder();
            for (int i = 1; i < query.length(); i++) {
                char c = query.charAt(i);
                if (c == '\\' && i + 1 < query.length()) {
                    name.append(query.charAt(++i));
                } else if ("/<([{!~^#@|*'?;:)}]".indexOf(c) >= 0) {
                    break;
                } else {
                    name.append(c);
                }
            }
            return name.isEmpty() ? null : name.toString();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** Every string in the JSON, with its JSON Pointer. */
    private static void strings(JsonElement e, String pointer, List<String[]> into) {
        if (e == null || e.isJsonNull()) {
            return;
        }
        if (e.isJsonPrimitive()) {
            if (e.getAsJsonPrimitive().isString()) {
                into.add(new String[] {pointer, e.getAsString()});
            }
        } else if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            for (int i = 0; i < a.size(); i++) {
                strings(a.get(i), pointer + "/" + i, into);
            }
        } else {
            for (var entry : e.getAsJsonObject().entrySet()) {
                strings(entry.getValue(), pointer + "/" + entry.getKey().replace("~", "~0").replace("/", "~1"), into);
            }
        }
    }

    // --- the route -------------------------------------------------------------------------------

    private final WorktreeOps ops;

    LspPassthrough(WorktreeOps ops) {
        this.ops = ops;
    }

    void register(HttpApi http) {
        http.route("POST", "/v1/lsp", TIMEOUT_DEFAULT_MS, r -> CompletableFuture.completedFuture(HttpApi.Response.json(200, run(r))));
    }

    private JsonObject run(HttpApi.Request request) throws Problem {
        Fields f = new Fields(HttpApi.object(request), "POST /v1/lsp");
        String[] target = CodeRequest.target(f);
        String method = f.string("method", true);
        JsonElement params = f.element("params", false);
        if (params != null && !params.isJsonObject() && !params.isJsonArray()) {
            f.error("params", "must be an object or an array, as LSP's params are");
        }
        if (method != null && method.isEmpty()) {
            f.error("method", "must not be empty: an LSP request method, such as textDocument/hover");
        }
        CodeRequest.Common c = CodeRequest.rest(f, target, TIMEOUT_DEFAULT_MS);
        f.done();
        CodeRequest.validate(c);
        Problem refused = refusal(method, params, served);
        if (refused != null) {
            throw refused;
        }
        LspClient lsp = ops.lsp();
        return CodeRequest.run(ops, request, "lsp " + method, c, false, false, s -> {
            checkUris(params, Mapping.registered(ops.registry(), c.worktree()), ops.registry(), Workspace::projectLocation);
            try (LspClient.ApplyEdits applyEdits = lsp.watchApplyEdits()) {
                TestJobs.holdIfArmed(HOLD_POINT, s.deadline());
                JsonElement result;
                try {
                    result = lsp.call(method, params, s.deadline());
                } catch (LspClient.Expired e) {
                    throw new DiskSync.Cancelled("running", e.getMessage());
                } catch (LspClient.Failed e) {
                    throw Problem.of(Code.LSP_ERROR).detail("JDT LS answered " + method + " with error " + e.error.get("code") + ".")
                            .with("lsp", e.error);
                }
                JsonObject o = new JsonObject();
                o.add("result", result);
                o.add("applyEditsRefused", applyEdits.refused());
                return o;
            }
        }, o -> (o.get("result").isJsonNull() ? "a null result" : "a result of " + Json.bytes(o.get("result")).length + " bytes")
                + (o.getAsJsonArray("applyEditsRefused").isEmpty() ? "" : "; refused " + o.getAsJsonArray("applyEditsRefused").size()
                        + " workspace/applyEdit"));
    }
}
