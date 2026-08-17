package com.gimle.core.io;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.LongFunction;

/**
 * Aborts a read mid-stream once more than {@code maxBytes} have been read, so an oversized
 * request/upload body's excess bytes are never fully buffered (or written to disk) before being
 * rejected. Shared by every process that caps an attacker-controlled inbound stream this way
 * (Andvari's artifact uploads, Muninn's ingest batches, Saga's ingest/import bodies) rather than
 * each carrying its own copy of the same counting {@link FilterInputStream}.
 *
 * <p>{@code onExceeded} is invoked with the byte count that triggered the limit and must return the
 * {@link RuntimeException} to throw, so each call site can still surface its own local exception
 * type unchanged.
 */
public final class SizeLimitedInputStream extends FilterInputStream {

  private final long maxBytes;
  private final LongFunction<RuntimeException> onExceeded;
  private long readSoFar;

  public SizeLimitedInputStream(
      InputStream in, long maxBytes, LongFunction<RuntimeException> onExceeded) {
    super(in);
    this.maxBytes = maxBytes;
    this.onExceeded = onExceeded;
  }

  @Override
  public int read() throws IOException {
    int b = super.read();
    if (b != -1) {
      countAndCheck(1);
    }
    return b;
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    int n = super.read(b, off, len);
    if (n > 0) {
      countAndCheck(n);
    }
    return n;
  }

  private void countAndCheck(int justRead) {
    readSoFar += justRead;
    if (readSoFar > maxBytes) {
      throw onExceeded.apply(readSoFar);
    }
  }
}
