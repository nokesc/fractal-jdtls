// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import java.io.InputStream;
import java.io.OutputStream;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.LanguageServerApplication;

/**
 * After {@code initialize}: JDT LS must hold the endpoint's pipes as its LSP streams (design §11 "The connection").
 * {@code LanguageServerApplication.prepareStreams} captures {@code System.in} and {@code System.out} when JDT LS's
 * application starts; a JDT LS that captured them earlier, or elsewhere, fails this check, and startup fails loudly.
 * The one class that touches JDT LS's internals, so the others load without it.
 */
final class StreamCheck {

    private StreamCheck() {}

    static void verify(InputStream in, OutputStream out) throws StartupException {
        LanguageServerApplication application = JavaLanguageServerPlugin.getLanguageServer();
        if (application == null) {
            throw new StartupException("JDT LS answered initialize, but has no LanguageServerApplication.");
        }
        boolean reads = application.getIn() == in;
        boolean writes = application.getOut() == out;
        if (!reads || !writes) {
            throw new StartupException("JDT LS doesn't use the endpoint's in-heap pipes as its LSP streams (it "
                    + (reads ? "reads" : "doesn't read") + " the endpoint's pipe, and " + (writes ? "writes" : "doesn't write")
                    + " the endpoint's). This JDT LS release captures stdio differently from the pinned one.");
        }
    }
}
