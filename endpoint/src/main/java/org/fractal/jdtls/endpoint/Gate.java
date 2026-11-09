// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * The checks every request passes before anything else (design §11 "Security"), in this order:
 *
 * <ol>
 *   <li>{@code Authorization: Bearer <token>}, exactly one, compared in constant time (as SHA-256 digests, so not
 *       even the length leaks). Otherwise {@code unauthorized}, with no detail.
 *   <li>{@code Host} is {@code 127.0.0.1:<port>}, exactly one. Otherwise {@code host-not-allowed}.
 *   <li>No {@code Origin} header at all, so no browser page can reach the endpoint through DNS rebinding. Otherwise
 *       {@code origin-not-allowed}.
 * </ol>
 *
 * <p>The token is checked first, so a caller without it learns nothing else.
 */
final class Gate {

    private static final int ECHO_LIMIT = 200;
    private final byte[] expected;
    private final String host;

    Gate(byte[] token, int port) {
        byte[] prefix = "Bearer ".getBytes(StandardCharsets.US_ASCII);
        byte[] authorization = new byte[prefix.length + token.length];
        System.arraycopy(prefix, 0, authorization, 0, prefix.length);
        System.arraycopy(token, 0, authorization, prefix.length, token.length);
        this.expected = sha256(authorization);
        this.host = "127.0.0.1:" + port;
    }

    /** Throws the problem for the first check the request fails. */
    void check(List<String> authorization, List<String> hosts, List<String> origins) throws Problem {
        boolean authorized = authorization != null && authorization.size() == 1
                && MessageDigest.isEqual(expected, sha256(authorization.get(0).getBytes(StandardCharsets.UTF_8)));
        if (!authorized) {
            throw Problem.of(Code.UNAUTHORIZED);
        }
        if (hosts == null || hosts.size() != 1 || !host.equals(hosts.get(0))) {
            String was = hosts == null || hosts.isEmpty() ? "missing" : "'" + echo(String.join(", ", hosts)) + "'";
            throw Problem.of(Code.HOST_NOT_ALLOWED).detail("Host was " + was + "; it must be '" + host + "'.");
        }
        if (origins != null) {
            throw Problem.of(Code.ORIGIN_NOT_ALLOWED)
                    .detail("The request has an Origin header ('" + echo(String.join(", ", origins)) + "').");
        }
    }

    private static String echo(String value) {
        return value.length() <= ECHO_LIMIT ? value : value.substring(0, ECHO_LIMIT) + "...";
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every JDK has SHA-256
        }
    }
}
