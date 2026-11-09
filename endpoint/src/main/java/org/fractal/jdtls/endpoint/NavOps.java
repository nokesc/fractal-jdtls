// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The navigation operations (design §11 "Navigation and search"): {@code definition}, {@code references},
 * {@code implementations}, {@code type-hierarchy}, {@code call-hierarchy}, {@code outline}, {@code symbols} and
 * {@code describe}. Each reads its request strictly, then runs as every operation on code does
 * ({@link CodeRequest}): the fixed point first, with its fast path, and the answer, from {@link Navigation}, read
 * under the worktree's read lock and reported with {@code sync}. No Eclipse class is named here, so none loads
 * before JDT LS has started.
 */
final class NavOps {

    static final long TIMEOUT_DEFAULT_MS = 120_000;
    static final List<String> KINDS = List.of("package", "class", "interface", "enum", "record", "annotation", "method", "constructor", "field",
            "enum-constant", "local-variable", "parameter", "type-parameter", "initializer");
    private static final Set<String> ANCHOR_FIELDS = Set.of("file", "text", "within", "select");

    /** A text anchor as the request wrote it ({@code TextAnchor}). */
    record Anchor(String file, String text, String within, String select) {}

    /** A {@code CodeTarget}: a Java name, or a text anchor. */
    record Target(String name, Anchor anchor) {
        String describe() {
            return name != null ? name : anchor.file() + " '" + HttpApi.printable(anchor.select() != null ? anchor.select() : anchor.text()) + "'";
        }
    }

    interface Op {
        JsonObject answer(CodeRequest.Scope scope) throws Exception;
    }

    private final WorktreeOps ops;

    NavOps(WorktreeOps ops) {
        this.ops = ops;
    }

    void register(HttpApi http) {
        route(http, "definition", (f, lsp) -> {
            Target t = target(f);
            return s -> Navigation.definition(s, lsp, t);
        }, o -> o.getAsJsonArray("definitions").size() + " definitions");
        route(http, "references", (f, lsp) -> {
            Target t = target(f);
            boolean declaration = f.bool("includeDeclaration", false);
            int limit = (int) f.integer("limit", 1, 10_000, 200);
            return s -> Navigation.references(s, lsp, t, declaration, limit);
        }, o -> o.get("total") + " references");
        route(http, "implementations", (f, lsp) -> {
            Target t = target(f);
            int limit = (int) f.integer("limit", 1, 10_000, 200);
            return s -> Navigation.implementations(s, lsp, t, limit);
        }, o -> o.get("total") + " implementations");
        route(http, "type-hierarchy", (f, lsp) -> {
            Target t = target(f);
            String direction = f.oneOf("direction", List.of("supertypes", "subtypes", "both"), "both");
            int depth = (int) f.integer("depth", 1, 50, 10);
            return s -> Navigation.typeHierarchy(s, lsp, t, direction, depth);
        }, o -> "the hierarchy of " + o.getAsJsonObject("type").get("name").getAsString());
        route(http, "call-hierarchy", (f, lsp) -> {
            Target t = target(f);
            boolean incoming = f.oneOf("direction", List.of("incoming", "outgoing"), "incoming").equals("incoming");
            int depth = (int) f.integer("depth", 1, 5, 1);
            return s -> Navigation.callHierarchy(s, lsp, t, incoming, depth);
        }, o -> o.getAsJsonArray("calls").size() + " calls");
        route(http, "outline", (f, lsp) -> {
            String file = f.string("file", true);
            return s -> Navigation.outline(s, lsp, file);
        }, o -> o.getAsJsonArray("symbols").size() + " top-level symbols");
        route(http, "symbols", (f, lsp) -> {
            String query = f.string("query", true);
            List<String> kinds = f.strings("kinds", KINDS.size(), KINDS);
            int limit = (int) f.integer("limit", 1, 1000, 50);
            return s -> Navigation.symbols(s, query, kinds == null ? null : Set.copyOf(kinds), limit);
        }, o -> o.get("total") + " symbols");
        route(http, "describe", (f, lsp) -> {
            Target t = target(f);
            boolean members = f.bool("members", true);
            return s -> Navigation.describe(s, lsp, t, members);
        }, o -> o.get("kind").getAsString() + " " + o.get("element").getAsString());
    }

    interface Parse {
        Op parse(Fields f, LspClient lsp) throws Problem;
    }

    private void route(HttpApi http, String name, Parse parse, java.util.function.Function<JsonObject, String> log) {
        http.route("POST", "/v1/" + name, TIMEOUT_DEFAULT_MS, r -> CompletableFuture.completedFuture(HttpApi.Response.json(200, run(r, name, parse, log))));
    }

    private JsonObject run(HttpApi.Request request, String name, Parse parse, java.util.function.Function<JsonObject, String> log) throws Problem {
        Fields f = new Fields(HttpApi.object(request), "POST /v1/" + name);
        String[] target = CodeRequest.target(f);
        Op op = parse.parse(f, ops.lsp());
        CodeRequest.Common c = CodeRequest.rest(f, target, TIMEOUT_DEFAULT_MS);
        f.done();
        CodeRequest.validate(c);
        return CodeRequest.run(ops, request, name, c, false, false, s -> Navigation.answer(op, s), log);
    }

    /** {@code target}: a non-empty string, or an object with {@code file} and {@code text}, and optionally {@code within} and {@code select}. */
    static Target target(Fields f) {
        JsonElement e = f.element("target", true);
        if (e == null) {
            return null;
        }
        if (e instanceof JsonPrimitive p && p.isString()) {
            if (p.getAsString().isBlank()) {
                f.error("target", "must not be empty: a Java name such as com.acme.Foo#bar(int), or a text anchor");
                return null;
            }
            return new Target(p.getAsString(), null);
        }
        if (!e.isJsonObject()) {
            f.error("target", "must be a Java name (a string), or a text anchor (an object with file and text)");
            return null;
        }
        JsonObject o = e.getAsJsonObject();
        boolean ok = true;
        for (String key : o.keySet()) {
            if (!ANCHOR_FIELDS.contains(key)) {
                f.error("target/" + key, "unknown field; a text anchor takes file, text, within and select");
                ok = false;
            }
        }
        String[] values = new String[4];
        String[] names = {"file", "text", "within", "select"};
        for (int i = 0; i < names.length; i++) {
            JsonElement v = o.get(names[i]);
            if (v == null || v.isJsonNull()) {
                if (i < 2) {
                    f.error("target/" + names[i], "required");
                    ok = false;
                }
            } else if (!(v instanceof JsonPrimitive p) || !p.isString()) {
                f.error("target/" + names[i], "must be a string");
                ok = false;
            } else if (p.getAsString().isEmpty()) {
                f.error("target/" + names[i], "must not be empty");
                ok = false;
            } else {
                values[i] = p.getAsString();
            }
        }
        return ok ? new Target(null, new Anchor(values[0], values[1], values[2], values[3])) : null;
    }
}
