// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.junit.jupiter.api.Test;

/** Joining the work with Eclipse's real job manager, outside JDT LS. */
class JobsTest {

    /** Like Eclipse's "Periodic workspace save": each run schedules the next one, a long delay later. */
    static final class Periodic extends Job {
        final CountDownLatch running = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger runs = new AtomicInteger();

        Periodic() {
            super("periodic");
            setSystem(true);
        }

        @Override
        protected IStatus run(IProgressMonitor monitor) {
            runs.incrementAndGet();
            running.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            schedule(600_000);
            return Status.OK_STATUS;
        }
    }

    /**
     * The race T002.04 found: a round finds the job running, and by the time the join reaches it, its run is over and
     * it has rescheduled itself. {@code Job.join} would then wait for its next run (Eclipse's workspace save: 300 s).
     */
    @Test
    void aJobFoundRunningThatHasGoneBackToSleepIsJoinedAtOnce() throws Exception {
        Periodic periodic = new Periodic();
        periodic.release.countDown(); // each run ends at once
        periodic.schedule();
        try {
            assertTrue(periodic.running.await(10, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (periodic.getState() != Job.SLEEPING && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(Job.SLEEPING, periodic.getState(), "the run is over, and the next is 600 s away");
            long start = System.nanoTime();
            assertTrue(Jobs.await(periodic, start));
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertTrue(ms < 1_000, "the join waited " + ms + " ms, for the job's next run");
            assertEquals(1, periodic.runs.get());
        } finally {
            periodic.cancel();
        }
    }

    @Test
    void aJobFoundRunningIsJoinedUntilTheEndOfThatRun() throws Exception {
        Jobs jobs = new Jobs();
        long mark = jobs.mark();
        Periodic periodic = new Periodic();
        periodic.schedule();
        try {
            assertTrue(periodic.running.await(10, TimeUnit.SECONDS));
            new Thread(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    return;
                }
                periodic.release.countDown();
            }).start();
            long start = System.nanoTime();
            Jobs.Joined joined = jobs.join(mark);
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertTrue(ms >= 250 && ms < 5_000, "the join took " + ms + " ms");
            assertEquals(Job.SLEEPING, periodic.getState());
            assertTrue(joined.jobs().get(0).startsWith("periodic ("), joined.toString());
        } finally {
            periodic.cancel();
        }
    }

    @Test
    void jobsScheduledBeforeTheMarkAreNotJoined() throws Exception {
        Periodic before = new Periodic();
        before.schedule();
        try {
            assertTrue(before.running.await(10, TimeUnit.SECONDS));
            Jobs jobs = new Jobs();
            Jobs.Joined joined = jobs.join(jobs.mark());
            assertEquals(0, joined.rounds(), joined.toString());
        } finally {
            before.release.countDown();
            before.cancel();
        }
    }
}
