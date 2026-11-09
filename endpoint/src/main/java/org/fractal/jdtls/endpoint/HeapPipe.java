// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;

/**
 * A one-way byte pipe that lives only on the Java heap: no file descriptor, no socket, nothing another
 * process could open. Unbounded, so a writer never blocks; any number of threads may write.
 *
 * java.io.PipedInputStream isn't used: it fails with "Write end dead" once the last thread that wrote to
 * it has ended, and lsp4j and JDT LS write from pooled threads that come and go.
 */
final class HeapPipe {

    private final String name;
    private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
    private byte[] current;
    private int position;
    private boolean closed;

    HeapPipe(String name) {
        this.name = name;
    }

    final InputStream input = new InputStream() {
        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            synchronized (HeapPipe.this) {
                while (current == null || position >= current.length) {
                    current = chunks.poll();
                    position = 0;
                    if (current != null) {
                        continue;
                    }
                    if (closed) {
                        return -1;
                    }
                    try {
                        HeapPipe.this.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException(name + ": interrupted", e);
                    }
                }
                int n = Math.min(len, current.length - position);
                System.arraycopy(current, position, b, off, n);
                position += n;
                return n;
            }
        }

        @Override
        public int available() {
            synchronized (HeapPipe.this) {
                int n = current == null ? 0 : current.length - position;
                for (byte[] c : chunks) {
                    n += c.length;
                }
                return n;
            }
        }
    };

    final OutputStream output = new OutputStream() {
        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return;
            }
            byte[] copy = new byte[len];
            System.arraycopy(b, off, copy, 0, len);
            synchronized (HeapPipe.this) {
                if (closed) {
                    throw new IOException(name + ": closed");
                }
                chunks.add(copy);
                HeapPipe.this.notifyAll();
            }
        }

        @Override
        public void close() {
            synchronized (HeapPipe.this) {
                closed = true;
                HeapPipe.this.notifyAll();
            }
        }
    };
}
