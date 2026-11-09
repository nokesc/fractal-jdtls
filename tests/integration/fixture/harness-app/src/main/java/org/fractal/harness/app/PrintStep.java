// SPDX-License-Identifier: Apache-2.0
package org.fractal.harness.app;

import org.fractal.harness.contract.ExecutionId;
import org.fractal.harness.contract.Names;
import org.fractal.harness.contract.Step;

public class PrintStep implements Step {

    private final Greeter greeter = new Greeter();

    @Override
    public String run(ExecutionId id) {
        return greeter.greet(Names.describe(id));
    }
}
