// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HeapPipeTest {

    @Test
    void bytesWrittenByThreadsThatHaveEndedCanStillBeRead() throws Exception {
        // java.io.PipedInputStream fails here with "Write end dead".
        HeapPipe pipe = new HeapPipe("test");
        for (int i = 0; i < 4; i++) {
            int n = i;
            Thread t = new Thread(() -> {
                try {
                    pipe.output.write(("chunk" + n).getBytes(StandardCharsets.US_ASCII));
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            });
            t.start();
            t.join();
        }
        assertEquals(24, pipe.input.available());
        assertEquals("chunk0chunk1chunk2chunk3", new String(pipe.input.readNBytes(24), StandardCharsets.US_ASCII));
    }

    @Test
    void aReaderWaitsForTheWriterAndSeesTheEndAfterClose() throws Exception {
        HeapPipe pipe = new HeapPipe("test");
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(50);
                pipe.output.write(new byte[] {1, 2, 3});
                pipe.output.close();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        writer.start();
        assertEquals(1, pipe.input.read());
        byte[] rest = pipe.input.readAllBytes();
        assertEquals(2, rest.length);
        assertEquals(-1, pipe.input.read());
        assertThrows(IOException.class, () -> pipe.output.write(4));
    }
}
