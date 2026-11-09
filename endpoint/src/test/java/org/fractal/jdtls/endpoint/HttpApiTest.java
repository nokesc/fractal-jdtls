// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The HTTP pipeline on a real server on 127.0.0.1, with raw requests so every header is the test's own. */
class HttpApiTest {

    static final class FakeLifecycle implements Lifecycle {
        final CompletableFuture<Void> ready = new CompletableFuture<>();
        final AtomicInteger shutdowns = new AtomicInteger();

        public String release() { return "1.63.0-1"; }
        public String jdtlsVersion() { return "1.63.0"; }
        public long pid() { return 4242; }
        public long uptimeMs() { return 3100; }
        public CompletableFuture<Void> ready() { return ready; }
        public void shutdown() { shutdowns.incrementAndGet(); }
    }

    static Lifecycle lifecycle() {
        return new FakeLifecycle();
    }

    record Reply(int status, Map<String, String> headers, String body) {
        JsonObject json() {
            return Json.parseStrict(body).getAsJsonObject();
        }
    }

    final FakeLifecycle lifecycle = new FakeLifecycle();
    final Description description = DescriptionTest.description();
    HttpApi api;
    int port;
    final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    final java.util.concurrent.Semaphore entered = new java.util.concurrent.Semaphore(0);
    final AtomicInteger queuedRuns = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        api = new HttpApi(description, lifecycle, GateTest.TOKEN.getBytes(StandardCharsets.US_ASCII));
        api.route("GET", "/test/fail", r -> {
            throw new NullPointerException("a bug");
        });
        api.route("POST", "/test/queued", 800, r -> {
            queuedRuns.incrementAndGet();
            return CompletableFuture.completedFuture(HttpApi.Response.json(200, new JsonObject()));
        });
        api.route("GET", "/test/hold", r -> {
            entered.release();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return CompletableFuture.completedFuture(HttpApi.Response.json(200, new JsonObject()));
        });
        port = api.start();
    }

    @AfterEach
    void stop() {
        release.countDown();
        api.stop();
    }

    Reply send(String method, String path, String headers, byte[] body) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            String head = method + " " + path + " HTTP/1.1\r\n" + headers + "Connection: close\r\n"
                    + (body == null ? "" : "Content-Length: " + body.length + "\r\n") + "\r\n";
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            if (body != null) {
                out.write(body);
            }
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            in.transferTo(all);
            String text = all.toString(StandardCharsets.UTF_8);
            int split = text.indexOf("\r\n\r\n");
            String[] lines = text.substring(0, split).split("\r\n");
            Map<String, String> h = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                h.put(lines[i].substring(0, colon).toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
            }
            return new Reply(Integer.parseInt(lines[0].split(" ")[1]), h, text.substring(split + 4));
        }
    }

    String good() {
        return "Host: 127.0.0.1:" + port + "\r\nAuthorization: Bearer " + GateTest.TOKEN + "\r\n";
    }

    Reply get(String path) throws Exception {
        return send("GET", path, good(), null);
    }

    Reply post(String path, String body) throws Exception {
        return send("POST", path, good(), body.getBytes(StandardCharsets.UTF_8));
    }

    static void assertProblem(Reply reply, int status, String code) {
        assertEquals(status, reply.status(), reply.body());
        assertTrue(reply.headers().get("content-type").startsWith("application/problem+json"), reply.headers().toString());
        assertEquals(code, reply.json().get("code").getAsString(), reply.body());
    }

    @Test
    void theChecksComeFirst() throws Exception {
        Reply r = send("GET", "/v1/status", "Host: 127.0.0.1:" + port + "\r\n", null);
        assertProblem(r, 401, "unauthorized");
        assertEquals("Bearer", r.headers().get("www-authenticate"));
        assertProblem(send("GET", "/nowhere", "Host: 127.0.0.1:" + port + "\r\nAuthorization: Bearer nope\r\n", null), 401, "unauthorized");
        assertProblem(send("GET", "/v1/status", "Host: localhost:" + port + "\r\nAuthorization: Bearer " + GateTest.TOKEN + "\r\n", null),
                403, "host-not-allowed");
        assertProblem(send("GET", "/v1/status", good() + "Origin: http://example.test\r\n", null), 403, "origin-not-allowed");
    }

    @Test
    void unknownRoutesAndMethodsAreProblems() throws Exception {
        assertProblem(get("/v1/rename"), 404, "route-not-found");
        assertProblem(post("/v1/definition", "{}"), 404, "route-not-found"); // not registered before T002.06
        Reply r = post("/v1/status", "{}");
        assertProblem(r, 405, "method-not-allowed");
        assertEquals("GET", r.headers().get("allow"));
        assertProblem(get("/v1/shutdown"), 405, "method-not-allowed");
    }

    @Test
    void statusAnswersAtOnceAndWaitsForReadyWhenAsked() throws Exception {
        JsonObject s = get("/v1/status").json();
        assertEquals("1.63.0-1", s.get("release").getAsString());
        assertEquals(description.apiVersion, s.get("api").getAsString());
        assertEquals(false, s.get("ready").getAsBoolean());
        assertEquals(0, s.getAsJsonArray("worktrees").size());

        Reply waited = get("/v1/status?wait=ready&timeoutMs=200");
        assertProblem(waited, 503, "not-ready");
        assertEquals("starting", waited.json().get("reason").getAsString());
        assertEquals("2", waited.headers().get("retry-after"));
        assertEquals("JDT LS is still starting (3.1 s since launch).", waited.json().get("detail").getAsString());

        CompletableFuture<Reply> pending = CompletableFuture.supplyAsync(() -> {
            try {
                return get("/v1/status?wait=ready&timeoutMs=20000");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(200);
        assertTrue(!pending.isDone());
        lifecycle.ready.complete(null);
        Reply ready = pending.get();
        assertEquals(200, ready.status());
        assertEquals(true, ready.json().get("ready").getAsBoolean());
    }

    @Test
    void statusRefusesUnknownOrInvalidQueryParameters() throws Exception {
        Reply r = get("/v1/status?wait=now&timeoutMs=0&extra=1");
        assertProblem(r, 400, "invalid-request");
        assertEquals(3, r.json().getAsJsonArray("errors").size());
        assertProblem(get("/v1/status?timeoutMs=5"), 400, "invalid-request");
        assertProblem(get("/v1/status?wait=ready&wait=ready"), 400, "invalid-request");
        assertProblem(get("/v1/openapi.json?x=1"), 400, "invalid-request");
    }

    @Test
    void openapiServesTheDescriptionAsItIs() throws Exception {
        Reply r = get("/v1/openapi.json");
        assertEquals(200, r.status());
        assertArrayEquals(description.bytes, r.body().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void shutdownAnswersFirstAndOnlyOnce() throws Exception {
        assertProblem(post("/v1/shutdown", ""), 400, "invalid-request");
        assertProblem(post("/v1/shutdown", "{\"now\": true}"), 400, "invalid-request");
        assertProblem(post("/v1/shutdown", "{nope"), 400, "invalid-request");
        assertEquals(0, lifecycle.shutdowns.get());
        Reply r = post("/v1/shutdown", "{}");
        assertEquals(202, r.status());
        assertEquals(true, r.json().get("shuttingDown").getAsBoolean());
        assertEquals(202, post("/v1/shutdown", "{}").status());
        assertEquals(200, get("/v1/status").status());
        assertEquals(1, lifecycle.shutdowns.get());
        assertProblem(get("/test/fail"), 503, "shutting-down");
    }

    /**
     * R2 of T002.03's review: eight connections without the token, each declaring a body it never sends, and eight
     * that never finish their headers, all kept open. None may hold what an authenticated request needs.
     */
    @Test
    void connectionsThatWithholdTheirBodyOrHeadersDontBlockStatus() throws Exception {
        java.util.List<Socket> held = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < HttpApi.WORKERS; i++) {
                Socket s = new Socket("127.0.0.1", port);
                s.getOutputStream().write(("POST /v1/shutdown HTTP/1.1\r\nHost: 127.0.0.1:" + port
                        + "\r\nContent-Length: 1\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                s.getOutputStream().flush();
                held.add(s);
                Socket h = new Socket("127.0.0.1", port);
                h.getOutputStream().write("GET /v1/status HTTP/1.1\r\nHost: 127.0.0.1:".getBytes(StandardCharsets.US_ASCII));
                h.getOutputStream().flush();
                held.add(h);
            }
            Thread.sleep(500); // every one of them has reached the server
            long start = System.nanoTime();
            CompletableFuture<Reply> status = CompletableFuture.supplyAsync(() -> {
                try {
                    return get("/v1/status");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            Reply r = status.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(200, r.status());
            assertTrue(System.nanoTime() - start < 2_000_000_000L, "status took " + (System.nanoTime() - start) / 1_000_000 + " ms");
        } finally {
            for (Socket s : held) {
                s.close();
            }
        }
    }

    @Test
    void aBodyOverTheLimitIsRefused() throws Exception {
        assertProblem(send("POST", "/v1/shutdown", good(), new byte[HttpApi.BODY_LIMIT + 1]), 413, "request-too-large");
    }

    @Test
    void anUnexpectedFailureIsInternalAndTheEndpointKeepsServing() throws Exception {
        Reply r = get("/test/fail");
        assertProblem(r, 500, "internal");
        assertTrue(r.json().get("instance").getAsString().startsWith("urn:fractal-jdtls:request:"));
        assertTrue(!r.body().contains("a bug") && !r.body().contains("at org."), r.body());
        assertEquals(200, get("/v1/status").status());
    }

    /**
     * T002.11: with every worker held, {@value HttpApi#QUEUE} more requests wait for one, in order, and each request
     * beyond them is {@code busy} at once, with {@code retryAfter} and {@code Retry-After}; {@code status} still
     * answers; once the workers are free, every queued request is answered.
     */
    @Test
    void aRequestBeyondTheQueueIsBusyWithRetryAfter() throws Exception {
        int beyond = 3;
        java.util.concurrent.ExecutorService callers = java.util.concurrent.Executors.newFixedThreadPool(HttpApi.WORKERS + HttpApi.QUEUE + beyond);
        try {
            java.util.List<java.util.concurrent.Future<Reply>> held = new java.util.ArrayList<>();
            for (int i = 0; i < HttpApi.WORKERS; i++) {
                held.add(callers.submit(() -> get("/test/hold")));
            }
            assertTrue(entered.tryAcquire(HttpApi.WORKERS, 10, java.util.concurrent.TimeUnit.SECONDS), "every worker is held");
            java.util.List<java.util.concurrent.Future<Reply>> more = new java.util.ArrayList<>();
            for (int i = 0; i < HttpApi.QUEUE + beyond; i++) {
                more.add(callers.submit(() -> get("/test/hold")));
            }
            java.util.List<Reply> busy = new java.util.ArrayList<>();
            long until = System.nanoTime() + 10_000_000_000L;
            while (busy.size() < beyond && System.nanoTime() < until) {
                busy.clear();
                for (java.util.concurrent.Future<Reply> f : more) {
                    if (f.isDone()) {
                        busy.add(f.get());
                    }
                }
                Thread.sleep(10);
            }
            assertEquals(beyond, busy.size(), "exactly the requests beyond the queue are answered at once");
            for (Reply r : busy) {
                assertProblem(r, 503, "busy");
                assertEquals(HttpApi.QUEUE_FULL_RETRY_AFTER_S, r.json().get("retryAfter").getAsInt());
                assertEquals(Integer.toString(HttpApi.QUEUE_FULL_RETRY_AFTER_S), r.headers().get("retry-after"));
                assertTrue(r.json().get("detail").getAsString().contains(HttpApi.QUEUE + " requests are already waiting"), r.body());
            }
            assertEquals(200, get("/v1/status").status(), "the lifecycle routes never wait for a worker");
            release.countDown();
            for (java.util.concurrent.Future<Reply> f : held) {
                assertEquals(200, f.get(10, java.util.concurrent.TimeUnit.SECONDS).status());
            }
            int queued = 0;
            for (java.util.concurrent.Future<Reply> f : more) {
                if (f.get(10, java.util.concurrent.TimeUnit.SECONDS).status() == 200) {
                    queued++;
                }
            }
            assertEquals(HttpApi.QUEUE, queued, "every queued request was answered");
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    /**
     * T002.11's review, R1: a request waiting in the queue is answered at its deadline, its {@code timeoutMs} or its
     * route's default, with {@code busy} ("waited … for a worker thread", {@code retryAfter: 1}), not when a worker
     * reaches it; and no worker runs it after that.
     */
    @Test
    void aQueuedRequestIsAnsweredAtItsDeadlineAndNeverRun() throws Exception {
        java.util.concurrent.ExecutorService callers = java.util.concurrent.Executors.newFixedThreadPool(HttpApi.WORKERS + 2);
        try {
            java.util.List<java.util.concurrent.Future<Reply>> held = new java.util.ArrayList<>();
            for (int i = 0; i < HttpApi.WORKERS; i++) {
                held.add(callers.submit(() -> get("/test/hold")));
            }
            assertTrue(entered.tryAcquire(HttpApi.WORKERS, 10, java.util.concurrent.TimeUnit.SECONDS), "every worker is held");
            long start = System.nanoTime();
            java.util.concurrent.Future<Reply> given = callers.submit(() -> post("/test/queued", "{\"timeoutMs\": 500}"));
            java.util.concurrent.Future<Reply> byDefault = callers.submit(() -> post("/test/queued", "{}"));
            Reply r = given.get(5, java.util.concurrent.TimeUnit.SECONDS);
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertProblem(r, 503, "busy");
            assertTrue(ms >= 500 && ms < 1_500, "answered at its timeoutMs of 500, after " + ms + " ms");
            assertEquals(1, r.json().get("retryAfter").getAsInt());
            assertTrue(r.json().get("detail").getAsString().contains("for a worker thread, past its timeoutMs of 500"), r.body());
            Reply d = byDefault.get(5, java.util.concurrent.TimeUnit.SECONDS);
            long dms = (System.nanoTime() - start) / 1_000_000;
            assertProblem(d, 503, "busy");
            assertTrue(dms >= 800 && dms < 1_800, "answered at the route's default of 800, after " + dms + " ms");
            release.countDown();
            for (java.util.concurrent.Future<Reply> f : held) {
                assertEquals(200, f.get(10, java.util.concurrent.TimeUnit.SECONDS).status());
            }
            assertEquals(200, post("/test/queued", "{}").status(), "a worker runs the next request");
            assertEquals(1, queuedRuns.get(), "neither expired request was run, then or later");
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }
}
