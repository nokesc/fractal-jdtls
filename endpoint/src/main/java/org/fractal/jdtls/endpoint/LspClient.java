// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JDT LS's only LSP client, inside the JVM (design §11 "Inside JDT LS"), over two {@link HeapPipe}s.
 *
 * <p>Its own base-protocol framing ({@code Content-Length} headers) over Gson's JSON tree, from the pinned JDT LS's
 * {@code com.google.gson} bundle, as in T002.01's spike, rather than lsp4j's typed endpoint: the LSP passthrough
 * forwards params unchanged, and each answer to JDT LS is a few lines, so typed adapters would add code to build and
 * maintain for no gain (HTTP-FINDINGS "Framing").
 *
 * <p>JDT LS's requests back to the client are answered from the endpoint's own state on the endpoint's own thread,
 * never by calling out, so JDT LS never waits on HTTP.
 */
final class LspClient {

    /** How long JDT LS may take to answer {@code initialize} before startup fails: a guard, not a wait. */
    static final long INITIALIZE_GUARD_MINUTES = 10;

    interface AfterInitialize {
        void check() throws Exception;
    }

    private final OutputStream toServer;
    private final InputStream fromServer;
    private final JsonObject settings;
    private final Object writeLock = new Object();
    private final AtomicInteger ids = new AtomicInteger();
    private final Map<Integer, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final ExecutorService answerer = Executors.newSingleThreadExecutor(r -> daemon(r, "fractal-endpoint-lsp-answerer"));

    /** JDT LS's {@code initialize} result's {@code capabilities}, once it answered (the passthrough reads its commands). */
    volatile JsonObject serverCapabilities = new JsonObject();

    /** The passthrough requests in flight, each collecting the {@code workspace/applyEdit} requests JDT LS sends meanwhile. */
    private final Set<ApplyEdits> applyEditWatches = ConcurrentHashMap.newKeySet();

    /** The registered worktrees, for JDT LS's {@code workspace/workspaceFolders} request. */
    volatile java.util.function.Supplier<JsonArray> workspaceFolders = JsonArray::new;

    /** Completes once JDT LS answered {@code initialize}, the check passed and {@code initialized} was sent. */
    final CompletableFuture<Void> initialized = new CompletableFuture<>();
    /** Completes when JDT LS reports {@code ServiceReady} through {@code language/status}. */
    final CompletableFuture<Void> serviceReady = new CompletableFuture<>();

    LspClient(OutputStream toServer, InputStream fromServer, JsonObject settings) {
        this.toServer = toServer;
        this.fromServer = fromServer;
        this.settings = settings;
    }

    void start(JsonObject initializeParams, AfterInitialize check) {
        daemon(this::readLoop, "fractal-endpoint-lsp-reader").start();
        daemon(() -> initialize(initializeParams, check), "fractal-endpoint-lsp-initialize").start();
    }

    private void initialize(JsonObject params, AfterInitialize check) {
        try {
            long start = System.nanoTime();
            JsonObject response = request("initialize", params).get(INITIALIZE_GUARD_MINUTES, TimeUnit.MINUTES);
            if (response.has("error")) {
                throw new StartupException("JDT LS refused initialize: " + response.get("error") + ".");
            }
            JsonElement result = response.get("result");
            if (result != null && result.isJsonObject() && result.getAsJsonObject().get("capabilities") instanceof JsonObject capabilities) {
                serverCapabilities = capabilities;
            }
            check.check();
            notify("initialized", new JsonObject());
            Log.info("initialized in " + (System.nanoTime() - start) / 1_000_000 + " ms");
            initialized.complete(null);
        } catch (java.util.concurrent.TimeoutException e) {
            initialized.completeExceptionally(new StartupException(
                    "JDT LS didn't answer initialize within " + INITIALIZE_GUARD_MINUTES + " minutes. Its log, <data>/.metadata/.log, may say why."));
        } catch (Throwable t) {
            initialized.completeExceptionally(t);
        }
    }

    // --- sending ---------------------------------------------------------------------------------

    CompletableFuture<JsonObject> request(String method, JsonElement params) {
        int id = ids.incrementAndGet();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending.put(id, future);
        JsonObject message = message();
        message.addProperty("id", id);
        message.addProperty("method", method);
        if (params != null) {
            message.add("params", params);
        }
        try {
            send(message);
        } catch (IOException e) {
            pending.remove(id);
            future.completeExceptionally(e);
        }
        return future;
    }

