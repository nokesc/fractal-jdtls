// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The endpoint's contract, the OpenAPI 3.1 description in the bundle ({@code /openapi.json}, design §11 "The
 * contract"). It's served as it is, and it's the error catalogue: each code's status, title and whether it's
 * retryable come from its {@code ErrorCode} entry.
 */
final class Description {

    static final String RESOURCE = "/openapi.json";
    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    record Entry(String code, int status, String title, boolean retryable) {}

    final byte[] bytes;
    final JsonObject document;
    final String apiVersion;
    private final Map<String, Entry> catalogue = new HashMap<>();

    private Description(byte[] bytes) {
        this.bytes = bytes;
        this.document = Json.parseStrict(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        this.apiVersion = document.getAsJsonObject("info").get("version").getAsString();
        for (JsonElement e : document.getAsJsonObject("components").getAsJsonObject("schemas")
                .getAsJsonObject("ErrorCode").getAsJsonArray("oneOf")) {
            JsonObject o = e.getAsJsonObject();
            String code = o.get("const").getAsString();
            catalogue.put(code, new Entry(code, o.get("x-status").getAsInt(), o.get("title").getAsString(),
                    o.get("x-retryable").getAsBoolean()));
        }
    }

    static Description load() throws StartupException {
        byte[] bytes;
        try (InputStream in = Description.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new StartupException("the bundle has no " + RESOURCE + ". The install is damaged: reinstall it.");
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new StartupException("can't read the bundle's " + RESOURCE + ": " + e + ".");
        }
        Description description;
        try {
            description = new Description(bytes);
        } catch (RuntimeException e) {
            throw new StartupException("the bundle's " + RESOURCE + " isn't a valid description: " + e + ".");
        }
        for (Code code : Code.values()) {
            if (description.entry(code) == null) {
                throw new StartupException("the error code " + code.id + " isn't in the bundle's " + RESOURCE + ".");
            }
        }
        return description;
    }

    Entry entry(Code code) {
        return catalogue.get(code.id);
    }

    Set<String> codes() {
        return new TreeSet<>(catalogue.keySet());
    }

    /** Each operation as "METHOD /path". */
    Set<String> operations() {
        Set<String> operations = new TreeSet<>();
        for (Map.Entry<String, JsonElement> path : document.getAsJsonObject("paths").entrySet()) {
            for (String method : path.getValue().getAsJsonObject().keySet()) {
                if (METHODS.contains(method)) {
                    operations.add(method.toUpperCase(java.util.Locale.ROOT) + " " + path.getKey());
                }
            }
        }
        return operations;
    }
}
