// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.ProgressProvider;
import org.junit.jupiter.api.Test;

/**
 * The request's deadline reaches Eclipse's wait for a scheduling rule (design §11 "Threading and queuing", T002.11).
 * JDT LS's progress provider ({@code ProgressReporterManager.monitorFor}) replaces any {@code NullProgressMonitor},
 * subclasses included, with its own monitor when {@code JobManager.beginRule} is called, so a deadline monitor that
 * extended it was never seen there: a fixed point waited for the workspace rule until the rule was free.
 */
class DeadlineTest {

    /** What JDT LS 1.63.0's {@code ProgressReporterManager} does with a monitor. */
    static final class LikeJdtls extends ProgressProvider {
        @Override
        public IProgressMonitor createMonitor(Job job) {
            return new NullProgressMonitor();
        }

        @Override
        public IProgressMonitor getDefaultMonitor() {
            return new NullProgressMonitor();
        }

        @Override
        public IProgressMonitor monitorFor(IProgressMonitor monitor) {
            return monitor == null || monitor instanceof NullProgressMonitor ? getDefaultMonitor() : monitor;
        }
    }

    static final class Rule implements ISchedulingRule {
        @Override
        public boolean contains(ISchedulingRule rule) {
            return rule == this;
        }

        @Override
        public boolean isConflicting(ISchedulingRule rule) {
            return rule == this;
        }
    }

    @Test
    void theDeadlineIsNotANullProgressMonitorThatJdtlsReplaces() {
        assertFalse(NullProgressMonitor.class.isAssignableFrom(DiskSync.Deadline.class));
        DiskSync.Deadline d = new DiskSync.Deadline(System.nanoTime() + 60_000_000_000L);
        assertFalse(d.isCanceled());
        d.setCanceled(true);
        assertTrue(d.isCanceled(), "cancelled by hand too");
        assertTrue(new DiskSync.Deadline(System.nanoTime() - 1).isCanceled(), "past its deadline");
    }

    @Test
    void aWaitForARuleHeldByAnotherThreadStopsAtTheDeadline() throws Exception {
        Rule rule = new Rule();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            Job.getJobManager().beginRule(rule, null);
            try {
                held.countDown();
                release.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                Job.getJobManager().endRule(rule);
            }
        }, "holds the rule");
        Job.getJobManager().setProgressProvider(new LikeJdtls());
        try {
            holder.start();
            assertTrue(held.await(10, TimeUnit.SECONDS));
            long start = System.nanoTime();
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThrows(OperationCanceledException.class,
                    () -> Job.getJobManager().beginRule(rule, new DiskSync.Deadline(System.nanoTime() + 300_000_000L))),
                    "the wait for the rule never saw the deadline");
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertTrue(ms >= 300 && ms < 2_000, ms + " ms");
        } finally {
            release.countDown();
            holder.join(10_000);
            Job.getJobManager().setProgressProvider(null);
        }
    }
}
