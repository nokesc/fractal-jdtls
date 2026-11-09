// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

/** A startup failure the user can act on: its message says what failed and what to do. */
final class StartupException extends Exception {

    private static final long serialVersionUID = 1L;

    StartupException(String message) {
        super(message);
    }

    StartupException(String message, Throwable cause) {
        super(message, cause);
    }
}
