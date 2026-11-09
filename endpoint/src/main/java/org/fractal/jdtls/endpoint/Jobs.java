// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;

/**
 * Joining the work, without timing (design §11 "Joining the work", HTTP-FINDINGS "Joining the work"). A job-change
 * listener numbers every scheduling; {@link #mark()} takes the current number, and {@link #join(Long)} joins, round
 * after round until a round finds none, every job scheduled after the mark that is running or waiting to run, or
 * sleeping if it's an {@code AutoBuildJob} or an m2e background-queue job. Never an implicit job ({@code ThreadJob}),
 * whose join never returns, and never JDT's indexer progress job, which searches don't need.
 */
final class Jobs {

    /** Each join's guard: not a wait, but a hang turned into a loud failure. */
    static final long JOIN_GUARD_MS = 600_000;
    static final int MAX_ROUNDS = 200;
    static final long SLICE_MS = 10_000;
    private static final String THREAD_JOB = "org.eclipse.core.internal.jobs.ThreadJob";
    private static final String INDEXER = "org.eclipse.jdt.internal.core.search.processing.JobManager";
    private static final String BACKGROUND_QUEUE = "org.eclipse.m2e.core.internal.jobs.IBackgroundProcessingQueue";

    /** What a join did: rounds, the jobs joined (name and milliseconds), and how long it took. */
    record Joined(int rounds, List<String> jobs, long ms) {}

    private final AtomicLong sequence = new AtomicLong();
    private final Map<Job, Long> scheduled = new ConcurrentHashMap<>();

    Jobs() {
        Job.getJobManager().addJobChangeListener(new JobChangeAdapter() {
            @Override
            public void scheduled(IJobChangeEvent event) {
                scheduled.put(event.getJob(), sequence.incrementAndGet());
            }

            @Override
            public void done(IJobChangeEvent event) {
                scheduled.remove(event.getJob());
            }
        });
    }

    /** A mark in the order of schedulings. */
    long mark() {
        return sequence.get();
    }

    /**
     * Joins what was scheduled after {@code since}, or, with null, every job that runs or waits (a restart's
     * startup, when nothing else may run). Throws if a join outlasts its guard.
     */
    Joined join(Long since) {
        try {
            return join(since, Long.MAX_VALUE);
        } catch (Expired e) {
            throw new IllegalStateException(e); // no deadline
        }
    }

    /** A join whose caller's deadline ({@link System#nanoTime()}) passed: it stops waiting, and cancels nothing. */
    static final class Expired extends Exception {
        private static final long serialVersionUID = 1L;

        Expired(String job) {
            super("the deadline passed while joining '" + job + "'", null, false, false);
        }
    }

    /** {@link #join(Long)}, until {@code deadline} at the latest: then {@link Expired}. */
    Joined join(Long since, long deadline) throws Expired {
        long start = System.nanoTime();
        List<String> joined = new ArrayList<>();
        int rounds = 0;
        while (true) {
            List<Job> round = new ArrayList<>();
            for (Job job : Job.getJobManager().find(null)) {
                if (joinable(job, since)) {
                    round.add(job);
                }
            }
            if (round.isEmpty()) {
                break;
            }
            if (++rounds > MAX_ROUNDS) {
                throw new IllegalStateException("joining JDT LS's jobs didn't end after " + MAX_ROUNDS + " rounds; still running: " + names(round));
            }
            for (Job job : round) {
                long t = System.nanoTime();
                boolean done = await(job, t, deadline);
                if (!done && deadline - System.nanoTime() <= 0) {
                    throw new Expired(job.getName());
                }
                if (!done) {
                    throw new IllegalStateException("JDT LS's job '" + job.getName() + "' (" + job.getClass().getName()
                            + ") didn't finish within the join's guard of " + JOIN_GUARD_MS / 1000 + " s");
                }
                joined.add(job.getName() + " (" + (System.nanoTime() - t) / 1_000_000 + " ms)");
            }
        }
        return new Joined(rounds, joined, (System.nanoTime() - start) / 1_000_000);
    }

