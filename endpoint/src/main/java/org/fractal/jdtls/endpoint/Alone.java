// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Imports run alone (design §11 "Worktrees"). A worktree change (an add, with its health check and repair, a removal,
 * or a restart's check of the saved worktrees) runs only when no request that reaches JDT LS is running, and, from the
 * moment it's announced until it ends, every such request waits, or gets {@code not-ready}. Requests that only read
 * the endpoint's own state ({@code status}, {@code worktrees/list}) don't come here.
 *
 * <p>Changes run one at a time, on the worktree thread; requests run concurrently with each other.
 */
final class Alone {

    enum Kind {
        ADD("being imported"), REMOVE("being removed"), RESTART("being checked after the restart");

        final String doing;

        Kind(String doing) {
            this.doing = doing;
        }
    }

    /** A change: announced when it's accepted, running from {@link #begin} to {@link #end}. */
    static final class Change {
        final Kind kind;
        final Path worktree;
        final long announcedAt = System.nanoTime();
        volatile long startedAt;

        Change(Kind kind, Path worktree) {
            this.kind = kind;
            this.worktree = worktree;
        }

        String describe() {
            String what = worktree == null ? "The saved worktrees are " : "The worktree " + worktree + " is ";
            return what + kind.doing;
        }
    }

    /** A request let through; closing it lets a waiting change run. */
    final class Pass implements AutoCloseable {
        final long waitedMs;

        private Pass(long waitedMs) {
            this.waitedMs = waitedMs;
        }

        @Override
        public void close() {
            synchronized (Alone.this) {
                running--;
                Alone.this.notifyAll();
            }
        }
    }

    private final List<Change> announced = new ArrayList<>();
    private Change current;
    private int running;

    synchronized void announce(Change change) {
        announced.add(change);
    }

    /** The change thread: waits until no request runs, then runs the change alone. */
    synchronized void begin(Change change) throws InterruptedException {
        while (running > 0) {
            wait();
        }
        announced.remove(change);
        change.startedAt = System.nanoTime();
        current = change;
    }

    synchronized void end(Change change) {
        if (current == change) {
            current = null;
        }
        announced.remove(change);
        notifyAll();
    }

    /** The change running, else the first announced, or null. */
    synchronized Change blocking() {
        return current != null ? current : announced.isEmpty() ? null : announced.get(0);
    }

    synchronized Change current() {
        return current;
    }

    /**
     * A request that reaches JDT LS: waits until no change is running or announced, up to {@code deadline}
     * ({@link System#nanoTime()}), or not at all without {@code wait}. On timeout, {@code not-ready}.
     */
    Pass enter(long deadline, boolean wait, Runnable waiting) throws Problem, InterruptedException {
        long start = System.nanoTime();
        synchronized (this) {
            boolean said = false;
            Change waitedFor = null;
            while (blocking() != null) {
                waitedFor = blocking();
                long left = deadline - System.nanoTime();
                if (!wait || left <= 0) {
                    throw notReady(blocking());
                }
                if (!said && waiting != null) {
                    waiting.run();
                    said = true;
                }
                wait(Math.max(1, left / 1_000_000));
            }
            if (waitedFor != null && System.nanoTime() >= deadline) {
                throw notReady(waitedFor); // the change ended, but after the request's time was up
            }
            running++;
        }
        return new Pass((System.nanoTime() - start) / 1_000_000);
    }

    static Problem notReady(Change change) {
        long since = change.startedAt != 0 ? change.startedAt : change.announcedAt;
        String seconds = String.format(Locale.ROOT, "%.1f", (System.nanoTime() - since) / 1e9);
        Problem p = Problem.of(Code.NOT_READY)
                .detail(change.describe() + " (" + seconds + " s so far). Imports run alone, so no other request reaches JDT LS until it"
                        + (change.kind == Kind.REMOVE ? " is done." : " and its health check are done."))
                .hint("Wait `retryAfter` seconds and send the same request again, or send it with `wait: true`.")
                .with("reason", "importing")
                .retryAfter(change.kind == Kind.REMOVE ? 2 : 5);
        if (change.worktree != null) {
            p.with("worktree", change.worktree.toString());
        }
        return p;
    }
}
