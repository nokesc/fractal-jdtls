// SPDX-License-Identifier: Apache-2.0
package org.fractal.harness.contract;

/** One step of an execution. */
public interface Step {

    /** Runs the step for the execution, and says what it did. */
    String run(ExecutionId id);
}
