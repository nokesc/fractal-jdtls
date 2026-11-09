// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The bearer token, read once from the pipe the launcher passes (design §11 "Launching").
 *
 * <p>The launcher takes the token from {@code FRACTAL_JDTLS_HTTP_TOKEN}, removes that variable from the environment
 * it starts Java with, writes the token into a pipe, closes the pipe's write end and passes the read end as an
 * inherited file descriptor, named by {@code -Dfractal.jdtls.http.tokenFd}. The endpoint reads it to the end through
 * {@code /proc/self/fd/<n>} and closes what it opened. Java can't close a descriptor by number, so descriptor
 * {@code <n>} itself stays open, holding an empty pipe whose writer is gone; the JDK closes every descriptor above 2
 * in the processes JDT LS starts, so none of them inherits it.
 */
final class Token {

    static final int MIN_LENGTH = 32;
    static final int MAX_LENGTH = 4096;

    private Token() {}

    static byte[] read(int fd) throws StartupException {
        return read(Path.of("/proc/self/fd/" + fd), "the token pipe (file descriptor " + fd + ")");
    }

    static byte[] read(Path source, String what) throws StartupException {
        byte[] bytes;
        try (InputStream in = Files.newInputStream(source)) {
            bytes = in.readNBytes(MAX_LENGTH + 1);
        } catch (IOException e) {
            throw new StartupException("can't read " + what + ": " + e
                    + ". Start JDT LS in HTTP mode with the install's bin/jdtls, which passes the token on it.");
        }
        return validate(bytes);
    }

    /** The token's bytes, if it's {@value #MIN_LENGTH} to {@value #MAX_LENGTH} visible ASCII characters. */
    static byte[] validate(byte[] token) throws StartupException {
        if (token.length < MIN_LENGTH || token.length > MAX_LENGTH) {
            throw new StartupException("the token has " + (token.length > MAX_LENGTH ? "more than " + MAX_LENGTH : token.length)
                    + " characters; it must have " + MIN_LENGTH + " to " + MAX_LENGTH + ".");
        }
        for (byte b : token) {
            if (b < 0x21 || b > 0x7e) {
                throw new StartupException("the token must be visible ASCII characters only, with no spaces.");
            }
        }
        return token;
    }
}
