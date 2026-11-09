// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class GateTest {

    static final String TOKEN = "0123456789abcdefghijklmnopqrstuvwxyzABCD";
    static final List<String> AUTH = List.of("Bearer " + TOKEN);
    static final List<String> HOST = List.of("127.0.0.1:4711");
    final Gate gate = new Gate(TOKEN.getBytes(StandardCharsets.US_ASCII), 4711);

    @Test
    void theRightTokenHostAndNoOriginPass() {
        assertDoesNotThrow(() -> gate.check(AUTH, HOST, null));
    }

    @Test
    void aMissingWrongOrRepeatedTokenIsUnauthorized() {
        for (List<String> auth : List.of(List.<String>of(), List.of("Bearer " + TOKEN + "x"), List.of("Bearer " + TOKEN.substring(1)),
                List.of(TOKEN), List.of("bearer " + TOKEN), List.of("Bearer " + TOKEN, "Bearer " + TOKEN))) {
            assertEquals(Code.UNAUTHORIZED, assertThrows(Problem.class, () -> gate.check(auth, HOST, null)).code, auth.toString());
        }
        assertEquals(Code.UNAUTHORIZED, assertThrows(Problem.class, () -> gate.check(null, HOST, null)).code);
    }

    @Test
    void theTokenIsCheckedFirst() {
        Problem p = assertThrows(Problem.class, () -> gate.check(null, List.of("localhost:4711"), List.of("http://x")));
        assertEquals(Code.UNAUTHORIZED, p.code);
    }

    @Test
    void anyOtherHostIsRefused() {
        for (List<String> host : List.of(List.of("localhost:4711"), List.of("127.0.0.1"), List.of("127.0.0.1:4712"),
                List.of("[::1]:4711"), List.of("127.0.0.1:4711", "127.0.0.1:4711"), List.<String>of())) {
            Problem p = assertThrows(Problem.class, () -> gate.check(AUTH, host, null), host.toString());
            assertEquals(Code.HOST_NOT_ALLOWED, p.code);
        }
        Problem p = assertThrows(Problem.class, () -> gate.check(AUTH, List.of("localhost:4711"), null));
        String detail = p.toJson(DescriptionTest.description(), "i").get("detail").getAsString();
        assertEquals("Host was 'localhost:4711'; it must be '127.0.0.1:4711'.", detail);
        assertEquals(Code.HOST_NOT_ALLOWED, assertThrows(Problem.class, () -> gate.check(AUTH, null, null)).code);
    }

    @Test
    void anyOriginHeaderIsRefused() {
        for (List<String> origin : List.of(List.of("http://example.test"), List.of(""), List.of("null"))) {
            assertEquals(Code.ORIGIN_NOT_ALLOWED, assertThrows(Problem.class, () -> gate.check(AUTH, HOST, origin)).code);
        }
    }

    @Test
    void theUnauthorizedProblemSaysNoMoreThanItsCode() {
        Problem p = assertThrows(Problem.class, () -> gate.check(List.of("Bearer wrong"), HOST, null));
        var body = p.toJson(DescriptionTest.description(), "urn:fractal-jdtls:request:1");
        assertEquals(java.util.Set.of("type", "title", "status", "code", "hint", "retryable"), body.keySet());
        assertTrue(!body.toString().contains(TOKEN) && !body.toString().contains("wrong"));
    }
}
