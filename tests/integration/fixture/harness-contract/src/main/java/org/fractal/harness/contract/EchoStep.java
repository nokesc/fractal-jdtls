// SPDX-License-Identifier: Apache-2.0
package org.fractal.harness.contract;

/** A step that only names its execution. */
public class EchoStep implements Step {

    @Override
    public String run(ExecutionId id) {
        return Names.describe(id);
    }
}
