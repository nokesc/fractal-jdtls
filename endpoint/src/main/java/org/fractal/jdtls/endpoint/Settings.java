// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The LSP settings, from the harness's settings file ({@code --http-settings}), with the settings the endpoint fixes
 * (design §11 "What the endpoint fixes"). The endpoint sends them in {@code initialize} and answers
 * {@code workspace/configuration} from them.
 *
 * <p>LSP settings may be nested ({@code {"java": {"autobuild": {"enabled": false}}}}), dotted
 * ({@code {"java.autobuild.enabled": false}}) or a mix, so a fixed setting is looked for in every form. A file that
 * sets one to another value is refused, naming it: it's never silently overridden.
 */
final class Settings {

    /** The settings the endpoint's contract depends on, with their values. */
    static final Map<String, JsonPrimitive> FIXED;

    static {
        Map<String, JsonPrimitive> fixed = new LinkedHashMap<>();
        fixed.put("java.autobuild.enabled", new JsonPrimitive(false));
        fixed.put("java.configuration.updateBuildConfiguration", new JsonPrimitive("disabled"));
        FIXED = Map.copyOf(fixed);
    }

    private Settings() {}

    static JsonObject load(Optional<Path> file) throws StartupException {
        if (file.isEmpty()) {
            return withFixed(new JsonObject(), "no settings file");
        }
        Path path = file.get();
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StartupException("can't read the settings file " + path + ": " + e + ".");
        }
        JsonElement value;
        try {
            value = Json.parseStrict(text);
        } catch (JsonParseException e) {
            throw new StartupException("the settings file " + path + " isn't JSON: " + e.getMessage() + ".");
        }
        if (!value.isJsonObject()) {
            throw new StartupException("the settings file " + path + " must hold a JSON object of LSP settings, such as {\"java\": {...}}.");
        }
        return withFixed(value.getAsJsonObject(), "the settings file " + path);
    }

    /** A copy of settings with every fixed setting set, or a refusal naming each one it sets otherwise. */
    static JsonObject withFixed(JsonObject settings, String source) throws StartupException {
        List<String> conflicts = new ArrayList<>();
        for (Map.Entry<String, JsonPrimitive> fixed : FIXED.entrySet()) {
            for (JsonElement value : values(settings, fixed.getKey())) {
                if (!value.equals(fixed.getValue())) {
                    conflicts.add(fixed.getKey() + " to " + value + ", but HTTP mode fixes it to " + fixed.getValue());
                }
            }
        }
        if (!conflicts.isEmpty()) {
            throw new StartupException(source + " sets " + String.join("; ", conflicts)
                    + ". The endpoint's contract depends on these: remove them from the file, or set them to those values.");
        }
        JsonObject merged = settings.deepCopy();
        for (Map.Entry<String, JsonPrimitive> fixed : FIXED.entrySet()) {
            JsonObject node = merged;
            String[] parts = fixed.getKey().split("\\.");
            for (int i = 0; i < parts.length - 1; i++) {
                JsonElement child = node.get(parts[i]);
                if (child == null) {
                    child = new JsonObject();
                    node.add(parts[i], child);
                } else if (!child.isJsonObject()) {
                    throw new StartupException(source + " sets " + String.join(".", java.util.Arrays.copyOf(parts, i + 1))
                            + " to " + child + ", but it must be an object, because HTTP mode fixes " + fixed.getKey() + ".");
                }
                node = child.getAsJsonObject();
            }
            node.add(parts[parts.length - 1], fixed.getValue());
        }
        return merged;
    }

    /** The value of a dotted section, as JDT LS's {@code workspace/configuration} needs it, or null. */
    static JsonElement lookup(JsonObject settings, String section) {
        List<JsonElement> found = values(settings, section);
        return found.isEmpty() ? JsonNull.INSTANCE : found.get(0);
    }

    /** Every value that a dotted path reaches, with each step a single key or several dotted together. */
    static List<JsonElement> values(JsonElement node, String path) {
        List<JsonElement> found = new ArrayList<>();
        collect(node, path.split("\\."), 0, found);
        return found;
    }

    private static void collect(JsonElement node, String[] parts, int from, List<JsonElement> found) {
        if (from == parts.length) {
            found.add(node);
            return;
        }
        if (node == null || !node.isJsonObject()) {
            return;
        }
        JsonObject object = node.getAsJsonObject();
        StringBuilder key = new StringBuilder();
        for (int i = from; i < parts.length; i++) {
            if (i > from) {
                key.append('.');
            }
            key.append(parts[i]);
            JsonElement child = object.get(key.toString());
            if (child != null) {
                collect(child, parts, i + 1, found);
            }
        }
    }
}
