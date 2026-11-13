package com.rafael.groksessions;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;

/** Bounded, ordered writes. Incomplete or duplicated transfers cannot be published. */
final class DownloadStream implements Closeable {
    private final OutputStream output;
    private final long expected;
    private long received;
    private int nextSequence;
    private boolean closed;

    DownloadStream(OutputStream output, long expected) {
        if (expected < 0) throw new IllegalArgumentException("Tamanho inválido");
        this.output = output;
        this.expected = expected;
    }

    void write(int sequence, byte[] bytes) throws IOException {
        if (closed || sequence != nextSequence || bytes.length == 0 || bytes.length > 65536
                || bytes.length > expected - received) throw new IOException("Bloco inválido");
        output.write(bytes);
        received += bytes.length;
        nextSequence++;
    }

    long received() { return received; }

    void finish(int sequence) throws IOException {
        if (closed || sequence != nextSequence || received != expected) throw new IOException("Arquivo incompleto");
        close();
    }

    @Override public void close() throws IOException {
        if (!closed) { closed = true; output.close(); }
    }
}
