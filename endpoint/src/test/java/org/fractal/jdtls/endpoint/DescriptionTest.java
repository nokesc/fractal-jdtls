// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** The description is the contract (design §11): every route and every code the endpoint has must be in it. */
class DescriptionTest {

    static Description description() {
        try {
            return Description.load();
        } catch (StartupException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void everyRegisteredRouteIsAnOperationOfTheDescription() {
        Description d = description();
        HttpApi api = new HttpApi(d, HttpApiTest.lifecycle(), GateTest.TOKEN.getBytes());
        new WorktreeOps(null, new Registry(java.nio.file.Path.of("/nowhere/worktrees.json")), java.util.concurrent.CompletableFuture::new,
                () -> 0).register(api);
        Set<String> missing = new TreeSet<>(api.routes());
        missing.removeAll(d.operations());
        assertTrue(missing.isEmpty(), "routes missing from the description: " + missing);
        assertEquals(Set.of("GET /v1/status", "GET /v1/openapi.json", "POST /v1/shutdown", "POST /v1/worktrees/add",
                "POST /v1/worktrees/remove", "POST /v1/worktrees/list", "POST /v1/modules/check", "POST /v1/build", "POST /v1/problems",
                "POST /v1/definition", "POST /v1/references", "POST /v1/implementations", "POST /v1/type-hierarchy", "POST /v1/call-hierarchy",
                "POST /v1/outline", "POST /v1/symbols", "POST /v1/describe", "POST /v1/refactor/rename", "POST /v1/refactor/organize-imports",
                "POST /v1/refactor/extract-variable", "POST /v1/refactor/extract-constant", "POST /v1/refactor/extract-method",
                "POST /v1/refactor/inline-variable", "POST /v1/lsp"),
                api.routes());
        assertEquals(24, d.operations().size());
    }

    @Test
    void everyCodeIsInTheCatalogue() {
        Description d = description();
        for (Code code : Code.values()) {
            Description.Entry entry = d.entry(code);
            assertNotNull(entry, code.id);
            assertFalse(code.hint.isBlank(), code.id);
        }
        assertEquals(43, d.codes().size());
    }

    @Test
    void aProblemCarriesItsCataloguedStatusTitleAndRetryable() {
        Description d = description();
        JsonObject o = Problem.of(Code.NOT_READY).detail("JDT LS is still starting.").retryAfter(2).with("reason", "starting")
                .toJson(d, "urn:fractal-jdtls:request:a");
        assertEquals("urn:fractal-jdtls:error:not-ready", o.get("type").getAsString());
        assertEquals("Not ready", o.get("title").getAsString());
        assertEquals(503, o.get("status").getAsInt());
        assertEquals("not-ready", o.get("code").getAsString());
        assertEquals(true, o.get("retryable").getAsBoolean());
        assertEquals(2, o.get("retryAfter").getAsInt());
        assertEquals("starting", o.get("reason").getAsString());
        assertEquals("urn:fractal-jdtls:request:a", o.get("instance").getAsString());
        assertEquals(Code.NOT_READY.hint, o.get("hint").getAsString());

        JsonObject internal = Problem.of(Code.INTERNAL).detail("x").toJson(d, "urn:fractal-jdtls:request:b");
        assertEquals(500, internal.get("status").getAsInt());
        assertEquals(false, internal.get("retryable").getAsBoolean());
    }

    @Test
    void invalidRequestListsEachField() {
        JsonObject o = Problem.of(Code.INVALID_REQUEST).detail("2 fields are invalid.").error("/a", "required").error("/b", "unknown field")
                .toJson(description(), null);
        assertEquals(2, o.getAsJsonArray("errors").size());
        assertEquals("/b", o.getAsJsonArray("errors").get(1).getAsJsonObject().get("field").getAsString());
        assertEquals(400, o.get("status").getAsInt());
    }
}
