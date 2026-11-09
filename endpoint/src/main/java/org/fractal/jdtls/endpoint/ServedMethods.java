// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.eclipse.jdt.ls.core.internal.handlers.JDTLanguageServer;
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints;

/**
 * What the pinned JDT LS serves, for the LSP passthrough's classification ({@link LspPassthrough#coverage}): every
 * JSON-RPC method of its server class, as lsp4j dispatches them (its {@code @JsonRequest}, {@code @JsonNotification}
 * and {@code @JsonDelegate} annotations, through {@link ServiceEndpoints#getSupportedMethods}), and the commands its
 * {@code initialize} result advertises. Read after {@code initialize}, so no Eclipse class loads earlier.
 */
final class ServedMethods {

    private ServedMethods() {}

    static LspPassthrough.Served read(JsonObject capabilities) {
        Map<String, Boolean> methods = new TreeMap<>();
        ServiceEndpoints.getSupportedMethods(JDTLanguageServer.class).forEach((name, m) -> methods.put(name, m.isNotification()));
        List<String> commands = new ArrayList<>();
        if (capabilities.get("executeCommandProvider") instanceof JsonObject provider && provider.get("commands") != null
                && provider.get("commands").isJsonArray()) {
            for (JsonElement c : provider.getAsJsonArray("commands")) {
                commands.add(c.getAsString());
            }
        }
        commands.sort(null);
        return new LspPassthrough.Served(methods, commands);
    }
}
