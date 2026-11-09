// SPDX-License-Identifier: Apache-2.0
package org.fractal.harness.app;

import org.fractal.harness.contract.Names;

public class Greeter {

    public String greet(String who) {
        String clean = Names.normalize(who);
        int width = 10 - clean.length();
        return "Hello, " + clean + " ".repeat(Math.max(0, width));
    }

    public String shout(String who) {
        return Names.normalize(who).toUpperCase();
    }
}