    /** A request whose deadline passed: it was cancelled with {@code $/cancelRequest}. */
    static final class Expired extends Exception {
        private static final long serialVersionUID = 1L;

        Expired(String message) {
            super(message, null, false, false);
        }
    }

    /** JDT LS's answer to a request that its error rejected. */
    static final class Failed extends Exception {
        private static final long serialVersionUID = 1L;
        final JsonObject error;

        Failed(String method, JsonObject error) {
            super("JDT LS answered " + method + " with error " + error, null, false, false);
            this.error = error;
        }
    }

    /**
     * Sends a request and waits for its result until {@code deadline} ({@link System#nanoTime()}). Past it, the
     * request is cancelled with {@code $/cancelRequest} (design §11 "Threading and queuing") and this throws
     * {@link Expired}; JDT LS's error answer is {@link Failed}.
     */
    JsonElement call(String method, JsonElement params, long deadline) throws Expired, Failed, IOException, InterruptedException {
        int id = ids.incrementAndGet();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending.put(id, future);
        JsonObject message = message();
        message.addProperty("id", id);
        message.addProperty("method", method);
        if (params != null) {
            message.add("params", params);
        }
        try {
            send(message);
        } catch (IOException e) {
            pending.remove(id);
            throw e;
        }
        JsonObject response;
        try {
            response = future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            JsonObject cancel = new JsonObject();
            cancel.addProperty("id", id);
            notify("$/cancelRequest", cancel);
            Log.info("sent $/cancelRequest for request " + id + " (" + method + "): its deadline passed");
            throw new Expired("waiting for JDT LS's answer to " + method);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException("the request " + method + " failed", e.getCause());
        } finally {
            pending.remove(id);
        }
        if (response.has("error")) {
            throw new Failed(method, response.getAsJsonObject("error"));
        }
        JsonElement result = response.get("result");
        return result == null ? JsonNull.INSTANCE : result;
    }

    void notify(String method, JsonElement params) throws IOException {
        JsonObject message = message();
        message.addProperty("method", method);
        if (params != null) {
            message.add("params", params);
        }
        send(message);
    }

    private void respond(JsonElement id, JsonElement result, JsonObject error) throws IOException {
        JsonObject message = message();
        message.add("id", id);
        if (error != null) {
            message.add("error", error);
        } else {
            message.add("result", result == null ? JsonNull.INSTANCE : result);
        }
        send(message);
    }

    private static JsonObject message() {
        JsonObject message = new JsonObject();
        message.addProperty("jsonrpc", "2.0");
        return message;
    }

    private void send(JsonObject message) throws IOException {
        byte[] body = Json.bytes(message);
        byte[] header = ("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        synchronized (writeLock) {
            toServer.write(header);
            toServer.write(body);
            toServer.flush();
        }
    }

    // --- receiving -------------------------------------------------------------------------------

    private void readLoop() {
        try {
            JsonObject message;
            while ((message = readMessage()) != null) {
                dispatch(message);
            }
            Log.info("JDT LS closed its output stream");
        } catch (Throwable t) {
            Log.error("the LSP reader stopped", t);
        }
    }

