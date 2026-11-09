// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

/** JSON through the pinned JDT LS's Gson: strict parsing (RFC 8259, one value, nothing after it). */
final class Json {

    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private Json() {}

    /** The one JSON value in text; {@link JsonParseException} with Gson's reason otherwise. */
    static JsonElement parseStrict(String text) {
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement value = JsonParser.parseReader(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new JsonParseException("more than one JSON value");
            }
            return value;
        } catch (IOException e) {
            throw new JsonParseException(e.getMessage(), e);
        }
    }

    static byte[] bytes(JsonElement value) {
        return GSON.toJson(value).getBytes(StandardCharsets.UTF_8);
    }
}
