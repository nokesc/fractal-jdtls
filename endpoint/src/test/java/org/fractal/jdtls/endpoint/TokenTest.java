// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TokenTest {

    @Test
    void aTokenOfTheRightLengthAndCharactersIsRead(@TempDir Path dir) throws Exception {
        Path pipe = dir.resolve("pipe");
        byte[] token = GateTest.TOKEN.getBytes(StandardCharsets.US_ASCII);
        Files.write(pipe, token);
        assertArrayEquals(token, Token.read(pipe, "the pipe"));
        byte[] longest = "x".repeat(Token.MAX_LENGTH).getBytes(StandardCharsets.US_ASCII);
        Files.write(pipe, longest);
        assertArrayEquals(longest, Token.read(pipe, "the pipe"));
    }

    @Test
    void aShortLongOrNonAsciiTokenIsRefusedWithoutShowingIt(@TempDir Path dir) throws Exception {
        Path pipe = dir.resolve("pipe");
        for (String token : new String[] {"", "x".repeat(31), "x".repeat(Token.MAX_LENGTH + 1), "x".repeat(32) + " y",
                "x".repeat(32) + "\n", "x".repeat(32) + "é"}) {
            Files.writeString(pipe, token);
            StartupException e = assertThrows(StartupException.class, () -> Token.read(pipe, "the pipe"));
            assertTrue(e.getMessage().startsWith("the token "), e.getMessage());
            assertFalse(!token.isEmpty() && e.getMessage().contains(token), e.getMessage());
        }
    }

    @Test
    void anUnreadablePipeIsAStartupFailure(@TempDir Path dir) {
        StartupException e = assertThrows(StartupException.class, () -> Token.read(dir.resolve("none"), "the token pipe (file descriptor 9)"));
        assertTrue(e.getMessage().startsWith("can't read the token pipe (file descriptor 9)"), e.getMessage());
    }
}
