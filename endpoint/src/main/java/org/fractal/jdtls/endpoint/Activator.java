// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

/**
 * Started by Equinox from its start list ({@code -Dosgi.bundles}, which the launcher sets in HTTP mode), in the
 * start-level thread, before JDT LS's application runs (design §11 "Loading").
 *
 * <p>It never lets a failure escape to Equinox, which would log it and start JDT LS without its client: any failure
 * ends the process with status 1 and the reason on stderr. It refers only to the JDK, OSGi and {@link Fatal}, so
 * even a failure to load the endpoint's other classes is caught here.
 */
public final class Activator implements BundleActivator {

    private volatile Endpoint endpoint;

    @Override
    public void start(BundleContext context) {
        try {
            endpoint = Endpoint.start(context);
        } catch (Throwable t) {
            Fatal.exit("the endpoint can't start", t);
        }
    }

    @Override
    public void stop(BundleContext context) {
        Endpoint running = endpoint;
        if (running != null) {
            running.stop();
        }
    }
}
