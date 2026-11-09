// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The refactorings (design §11 "Refactorings and edits"): {@code refactor/rename}, {@code /organize-imports},
 * {@code /extract-variable}, {@code /extract-constant}, {@code /extract-method} and {@code /inline-variable}. Each
 * reads its request strictly, then runs as {@link CodeRequest#edit} says: the fixed point, the edit computed by JDT
 * ({@link Refactorings}) under the read lock, and, unless {@code dryRun}, the apply as one more fixed point, with the
 * worktree's problems after it. No Eclipse class is named here, so none loads before JDT LS has started.
 */
final class EditOps {

    static final long TIMEOUT_DEFAULT_MS = 300_000;

    /** What the answer returns after the apply: the worktree's problems of these severities, at most {@code limit}. */
    record After(Set<String> severities, int limit) {}

    private final WorktreeOps ops;
    private HttpApi http;

    EditOps(WorktreeOps ops) {
        this.ops = ops;
    }

    /** What a refactoring reads beyond the common fields: its computation, given the fixed point's scope. */
    private interface Parse {
        CodeRequest.Compute parse(Fields f, EditOps.After after) throws Problem;
    }

    void register(HttpApi http) {
        this.http = http;
        route(http, "rename", (f, after) -> {
            NavOps.Target t = NavOps.target(f);
            String newName = f.string("newName", true);
            return s -> Refactorings.rename(s, ops.sync(), t, newName, after);
        });
        route(http, "organize-imports", (f, after) -> {
            String file = f.string("file", true);
            Map<String, String> choices = choices(f);
            return s -> Refactorings.organizeImports(s, ops.sync(), file, choices, after);
        });
        route(http, "extract-variable", (f, after) -> {
            NavOps.Anchor a = anchor(f);
            String name = f.string("name", true);
            boolean all = f.bool("replaceAll", false);
            return s -> Refactorings.extractVariable(s, ops.sync(), a, name, all, after);
        });
        route(http, "extract-constant", (f, after) -> {
            NavOps.Anchor a = anchor(f);
            String name = f.string("name", true);
            boolean all = f.bool("replaceAll", true);
            return s -> Refactorings.extractConstant(s, ops.sync(), a, name, all, after);
        });
        route(http, "extract-method", (f, after) -> {
            NavOps.Anchor a = anchor(f);
            String name = f.string("name", true);
            return s -> Refactorings.extractMethod(s, ops.sync(), a, name, after);
        });
        route(http, "inline-variable", (f, after) -> {
            NavOps.Anchor a = anchor(f);
            return s -> Refactorings.inlineVariable(s, ops.sync(), a, after);
        });
    }

    private void route(HttpApi http, String name, Parse parse) {
        String path = "/v1/refactor/" + name;
        http.route("POST", path, TIMEOUT_DEFAULT_MS, r -> CompletableFuture.completedFuture(HttpApi.Response.json(200, run(r, "refactor/" + name, parse))));
    }

    private JsonObject run(HttpApi.Request request, String name, Parse parse) throws Problem {
        JsonObject body = HttpApi.object(request);
        Fields f = new Fields(body, "POST /v1/" + name);
        String[] target = CodeRequest.target(f);
        List<String> severities = f.strings("severities", ProblemOps.SEVERITIES.size(), ProblemOps.SEVERITIES);
        long limit = f.integer("limit", 1, ProblemOps.LIMIT_MAX, ProblemOps.LIMIT_DEFAULT);
        boolean dryRun = f.bool("dryRun", false);
        String expect = f.string("expectWriteSetDigest", false);
        if (expect != null && !expect.matches("[0-9a-f]{64}")) {
            f.error("expectWriteSetDigest", "must be a writeSetDigest as an answer gives it: 64 lowercase hex digits");
            expect = null;
        }
        Set<String> wanted = new LinkedHashSet<>(severities == null ? List.of("error") : severities);
        CodeRequest.Compute compute = parse.parse(f, new EditOps.After(wanted, (int) limit));
        CodeRequest.Common c = CodeRequest.rest(f, target, TIMEOUT_DEFAULT_MS);
        f.done();
        CodeRequest.validate(c);
        return CodeRequest.edit(ops, http, request, body, name, c, dryRun, expect, compute, o -> o.getAsJsonArray("files").size() + " files");
    }

    /** {@code target} as a text anchor: the extracts and inline take code without a name. */
    static NavOps.Anchor anchor(Fields f) {
        NavOps.Target t = NavOps.target(f);
        if (t != null && t.anchor() == null) {
            f.error("target", "must be a text anchor, an object with file and text (and optionally within and select): this operation takes code"
                    + " without a name, such as an expression or statements");
            return null;
        }
        return t == null ? null : t.anchor();
    }

    /** {@code choices}: an object from a simple type name to the fully qualified type to import for it. */
    static Map<String, String> choices(Fields f) {
        JsonElement e = f.element("choices", false);
        Map<String, String> choices = new LinkedHashMap<>();
        if (e == null || e.isJsonNull()) {
            return choices;
        }
        if (!e.isJsonObject()) {
            f.error("choices", "must be an object, such as {\"List\": \"java.util.List\"}");
            return choices;
        }
        for (Map.Entry<String, JsonElement> c : e.getAsJsonObject().entrySet()) {
            if (!NameSyntax.identifier(c.getKey())) {
                f.error("choices/" + c.getKey(), "the key must be a simple type name, such as List");
            } else if (!(c.getValue() instanceof JsonPrimitive p) || !p.isString() || !qualified(p.getAsString())) {
                f.error("choices/" + c.getKey(), "must be a fully qualified type name, such as java.util.List");
            } else if (!simpleName(p.getAsString()).equals(c.getKey())) {
                f.error("choices/" + c.getKey(), "'" + HttpApi.printable(p.getAsString()) + "' isn't named " + c.getKey());
            } else {
                choices.put(c.getKey(), p.getAsString());
            }
        }
        return choices;
    }

    static String simpleName(String qualified) {
        return qualified.substring(qualified.lastIndexOf('.') + 1);
    }

    private static boolean qualified(String name) {
        String[] parts = name.split("\\.", -1);
        if (parts.length < 2) {
            return false;
        }
        for (String part : parts) {
            if (!NameSyntax.identifier(part)) {
                return false;
            }
        }
        return true;
    }
}
