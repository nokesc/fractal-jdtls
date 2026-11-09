// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

/**
 * A test-only mechanism, beside {@link TestPauses}: holds a passthrough request inside JDT LS, so the integration
 * suite can show its timeout's {@code $/cancelRequest} reaching JDT LS without depending on how long anything takes.
 * With {@code -Dfractal.jdtls.endpoint.testPauses=<folder>} and {@code <folder>/<point>.armed} created by the test,
 * the next passthrough request to reach the point deletes that file, creates {@code <folder>/<point>}, and schedules an
 * Eclipse job of JDT LS's document life-cycle family ({@code DocumentLifeCycleJobs}) that runs until the test deletes
 * the marker, or at most the request's deadline plus a minute. JDT LS's handlers such as
 * {@code textDocument/documentSymbol} join that family, with the request's own progress monitor, before they answer.
 * Without the property, which the launcher never passes, or with the point unarmed, it does nothing.
 */
final class TestJobs {

    /** {@code BaseDocumentLifeCycleHandler.DOCUMENT_LIFE_CYCLE_JOBS} in JDT LS 1.63.0. */
    static final String LIFE_CYCLE_FAMILY = "DocumentLifeCycleJobs";
    private static final long GUARD_NS = 60_000_000_000L;

    private TestJobs() {}

    static void holdIfArmed(String point, long deadline) throws IOException {
        String folder = System.getProperty(TestPauses.PROPERTY);
        if (folder == null || folder.isEmpty()) {
            return;
        }
        if (!Files.deleteIfExists(Path.of(folder).resolve(point + ".armed"))) {
            return;
        }
        Path marker = Path.of(folder).resolve(point);
        Files.writeString(marker, Long.toString(ProcessHandle.current().pid()));
        long until = deadline + GUARD_NS;
        Job hold = new Job("Fractal JDT LS test hold at " + point) {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                while (Files.exists(marker) && System.nanoTime() - until < 0) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                Log.info("test hold: released " + point);
                return Status.OK_STATUS;
            }

            @Override
            public boolean belongsTo(Object family) {
                return LIFE_CYCLE_FAMILY.equals(family);
            }
        };
        hold.setSystem(true);
        hold.schedule();
        Log.info("test hold: a " + LIFE_CYCLE_FAMILY + " job runs at " + point + " until " + marker + " is deleted");
    }
}
