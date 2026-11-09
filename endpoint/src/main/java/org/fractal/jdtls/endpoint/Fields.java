// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A request body's fields, read strictly: each field the operation reads is taken once, with its type checked, and
 * {@link #done()} refuses anything left over. Every problem is collected, so one {@code invalid-request} lists them
 * all.
 */
final class Fields {

    private final JsonObject body;
    private final String operation;
    private final Set<String> read = new LinkedHashSet<>();
    private final Problem invalid = Problem.of(Code.INVALID_REQUEST);
    private int errors;

    Fields(JsonObject body, String operation) {
        this.body = body;
        this.operation = operation;
    }

    private JsonElement take(String name) {
        read.add(name);
        JsonElement e = body.get(name);
        return e == null || e.isJsonNull() ? null : e;
    }

    /** The field's JSON as it is, or null when absent; its type is the caller's to check. */
    JsonElement element(String name, boolean required) {
        JsonElement e = take(name);
        if (e == null && required) {
            error(name, "required");
        }
        return e;
    }

    /** An error at {@code /<pointer>}, collected with the others. */
    void error(String name, String message) {
        invalid.error("/" + name, message);
        errors++;
    }

    String string(String name, boolean required) {
        JsonElement e = take(name);
        if (e == null) {
            if (required) {
                error(name, "required");
            }
            return null;
        }
        if (!(e instanceof JsonPrimitive p) || !p.isString()) {
            error(name, "must be a string");
            return null;
        }
        if (p.getAsString().isEmpty()) {
            error(name, "must not be empty");
            return null;
        }
        return p.getAsString();
    }

    boolean bool(String name, boolean fallback) {
        JsonElement e = take(name);
        if (e == null) {
            return fallback;
        }
        if (!(e instanceof JsonPrimitive p) || !p.isBoolean()) {
            error(name, "must be true or false");
            return fallback;
        }
        return p.getAsBoolean();
    }

    long integer(String name, long min, long max, long fallback) {
        JsonElement e = take(name);
        if (e == null) {
            return fallback;
        }
        if (e instanceof JsonPrimitive p && p.isNumber()) {
            try {
                long v = new java.math.BigDecimal(p.getAsString()).longValueExact();
                if (v >= min && v <= max) {
                    return v;
                }
            } catch (ArithmeticException | NumberFormatException ignored) {
                // below
            }
        }
        error(name, String.format(java.util.Locale.ROOT, "must be an integer from %d to %d", min, max));
        return fallback;
    }

    /** One of {@code allowed}, or {@code fallback} when absent. */
    String oneOf(String name, java.util.List<String> allowed, String fallback) {
        JsonElement e = take(name);
        if (e == null) {
            return fallback;
        }
        if (e instanceof JsonPrimitive p && p.isString() && allowed.contains(p.getAsString())) {
            return p.getAsString();
        }
        error(name, "must be one of " + String.join(", ", allowed));
        return fallback;
    }

    /**
     * An array of non-empty strings, at most {@code maxItems}, or null when absent. With {@code allowed}, each must be
     * one of them, at least one is required, and none may repeat.
     */
    java.util.List<String> strings(String name, int maxItems, java.util.List<String> allowed) {
        JsonElement e = take(name);
        if (e == null) {
            return null;
        }
        if (!e.isJsonArray()) {
            error(name, "must be an array of strings");
            return null;
        }
        java.util.List<String> values = new java.util.ArrayList<>();
        int i = 0;
        boolean ok = true;
        for (JsonElement item : e.getAsJsonArray()) {
            String pointer = name + "/" + i++;
            if (!(item instanceof JsonPrimitive p) || !p.isString() || p.getAsString().isEmpty()) {
                error(pointer, "must be a non-empty string");
                ok = false;
            } else if (allowed != null && !allowed.contains(p.getAsString())) {
                error(pointer, "must be one of " + String.join(", ", allowed));
                ok = false;
            } else if (allowed != null && values.contains(p.getAsString())) {
                error(pointer, "repeats " + p.getAsString());
                ok = false;
            } else {
                values.add(p.getAsString());
            }
        }
        if (values.size() > maxItems) {
            error(name, "must have at most " + maxItems + " items");
            ok = false;
        }
        if (allowed != null && i == 0) {
            error(name, "must have at least 1 item");
            ok = false;
        }
        return ok ? values : null;
    }

    /** Refuses fields the operation doesn't define, then throws one problem for every field in error. */
    void done() throws Problem {
        for (String name : body.keySet()) {
            if (!read.contains(name)) {
                error(name, "unknown field; " + operation + " takes " + String.join(", ", read));
            }
        }
        if (errors > 0) {
            throw invalid.detail(errors == 1 ? "1 field is invalid." : errors + " fields are invalid.");
        }
    }
}
