// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The operations in progress, for {@code GET /v1/status} ("Status and shutdown"): each with its route's path, and a
 * refactoring with its operation folder's ID ({@code operation}, "Operation folders"), set before the folder is
 * made. An entry closes only after its answer, and a refactoring's {@code response.json}, is written.
 */
final class InFlight {

    final class Op implements AutoCloseable {
        private final long id;
        final String path;
        final String worktree;
        final String module;
        final long start = System.nanoTime();
        volatile String phase = "running";
        /** A refactoring's operation folder's ID, once it has one. */
        volatile String operation;

        private Op(long id, String path, String worktree, String module) {
            this.id = id;
            this.path = path;
            this.worktree = worktree;
            this.module = module;
        }

        Op phase(String phase) {
            this.phase = phase;
            return this;
        }

        void operation(String id) {
            this.operation = id;
        }

        @Override
        public void close() {
            ops.remove(id);
        }
    }

    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, Op> ops = new ConcurrentHashMap<>();

    Op begin(String path, String worktree, String module) {
        Op op = new Op(ids.incrementAndGet(), path, worktree, module);
        ops.put(op.id, op);
        return op;
    }

    JsonArray toJson() {
        JsonArray list = new JsonArray();
        ops.values().stream().sorted((a, b) -> Long.compare(a.id, b.id)).forEach(op -> {
            JsonObject o = new JsonObject();
            o.addProperty("path", op.path);
            if (op.operation != null) {
                o.addProperty("operation", op.operation);
            }
            if (op.worktree != null) {
                o.addProperty("worktree", op.worktree);
            }
            if (op.module != null) {
                o.addProperty("module", op.module);
            }
            o.addProperty("ageMs", (System.nanoTime() - op.start) / 1_000_000);
            o.addProperty("phase", op.phase);
            list.add(o);
        });
        return list;
    }
}
