package org.fractal.harness.app;

import org.fractal.harness.contract.ExecutionId;

public class Launcher {

    public String launch(ExecutionId id) {
        return id.value();
    }
}
