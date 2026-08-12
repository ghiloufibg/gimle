package com.gimle.core.codec;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes a length-prefixed wire frame, shared by every Gimlé transport codec (Raft peer traffic,
 * store RPC, fabric invocation).
 *
 * <p>The one thing this exists to guarantee is that the 4-byte length prefix and the body leave as
 * a <em>single</em> {@code write}. That is not a micro-optimization: a socket {@code OutputStream}
 * is unbuffered, so writing them separately puts two small segments on the wire, and TCP's Nagle
 * algorithm then holds the second one until the first is acknowledged. The peer's delayed-ACK timer
 * does not fire for ~40ms, so every request and every response pays that delay -- roughly 80ms per
 * round trip on a link whose actual latency is microseconds. Measured on loopback, this made a
 * single control-plane reconcile pass take 1.8 seconds instead of 55 milliseconds.
 *
 * <p>Transports additionally set {@code TCP_NODELAY}, which is the direct fix for that interaction;
 * single-write framing is the complementary one, and applies to every transport regardless of
 * protocol (a Unix domain socket has no Nagle to disable, but still benefits from one syscall
 * instead of two).
 */
public final class Frames {

  private Frames() {}

  /** Writes {@code body} prefixed by its big-endian 4-byte length, as one write, then flushes. */
  public static void writeFrame(OutputStream out, byte[] body) throws IOException {
    final byte[] frame = new byte[Integer.BYTES + body.length];
    frame[0] = (byte) (body.length >>> 24);
    frame[1] = (byte) (body.length >>> 16);
    frame[2] = (byte) (body.length >>> 8);
    frame[3] = (byte) body.length;
    System.arraycopy(body, 0, frame, Integer.BYTES, body.length);
    out.write(frame);
    out.flush();
  }
}
