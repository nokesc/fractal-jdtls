// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The in-JVM client against a fake JDT LS on the other ends of the two pipes. */
class LspClientTest {

    final HeapPipe toServer = new HeapPipe("to");
    final HeapPipe fromServer = new HeapPipe("from");
    final JsonObject settings = SettingsTest.json("{\"java\": {\"format\": {\"tabSize\": 4}}}");
    final LspClient client = new LspClient(toServer.output, fromServer.input, settings);
    /** Reads what the client sends, with the client's own framing. */
    final LspClient serverSide = new LspClient(fromServer.output, toServer.input, new JsonObject());

    void serverSends(String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        fromServer.output.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        fromServer.output.write(body);
    }

    JsonObject serverReads() throws IOException {
        return serverSide.readMessage();
    }

    @Test
    void initializeIsAnsweredCheckedAndFollowedByInitialized() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        client.start(SettingsTest.json("{\"processId\": null}"), checks::incrementAndGet);
        JsonObject initialize = serverReads();
        assertEquals("initialize", initialize.get("method").getAsString());
        assertTrue(initialize.getAsJsonObject("params").get("processId").isJsonNull());
        serverSends("{\"jsonrpc\": \"2.0\", \"id\": " + initialize.get("id") + ", \"result\": {\"capabilities\": {}}}");
        assertEquals("initialized", serverReads().get("method").getAsString());
        client.initialized.get(10, TimeUnit.SECONDS);
        assertEquals(1, checks.get());

