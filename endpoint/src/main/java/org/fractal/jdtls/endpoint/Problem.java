// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * An RFC 9457 problem ({@code application/problem+json}), written for an LLM (design §11 "Errors"): a stable
 * {@code code}, {@code detail} in the caller's terms, a {@code hint}, and {@code retryable}. Status, title and
 * retryable come from the description's catalogue, so they can't disagree with the contract.
 */
final class Problem extends Exception {

    private static final long serialVersionUID = 1L;
    static final String TYPE_PREFIX = "urn:fractal-jdtls:error:";

    final Code code;
    private String detail;
    private String hint;
    private Integer retryAfter;
    private final JsonObject extra = new JsonObject();

    private Problem(Code code) {
        super(code.id, null, false, false);
        this.code = code;
        this.hint = code.hint;
    }

    static Problem of(Code code) {
        return new Problem(code);
    }

    Problem detail(String detail) {
        this.detail = detail;
        return this;
    }

    /** The detail, for tests. */
    String detail() {
        return detail;
    }

    Problem hint(String hint) {
        this.hint = hint;
        return this;
    }

    Problem retryAfter(int seconds) {
        this.retryAfter = Math.max(1, seconds);
        return this;
    }

    Problem with(String field, JsonElement value) {
        extra.add(field, value);
        return this;
    }

    Problem with(String field, String value) {
        extra.addProperty(field, value);
        return this;
    }

    /** invalid-request: one entry of {@code errors}. */
    Problem error(String pointer, String message) {
        JsonArray errors = extra.has("errors") ? extra.getAsJsonArray("errors") : new JsonArray();
        JsonObject e = new JsonObject();
        e.addProperty("field", pointer);
        e.addProperty("message", message);
        errors.add(e);
        extra.add("errors", errors);
        return this;
    }

    Integer retryAfter() {
        return retryAfter;
    }

    int status(Description description) {
        return description.entry(code).status();
    }

    /** The body. {@code unauthorized} says no more than its code (design §11 "Security"). */
    JsonObject toJson(Description description, String instance) {
        Description.Entry entry = description.entry(code);
        JsonObject o = new JsonObject();
        o.addProperty("type", TYPE_PREFIX + code.id);
        o.addProperty("title", entry.title());
        o.addProperty("status", entry.status());
        o.addProperty("code", code.id);
        if (code != Code.UNAUTHORIZED && detail != null) {
            o.addProperty("detail", detail);
        }
        o.addProperty("hint", hint);
        o.addProperty("retryable", entry.retryable());
        if (retryAfter != null) {
            o.addProperty("retryAfter", retryAfter);
        }
        if (code != Code.UNAUTHORIZED) {
            if (instance != null) {
                o.addProperty("instance", instance);
            }
            for (var e : extra.entrySet()) {
                o.add(e.getKey(), e.getValue());
            }
        }
        return o;
    }
}