    /**
     * Waits for the end of the run the round found, never for a later one: on the job's own {@code done} event, or at
     * once if the job has already ended, or gone back to sleep, unless it's a kind that's joined while it sleeps.
     * {@code Job.join} would wait for the next run of a job that rescheduled itself: T002.04 found a removal waiting
     * 300 s for Eclipse's "Periodic workspace save", which had run during it and gone back to sleep for its interval.
     * The listener is added before the state is read, so no {@code done} is missed. Waits in slices of
     * {@value #SLICE_MS} ms, each logged with the job's name and the other jobs, up to the guard.
     */
    static boolean await(Job job, long start) {
        return await(job, start, Long.MAX_VALUE);
    }

    /** {@link #await(Job, long)}, giving up at {@code deadline} too. */
    static boolean await(Job job, long start, long deadline) {
        java.util.concurrent.Semaphore ended = new java.util.concurrent.Semaphore(0);
        JobChangeAdapter listener = new JobChangeAdapter() {
            @Override
            public void done(IJobChangeEvent event) {
                ended.release();
            }
        };
        job.addJobChangeListener(listener);
        try {
            for (long waited = 0; waited < JOIN_GUARD_MS; waited += SLICE_MS) {
                int state = job.getState();
                if (state == Job.NONE || state == Job.SLEEPING && !delayedKind(job)) {
                    return true;
                }
                long left = deadline == Long.MAX_VALUE ? Long.MAX_VALUE : (deadline - System.nanoTime()) / 1_000_000;
                if (left <= 0) {
                    return false;
                }
                if (ended.tryAcquire(Math.min(left, Math.min(SLICE_MS, JOIN_GUARD_MS - waited)), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    return true;
                }
                if (deadline != Long.MAX_VALUE && deadline - System.nanoTime() <= 0) {
                    return false;
                }
                Log.info("still joining JDT LS's job '" + job.getName() + "' (" + job.getClass().getName() + ", " + stateName(job.getState())
                        + ") after " + (System.nanoTime() - start) / 1_000_000 + " ms; other jobs: " + others(job));
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted joining " + job.getName(), e);
        } finally {
            job.removeJobChangeListener(listener);
        }
    }

    /** The jobs that are joined while they sleep: they're scheduled with a delay, and the work is theirs to do. */
    private static boolean delayedKind(Job job) {
        return job.belongsTo(ResourcesPlugin.FAMILY_AUTO_BUILD) || backgroundQueue(job.getClass());
    }

    private boolean joinable(Job job, Long since) {
        int state = job.getState();
        boolean delayed = state == Job.SLEEPING && delayedKind(job);
        if (!(state == Job.RUNNING || state == Job.WAITING || delayed) || job == Job.getJobManager().currentJob()) {
            return false;
        }
        String type = job.getClass().getName();
        if (type.equals(THREAD_JOB) || type.startsWith(INDEXER)) {
            return false;
        }
        if (since == null) {
            return true;
        }
        Long at = scheduled.get(job);
        return at != null && at > since;
    }

    static String stateName(int state) {
        return switch (state) {
            case Job.RUNNING -> "running";
            case Job.WAITING -> "waiting";
            case Job.SLEEPING -> "sleeping";
            default -> "done";
        };
    }

    private static String others(Job joining) {
        List<String> others = new ArrayList<>();
        for (Job j : Job.getJobManager().find(null)) {
            if (j != joining) {
                others.add(j.getName() + " (" + j.getClass().getName() + ", " + stateName(j.getState()) + ")");
            }
        }
        return String.join("; ", others);
    }

    private static boolean backgroundQueue(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                if (i.getName().equals(BACKGROUND_QUEUE)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String names(List<Job> jobs) {
        List<String> names = new ArrayList<>();
        jobs.forEach(j -> names.add(j.getName()));
        return String.join(", ", names);
    }
}