    /** One message, or null at the end of the stream. */
    JsonObject readMessage() throws IOException {
        int length = -1;
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int c = fromServer.read();
            if (c < 0) {
                return null;
            }
            if (c != '\n') {
                line.write(c);
                continue;
            }
            String header = line.toString(StandardCharsets.US_ASCII).trim();
            line.reset();
            if (header.isEmpty()) {
                if (length >= 0) {
                    break;
                }
                continue; // a stray blank line; never guess a body
            }
            int colon = header.indexOf(':');
            String name = colon > 0 ? header.substring(0, colon).trim() : "";
            if (name.equalsIgnoreCase("Content-Length")) {
                length = Integer.parseInt(header.substring(colon + 1).trim());
            } else if (!name.equalsIgnoreCase("Content-Type")) {
                Log.info("unexpected bytes on JDT LS's LSP stream: " + header);
            }
        }
        byte[] body = fromServer.readNBytes(length);
        return Json.parseStrict(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private void dispatch(JsonObject message) {
        if (message.has("method")) {
            String method = message.get("method").getAsString();
            if (message.has("id")) {
                answerer.execute(() -> answer(message, method));
            } else {
                onNotification(method, message.get("params"));
            }
        } else if (message.has("id")) {
            JsonElement id = message.get("id");
            CompletableFuture<JsonObject> future = id.isJsonPrimitive() && id.getAsJsonPrimitive().isNumber()
                    ? pending.remove(id.getAsInt()) : null;
            if (future != null) {
                future.complete(message);
            } else {
                // Typically the answer to a request the endpoint cancelled: lsp4j answers a cancelled request with
                // error -32800 (RequestCancelled), which shows the cancel reached JDT LS.
                Log.info("JDT LS answered request " + id + ", which the endpoint no longer waits for: "
                        + (message.get("error") instanceof JsonObject e ? "error " + e.get("code") + " " + e.get("message") : "a result"));
            }
        }
    }

    /** JDT LS's requests back to the client, answered from the endpoint's own state (design §11). */
    void answer(JsonObject request, String method) {
        JsonElement id = request.get("id");
        try {
            respondTo(id, method, request.get("params"));
        } catch (Throwable t) {
            Log.error("answering JDT LS's " + method, t);
        }
    }

    private void respondTo(JsonElement id, String method, JsonElement params) throws IOException {
        switch (method) {
            case "workspace/configuration" -> {
                JsonArray result = new JsonArray();
                for (JsonElement item : params.getAsJsonObject().getAsJsonArray("items")) {
                    JsonElement section = item.getAsJsonObject().get("section");
                    result.add(section == null || section.isJsonNull() ? settings.deepCopy() : Settings.lookup(settings, section.getAsString()).deepCopy());
                }
                respond(id, result, null);
            }
            case "workspace/applyEdit" -> {
                // The endpoint writes edits itself, under its own checks, never on JDT LS's request (design §11).
                // During a passthrough request, the edit goes back to its caller, unapplied ("The LSP passthrough").
                JsonObject result = new JsonObject();
                result.addProperty("applied", false);
                if (applyEditWatches.isEmpty()) {
                    result.addProperty("failureReason", "the endpoint applies edits itself");
                    Log.info("refused JDT LS's workspace/applyEdit, outside any passthrough request");
                } else {
                    result.addProperty("failureReason", "the Fractal JDT LS endpoint applies no edit JDT LS sends: the LSP passthrough"
                            + " returns it to its caller, unapplied");
                    JsonObject edit = params != null && params.isJsonObject() ? params.getAsJsonObject() : new JsonObject();
                    applyEditWatches.forEach(w -> w.add(edit));
                    Log.info("refused JDT LS's workspace/applyEdit during " + applyEditWatches.size() + " passthrough requests");
                }
                respond(id, result, null);
            }
            case "workspace/workspaceFolders" -> respond(id, workspaceFolders.get(), null);
            case "window/workDoneProgress/create", "client/registerCapability", "window/showMessageRequest", "window/showDocument" ->
                    respond(id, JsonNull.INSTANCE, null);
            default -> {
                JsonObject error = new JsonObject();
                error.addProperty("code", -32601);
                error.addProperty("message", "the Fractal JDT LS endpoint doesn't handle " + method);
                respond(id, null, error);
            }
        }
    }

    /** The {@code workspace/applyEdit} requests JDT LS sent while a passthrough request was in flight, each refused. */
    static final class ApplyEdits implements AutoCloseable {
        private final Set<ApplyEdits> watches;
        private final JsonArray edits = new JsonArray();

        private ApplyEdits(Set<ApplyEdits> watches) {
            this.watches = watches;
            watches.add(this);
        }

        private synchronized void add(JsonObject params) {
            edits.add(params.deepCopy());
        }

        /** Each one's params ({@code label} and {@code edit}), as JDT LS sent them. */
        synchronized JsonArray refused() {
            return edits.deepCopy();
        }

        @Override
        public void close() {
            watches.remove(this);
        }
    }

    /** Starts collecting the {@code workspace/applyEdit} requests JDT LS sends, until closed. */
    ApplyEdits watchApplyEdits() {
        return new ApplyEdits(applyEditWatches);
    }

    private void onNotification(String method, JsonElement params) {
        if ("language/status".equals(method) && params != null && params.isJsonObject()) {
            JsonObject status = params.getAsJsonObject();
            String type = status.has("type") ? status.get("type").getAsString() : "";
            Log.info("JDT LS status " + type + ": " + (status.has("message") ? status.get("message").getAsString() : ""));
            if ("ServiceReady".equals(type)) {
                serviceReady.complete(null);
            }
        } else if ("window/showMessage".equals(method)) {
            Log.info("JDT LS message: " + params);
        }
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
