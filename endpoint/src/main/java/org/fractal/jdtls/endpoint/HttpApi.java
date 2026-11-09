// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The HTTP API (design §11 "Listening", "Security", "The contract", "Errors", "Status and shutdown"): the JDK's
 * {@code com.sun.net.httpserver} on {@code 127.0.0.1}, port 0.
 *
 * <p>Two kinds of threads. The JDK's server reads each request's headers and body, and writes its response, on a
 * connection thread; {@link ServerLimits} bounds how long a caller can take, and how many connections there are.
 * An operation's handler runs on the fixed pool of {@value #WORKERS} worker threads, which only a request that
 * passed the gate and whose body was read in full reaches; up to {@value #QUEUE} more wait for one, in order, and a
 * request beyond them is answered {@code busy} at once (design §11 "Threading and queuing", sizes from T002.11's
 * measurements). The lifecycle routes, which are quick and never answer {@code busy}, run on the connection thread. So a caller without the token never holds a worker, and its
 * connection is closed as soon as it's answered: its unread body isn't drained.
 *
 * <p>Every request passes the {@link Gate} first. Then its path and method are looked up among the registered
 * routes, each of which is an operation of the description (a JUnit test checks it). Every failure is a
 * {@link Problem}; anything unexpected is {@code internal}, logged with its stack trace under the request's
 * {@code instance}, and the endpoint keeps serving.
 */
final class HttpApi {

    static final int BODY_LIMIT = 1 << 20;
    static final long DRAIN_LIMIT = 64L << 20;
    /**
     * The worker threads (design §11 "Threading and queuing"): T002.11 measured the fixture's reads level off at 16
     * concurrent requests on 12 CPUs (119 a second), and beyond it only their latency and the heap grow.
     */
    static final int WORKERS = 16;
    /** The requests that wait for a worker, beyond which a request is {@code busy}: at the measured rates, under 2 s of work. */
    static final int QUEUE = 64;
    /** {@code busy}'s {@code retryAfter} when the queue is full, in seconds. */
    static final int QUEUE_FULL_RETRY_AFTER_S = 2;
    static final long STATUS_TIMEOUT_DEFAULT_MS = 120_000;
    static final long TIMEOUT_MAX_MS = 3_600_000;
    static final String INSTANCE_PREFIX = "urn:fractal-jdtls:request:";
    /** The routes that keep answering after shutdown, and never answer {@code busy} (T002.02 decision 13). */
    private static final Set<String> LIFECYCLE = Set.of("/v1/status", "/v1/openapi.json", "/v1/shutdown");

    /** A request that passed the gate, with its body read; {@code received} is {@link System#nanoTime()} when it came. */
    record Request(String method, String path, Map<String, List<String>> query, byte[] body, String instance, long received) {}

    /** A response; {@code afterSend} runs once it has been sent and the exchange closed. */
    record Response(int status, String contentType, byte[] body, Runnable afterSend) {
        Response(int status, String contentType, byte[] body) {
            this(status, contentType, body, null);
        }

        static Response json(int status, JsonElement value) {
            return new Response(status, "application/json", Json.bytes(value));
        }
    }

    interface Handler {
        CompletableFuture<Response> handle(Request request) throws Problem;
    }

    private final Description description;
    private final Lifecycle lifecycle;
    private final byte[] token;
    private final Map<String, Map<String, Handler>> routes = new LinkedHashMap<>();
    private final Map<String, Long> timeoutDefaults = new java.util.HashMap<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicLong shutdownAt = new AtomicLong();
    private ThreadPoolExecutor workers;
    /** Answers a request still queued at its deadline: one thread, which only completes futures. */
    private java.util.concurrent.ScheduledThreadPoolExecutor queueDeadlines;
    private ExecutorService connections;
    private HttpServer server;
    private Gate gate;

    HttpApi(Description description, Lifecycle lifecycle, byte[] token) {
        this.description = description;
        this.lifecycle = lifecycle;
        this.token = token.clone();
        route("GET", "/v1/status", this::status);
        route("GET", "/v1/openapi.json", this::openapi);
        route("POST", "/v1/shutdown", this::shutdown);
    }

    void route(String method, String path, Handler handler) {
        routes.computeIfAbsent(path, p -> new LinkedHashMap<>()).put(method, handler);
    }

    /**
     * A route whose requests take {@code timeoutMs}, with this default: a request still queued for a worker at its
     * deadline is answered then (T002.11's review, R1).
     */
    void route(String method, String path, long timeoutDefaultMs, Handler handler) {
        route(method, path, handler);
        timeoutDefaults.put(method + " " + path, timeoutDefaultMs);
    }

    /** Each registered route as "METHOD /path", as {@link Description#operations()} lists them. */
    Set<String> routes() {
        Set<String> all = new TreeSet<>();
        routes.forEach((path, methods) -> methods.keySet().forEach(m -> all.add(m + " " + path)));
        return all;
    }

    /** Listens on 127.0.0.1 and an ephemeral port, which this returns; refuses to without HTTP mode's limits. */
    int start() throws IOException, StartupException {
        ServerLimits.check();
        workers = new ThreadPoolExecutor(WORKERS, WORKERS, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE), threads("fractal-endpoint-http-"),
                new ThreadPoolExecutor.AbortPolicy());
        queueDeadlines = new java.util.concurrent.ScheduledThreadPoolExecutor(1, threads("fractal-endpoint-queue-deadline-"));
        queueDeadlines.setRemoveOnCancelPolicy(true);
        // Bounded by jdk.httpserver.maxConnections, and each thread's reading by sun.net.httpserver.maxReqTime.
        connections = Executors.newCachedThreadPool(threads("fractal-endpoint-connection-"));
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 0);
        server.setExecutor(connections);
        server.createContext("/", this::handle);
        server.start();
        int port = server.getAddress().getPort();
        gate = new Gate(token, port);
        return port;
    }

    void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (workers != null) {
            workers.shutdownNow();
        }
        if (queueDeadlines != null) {
            queueDeadlines.shutdownNow();
        }
        if (connections != null) {
            connections.shutdownNow();
        }
    }

    private static java.util.concurrent.ThreadFactory threads(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    // --- the request pipeline --------------------------------------------------------------------

    private void handle(HttpExchange exchange) {
        long start = System.nanoTime();
        String instance = INSTANCE_PREFIX + Integer.toHexString(requests.incrementAndGet());
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getRawPath();
        String session = exchange.getRequestHeaders().getFirst("X-Fractal-Session");
        CompletableFuture<Response> response;
        try {
            response = dispatch(exchange, method, path, instance, start);
        } catch (Problem p) {
            response = CompletableFuture.completedFuture(problem(p, instance));
        } catch (Throwable t) {
            response = CompletableFuture.completedFuture(internal(t, instance, method, path));
        }
        response.whenCompleteAsync((r, failure) -> {
            Response sent = r;
            if (failure != null) {
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
                sent = cause instanceof Problem p ? problem(p, instance) : internal(cause, instance, method, path);
            }
            send(exchange, sent);
            Log.info(instance + " " + method + " " + path + " -> " + sent.status() + " in "
                    + (System.nanoTime() - start) / 1_000_000 + " ms"
                    + (session == null ? "" : " (session " + printable(session) + ")"));
            if (sent.afterSend() != null) {
                sent.afterSend().run();
            }
        }, connections);
    }

    private CompletableFuture<Response> dispatch(HttpExchange exchange, String method, String path, String instance, long received)
            throws Problem, IOException {
        Headers headers = exchange.getRequestHeaders();
        try {
            gate.check(headers.get("Authorization"), headers.get("Host"), headers.get("Origin"));
        } catch (Problem refused) {
            // Answered and disconnected: its body, if any, is never read (sun.net.httpserver.drainAmount=0).
            exchange.getResponseHeaders().set("Connection", "close");
            throw refused;
        }
        Map<String, Handler> methods = routes.get(path);
        if (methods == null) {
            throw Problem.of(Code.ROUTE_NOT_FOUND).detail("There's no operation at " + method + " " + printable(path) + ".");
        }
        Handler handler = methods.get(method);
        if (handler == null) {
            exchange.getResponseHeaders().set("Allow", String.join(", ", methods.keySet()));
            throw Problem.of(Code.METHOD_NOT_ALLOWED)
                    .detail(printable(method) + " " + path + ": " + path + " takes " + String.join(" or ", methods.keySet()) + ".");
        }
        long since = shutdownAt.get();
        if (since != 0 && !LIFECYCLE.contains(path)) {
            throw Problem.of(Code.SHUTTING_DOWN).detail("POST /v1/shutdown was called "
                    + seconds(System.nanoTime() - since) + " s ago.");
        }
        byte[] body = body(exchange);
        Request request = new Request(method, path, query(exchange.getRequestURI().getRawQuery()), body, instance, received);
        if (LIFECYCLE.contains(path)) {
            return handler.handle(request);
        }
        return queued(request, handler, timeoutDefaults.get(method + " " + path));
    }

    /**
     * The request on the worker pool. With a deadline (its {@code timeoutMs}, else the route's default), a request
     * still queued when it passes is answered then, {@code busy} as {@link #waitedForAWorker} says, taken out of the
     * queue, and never run: whichever comes first, the worker or the deadline, claims it (T002.11's review, R1).
     */
    private CompletableFuture<Response> queued(Request request, Handler handler, Long timeoutDefaultMs) throws Problem {
        CompletableFuture<Response> answer = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicBoolean claimed = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<?>> expiry = new java.util.concurrent.atomic.AtomicReference<>();
        Runnable run = () -> {
            if (!claimed.compareAndSet(false, true)) {
                return; // answered at its deadline already
            }
            java.util.concurrent.ScheduledFuture<?> e = expiry.get();
            if (e != null) {
                e.cancel(false);
            }
            try {
                handler.handle(request).whenComplete((r, failure) -> {
                    if (failure != null) {
                        answer.completeExceptionally(failure);
                    } else {
                        answer.complete(r);
                    }
                });
            } catch (Throwable t) {
                answer.completeExceptionally(t);
            }
        };
        try {
            workers.execute(run);
        } catch (RejectedExecutionException full) {
            throw queueFull();
        }
        if (timeoutDefaultMs != null) {
            long timeoutMs = timeoutMs(request.body(), timeoutDefaultMs);
            long delay = request.received() + timeoutMs * 1_000_000 - System.nanoTime();
            expiry.set(queueDeadlines.schedule(() -> {
                if (claimed.compareAndSet(false, true)) {
                    workers.remove(run);
                    answer.completeExceptionally(waitedForAWorker((System.nanoTime() - request.received()) / 1_000_000, timeoutMs));
                }
            }, Math.max(0, delay), TimeUnit.NANOSECONDS));
            if (claimed.get()) {
                expiry.get().cancel(false); // a worker took it before the deadline was set
            }
        }
        return answer;
    }

    /** The body's {@code timeoutMs} if it's a valid one, else the route's default; the route itself refuses an invalid one. */
    static long timeoutMs(byte[] body, long timeoutDefaultMs) {
        try {
            JsonElement value = Json.parseStrict(new String(body, StandardCharsets.UTF_8));
            JsonElement t = value != null && value.isJsonObject() ? value.getAsJsonObject().get("timeoutMs") : null;
            if (t != null && t.isJsonPrimitive() && t.getAsJsonPrimitive().isNumber()) {
                double d = t.getAsDouble();
                if (d == Math.rint(d) && d >= 1 && d <= TIMEOUT_MAX_MS) {
                    return (long) d;
                }
            }
        } catch (RuntimeException e) {
            // not JSON: the route refuses it, if a worker reaches it
        }
        return timeoutDefaultMs;
    }

    /** {@code busy}: the request waited for a worker thread past its {@code timeoutMs}, so it wasn't run (design §11). */
    static Problem waitedForAWorker(long waitedMs, long timeoutMs) {
        return Problem.of(Code.BUSY)
                .detail(String.format(Locale.ROOT, "The request waited %,d ms for a worker thread, past its timeoutMs of %,d, so it wasn't run.", waitedMs, timeoutMs))
                .retryAfter(1);
    }

    /** {@code busy}: every worker is taken and {@value #QUEUE} requests already wait for one. */
    static Problem queueFull() {
        return Problem.of(Code.BUSY)
                .detail("All " + WORKERS + " worker threads are taken and " + QUEUE + " requests are already waiting for one, as many as the endpoint queues, so this one wasn't queued.")
                .retryAfter(QUEUE_FULL_RETRY_AFTER_S);
    }

    private static byte[] body(HttpExchange exchange) throws Problem, IOException {
        String declared = exchange.getRequestHeaders().getFirst("Content-Length");
        try (InputStream in = exchange.getRequestBody()) {
            if (declared != null) {
                long length;
                try {
                    length = Long.parseLong(declared.trim());
                } catch (NumberFormatException e) {
                    length = -1;
                }
                if (length > BODY_LIMIT) {
                    drain(in);
                    throw tooLarge(String.format(Locale.ROOT, "%,d", length));
                }
            }
            byte[] body = in.readNBytes(BODY_LIMIT + 1);
            if (body.length > BODY_LIMIT) {
                drain(in);
                throw tooLarge("more than " + String.format(Locale.ROOT, "%,d", BODY_LIMIT));
            }
            return body;
        }
    }

    /**
     * Reads and drops what's left of a body that's too large, up to {@value #DRAIN_LIMIT} bytes, so the caller, still
     * sending, gets the {@code request-too-large} answer instead of a reset connection.
     */
    private static void drain(InputStream in) throws IOException {
        long left = DRAIN_LIMIT;
        byte[] buffer = new byte[1 << 16];
        int n;
        while (left > 0 && (n = in.read(buffer, 0, (int) Math.min(buffer.length, left))) > 0) {
            left -= n;
        }
    }

    private static Problem tooLarge(String size) {
        return Problem.of(Code.REQUEST_TOO_LARGE)
                .detail("The body has " + size + " bytes; the limit is " + String.format(Locale.ROOT, "%,d", BODY_LIMIT) + ".");
    }

    private static Map<String, List<String>> query(String raw) throws Problem {
        Map<String, List<String>> query = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return query;
        }
        for (String pair : raw.split("&", -1)) {
            int eq = pair.indexOf('=');
            try {
                String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                query.computeIfAbsent(name, n -> new ArrayList<>()).add(value);
            } catch (IllegalArgumentException e) {
                throw Problem.of(Code.INVALID_REQUEST).detail("The query string isn't valid: " + e.getMessage() + ".")
                        .error("/", "the query string isn't valid");
            }
        }
        return query;
    }

    // --- the routes ------------------------------------------------------------------------------

    private CompletableFuture<Response> status(Request request) throws Problem {
        Map<String, List<String>> query = new LinkedHashMap<>(request.query());
        String wait = single(query, "wait");
        String timeout = single(query, "timeoutMs");
        Problem invalid = Problem.of(Code.INVALID_REQUEST);
        int invalidFields = 0;
        for (String name : query.keySet()) {
            invalid.error("/" + name, "unknown query parameter; GET /v1/status takes wait and timeoutMs");
            invalidFields++;
        }
        if (wait != null && !wait.equals("ready")) {
            invalid.error("/wait", "must be ready");
            invalidFields++;
        }
        long timeoutMs = STATUS_TIMEOUT_DEFAULT_MS;
        if (timeout != null) {
            try {
                timeoutMs = Long.parseLong(timeout);
            } catch (NumberFormatException e) {
                timeoutMs = -1;
            }
            if (timeoutMs < 1 || timeoutMs > TIMEOUT_MAX_MS) {
                invalid.error("/timeoutMs", "must be an integer from 1 to " + TIMEOUT_MAX_MS);
                invalidFields++;
            } else if (wait == null) {
                invalid.error("/timeoutMs", "only with wait=ready");
                invalidFields++;
            }
        }
        if (invalidFields > 0) {
            throw invalid.detail(invalidFields == 1 ? "1 query parameter is invalid." : invalidFields + " query parameters are invalid.");
        }
        if (wait == null) {
            return CompletableFuture.completedFuture(Response.json(200, statusBody()));
        }
        long waitMs = timeoutMs;
        return lifecycle.ready().copy().orTimeout(waitMs, TimeUnit.MILLISECONDS).handle((ok, failure) -> {
            if (failure == null) {
                return Response.json(200, statusBody());
            }
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
            if (cause instanceof TimeoutException) {
                throw new CompletionException(notReady());
            }
            throw new CompletionException(cause);
        });
    }

    private static String single(Map<String, List<String>> query, String name) throws Problem {
        List<String> values = query.remove(name);
        if (values == null) {
            return null;
        }
        if (values.size() != 1) {
            throw Problem.of(Code.INVALID_REQUEST).detail("1 query parameter is invalid.").error("/" + name, "given " + values.size() + " times");
        }
        return values.get(0);
    }

    Problem notReady() {
        return Problem.of(Code.NOT_READY).detail("JDT LS is still starting (" + seconds(lifecycle.uptimeMs() * 1_000_000) + " s since launch).")
                .hint("Wait `retryAfter` seconds and call `GET /v1/status?wait=ready` again.")
                .with("reason", "starting").retryAfter(2);
    }

    JsonObject statusBody() {
        JsonObject o = new JsonObject();
        o.addProperty("release", lifecycle.release());
        o.addProperty("api", description.apiVersion);
        o.addProperty("jdtls", lifecycle.jdtlsVersion());
        o.addProperty("pid", lifecycle.pid());
        o.addProperty("uptimeMs", lifecycle.uptimeMs());
        o.addProperty("ready", lifecycle.ready().isDone() && !lifecycle.ready().isCompletedExceptionally());
        o.add("worktrees", new JsonArray());
        o.add("importing", JsonNull.INSTANCE);
        o.add("droppedWorktrees", new JsonArray());
        o.add("inFlight", new JsonArray());
        lifecycle.status(o);
        return o;
    }

    private CompletableFuture<Response> openapi(Request request) throws Problem {
        if (!request.query().isEmpty()) {
            Problem p = Problem.of(Code.INVALID_REQUEST).detail("GET /v1/openapi.json takes no query parameters.");
            request.query().keySet().forEach(name -> p.error("/" + name, "unknown query parameter"));
            throw p;
        }
        return CompletableFuture.completedFuture(new Response(200, "application/json", description.bytes));
    }

    private CompletableFuture<Response> shutdown(Request request) throws Problem {
        JsonObject body = object(request);
        if (!body.isEmpty()) {
            Problem p = Problem.of(Code.INVALID_REQUEST).detail("POST /v1/shutdown takes an empty object, {}.");
            body.keySet().forEach(name -> p.error("/" + name, "unknown field"));
            throw p;
        }
        boolean first = shutdownAt.compareAndSet(0, System.nanoTime());
        JsonObject o = new JsonObject();
        o.addProperty("shuttingDown", true);
        // Answers first: JDT LS's shutdown starts only once the response has been sent (design §11).
        return CompletableFuture.completedFuture(new Response(202, "application/json", Json.bytes(o), first ? lifecycle::shutdown : null));
    }

    static JsonObject object(Request request) throws Problem {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(request.body())).toString();
        } catch (CharacterCodingException e) {
            throw Problem.of(Code.INVALID_REQUEST).detail("The body isn't UTF-8.").error("", "not UTF-8");
        }
        JsonElement value;
        try {
            value = text.isBlank() ? null : Json.parseStrict(text);
        } catch (JsonParseException e) {
            throw Problem.of(Code.INVALID_REQUEST).detail("The body isn't JSON: " + e.getMessage() + ".").error("", "not JSON");
        }
        if (value == null || !value.isJsonObject()) {
            throw Problem.of(Code.INVALID_REQUEST)
                    .detail(request.method() + " " + request.path() + " takes a JSON object as its body" + (value == null ? ", and the body is empty." : "."))
                    .error("", "must be a JSON object");
        }
        return value.getAsJsonObject();
    }

    // --- responses -------------------------------------------------------------------------------

    private Response problem(Problem p, String instance) {
        return new Response(p.status(description), "application/problem+json", Json.bytes(p.toJson(description, instance)));
    }

    private Response internal(Throwable t, String instance, String method, String path) {
        Log.error(instance + " " + method + " " + path + " failed", t);
        return problem(internal(t), instance);
    }

    private static Problem internal(Throwable t) {
        return Problem.of(Code.INTERNAL).detail("An unexpected " + t.getClass().getSimpleName() + " in the endpoint, logged under the instance.");
    }

    /** The problem the caller is sent when a route fails with {@code t}, as JSON: a refactoring's {@code response.json}. */
    JsonObject problemJson(Throwable t, String instance) {
        return (t instanceof Problem p ? p : internal(t)).toJson(description, instance);
    }

    private void send(HttpExchange exchange, Response response) {
        try (exchange) {
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", response.contentType() + "; charset=utf-8");
            headers.set("Cache-Control", "no-store");
            if (response.status() == 401) {
                headers.set("WWW-Authenticate", "Bearer");
            }
            if (response.contentType().equals("application/problem+json") && response.status() == 503) {
                JsonElement retry = Json.parseStrict(new String(response.body(), StandardCharsets.UTF_8)).getAsJsonObject().get("retryAfter");
                if (retry != null) {
                    headers.set("Retry-After", retry.getAsString());
                }
            }
            exchange.sendResponseHeaders(response.status(), response.body().length == 0 ? -1 : response.body().length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response.body());
            }
        } catch (IOException e) {
            Log.info("couldn't send a response (the caller may have gone): " + e);
        }
    }

    private static String seconds(long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1e9);
    }

    /** A caller's string for the log or a detail: printable ASCII, at most 200 characters. */
    static String printable(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < 200; i++) {
            char c = s.charAt(i);
            b.append(c >= 0x20 && c < 0x7f ? c : '?');
        }
        return s.length() > 200 ? b + "..." : b.toString();
    }
}