        assertTrue(!client.serviceReady.isDone());
        serverSends("{\"jsonrpc\": \"2.0\", \"method\": \"language/status\", \"params\": {\"type\": \"Started\", \"message\": \"Ready\"}}");
        serverSends("{\"jsonrpc\": \"2.0\", \"method\": \"language/status\", \"params\": {\"type\": \"ServiceReady\", \"message\": \"ServiceReady\"}}");
        client.serviceReady.get(10, TimeUnit.SECONDS);
    }

    @Test
    void aRefusedInitializeOrAFailedCheckFailsStartup() throws Exception {
        client.start(new JsonObject(), () -> {});
        JsonObject initialize = serverReads();
        serverSends("{\"jsonrpc\": \"2.0\", \"id\": " + initialize.get("id") + ", \"error\": {\"code\": -32603, \"message\": \"no\"}}");
        ExecutionException e = assertThrows(ExecutionException.class, () -> client.initialized.get(10, TimeUnit.SECONDS));
        assertInstanceOf(StartupException.class, e.getCause());

        HeapPipe to = new HeapPipe("to2");
        HeapPipe from = new HeapPipe("from2");
        LspClient other = new LspClient(to.output, from.input, settings);
        other.start(new JsonObject(), () -> {
            throw new StartupException("not our pipes");
        });
        JsonObject init = new LspClient(from.output, to.input, new JsonObject()).readMessage();
        byte[] body = ("{\"jsonrpc\": \"2.0\", \"id\": " + init.get("id") + ", \"result\": {}}").getBytes(StandardCharsets.UTF_8);
        from.output.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        from.output.write(body);
        e = assertThrows(ExecutionException.class, () -> other.initialized.get(10, TimeUnit.SECONDS));
        assertEquals("not our pipes", e.getCause().getMessage());
    }

    @Test
    void jdtlsRequestsAreAnsweredFromTheEndpointsOwnState() throws Exception {
        client.start(new JsonObject(), () -> {});
        serverReads(); // initialize, left unanswered
        serverSends("{\"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"workspace/configuration\", \"params\": {\"items\": ["
                + "{\"section\": \"java.format.tabSize\"}, {\"section\": \"java.home\"}, {}]}}");
        assertEquals("[4,null,{\"java\":{\"format\":{\"tabSize\":4}}}]", serverReads().get("result").toString());
        serverSends("{\"jsonrpc\": \"2.0\", \"id\": 2, \"method\": \"workspace/applyEdit\", \"params\": {\"edit\": {}}}");
        assertEquals(false, serverReads().getAsJsonObject("result").get("applied").getAsBoolean());
        for (String method : new String[] {"window/workDoneProgress/create", "client/registerCapability", "window/showMessageRequest",
                "window/showDocument"}) {
            serverSends("{\"jsonrpc\": \"2.0\", \"id\": \"s\", \"method\": \"" + method + "\", \"params\": {}}");
            JsonObject answer = serverReads();
            assertEquals("\"s\"", answer.get("id").toString());
            assertTrue(answer.get("result").isJsonNull(), method);
        }
        client.workspaceFolders = () -> WorktreeOps.folderList(java.util.List.of(java.nio.file.Path.of("/work/wt-a")));
        serverSends("{\"jsonrpc\": \"2.0\", \"id\": 3, \"method\": \"workspace/workspaceFolders\"}");
        assertEquals("[{\"uri\":\"file:///work/wt-a\",\"name\":\"wt-a\"}]", serverReads().get("result").toString());
        serverSends("{\"jsonrpc\": \"2.0\", \"id\": 4, \"method\": \"workspace/codeLens/refresh\"}");
        assertEquals(-32601, serverReads().getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void anApplyEditDuringAPassthroughRequestIsRefusedAndKept() throws Exception {
        client.start(new JsonObject(), () -> {});
        serverReads(); // initialize, left unanswered
        try (LspClient.ApplyEdits watch = client.watchApplyEdits()) {
            serverSends("{\"jsonrpc\": \"2.0\", \"id\": 7, \"method\": \"workspace/applyEdit\", \"params\": {\"label\": \"Organize imports\","
                    + " \"edit\": {\"changes\": {\"file:///w/A.java\": []}}}}");
            JsonObject answer = serverReads().getAsJsonObject("result");
            assertEquals(false, answer.get("applied").getAsBoolean());
            assertTrue(answer.get("failureReason").getAsString().contains("returns it to its caller, unapplied"), answer.toString());
            assertEquals("[{\"label\":\"Organize imports\",\"edit\":{\"changes\":{\"file:///w/A.java\":[]}}}]", watch.refused().toString());
        }
        serverSends("{\"jsonrpc\": \"2.0\", \"id\": 8, \"method\": \"workspace/applyEdit\", \"params\": {\"edit\": {}}}");
        assertEquals("the endpoint applies edits itself", serverReads().getAsJsonObject("result").get("failureReason").getAsString());
    }

    @Test
    void aRequestPastItsDeadlineIsCancelledAndJdtlsErrorsAreKept() throws Exception {
        client.start(new JsonObject(), () -> {});
        serverReads(); // initialize
        Thread t = new Thread(() -> {
            try {
                JsonObject request = serverReads();
                JsonObject cancel = serverReads();
                assertEquals("$/cancelRequest", cancel.get("method").getAsString());
                assertEquals(request.get("id"), cancel.getAsJsonObject("params").get("id"));
                serverSends("{\"jsonrpc\": \"2.0\", \"id\": " + request.get("id") + ", \"error\": {\"code\": -32800, \"message\": \"cancelled\"}}");
                JsonObject second = serverReads();
                serverSends("{\"jsonrpc\": \"2.0\", \"id\": " + second.get("id") + ", \"error\": {\"code\": -32602, \"message\": \"bad\","
                        + " \"data\": {\"x\": 1}}}");
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
        t.start();
        assertThrows(LspClient.Expired.class, () -> client.call("textDocument/hover", new JsonObject(), System.nanoTime() + 50_000_000));
        LspClient.Failed failed = assertThrows(LspClient.Failed.class,
                () -> client.call("textDocument/hover", new JsonObject(), System.nanoTime() + 10_000_000_000L));
        assertEquals("{\"code\":-32602,\"message\":\"bad\",\"data\":{\"x\":1}}", failed.error.toString());
        t.join(10_000);
    }

    @Test
    void framingSurvivesSplitWritesAndAContentType() throws Exception {
        byte[] body = "{\"jsonrpc\":\"2.0\",\"method\":\"x\",\"params\":{\"s\":\"é\"}}".getBytes(StandardCharsets.UTF_8);
        byte[] all = ("Content-Length: " + body.length + "\r\nContent-Type: application/vscode-jsonrpc; charset=utf-8\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        for (byte b : all) {
            fromServer.output.write(b);
        }
        fromServer.output.write(body);
        assertEquals("é", client.readMessage().getAsJsonObject("params").get("s").getAsString());
    }
}
